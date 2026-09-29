package ai.mengluo.dsh.android;

import android.system.Os;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/** Extract only verified archives into a fresh application-private staging directory. */
final class ArchiveInstaller {
    private static final long MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024;
    interface FileOperations {
        void chmod(File file, int mode) throws Exception;
        void symlink(String source, File target) throws Exception;
    }
    private static final FileOperations ANDROID_FILES = new FileOperations() {
        public void chmod(File file, int mode) throws Exception { Os.chmod(file.getPath(), mode); }
        public void symlink(String source, File target) throws Exception { Os.symlink(source, target.getPath()); }
    };
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
        extract(archive, root, stripComponents, progress, ANDROID_FILES, MAX_EXTRACTED_BYTES);
    }
    // JVM tests substitute only chmod/symlink; hard-link entries always use the real copy path.
    static void extract(File archive, File root, int stripComponents, Consumer<String> progress,
                        FileOperations files, long maxBytes) throws Exception {
        if (stripComponents < 0 || maxBytes < 0) throw new IllegalArgumentException("Invalid extraction limits");
        ArrayList<String[]> hardLinks = new ArrayList<>(), symbolicLinks = new ArrayList<>();
        Map<File, Integer> regularFiles = new HashMap<>();
        long total = 0; int count = 0;
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new GZIPInputStream(new FileInputStream(archive)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entryPath(entry.getName(), stripComponents);
                if (name.isEmpty()) continue;
                File target = RuntimePolicy.inside(root, name);
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("无法创建目录：" + name);
                } else if (entry.isSymbolicLink() || entry.isLink()) {
                    String link = entry.getLinkName();
                    if (link.indexOf('\0') >= 0) throw new IOException("无效链接");
                    if (entry.isLink()) hardLinks.add(new String[] { name, entryPath(link, stripComponents) });
                    else symbolicLinks.add(new String[] { name, link });
                } else if (entry.isFile()) {
                    total = addSize(total, entry.getSize(), maxBytes);
                    File parent = target.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建父目录");
                    if (Files.isSymbolicLink(target.toPath())) throw new IOException("拒绝覆盖链接");
                    try (OutputStream output = new FileOutputStream(target)) { IO.copy(tar, output); }
                    int mode = entry.getMode() & 0777;
                    files.chmod(target, mode);
                    regularFiles.put(target, mode);
                }
                if (++count % 200 == 0) progress.accept("解压运行环境 · " + count + " 个文件");
            }
        }
        // Android SELinux denies link(2) to ordinary apps. Materialize archive hard links
        // as independent regular files, including the original executable bits. Resolve
        // forward references before creating any symlinks; never follow a link as input.
        while (!hardLinks.isEmpty()) {
            boolean resolved = false;
            for (Iterator<String[]> iterator = hardLinks.iterator(); iterator.hasNext();) {
                String[] link = iterator.next();
                File source = RuntimePolicy.inside(root, link[1]);
                Integer mode = regularFiles.get(source);
                if (mode == null) continue;
                if (!Files.isRegularFile(source.toPath(), LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("硬链接来源不是普通文件：" + link[1]);
                File target = newLinkTarget(root, link[0]);
                total = addSize(total, Files.size(source.toPath()), maxBytes);
                Files.copy(source.toPath(), target.toPath(), LinkOption.NOFOLLOW_LINKS);
                files.chmod(target, mode);
                regularFiles.put(target, mode);
                iterator.remove(); resolved = true;
            }
            if (!resolved) throw new IOException("硬链接来源缺失、循环或不是归档中的普通文件：" + hardLinks.get(0)[0]);
        }
        // Deferring symlinks prevents later archive data from being written through them.
        for (String[] link : symbolicLinks) files.symlink(link[1], newLinkTarget(root, link[0]));
    }
    private static File newLinkTarget(File root, String name) throws IOException {
        File target = RuntimePolicy.inside(root, name);
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建链接父目录：" + name);
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("重复归档路径：" + name);
        return target;
    }
    private static long addSize(long total, long size, long limit) throws IOException {
        if (size < 0 || size > limit - total) throw new IOException("解压大小超限");
        return total + size;
    }
    private static String entryPath(String name, int stripComponents) throws IOException {
        if (name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0 || name.startsWith("/"))
            throw new IOException("拒绝无效归档路径");
        for (String part : name.split("/")) if (part.equals("..")) throw new IOException("拒绝归档路径穿越");
        while (name.startsWith("./")) name = name.substring(2);
        for (int i = 0; i < stripComponents; i++) name = name.contains("/") ? name.substring(name.indexOf('/') + 1) : "";
        return name;
    }
}
