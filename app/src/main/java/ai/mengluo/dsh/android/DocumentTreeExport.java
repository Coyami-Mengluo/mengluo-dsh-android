package ai.mengluo.dsh.android;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Creates a fresh child under a user-selected SAF tree. Never merges into an existing project. */
final class DocumentTreeExport implements ProjectExport.Sink {
    private final ContentResolver resolver;
    private final HashMap<String, Uri> directories = new HashMap<>();
    private final Uri marker;
    final String name;
    DocumentTreeExport(ContentResolver resolver, Uri tree, String projectName) throws IOException {
        this.resolver = resolver;
        if (!"content".equals(tree.getScheme()) || !DocumentsContract.isTreeUri(tree)) throw new IOException("请选择保存文件夹");
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cursor = resolver.query(parent, new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || !DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(0)))
                throw new IOException("所选位置不是文件夹");
        }
        String unique = UUID.randomUUID().toString().substring(0, 8);
        name = ProjectExport.safeName(projectName) + "-export-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + unique;
        Uri root = create(parent, name, DocumentsContract.Document.MIME_TYPE_DIR); directories.put("", root);
        // A text/plain request can make Android append .txt and change our marker name.
        marker = create(root, ".mengluo-export-" + unique + ".incomplete", "application/octet-stream");
        try (OutputStream output = open(marker)) {
            output.write("导出尚未完成。若导出中止，这个文件夹可能只有部分内容；App 内原项目未修改。成功后此标记会自动删除。\n".getBytes(StandardCharsets.UTF_8));
        }
    }
    private Uri create(Uri parent, String name, String type) throws IOException {
        Uri created = DocumentsContract.createDocument(resolver, parent, type, name);
        if (created == null || !"content".equals(created.getScheme()) || !Objects.equals(created.getAuthority(), parent.getAuthority()) || created.equals(parent))
            throw new IOException("保存位置无法创建：" + name);
        try (Cursor cursor = resolver.query(created, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || !name.equals(cursor.getString(0)))
                throw new IOException("保存位置无法保留文件名（可能重名或不支持字符），请改用 ZIP：" + name);
        }
        return created;
    }
    private Uri parent(String path) throws IOException {
        String relative = ProjectFiles.relativePath(path);
        int slash = relative.lastIndexOf('/');
        Uri result = directories.get(slash < 0 ? "" : relative.substring(0, slash));
        if (result == null) throw new IOException("导出目录结构不完整");
        return result;
    }
    private static String leaf(String path) { return path.substring(path.lastIndexOf('/') + 1); }
    private OutputStream open(Uri file) throws IOException {
        OutputStream output = resolver.openOutputStream(file, "wt");
        if (output == null) throw new IOException("无法写入保存位置"); return output;
    }
    @Override public void directory(String path) throws IOException {
        directories.put(path, create(parent(path), leaf(path), DocumentsContract.Document.MIME_TYPE_DIR));
    }
    @Override public OutputStream file(String path) throws IOException {
        return open(create(parent(path), leaf(path), "application/octet-stream"));
    }
    void finish() throws IOException {
        if (!DocumentsContract.deleteDocument(resolver, marker)) throw new IOException("文件已复制，但保存位置未能移除未完成标记，请检查导出目录");
    }
}
