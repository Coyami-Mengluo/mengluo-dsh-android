package ai.mengluo.dsh.android;

import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Signal only a launched process tree owned by our UID; PRoot does not forward every signal. */
final class RuntimeProcesses {
    private static final Map<java.lang.Process, Stamp> roots = Collections.synchronizedMap(new WeakHashMap<>());
    static java.lang.Process launch(ProcessBuilder builder, File cache) throws IOException {
        File ticket = new File(cache, "runtime-pid-" + UUID.randomUUID());
        ArrayList<String> original = new ArrayList<>(builder.command());
        ArrayList<String> command = new ArrayList<>(List.of("/system/bin/sh", "-c", "echo $$ > \"$1\"; shift; exec \"$@\"", "mengluo-runtime", ticket.getPath()));
        command.addAll(original); builder.command(command);
        java.lang.Process process = builder.start();
        try {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < until) {
                if (ticket.isFile()) {
                    try {
                        int pid = Integer.parseInt(IO.text(ticket).trim()); Stamp stamp = read(pid);
                        if (stamp != null && stamp.parent == Process.myPid()) { roots.put(process, stamp); return process; }
                    } catch (NumberFormatException partial) { /* Shell may still be writing the small ticket. */ }
                }
                // A short command can finish before its PID is sampled. Its Process still owns
                // the exit status and output; let the caller drain them and decide success/failure.
                // Only live processes need the verified stamp used for later tree termination.
                if (!process.isAlive()) return process;
                Thread.sleep(10);
            }
            if (!process.isAlive()) return process;
            throw new IOException("无法确认运行进程身份，已停止启动");
        } catch (InterruptedException interrupted) { process.destroy(); Thread.currentThread().interrupt(); throw new IOException("启动已中断", interrupted); }
        catch (IOException error) { process.destroy(); throw error; }
        finally { ticket.delete(); }
    }
    static void stop(java.lang.Process process) {
        if (process == null) return;
        Stamp root = roots.get(process);
        if (root == null || !root.same()) { process.destroy(); return; }
        List<Stamp> tree = descendants(root); Collections.reverse(tree);
        for (Stamp item : tree) signal(item, OsConstants.SIGTERM);
        // Let the traced children exit first, so PRoot can clean its own resources.
        if (tree.isEmpty()) signal(root, OsConstants.SIGTERM);
    }
    static boolean stopAndWait(java.lang.Process process, int seconds) throws InterruptedException {
        stop(process);
        if (process.waitFor(seconds, TimeUnit.SECONDS)) { roots.remove(process); return true; }
        Stamp root = roots.get(process);
        if (root != null && root.same()) {
            List<Stamp> tree = descendants(root); Collections.reverse(tree);
            for (Stamp item : tree) signal(item, OsConstants.SIGKILL);
            if (!process.waitFor(2, TimeUnit.SECONDS)) signal(root, OsConstants.SIGKILL);
        } else process.destroyForcibly();
        boolean exited = process.waitFor(2, TimeUnit.SECONDS);
        if (exited) roots.remove(process);
        return exited;
    }
    private static List<Stamp> descendants(Stamp root) {
        ArrayList<Stamp> all = new ArrayList<>(), result = new ArrayList<>();
        File[] entries = new File("/proc").listFiles();
        if (entries == null) return result;
        for (File entry : entries) {
            if (!entry.getName().matches("[0-9]+")) continue;
            Stamp item = read(Integer.parseInt(entry.getName())); if (item != null) all.add(item);
        }
        Set<Integer> parents = new HashSet<>(); parents.add(root.pid);
        boolean added;
        do {
            added = false;
            for (Stamp item : all) if (parents.contains(item.parent) && !parents.contains(item.pid)) { parents.add(item.pid); result.add(item); added = true; }
        } while (added);
        return result;
    }
    private static Stamp read(int pid) {
        if (pid <= 1 || pid == Process.myPid()) return null;
        try {
            File path = new File("/proc/" + pid);
            if (Os.stat(path.getPath()).st_uid != Process.myUid()) return null;
            String stat = IO.text(new File(path, "stat")); String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            return new Stamp(pid, Integer.parseInt(fields[1]), fields[19]);
        } catch (Exception gone) { return null; }
    }
    private static void signal(Stamp item, int signal) { if (item.same()) try { Os.kill(item.pid, signal); } catch (Exception gone) { /* Already exited. */ } }
    private static final class Stamp {
        final int pid, parent; final String start;
        Stamp(int pid, int parent, String start) { this.pid = pid; this.parent = parent; this.start = start; }
        boolean same() { Stamp now = read(pid); return now != null && start.equals(now.start); }
    }
}
