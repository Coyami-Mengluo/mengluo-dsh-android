package ai.mengluo.dsh.android;

import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Active/previous pointer is one synchronous atomic write, never deferred until app exit. */
final class RuntimeStore {
    final File rootfs;
    private final AtomicFile state;
    RuntimeStore(File rootfs) { this.rootfs = rootfs; state = new AtomicFile(new File(rootfs.getParentFile(), "state.json")); }
    static final class Slot {
        final String version, path, pnpm;
        Slot(String version, String path, String pnpm) {
            this.version = UpdatePolicy.version(version); this.pnpm = UpdatePolicy.version(pnpm);
            if (!path.equals("/opt/harness") && !path.matches("/opt/harness-slots/[A-Za-z0-9.-]+")) throw new IllegalArgumentException("无效版本目录");
            this.path = path;
        }
        String cli() { return path + "/node_modules/@deepseek-ai/dsh/lib/bin.js"; }
        String pnpmCli() { return path.equals("/opt/harness") ? "/opt/pnpm/node_modules/pnpm/bin/pnpm.cjs" : path + "/tools/node_modules/pnpm/bin/pnpm.cjs"; }
        JSONObject json() throws JSONException { return new JSONObject().put("version", version).put("path", path).put("pnpm", pnpm); }
        static Slot parse(JSONObject o) throws JSONException { return new Slot(o.getString("version"), o.getString("path"), o.getString("pnpm")); }
    }
    synchronized JSONObject read() throws Exception {
        try (InputStream in = state.openRead()) { return new JSONObject(new String(IO.bytes(in), StandardCharsets.UTF_8)); }
    }
    synchronized Slot active() {
        try {
            JSONObject o = read(); if (!BuildConfig.RUNTIME_ABI.equals(o.optString("abi", "x86_64"))) return null;
            if (o.optInt("schema") != 2 && o.optInt("schema") != 3) return null;
            Slot slot = o.optInt("schema") == 2 ? new Slot(o.getString("version"), "/opt/harness", RegistryClient.PNPM_VERSION) : Slot.parse(o.getJSONObject("active"));
            return valid(slot) ? slot : null;
        } catch (Exception absent) { return null; }
    }
    synchronized Slot previous() {
        try { Slot slot = Slot.parse(read().getJSONObject("previous")); return valid(slot) ? slot : null; }
        catch (Exception absent) { return null; }
    }
    boolean valid(Slot slot) {
        try {
            File directory = RuntimePolicy.inside(rootfs, slot.path.substring(1));
            if (!directory.getCanonicalPath().equals(new File(rootfs.getCanonicalFile(), slot.path.substring(1)).getAbsolutePath())) return false;
            File manifest = RuntimePolicy.inside(directory, "node_modules/@deepseek-ai/dsh/package.json");
            JSONObject pkg = new JSONObject(IO.text(manifest));
            return "@deepseek-ai/dsh".equals(pkg.getString("name")) && slot.version.equals(pkg.getString("version"))
                && RuntimePolicy.inside(rootfs, slot.cli().substring(1)).isFile();
        } catch (Exception invalid) { return false; }
    }
    synchronized void activate(Slot target) throws Exception {
        if (!valid(target)) throw new IOException("候选 Harness 安装不完整，当前版本未改动");
        Slot prior = active();
        JSONObject value = new JSONObject().put("schema", 3).put("abi", BuildConfig.RUNTIME_ABI).put("active", target.json());
        if (prior != null) value.put("previous", prior.json());
        FileOutputStream out = state.startWrite();
        try { out.write(value.toString().getBytes(StandardCharsets.UTF_8)); state.finishWrite(out); }
        catch (Exception e) { state.failWrite(out); throw e; }
    }
}
