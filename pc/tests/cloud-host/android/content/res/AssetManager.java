package android.content.res;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

public final class AssetManager {
    public static final AtomicInteger opens = new AtomicInteger();
    public InputStream open(String name) throws IOException {
        if (!"russian_trusted_bundle.pem".equals(name)) throw new IOException("asset_rejected");
        opens.incrementAndGet();
        return new FileInputStream(new File(System.getProperty("cloud.test.assets"), name));
    }
}
