package local.station.voicebridge;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class PrivateConfig {
    private PrivateConfig() { }
    static JSONObject read(Context context, String name) throws Exception {
        File file = new File(context.getFilesDir(), name);
        if (!file.exists()) return new JSONObject();
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > 262144) throw new IllegalStateException("local_file_too_large");
                bytes.write(buffer, 0, count);
            }
            return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
    }
    static void write(Context context, String name, JSONObject data) throws Exception {
        File file = new File(context.getFilesDir(), name);
        File temporary = new File(context.getFilesDir(), name + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(data.toString(2).getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (!temporary.renameTo(file)) throw new IllegalStateException("local_save_failed");
    }
}
