package ai.mengluo.dsh.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import java.io.*;
import java.util.function.BooleanSupplier;

/** Selection survives restarts; missing permission/path must fail, never silently use another project. */
final class WorkspaceStore {
    private final SharedPreferences preferences;
    final File sharedRoot;
    private final BooleanSupplier permission;

    WorkspaceStore(Context context) {
        this(context.getSharedPreferences("workspaces", 0), Environment.getExternalStorageDirectory(), Environment::isExternalStorageManager);
    }
    WorkspaceStore(SharedPreferences preferences, File sharedRoot, BooleanSupplier permission) {
        this.preferences = preferences; this.sharedRoot = sharedRoot; this.permission = permission;
    }
    boolean hasAccess() { return permission.getAsBoolean(); }
    File selected() {
        String value = preferences.getString("directory", "");
        return value.isEmpty() ? null : new File(value);
    }
    String guestPath() { File selected = selected(); return selected == null ? "/workspace" : selected.getPath(); }
    void requireAccess() throws IOException {
        if (!hasAccess()) throw new IOException("请先在系统设置中允许所有文件访问，或手动切回应用内工作目录");
    }
    File validate(File directory, boolean allowRoot) throws IOException {
        requireAccess(); return WorkspacePaths.validate(sharedRoot, directory, allowRoot);
    }
    File active() throws IOException {
        File directory = selected(); return directory == null ? null : validate(directory, false);
    }
    void select(File directory) throws IOException {
        File checked = directory == null ? null : validate(directory, false);
        if (checked != null) WorkspacePaths.probeWrite(checked);
        SharedPreferences.Editor edit = preferences.edit();
        if (checked == null) edit.remove("directory"); else edit.putString("directory", checked.getPath());
        if (!edit.commit()) throw new IOException("工作目录保存失败，请重试");
    }
}
