package ai.mengluo.dsh.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.*;

/** Owns local processes and the private runtime; project selection belongs to Harness. */
final class Engine {
    private static Engine instance;
    static synchronized Engine get(Context context) {
        if (instance == null) instance = new Engine(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    final File workspace, profile, rootfs;
    final WorkspaceStore workspaces;
    private final File stateFile;
    final RuntimeStore versions;
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService readers = Executors.newCachedThreadPool();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Consumer<Engine>> listeners = new ArrayList<>();
    private final RuntimeLog log;
    private volatile Process backend, operation;
    private OperationCancellation installation;
    private volatile long generation;
    volatile boolean busy;
    volatile boolean checkingSource;
    private volatile DownloadSource downloadSource;
    volatile String status = "尚未安装本地运行环境", readyUrl;
    private boolean eventPending;
    Engine(Context context) {
        this.context = context;
        workspaces = new WorkspaceStore(context);
        downloadSource = DownloadSource.fromId(context.getSharedPreferences("downloads", 0).getString("source", "mirror"));
        File files = context.getFilesDir();
        log = new RuntimeLog(new File(files, "logs"));
        workspace = new File(files, "workspace"); profile = new File(files, "profile");
        rootfs = new File(files, "runtime/rootfs"); stateFile = new File(files, "runtime/state.json");
        versions = new RuntimeStore(rootfs);
        workspace.mkdirs(); profile.mkdirs();
        note("客户端启动 · " + BuildConfig.VERSION_NAME + " · " + downloadSource.title);
        note("设备：" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " · Android " + android.os.Build.VERSION.RELEASE
            + " · APK " + BuildConfig.RUNTIME_ABI + " · 系统 ABI " + String.join(",", android.os.Build.SUPPORTED_64_BIT_ABIS));
        if (installed()) status = "本地运行环境已安装，点击启动";
    }
    DownloadSource downloadSource() { return downloadSource; }
    synchronized void downloadSource(DownloadSource source) throws IOException {
        if (busy || checkingSource) throw new IOException("请等待当前操作完成后切换下载源");
        if (!context.getSharedPreferences("downloads", 0).edit().putString("source", source.id).commit()) throw new IOException("下载源保存失败，请重试");
        downloadSource = source;
        event(status);
    }
    void checkDownloadSource(Consumer<String> result) {
        DownloadSource source;
        synchronized (this) {
            if (busy || checkingSource) { result.accept("请等待当前操作完成"); return; }
            checkingSource = true; source = downloadSource;
        }
        event(status);
        readers.execute(() -> {
            long started = System.nanoTime(); String message;
            try {
                RegistryClient.checkVersion(source, RegistryClient.NAME, currentVersion());
                RegistryClient.checkVersion(source, "pnpm", RegistryClient.PNPM_VERSION);
                message = source.title + "可用，Harness " + currentVersion() + " 和 pnpm " + RegistryClient.PNPM_VERSION
                    + " 已同步（" + ((System.nanoTime() - started) / 1_000_000) + " 毫秒）";
            } catch (Exception error) { message = "连接检查失败：" + error.getMessage(); }
            String response = message;
            checkingSource = false; main.post(() -> { event(status); result.accept(response); });
        });
    }
    synchronized void listen(Consumer<Engine> listener) { listeners.add(listener); listener.accept(this); }
    synchronized void unlisten(Consumer<Engine> listener) { listeners.remove(listener); }
    String logs() { return log.tail(); }
    byte[] exportLogs() throws IOException {
        return log.snapshot("MengLuo DSH Android " + BuildConfig.VERSION_NAME + " · " + BuildConfig.RUNTIME_ABI
            + "\nHarness " + currentVersion() + " · 下载源：" + downloadSource.title);
    }
    synchronized void note(String value) {
        String clean = RuntimePolicy.redact(value);
        log.append(clean);
        android.util.Log.i("MengLuoRuntime", clean);
    }
    private synchronized void event(String value) {
        status = value;
        if (eventPending) return;
        eventPending = true;
        main.postDelayed(() -> { synchronized (Engine.this) { eventPending = false; for (Consumer<Engine> listener : new ArrayList<>(listeners)) listener.accept(this); } }, 100);
    }
    private synchronized void event(long epoch, String value) { if (epoch == generation) event(value); }
    boolean installed() {
        return versions.active() != null;
    }
    String currentVersion() { RuntimeStore.Slot slot = versions.active(); return slot == null ? RuntimePolicy.HARNESS_VERSION : slot.version; }
    void install() {
        install(RuntimePolicy.HARNESS_VERSION);
    }
    void install(String targetVersion) {
        UpdatePolicy.version(targetVersion);
        final long epoch;
        final OperationCancellation cancellation;
        synchronized (this) {
            if (busy || checkingSource || backend != null) return;
            busy = true; epoch = generation;
            installation = cancellation = new OperationCancellation(readers);
        }
        DownloadSource source = downloadSource;
        Consumer<String> progress = value -> event(epoch, value);
        progress.accept("准备安装 · " + source.title);
        work.execute(() -> {
            try {
                cancellation.check();
                progress.accept("检查下载源及 Harness 目标版本 · " + source.title);
                RegistryClient.Release release = RegistryClient.official(targetVersion, cancellation);
                RegistryClient.checkVersion(source, RegistryClient.NAME, targetVersion, cancellation);
                cancelled(epoch);
                File runtime = rootfs.getParentFile(); runtime.mkdirs();
                if (!new File(rootfs, "opt/node/bin/node").isFile()) {
                    if (rootfs.exists() && !rootfs.renameTo(new File(runtime, "incomplete-" + System.currentTimeMillis())))
                        throw new IOException("无法保留未完成的运行环境；工作区未修改");
                    File staging = new File(runtime, "staging-" + System.currentTimeMillis()); staging.mkdirs();
                    JSONObject manifest;
                    try (InputStream input = context.getAssets().open("runtime/manifest.json")) {
                        manifest = new JSONObject(new String(IO.bytes(input), StandardCharsets.UTF_8));
                    }
                    if (!BuildConfig.RUNTIME_ABI.equals(manifest.getString("abi"))) throw new IOException("安装包运行架构不匹配");
                    JSONArray assets = manifest.getJSONArray("assets");
                    for (int i = 0; i < assets.length(); i++) {
                        cancelled(epoch);
                        JSONObject asset = assets.getJSONObject(i);
                        String name = asset.getString("name");
                        File copy = RuntimeAssets.obtain(context.getCacheDir(), asset, source, progress, cancellation);
                        cancelled(epoch);
                        progress.accept("校验并解压 " + name);
                        ArchiveInstaller.verify(copy, asset.getString("sha256"), cancellation);
                        File destination = name.startsWith("node") ? new File(staging, "opt/node") : staging;
                        destination.mkdirs();
                        ArchiveInstaller.extract(copy, destination, name.startsWith("node") ? 1 : 0, progress, cancellation);
                        cancelled(epoch);
                        copy.delete();
                    }
                    cancelled(epoch);
                    if (!staging.renameTo(rootfs)) throw new IOException("无法保存基础运行环境");
                }
                prepareDirectories();
                progress.accept("检查本机 Node 和 Bash");
                run(List.of("/opt/node/bin/node", "-e", "console.log('node='+process.version+' platform='+process.platform+' arch='+process.arch)"), 30, epoch);
                if (!installed() || !new File(rootfs, "usr/bin/git").isFile() || !new File(rootfs, "usr/bin/python3").exists()
                    || !new File(rootfs, "etc/ssl/certs/ca-certificates.crt").isFile()) {
                    AptSetup.prepare(rootfs, BuildConfig.RUNTIME_ABI, SystemCertificates.pem());
                    AptSetup.install(source, BuildConfig.RUNTIME_ABI, (command, seconds) -> run(command, seconds, epoch),
                        message -> { note(message); progress.accept(message); }, () -> cancelled(epoch));
                }
                RuntimeStore.Slot candidate = installCandidate(release, source, epoch, cancellation);
                progress.accept("隔离启动测试 · " + targetVersion + " · 上限 120 秒");
                smoke(candidate, epoch);
                cancelled(epoch);
                versions.activate(candidate);
                progress.accept("Harness " + targetVersion + " 已安装并切换 · 可以启动");
            } catch (Exception error) {
                if (epoch != generation) note("[runtime] 安装已停止，工作区与安装版本保留");
                else { note("安装失败：" + error); progress.accept("安装失败：" + error.getMessage()); }
            } finally {
                synchronized (this) {
                    if (installation == cancellation) installation = null;
                    if (epoch == generation) { busy = false; event(status); }
                }
            }
        });
    }
    private RuntimeStore.Slot installCandidate(RegistryClient.Release release, DownloadSource source, long epoch, OperationCancellation cancellation) throws Exception {
        cancellation.check();
        String guest = "/opt/harness-slots/v" + release.version + "-" + UUID.randomUUID();
        File directory = RuntimePolicy.inside(rootfs, guest.substring(1)); directory.mkdirs();
        File config = new File(directory, "npmrc"); IO.text(config, ""); IO.text(new File(directory, "npmrc.global"), "");
        try (InputStream in = context.getAssets().open("runtime-verify.mjs")) { IO.text(new File(directory, "verify.mjs"), new String(IO.bytes(in), StandardCharsets.UTF_8)); }
        ArrayList<String> args = npmArguments(guest, "install", DownloadSource.OFFICIAL);
        args.add("--package-lock-only"); args.add("@deepseek-ai/dsh@" + release.version);
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(30);
        event(epoch, "解析官方依赖 · " + release.version + " · 安装上限 30 分钟");
        run(args, remaining(deadline), epoch);
        run(List.of("/opt/node/bin/node", guest + "/verify.mjs", guest, release.version, release.integrity), 30, epoch);
        String trustedLock = IO.text(new File(directory, "package-lock.json"));
        event(epoch, "下载并安装 " + release.version + " · " + source.title + " · 复用 npm 缓存");
        run(npmArguments(guest, "ci", source), remaining(deadline), epoch);
        if (!trustedLock.equals(IO.text(new File(directory, "package-lock.json")))) throw new IOException("官方依赖锁被改动，已拒绝切换");
        JSONObject pkg = new JSONObject(IO.text(new File(directory, "node_modules/@deepseek-ai/dsh/package.json")));
        String manager = pkg.optString("packageManager", "pnpm@" + RegistryClient.PNPM_VERSION);
        if (!manager.matches("pnpm@[0-9]+\\.[0-9]+\\.[0-9]+(?:\\+sha[0-9]+\\.[a-fA-F0-9]+)?")) throw new IOException("新版配套包管理器尚不支持，请更新客户端");
        String pnpm = manager.substring(5).split("\\+", 2)[0]; UpdatePolicy.version(pnpm);
        RegistryClient.checkVersion(source, "pnpm", pnpm, cancellation);
        event(epoch, "解析官方配套 pnpm " + pnpm);
        new File(directory, "tools").mkdirs();
        IO.text(new File(directory, "tools/npmrc"), ""); IO.text(new File(directory, "tools/npmrc.global"), "");
        ArrayList<String> tools = npmArguments(guest + "/tools", "install", DownloadSource.OFFICIAL);
        tools.add("--package-lock-only"); tools.add("pnpm@" + pnpm);
        run(tools, remaining(deadline), epoch);
        String toolsLock = IO.text(new File(directory, "tools/package-lock.json"));
        event(epoch, "下载配套 pnpm " + pnpm + " · " + source.title);
        run(npmArguments(guest + "/tools", "ci", source), remaining(deadline), epoch);
        if (!toolsLock.equals(IO.text(new File(directory, "tools/package-lock.json")))) throw new IOException("配套 pnpm 依赖锁被改动，已拒绝切换");
        RuntimeStore.Slot slot = new RuntimeStore.Slot(release.version, guest, pnpm);
        if (!versions.valid(slot)) throw new IOException("安装后的 Harness 版本不匹配");
        run(List.of("/opt/node/bin/node", "--expose-internals", slot.cli(), "--version"), 60, epoch);
        return slot;
    }
    private int remaining(long deadline) throws IOException {
        int seconds = (int) TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime());
        if (seconds <= 0) throw new IOException("npm 安装已超过 30 分钟，原版本保留");
        return seconds;
    }
    private ArrayList<String> npmArguments(String prefix, String verb, DownloadSource source) {
        return new ArrayList<>(List.of("/opt/node/bin/node", "/opt/node/lib/node_modules/npm/bin/npm-cli.js", verb,
            "--prefix", prefix, "--omit=dev", "--ignore-scripts", "--save-exact", "--package-lock=true", "--engine-strict=true",
            "--audit=false", "--fund=false", "--loglevel=http", "--registry=" + source.registry, "--replace-registry-host=npmjs",
            "--userconfig=" + prefix + "/npmrc", "--globalconfig=" + prefix + "/npmrc.global", "--cache=/root/.npm"));
    }
    void rollback() {
        synchronized (this) { if (busy || backend != null) return; busy = true; }
        long epoch = generation;
        work.execute(() -> {
            try {
                RuntimeStore.Slot slot = versions.previous(); if (slot == null) throw new IOException("没有可回退的上一版");
                event("隔离测试上一版 " + slot.version); smoke(slot, epoch); cancelled(epoch); versions.activate(slot);
                event("已回退到 " + slot.version + "，项目与插件数据未修改");
            } catch (Exception error) { note("回退失败：" + error); event("回退失败：" + error.getMessage()); }
            finally { if (epoch == generation) { busy = false; event(status); } }
        });
    }
    private void smoke(RuntimeStore.Slot slot, long epoch) throws Exception {
        File temporary = new File(context.getCacheDir(), "harness-smoke-" + UUID.randomUUID());
        File home = new File(temporary, "home"), working = new File(temporary, "workspace"), data = new File(temporary, "profile");
        home.mkdirs(); working.mkdirs(); data.mkdirs();
        Process process = spawn(List.of("/opt/node/bin/node", "--expose-internals", slot.cli(), "web", "--host", "127.0.0.1", "--port", "0", "--no-open"), home, working, data);
        operation = process;
        BlockingQueue<String> urls = new LinkedBlockingQueue<>(1);
        Future<?> output = readers.submit(() -> consume(process, line -> { String url = readyAddress(line); if (url != null) urls.offer(url); }));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        try {
            while (System.nanoTime() < until) {
                cancelled(epoch);
                if (!process.isAlive()) throw new IOException("候选 Harness 提前退出，原版本未切换");
                String url = urls.poll(250, TimeUnit.MILLISECONDS);
                if (url != null) { HarnessPage.check(url); return; }
            }
            throw new IOException("候选 Harness 启动测试超过 120 秒，原版本未切换");
        } finally {
            boolean exited = RuntimeProcesses.stopAndWait(process, 5);
            output.cancel(true); if (operation == process) operation = null;
            if (!exited) throw new IOException("测试服务未能退出，已拒绝切换版本");
            // Isolated smoke files remain in app cache for Android to reclaim; never touch user projects.
        }
    }
    static String readyAddress(String line) {
        Matcher match = Pattern.compile("dsh web:\\s*(http://127\\.0\\.0\\.1:([0-9]{1,5})/?\\?token=[A-Za-z0-9._~-]+)").matcher(line);
        if (!match.find()) return null;
        int port = Integer.parseInt(match.group(2)); return port > 0 && port <= 65535 ? match.group(1) : null;
    }
    private void prepareDirectories() throws IOException {
        RuntimePolicy.requireElf64(new File(rootfs, "opt/node/bin/node"), BuildConfig.RUNTIME_ABI);
        for (String dir : List.of("root/.dsh", "root/.npm", "tmp", "workspace", "opt/harness", "proc", "sys", "dev", "etc")) new File(rootfs, dir).mkdirs();
        // Android's network-selected DNS is inherited via explicit userland resolver addresses.
        File resolv = new File(rootfs, "etc/resolv.conf");
        android.net.ConnectivityManager cm = context.getSystemService(android.net.ConnectivityManager.class);
        android.net.Network network = cm == null ? null : cm.getActiveNetwork();
        android.net.LinkProperties links = network == null ? null : cm.getLinkProperties(network);
        RuntimeDns.update(resolv, links == null ? List.of() : links.getDnsServers());
        File dsh = new File(rootfs, "usr/local/bin/dsh"); dsh.getParentFile().mkdirs();
        RuntimeStore.Slot active = versions.active();
        String cli = active == null ? "/opt/harness/node_modules/@deepseek-ai/dsh/lib/bin.js" : active.cli();
        IO.text(dsh, "#!/bin/sh\nexec /opt/node/bin/node --expose-internals " + RuntimePolicy.quote(cli) + " \"$@\"\n");
        dsh.setExecutable(true, true);
        File pnpm = new File(rootfs, "usr/local/bin/pnpm");
        IO.text(pnpm, "#!/bin/sh\nexec /opt/node/bin/node " + RuntimePolicy.quote(active == null ? "/opt/pnpm/node_modules/pnpm/bin/pnpm.cjs" : active.pnpmCli()) + " \"$@\"\n");
        pnpm.setExecutable(true, true);
    }
    private void cancelled(long epoch) throws IOException { if (generation != epoch) throw new IOException("操作已停止"); }
    private Process spawn(List<String> command) throws IOException {
        return spawn(command, null, workspace, profile);
    }
    private Process spawn(List<String> command, File isolatedHome, File working, File data) throws IOException {
        return spawn(command, isolatedHome, working, data, false);
    }
    Process spawn(List<String> command, File isolatedHome, File working, File data, boolean phoneStorage) throws IOException {
        return spawn(command, isolatedHome, working, data, phoneStorage, List.of());
    }
    private Process spawn(List<String> command, File isolatedHome, File working, File data, boolean phoneStorage, List<String> nativeEnvironment) throws IOException {
        String libs = context.getApplicationInfo().nativeLibraryDir;
        RuntimePolicy.requireElf64(new File(libs, "libproot.so"), BuildConfig.RUNTIME_ABI);
        ArrayList<String> args = new ArrayList<>(List.of(libs + "/libproot.so", "--kill-on-exit", "-0", "-r", rootfs.getPath(),
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", working.getPath() + ":/workspace"));
        if (isolatedHome != null) { new File(isolatedHome, ".dsh").mkdirs(); args.addAll(List.of("-b", isolatedHome.getPath() + ":/root")); }
        // Installation/smoke processes stay private. User processes see the real Android namespace.
        if (phoneStorage) workspaces.addBindings(args, rootfs);
        args.addAll(List.of("-b", data.getPath() + ":/root/.dsh", "-w", "/workspace", "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LANG=C.UTF-8", "TERM=xterm-256color", "TMPDIR=/tmp", "DEBIAN_FRONTEND=noninteractive",
            "PATH=" + RuntimePolicy.GUEST_PATH, "DSH_HOME=/root/.dsh", "DSH_TELEMETRY_DISABLED=1"));
        args.addAll(downloadSource.packageEnvironment());
        args.addAll(nativeEnvironment);
        args.addAll(command);
        ProcessBuilder builder = new ProcessBuilder(args).directory(context.getFilesDir()).redirectErrorStream(true);
        ProotLibraries.configure(builder, new File(libs), new File(context.getCodeCacheDir(), "proot-host-libs"));
        builder.environment().put("PROOT_LOADER", libs + "/libproot-loader.so");
        builder.environment().put("PROOT_TMP_DIR", context.getCacheDir().getPath());
        builder.environment().put("PROOT_NO_SECCOMP", "1");
        ProotCompatibility.configure(builder, rootfs);
        return RuntimeProcesses.launch(builder, context.getCacheDir());
    }
    private void consume(Process process, Consumer<String> line) {
        try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            RuntimeOutput.read(reader, value -> { note(value); line.accept(value); });
        } catch (IOException error) { note("输出流已结束：" + error.getMessage()); }
    }
    private void run(List<String> command, int seconds, long epoch) throws Exception {
        run(command, seconds, epoch, false);
    }
    private void run(List<String> command, int seconds, long epoch, boolean phoneStorage) throws Exception {
        cancelled(epoch);
        Process process = spawn(command, null, workspace, profile, phoneStorage);
        synchronized (this) { if (epoch != generation) { RuntimeProcesses.stopAndWait(process, 3); cancelled(epoch); } operation = process; }
        CommandFailure.Output captured = new CommandFailure.Output();
        Future<?> output = readers.submit(() -> consume(process, captured::add));
        try {
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                RuntimeProcesses.stopAndWait(process, 3); cancelled(epoch);
                throw new CommandFailure(command.get(0), "执行超过 " + seconds + " 秒", captured);
            }
            output.get(5, TimeUnit.SECONDS);
            cancelled(epoch);
            if (process.exitValue() != 0) throw new CommandFailure(command.get(0), "退出码 " + process.exitValue(), captured);
        } finally { if (process.isAlive()) RuntimeProcesses.stopAndWait(process, 3); if (operation == process) operation = null; }
    }
    void start() {
        synchronized (this) { if (busy || backend != null) return; if (!installed()) { event("请先安装本地运行环境"); return; } busy = true; }
        long epoch = generation;
        note("[runtime] 正在启动 Harness " + currentVersion());
        event("正在启动本机 Harness");
        work.execute(() -> {
            try {
                prepareDirectories(); cancelled(epoch);
                int port;
                try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
                RuntimeStore.Slot active = versions.active(); if (active == null) throw new IOException("当前 Harness 安装不完整");
                List<String> nativeEnvironment = List.of();
                if (PhoneControl.supported(active.version)) {
                    try {
                        nativeEnvironment = PhoneControl.get(context).prepare(rootfs);
                    } catch (IOException error) { PhoneControl.get(context).runtimeStopped(); note("[phone] 手机扩展未加载，不影响普通 Harness 使用"); }
                }
                List<String> command = PhoneControl.webCommand(active.cli(), port, !nativeEnvironment.isEmpty());
                Process process = spawn(command, null, workspace, profile, true, nativeEnvironment);
                synchronized (this) { if (epoch != generation) { RuntimeProcesses.stopAndWait(process, 3); cancelled(epoch); } backend = process; }
                readers.execute(() -> consume(process, value -> {
                    String url = readyAddress(value);
                    if (url != null && epoch == generation) {
                        readers.execute(() -> {
                            try {
                                HarnessPage.check(url);
                                synchronized (Engine.this) {
                                    if (epoch == generation && backend == process && process.isAlive() && readyUrl == null) {
                                        readyUrl = url; busy = false;
                                        note("[runtime] Harness 启动成功，页面检查通过");
                                        event("Harness 已在本机运行");
                                    }
                                }
                            } catch (Exception error) { note("页面检查失败：" + error.getMessage()); }
                        });
                    }
                }));
                readers.execute(() -> {
                    try { backendExited(process, epoch, process.waitFor()); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                });
                main.postDelayed(() -> startupTimedOut(process, epoch), 120_000);
            } catch (Exception error) { PhoneControl.get(context).runtimeStopped(); note("启动失败：" + error); synchronized (this) { if (epoch == generation) { busy = false; event("启动失败：" + error.getMessage()); } } }
        });
    }
    synchronized void backendExited(Process process, long epoch, int code) {
        boolean current = backend == process && generation == epoch;
        note("[runtime] Harness 已退出，退出码 " + code + (current ? "（进程自行结束）" : "（已请求停止）"));
        if (current) {
            PhoneControl.get(context).runtimeStopped();
            backend = null; readyUrl = null; busy = false;
            event("Harness 已停止（" + code + "），工作区与安装版本保留");
        }
    }
    synchronized void startupTimedOut(Process process, long epoch) {
        if (backend != process || generation != epoch || readyUrl != null) return;
        note("[runtime] Harness 启动超时：超过 120 秒仍未通过页面检查，正在停止本次进程");
        stop();
        event("启动超过 120 秒，请查看日志");
    }
    synchronized void stop() {
        PhoneControl.get(context).runtimeStopped();
        generation++;
        if (installation != null) installation.cancel();
        Process job = operation, process = backend;
        if (job != null || process != null || busy) note("[runtime] 已请求停止运行进程，工作区与安装版本保留");
        backend = null; readyUrl = null;
        // Blocking waits run off the UI thread. The serial work queue cannot launch another install before cleanup.
        work.execute(() -> {
            try { if (job != null) RuntimeProcesses.stopAndWait(job, 3); if (process != null) RuntimeProcesses.stopAndWait(process, 3); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        if (job != null) RuntimeProcesses.stop(job);
        if (process != null) RuntimeProcesses.stop(process);
        busy = false; event("已停止，工作区与安装版本保留");
    }
    void command(String text, Consumer<String> result) {
        synchronized (this) { if (busy || !installed()) { result.accept("请先完成运行环境安装或等待当前操作结束"); return; } busy = true; }
        long epoch = generation;
        event("正在执行工作区命令");
        work.execute(() -> {
            String message;
            try { prepareDirectories(); run(List.of("/bin/bash", "-lc", text), 600, epoch, true); message = "执行完成，请查看日志"; }
            catch (Exception error) { note("命令失败：" + error); message = error.getMessage(); }
            finally { synchronized (this) { if (epoch == generation) { busy = false; event(readyUrl == null ? "运行环境已就绪" : "Harness 已在本机运行"); } } }
            String response = message; main.post(() -> result.accept(response));
        });
    }
}
