package ai.mengluo.dsh.android;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;

/** Android app UIDs cannot rely on native hard links, even in private storage. */
final class ProotCompatibility {
    static final String LINK_STORE = ".l2s";
    private ProotCompatibility() {}

    static void configure(ProcessBuilder builder, File rootfs) throws IOException {
        if (!Files.isDirectory(rootfs.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("运行环境目录不存在或不是实际目录");
        Path store = rootfs.getCanonicalFile().toPath().resolve(LINK_STORE);
        try { Files.createDirectory(store); }
        catch (FileAlreadyExistsException existing) {
            if (!Files.isDirectory(store, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("运行环境硬链接兼容目录不是实际目录，未修改原文件", existing);
        }
        // These backing files belong to the installed environment, not a disposable cache.
        // Never clear them on restart, an APK upgrade, or a failed package installation.
        var command = new ArrayList<>(builder.command());
        if (command.isEmpty()) throw new IOException("缺少运行环境命令");
        command.add(1, "--link2symlink");
        command.add(2, "-L"); // Match dpkg's lstat/readlink expectations for guest symlinks.
        builder.command(command);
        builder.environment().put("PROOT_L2S_DIR", store.toString());
    }
}
