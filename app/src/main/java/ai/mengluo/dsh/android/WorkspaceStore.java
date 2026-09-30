package ai.mengluo.dsh.android;

import android.content.Context;
import android.os.Environment;
import java.io.*;
import java.nio.file.Files;
import java.util.List;

/** Exposes Android's storage namespace; Harness, not the shell, owns project selection. */
final class WorkspaceStore {
    final File storageRoot = new File("/storage");
    final File sharedRoot = Environment.getExternalStorageDirectory();

    WorkspaceStore(Context context) { /* Old directory preferences and all project files are left untouched. */ }
    boolean hasAccess() { return Environment.isExternalStorageManager(); }

    void addBindings(List<String> args, File rootfs) throws IOException {
        if (!storageRoot.isDirectory() || !sharedRoot.isDirectory())
            throw new IOException("手机存储尚未就绪，请稍后重试；不会改用运行环境内的同名目录");
        // Android still enforces permissions on this real namespace, including before consent.
        // Never fall back to a misleading private /storage copy after permission is revoked.
        Files.createDirectories(new File(rootfs, "storage").toPath());
        Files.createDirectories(new File(rootfs, "sdcard").toPath());
        // Prefer the original primary path over aliases when PRoot translates getcwd/realpath.
        args.addAll(List.of("-b", storageRoot.getPath() + ":/storage",
            "-b", sharedRoot.getPath() + ":" + sharedRoot.getPath(),
            "-b", sharedRoot.getPath() + ":/sdcard",
            "-b", sharedRoot.getPath() + ":/storage/self/primary"));
    }
}
