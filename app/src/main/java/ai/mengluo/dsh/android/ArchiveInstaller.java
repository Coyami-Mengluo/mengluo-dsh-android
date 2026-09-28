package ai.mengluo.dsh.android;

import android.system.Os;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/** Extract only verified bundled archives into a fresh application-private staging directory. */
final class ArchiveInstaller {
    static void verify(File file, String expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[64 * 1024]; int length;
            while ((length = input.read(bytes)) != -1) digest.update(bytes, 0, length);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
        if (!hex.toString().equals(expected)) throw new IOException("运行环境校验失败：" + file.getName());
    }
    static void extract(File archive, File root, int stripComponents, Consumer<String> progress) throws Exception {
        ArrayList<String[]> links = new ArrayList<>();
        long total = 0; int count = 0;
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(new FileInputStream(archive)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName();
                while (name.startsWith("./")) name = name.substring(2);
                for (int i = 0; i < stripComponents; i++) name = name.contains("/") ? name.substring(name.indexOf('/') + 1) : "";
                if (name.isEmpty()) continue;
                File target = RuntimePolicy.inside(root, name);
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("无法创建目录：" + name);
                } else if (entry.isSymbolicLink() || entry.isLink()) {
                    String link = entry.getLinkName();
                    if (link.indexOf('\0') >= 0) throw new IOException("无效链接");
                    links.add(new String[] { name, link, entry.isLink() ? "hard" : "symbolic" });
                } else if (entry.isFile()) {
                    total += entry.getSize();
                    if (total > 2L * 1024 * 1024 * 1024 || entry.getSize() < 0) throw new IOException("解压大小超限");
                    File parent = target.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建父目录");
                    if (Files.isSymbolicLink(target.toPath())) throw new IOException("拒绝覆盖链接");
                    try (OutputStream output = new FileOutputStream(target)) { IO.copy(tar, output); }
                    Os.chmod(target.getPath(), entry.getMode() & 0777);
                }
                if (++count % 200 == 0) progress.accept("解压运行环境 · " + count + " 个文件");
            }
        }
        // Deferring links prevents a later archive entry from writing through a symlink.
        for (String[] link : links) {
            File target = RuntimePolicy.inside(root, link[0]);
            target.getParentFile().mkdirs();
            if (target.exists() || Files.isSymbolicLink(target.toPath())) throw new IOException("重复归档路径：" + link[0]);
            if (link[2].equals("hard")) {
                String name = link[1];
                while (name.startsWith("./")) name = name.substring(2);
                Os.link(RuntimePolicy.inside(root, name).getPath(), target.getPath());
            } else Os.symlink(link[1], target.getPath());
        }
    }
}
