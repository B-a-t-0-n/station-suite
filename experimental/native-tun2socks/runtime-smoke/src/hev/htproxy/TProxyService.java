package hev.htproxy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;

/** Exact upstream JNI class/method contract; loading is explicit and hash-pinned. */
public final class TProxyService {
    private static boolean loaded;
    private TProxyService() { }

    public static synchronized void loadVerified(String absolutePath, String expectedSha256) throws Exception {
        if (loaded || expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("single_load_with_sha256_required");
        File file = new File(absolutePath);
        if (!file.isAbsolute() || !file.isFile() || !file.getAbsoluteFile().equals(file.getCanonicalFile())
                || file.length() < 4096 || file.length() > 4 * 1024 * 1024)
            throw new IOException("canonical_bounded_library_required");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (FileInputStream input = new FileInputStream(file)) {
            for (int count; (count = input.read(buffer)) >= 0;) if (count != 0) digest.update(buffer, 0, count);
        }
        StringBuilder observed = new StringBuilder();
        for (byte value : digest.digest()) observed.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        if (!MessageDigest.isEqual(observed.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                expectedSha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) throw new IOException("library_sha256_mismatch");
        System.load(file.getAbsolutePath());
        loaded = true;
    }

    public static native boolean TProxyStartService(String configPath, int fd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    public static native long[] TProxyGetStats();
}
