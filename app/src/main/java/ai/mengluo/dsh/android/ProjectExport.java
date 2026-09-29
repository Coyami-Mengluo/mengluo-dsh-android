package ai.mengluo.dsh.android;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

/** Read-only, bounded project tree export. Never follows links or reads runtime/profile mounts. */
final class ProjectExport {
    static final int MAX_ENTRIES = 50_000, MAX_DEPTH = 64;
    static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    interface Progress { void update(String name, long done, long total); }
    interface Sink {
        void directory(String path) throws IOException;
        OutputStream file(String path) throws IOException;
    }
    static final class Entry {
        final String path, source;
        final boolean directory;
        final BasicFileAttributes attributes;
        Entry(String path, String source, BasicFileAttributes attributes) {
            this.path = path; this.source = source; this.attributes = attributes; directory = attributes.isDirectory();
        }
    }
    static final class Plan {
        final ProjectFiles files;
        final ProjectFiles.Project project;
        final String relative, name;
        final ArrayList<Entry> entries = new ArrayList<>();
        final ArrayList<String> skipped = new ArrayList<>();
        int skippedCount, visited, fileCount;
        long bytes;
        Plan(ProjectFiles files, ProjectFiles.Project project, String relative) throws IOException {
            this.files = files; this.project = project; this.relative = ProjectFiles.relativePath(relative);
            name = files.directory(project, relative).getName();
        }
        void skip(String path, String reason) { skippedCount++; if (skipped.size() < 20) skipped.add(path + "（" + reason + "）"); }
        String summary() {
            String text = "共导出 " + fileCount + " 个文件，保留子目录和空文件夹。原项目未修改。";
            if (skippedCount > 0) text += "\n\n跳过 " + skippedCount + " 项：\n" + String.join("\n", skipped)
                + (skippedCount > skipped.size() ? "\n…其余跳过项未展开" : "");
            return text;
        }
    }
    static void check(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("导出已取消");
    }
    static String safeName(String name) {
        String result = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("^[. ]+|[. ]+$", "");
        if (result.isEmpty()) result = "project";
        return result.length() > 80 ? result.substring(0, 80) : result;
    }
    static Plan scan(ProjectFiles files, ProjectFiles.Project project, String relative, BooleanSupplier cancelled,
                     Progress progress) throws IOException {
        Plan plan = new Plan(files, project, relative);
        scan(plan, plan.relative, "", 0, cancelled, progress);
        return plan;
    }
    private static void scan(Plan plan, String source, String path, int depth, BooleanSupplier cancelled,
                             Progress progress) throws IOException {
        check(cancelled);
        if (++plan.visited > MAX_ENTRIES) throw new IOException("文件夹超过 50,000 项，请分子目录导出");
        if (depth > MAX_DEPTH) throw new IOException("文件夹层级超过 64 层，请分子目录导出");
        String guest = ProjectFiles.guestPath(plan.project.path + (source.isEmpty() ? "" : "/" + source));
        if (ProjectFiles.runtimePath(guest)) { plan.skip(path, "运行环境或配置目录"); return; }
        File file = plan.files.resolve(plan.project, source);
        BasicFileAttributes before = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() && !before.isDirectory()) { plan.skip(path, "非普通文件"); return; }
        Entry entry = new Entry(path, source, before); plan.entries.add(entry);
        progress.update("扫描 " + (path.isEmpty() ? plan.name : path) + " · " + plan.visited + " 项", 0, 0);
        if (before.isDirectory()) {
            ArrayList<String> names = new ArrayList<>();
            try (DirectoryStream<Path> children = Files.newDirectoryStream(file.toPath())) {
                for (Path child : children) {
                    check(cancelled);
                    if (names.size() + plan.visited >= MAX_ENTRIES) throw new IOException("文件夹超过 50,000 项，请分子目录导出");
                    names.add(child.getFileName().toString());
                }
            }
            Collections.sort(names);
            for (String name : names) {
                String childSource = ProjectFiles.child(source, name), childPath = ProjectFiles.child(path, name);
                // Identify links without resolving them; do not recurse even into links inside this project.
                File parent = plan.files.resolve(plan.project, source);
                if (Files.isSymbolicLink(new File(parent, name).toPath())) { plan.visited++; plan.skip(childPath, "符号链接"); }
                else scan(plan, childSource, childPath, depth + 1, cancelled, progress);
            }
            unchanged(plan, entry);
        } else {
            if (before.size() > MAX_BYTES - plan.bytes) throw new IOException("文件夹超过 2 GiB，请分子目录导出");
            plan.bytes += before.size(); plan.fileCount++;
        }
    }
    private static File unchanged(Plan plan, Entry entry) throws IOException {
        File file = plan.files.resolve(plan.project, entry.source);
        BasicFileAttributes now = Files.readAttributes(file.toPath(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (now.isDirectory() != entry.directory || (!entry.directory && !now.isRegularFile())
            || now.size() != entry.attributes.size() || !now.lastModifiedTime().equals(entry.attributes.lastModifiedTime())
            || !Objects.equals(now.fileKey(), entry.attributes.fileKey()))
            throw new IOException("源文件夹正在被修改，请暂停写入后重试：" + entry.path);
        return file;
    }
    static void copy(Plan plan, Sink sink, BooleanSupplier cancelled, Progress progress) throws IOException {
        long[] done = {0}; int entries = 0;
        for (Entry entry : plan.entries) {
            check(cancelled); File source = unchanged(plan, entry);
            if (!entry.path.isEmpty()) {
                if (entry.directory) sink.directory(entry.path);
                else try (InputStream input = Files.newInputStream(source.toPath(), LinkOption.NOFOLLOW_LINKS);
                          OutputStream output = sink.file(entry.path)) {
                    byte[] buffer = new byte[64 * 1024]; int count; long copied = 0;
                    while ((count = input.read(buffer)) != -1) {
                        check(cancelled); copied += count;
                        if (copied > entry.attributes.size()) throw new IOException("源文件正在变动：" + entry.path);
                        output.write(buffer, 0, count); done[0] += count;
                        progress.update(entry.path, done[0], plan.bytes);
                    }
                    if (copied != entry.attributes.size()) throw new IOException("源文件正在变动：" + entry.path);
                }
            }
            unchanged(plan, entry); entries++;
            progress.update(entry.path, plan.bytes == 0 ? entries : done[0], plan.bytes == 0 ? plan.entries.size() : plan.bytes);
        }
        // Detect additions/removals and ordinary edits during the copy instead of claiming a full snapshot.
        for (Entry entry : plan.entries) { check(cancelled); unchanged(plan, entry); }
    }
    static void zip(Plan plan, OutputStream destination, BooleanSupplier cancelled, Progress progress) throws IOException {
        String prefix = safeName(plan.name) + "/";
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(destination))) {
            zip.setLevel(1);
            zip.putNextEntry(new ZipEntry(prefix)); zip.closeEntry();
            copy(plan, new Sink() {
                @Override public void directory(String path) throws IOException {
                    zip.putNextEntry(new ZipEntry(prefix + path + "/")); zip.closeEntry();
                }
                @Override public OutputStream file(String path) throws IOException {
                    zip.putNextEntry(new ZipEntry(prefix + path));
                    return new FilterOutputStream(zip) {
                        @Override public void write(byte[] b, int offset, int count) throws IOException { out.write(b, offset, count); }
                        @Override public void close() throws IOException { zip.closeEntry(); }
                    };
                }
            }, cancelled, progress);
        }
    }
}
