package local.station.relay;

import java.util.concurrent.atomic.AtomicReference;

/** Test-only entry point: ephemeral numeric loopback ports, no production CLI change. */
public final class RelayHostServer {
    private RelayHostServer() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("host_test_args_required");
        int duration = Integer.parseInt(args[1]);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        try (LocalServiceRelay server = new LocalServiceRelay(args[0], duration, 0,
                Integer.parseInt(args[2]), Integer.parseInt(args[3]), Integer.parseInt(args[4]))) {
            Thread runner = new Thread(() -> {
                try { server.run(); }
                catch (Throwable error) { failure.set(error); }
            }, "host-test-relay");
            runner.start();
            long readyDeadline = System.nanoTime() + 2000000000L;
            int port;
            while ((port = server.boundListenPort()) <= 0) {
                if (failure.get() != null) throw new AssertionError("host server failed", failure.get());
                if (System.nanoTime() >= readyDeadline) throw new AssertionError("host server readiness timeout");
                Thread.sleep(2);
            }
            System.out.println("HOST_RELAY_READY=" + port);
            System.out.flush();
            runner.join(duration + 2000L);
            if (runner.isAlive()) throw new AssertionError("relay did not stop at deadline");
            if (failure.get() != null) throw new AssertionError("host server failed", failure.get());
            System.out.println(server.summary());
        }
    }
}
