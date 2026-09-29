package ai.mengluo.dsh.android;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** SAF protocol fixture, ONLY in the test APK. Files are confined to its own cache. */
public final class ExportTestProvider extends ContentProvider {
    static final String AUTHORITY = "ai.mengluo.dsh.android.test.export";
    private File base;
    @Override public boolean onCreate() {
        try {
            base = new File(getContext().getCacheDir(), "saf-export-fixtures").getCanonicalFile();
            return base.mkdirs() || base.isDirectory();
        } catch (IOException error) { return false; }
    }
    private void authorize() {
        try {
            int app = getContext().getPackageManager().getApplicationInfo("ai.mengluo.dsh.android", 0).uid;
            if (Binder.getCallingUid() != app && Binder.getCallingUid() != android.os.Process.myUid()) throw new SecurityException("Test app only");
        } catch (android.content.pm.PackageManager.NameNotFoundException error) { throw new SecurityException(error); }
    }
    private File file(Uri uri) throws IOException {
        String id = DocumentsContract.getDocumentId(uri);
        if (id.startsWith("/") || id.contains("\\") || Arrays.asList(id.split("/")).contains("..")) throw new IOException("Invalid fixture id");
        File result = new File(base, id).getCanonicalFile();
        if (!result.toPath().startsWith(base.getCanonicalFile().toPath()) || result.equals(base)) throw new IOException("Outside fixture");
        return result;
    }
    private Uri uri(File file) { return DocumentsContract.buildDocumentUri(AUTHORITY, base.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')); }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        authorize(); Bundle result = new Bundle();
        try {
            if (method.equals("fixture.new")) {
                File root = new File(base, UUID.randomUUID().toString()); if (!root.mkdir()) throw new IOException("Cannot create fixture");
                Files.write(new File(root, "keep.txt").toPath(), "existing-user-file".getBytes(StandardCharsets.UTF_8));
                result.putParcelable("uri", DocumentsContract.buildTreeDocumentUri(AUTHORITY, root.getName())); return result;
            }
            Uri target = extras.getParcelable("uri"); File file = file(target);
            if (method.equals("fixture.remove")) {
                if (!file.getParentFile().equals(base)) throw new IOException("Not a fixture root");
                remove(file); return result;
            }
            if (method.equals("android:createDocument")) {
                String name = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals("..") || name.equals(".")) throw new IOException("Invalid name");
                // Like Android's standard storage provider, normalize names for text/plain requests.
                if ("text/plain".equals(extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE)) && !name.endsWith(".txt")) name += ".txt";
                File child = new File(file, name);
                boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE));
                if (directory ? !child.mkdir() : !child.createNewFile()) throw new IOException("Refuse replacement");
                result.putParcelable("uri", uri(child)); return result;
            }
            if (method.equals("android:deleteDocument")) { if (!file.delete()) throw new IOException("Delete failed"); return result; }
            throw new UnsupportedOperationException(method);
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
    private static void remove(File root) throws IOException {
        try (var paths = Files.walk(root.toPath())) {
            for (var file : paths.sorted(Comparator.reverseOrder()).toArray(java.nio.file.Path[]::new)) Files.delete(file);
        }
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        authorize(); String[] columns = projection != null ? projection : new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        MatrixCursor cursor = new MatrixCursor(columns);
        try {
            File target = file(uri);
            File[] matches = "children".equals(uri.getLastPathSegment()) ? target.listFiles() : new File[]{target};
            if (matches == null) throw new IOException("Cannot enumerate fixture");
            for (File item : matches) {
                Object[] row = new Object[columns.length];
                for (int i = 0; i < columns.length; i++) switch (columns[i]) {
                    case DocumentsContract.Document.COLUMN_DOCUMENT_ID: row[i] = DocumentsContract.getDocumentId(uri(item)); break;
                    case DocumentsContract.Document.COLUMN_DISPLAY_NAME: row[i] = item.getName(); break;
                    case DocumentsContract.Document.COLUMN_MIME_TYPE: row[i] = item.isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream"; break;
                    case DocumentsContract.Document.COLUMN_SIZE: row[i] = item.length(); break;
                    default: row[i] = 0;
                }
                cursor.addRow(row);
            }
            return cursor;
        } catch (IOException error) { cursor.close(); throw new IllegalStateException(error); }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        authorize(); try { return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode)); }
        catch (IOException error) { throw new FileNotFoundException(error.toString()); }
    }
    @Override public String getType(Uri uri) { authorize(); try { return file(uri).isDirectory() ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream"; } catch (IOException e) { return null; } }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
