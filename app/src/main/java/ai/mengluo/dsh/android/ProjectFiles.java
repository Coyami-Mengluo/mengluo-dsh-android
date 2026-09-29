package ai.mengluo.dsh.android;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Translates guest project paths without following Linux symlinks on the Android host. */
final class ProjectFiles {
    static final long TEXT_LIMIT = 2L * 1024 * 1024, IMPORT_LIMIT = 32L * 1024 * 1024;
    private final File workspace, rootfs;
    ProjectFiles(File workspace, File rootfs) { this.workspace = workspace; this.rootfs = rootfs; }

    static final class Project {
        final String title, path;
        Project(String title, String path) { this.title = title; this.path = path; }
    }
    static String guestPath(String raw) throws IOException {
        if (raw == null || !raw.startsWith("/") || raw.length() > 4096 || raw.indexOf('\\') >= 0
            || raw.chars().anyMatch(c -> c < 32 || c == 127)) throw new IOException("项目路径无效");
        ArrayList<String> parts = new ArrayList<>();
        for (String part : raw.split("/")) {
            if (part.equals("..")) throw new IOException("项目路径不能包含 ..");
            if (!part.isEmpty() && !part.equals(".")) parts.add(part);
        }
        return "/" + String.join("/", parts);
    }
    static String relativePath(String value) throws IOException {
        if (value == null || value.startsWith("/")) throw new IOException("需要项目内相对路径");
        return guestPath("/" + value).substring(1);
    }
    static String child(String directory, String name) throws IOException {
        if (name == null || name.trim().isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0)
            throw new IOException("请输入单个文件名");
        return relativePath(directory.isEmpty() ? name : directory + "/" + name);
    }
    private static boolean under(String path, String base) { return path.equals(base) || path.startsWith(base + "/"); }
    static boolean runtimePath(String path) {
        for (String reserved : List.of("/dev", "/proc", "/sys", "/root/.dsh", "/opt", "/usr", "/bin", "/sbin", "/lib", "/lib64", "/etc", "/run", "/" + ProotCompatibility.LINK_STORE))
            if (under(path, reserved)) return true;
        return false;
    }
    File resolve(Project project, String relative) throws IOException {
        String root = guestPath(project.path), tail = relativePath(relative);
        if (root.equals("/")) throw new IOException("请在 Harness 中选择具体项目，而不是整个系统根目录");
        String path = root + (tail.isEmpty() ? "" : "/" + tail);
        // These are runtime internals or host/profile bind mounts, not code projects.
        if (runtimePath(path)) throw new IOException("此目录属于运行环境或配置，不能作为代码目录打开");
        File base = under(path, "/workspace") ? workspace : rootfs;
        String suffix = under(path, "/workspace") ? path.substring("/workspace".length()) : path;
        return withoutLinks(base, suffix.startsWith("/") ? suffix.substring(1) : suffix);
    }
    static File withoutLinks(File base, String relative) throws IOException {
        File file = base.getAbsoluteFile();
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("暂不支持符号链接目录");
        for (String part : relativePath(relative).split("/")) {
            if (part.isEmpty()) continue;
            file = new File(file, part);
            if (Files.isSymbolicLink(file.toPath())) throw new IOException("暂不支持符号链接，请在 Harness 中选择实际目录");
        }
        if (!file.getCanonicalFile().toPath().startsWith(base.getCanonicalFile().toPath())) throw new IOException("拒绝项目外路径");
        return file;
    }
    File directory(Project project, String relative) throws IOException {
        File directory = resolve(project, relative);
        if (!directory.isDirectory()) throw new IOException("目录已移动或不存在，请在 Harness 中重新选择并刷新项目列表");
        return directory;
    }
    List<File> list(Project project, String relative) throws IOException {
        File[] files = directory(project, relative).listFiles();
        if (files == null) throw new IOException("无法读取目录");
        Arrays.sort(files, Comparator.comparing((File file) -> !Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS))
            .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER).thenComparing(File::getName));
        return Arrays.asList(files);
    }
    File create(Project project, String directory, String name) throws IOException {
        directory(project, directory);
        File file = resolve(project, child(directory, name));
        if (!file.createNewFile()) throw new IOException("同名文件已存在，未覆盖");
        return file;
    }
    String readText(Project project, String relative) throws IOException {
        File file = resolve(project, relative);
        if (!file.isFile()) throw new IOException("文件不存在或不是普通文件");
        try (InputStream input = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = boundedBytes(input, TEXT_LIMIT);
            String text;
            try { text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
            catch (java.nio.charset.CharacterCodingException error) { throw new IOException("不是 UTF-8 文本，请导出后用相应软件打开"); }
            if (text.indexOf('\0') >= 0) throw new IOException("这不是文本文件，可使用导出功能");
            return text;
        }
    }
    static byte[] boundedBytes(InputStream input, long limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if ((long) output.size() + count > limit) throw new IOException("文件过大，内置编辑器仅支持 2 MB 以内的文本，可使用导出功能");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    void saveText(Project project, String relative, String original, String replacement) throws IOException {
        byte[] bytes = replacement.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > TEXT_LIMIT) throw new IOException("内置编辑器仅保存 2 MB 以内的文本");
        File target = resolve(project, relative);
        java.nio.file.Path temporary = Files.createTempFile(target.getParentFile().toPath(), ".mengluo-save-", ".tmp");
        try {
            Files.write(temporary, bytes);
            if (!readText(project, relative).equals(original)) throw new IOException("文件已被 Harness 或其他操作修改，请重新打开，未覆盖新内容");
            target = resolve(project, relative);
            Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    void importFile(Project project, String directory, String name, InputStream input, File cache) throws IOException {
        directory(project, directory);
        String relative = child(directory, name);
        File target = resolve(project, relative);
        if (target.exists()) throw new IOException("同名文件已存在，未覆盖");
        File staging = File.createTempFile("project-import-", ".partial", cache);
        try {
            try (OutputStream output = new FileOutputStream(staging)) {
                byte[] buffer = new byte[65536]; int length; long total = 0;
                while ((length = input.read(buffer)) != -1) {
                    total += length; if (total > IMPORT_LIMIT) throw new IOException("单文件导入上限 32 MB");
                    output.write(buffer, 0, length);
                }
            }
            // Re-resolve after the picker/copy; never replace a concurrently created file.
            target = resolve(project, relative);
            Files.move(staging.toPath(), target.toPath());
        } finally { Files.deleteIfExists(staging.toPath()); }
    }
    void exportFile(Project project, String relative, OutputStream output) throws IOException {
        File file = resolve(project, relative);
        if (!file.isFile()) throw new IOException("文件不存在或不是普通文件");
        try (InputStream input = Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536]; int length;
            while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
        }
    }
}
