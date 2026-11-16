package local.station.vpnprobe;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Explicit app_process control connection, run as a non-excluded test UID. */
public final class ManagedCanary {
    private ManagedCanary() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || android.os.Process.myUid() < 10000)
            throw new IllegalArgumentException("app_uid_and_destination_required");
        String host = ProbeSpec.validateConfigIp(args[0]);
        int port = Integer.parseInt(args[1]);
        if (port < 46080 || port > 46099) throw new IllegalArgumentException("fixed_canary_port_range");
        try (Socket socket = new Socket()) {
            socket.bind(new InetSocketAddress(port));
            socket.setSoTimeout(1500);
            socket.connect(new InetSocketAddress(host, 80), 1200);
            socket.getOutputStream().write(("HEAD /generate_204 HTTP/1.1\r\nHost: scbh.yandex.net\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            ByteArrayOutputStream status = new ByteArrayOutputStream();
            while (status.size() < 96) {
                int value = socket.getInputStream().read();
                if (value == -1 || value == '\n') break;
                if (value != '\r' && (value < 32 || value > 126))
                    throw new java.io.IOException("non_ascii_status");
                if (value != '\r') status.write(value);
            }
            System.out.println("uid=" + android.os.Process.myUid() + " status="
                    + new String(status.toByteArray(), StandardCharsets.US_ASCII));
        }
    }
}
