package ai.mengluo.dsh.android;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Picker-only default. Never changes Linux HOME, Harness profiles, or existing project paths. */
final class DirectoryDefaults {
    private DirectoryDefaults() { }
    static String script(Context context, File primaryStorage) throws IOException {
        try (InputStream input = context.getAssets().open("directory-default.js")) {
            return new String(IO.bytes(input), StandardCharsets.UTF_8)
                .replace("\"__MENG_LUO_STORAGE_ROOT__\"", JSONObject.quote(primaryStorage.getPath()));
        }
    }
}
