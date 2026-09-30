package ai.mengluo.dsh.android;

import java.io.*;
import java.nio.file.*;

/** Public project directories only; never relocate the Linux runtime or alias /workspace. */
final class WorkspacePaths {
    static File validate(File sharedRoot, File directory, boolean allowRoot) throws IOException {
        if (directory == null || !directory.isAbsolute()) throw new IOException("请选择手机存储中的完整目录");
        Path root = sharedRoot.getCanonicalFile().toPath();
        Path path = directory.getAbsoluteFile().toPath();
        // Check before relativize: some host Path implementations normalize traversal there.
        for (Path part : path) if (part.toString().equals(".") || part.toString().equals(".."))
            throw new IOException("目录路径不能包含 . 或 ..");
        if (!path.startsWith(root)) throw new IOException("请选择手机内部存储中的目录");
        Path relative = root.relativize(path);
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals(".") || name.equals("..") || name.indexOf(':') >= 0 || name.indexOf('\\') >= 0
                || name.chars().anyMatch(c -> c < 32 || c == 127)) throw new IOException("目录路径包含不支持的字符");
        }
        if (!relative.toString().isEmpty() && relative.getName(0).toString().equalsIgnoreCase("Android"))
            throw new IOException("请选择 Documents、Download 等公共目录，不支持 Android 应用数据目录");
        Path current = root;
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("请选择实际目录，不支持符号链接");
        }
        File resolved = path.toFile().getCanonicalFile();
        if (!resolved.toPath().startsWith(root)) throw new IOException("目录不在手机内部存储中");
        if (!allowRoot && resolved.toPath().equals(root)) throw new IOException("请选择具体项目文件夹，不要选择整个手机存储");
        if (!resolved.isDirectory()) throw new IOException("目录已移动或不存在，请重新选择；不会自动切回其他目录");
        if (!resolved.canRead() || !resolved.canWrite()) throw new IOException("目录不可读写，请检查所有文件访问权限");
        return resolved;
    }

    static void probeWrite(File directory) throws IOException {
        Path probe = Files.createTempFile(directory.toPath(), ".mengluo-access-", ".tmp");
        try { Files.write(probe, new byte[]{42}); }
        finally { Files.deleteIfExists(probe); }
    }
}
