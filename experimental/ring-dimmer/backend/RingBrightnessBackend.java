import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Experimental fixed-target backend. The shell supervisor owns the probe lease. */
public final class RingBrightnessBackend {
    private static final String BRIGHTNESS = "/sys/class/backlight/fb5/brightness";
    private static final String MAXIMUM = "/sys/class/backlight/fb5/max_brightness";
    private static final String NAME = "/sys/class/graphics/fb5/name";
    private static final String EXPECTED_NAME = "YNDX LED ring";
    private final Object service;
    private final Class<?> callbackClass;
    private final Method read;
    private final Method write;

    private RingBrightnessBackend() throws Exception {
        Class<?> iface = Class.forName("vendor.amlogic.hardware.systemcontrol.V1_1.ISystemControl");
        callbackClass = Class.forName(
            "vendor.amlogic.hardware.systemcontrol.V1_0.ISystemControl$readSysfsCallback");
        service = iface.getMethod("getService", boolean.class).invoke(null, false);
        if (service == null) throw new IllegalStateException("SERVICE_UNAVAILABLE");
        read = iface.getMethod("readSysfs", String.class, callbackClass);
        write = iface.getMethod("writeSysfs", String.class, String.class);
    }

    private String readValue(final String path) throws Exception {
        final String[] value = {null};
        Object callback = Proxy.newProxyInstance(callbackClass.getClassLoader(),
            new Class<?>[] {callbackClass}, new InvocationHandler() {
                public Object invoke(Object proxy, Method method, Object[] args) {
                    if ("onValues".equals(method.getName())) {
                        if (value[0] != null || args == null || args.length != 2
                                || !(args[1] instanceof String)) {
                            throw new IllegalStateException("INVALID_CALLBACK");
                        }
                        // This firmware's result code is not reliable; validate actual contents.
                        value[0] = ((String) args[1]).trim();
                    }
                    return null;
                }
            });
        read.invoke(service, path, callback);
        if (value[0] == null) throw new IllegalStateException("NO_CALLBACK");
        return value[0];
    }

    private static int brightness(String value) {
        if (!value.matches("0|[1-9][0-9]{0,2}")) {
            throw new IllegalArgumentException("INVALID_BRIGHTNESS");
        }
        int result = Integer.parseInt(value);
        if (result > 255) throw new IllegalArgumentException("INVALID_BRIGHTNESS");
        return result;
    }

    private int verifiedCurrent() throws Exception {
        if (!"255".equals(readValue(MAXIMUM)) || !EXPECTED_NAME.equals(readValue(NAME))) {
            throw new IllegalStateException("HARDWARE_MISMATCH");
        }
        return brightness(readValue(BRIGHTNESS));
    }

    private void setAndVerify(int value) throws Exception {
        write.invoke(service, BRIGHTNESS, Integer.toString(value));
        if (brightness(readValue(BRIGHTNESS)) != value) {
            throw new IllegalStateException("WRITE_READBACK_FAILED");
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("USAGE");
        String command = args[0];
        if ("ticks".equals(command) && args.length == 1) {
            // Android's System.nanoTime uses CLOCK_MONOTONIC. Reading this clock
            // does not require the /proc/uptime permission denied to priv_app.
            long ticks = System.nanoTime() / 10000000L;
            if (ticks < 0) throw new IllegalStateException("MONOTONIC_CLOCK_UNAVAILABLE");
            System.out.println("TICKS " + ticks);
            return;
        }
        if (!("current".equals(command) && args.length == 1)
                && !(("dim".equals(command) || "restore".equals(command)) && args.length == 2)) {
            throw new IllegalArgumentException("USAGE");
        }
        int old = args.length == 2 ? brightness(args[1]) : -1;
        if (old >= 0 && old < 32) throw new IllegalArgumentException("ALREADY_DIM");
        int target = old >= 0 ? old * 3 / 4 : -1;
        RingBrightnessBackend backend = new RingBrightnessBackend();
        int current = backend.verifiedCurrent();
        if ("current".equals(command)) {
            System.out.println("CURRENT " + current);
        } else if ("dim".equals(command)) {
            if (current != old) throw new IllegalStateException("OWNER_CHANGED");
            backend.setAndVerify(target);
            System.out.println("DIMMED " + old + " " + target);
        } else {
            if (current != old && current != target) {
                throw new IllegalStateException("OWNER_CHANGED");
            }
            if (current == target) backend.setAndVerify(old);
            System.out.println("RESTORED");
        }
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception error) {
            Throwable reason = error;
            while (reason instanceof InvocationTargetException && reason.getCause() != null) {
                reason = reason.getCause();
            }
            // No caller paths, Binder payloads, credentials, or system logs in diagnostic output.
            String message = reason.getMessage();
            if (message == null || !message.matches("[A-Z_]{1,48}")) message = "BACKEND_UNAVAILABLE";
            System.out.println(message);
            System.exit(1);
        }
    }
}
