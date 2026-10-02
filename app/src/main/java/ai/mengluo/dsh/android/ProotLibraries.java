package ai.mengluo.dsh.android;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/** Resolve Android PRoot dependencies without passing Android preloads to the guest loader. */
final class ProotLibraries {
    private ProotLibraries() {}

    static synchronized void configure(ProcessBuilder builder, File nativeLibraries, File aliases) throws IOException {
        Path directory = aliases.toPath();
        try { Files.createDirectory(directory); }
        catch (FileAlreadyExistsException exists) {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("运行工具库目录不是实际目录，未修改原文件", exists);
        }
        // Android extracts lib*.so, while the pinned talloc binary's SONAME is libtalloc.so.2.
        // The alias points to the APK-installed executable bytes; never copy/patch native libraries.
        alias(directory, "libtalloc.so.2", new File(nativeLibraries, "libtalloc.so").toPath());
        alias(directory, "libandroid-shmem.so", new File(nativeLibraries, "libandroid-shmem.so").toPath());
        builder.environment().remove("LD_PRELOAD");
        builder.environment().put("LD_LIBRARY_PATH", aliases.getAbsolutePath() + ":" + nativeLibraries.getAbsolutePath());
    }

    private static void alias(Path directory, String name, Path target) throws IOException {
        target = target.toAbsolutePath();
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("运行工具库缺失：" + name);
        Path link = directory.resolve(name);
        if (Files.isSymbolicLink(link)) {
            if (Files.readSymbolicLink(link).equals(target)) return;
        } else if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("运行工具库别名被其他文件占用，未修改原文件：" + name);
        }
        // An APK upgrade changes nativeLibraryDir. Replace only our alias, atomically.
        Path temporary = directory.resolve(name + ".tmp-" + UUID.randomUUID());
        try {
            Files.createSymbolicLink(temporary, target);
            Files.move(temporary, link, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
