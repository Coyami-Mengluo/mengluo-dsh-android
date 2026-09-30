package ai.mengluo.dsh.android;

/** Separate component from the APK update provider; exposes only the selected-file cache. */
public final class OpenedFileProvider extends androidx.core.content.FileProvider {
    @Override public android.os.ParcelFileDescriptor openFile(android.net.Uri uri, String mode) throws java.io.FileNotFoundException {
        if (!"r".equals(mode)) throw new java.io.FileNotFoundException("Shared project snapshots are read-only");
        return super.openFile(uri, mode);
    }
}
