package vendor.amlogic.hardware.systemcontrol.V1_1;

import java.util.ArrayList;
import java.util.List;
import vendor.amlogic.hardware.systemcontrol.V1_0.ISystemControl.readSysfsCallback;

/** In-memory transport only: deliberately has no Android, filesystem or network access. */
public final class ISystemControl {
    public static final String BRIGHTNESS = "/sys/class/backlight/fb5/brightness";
    public static final String MAXIMUM = "/sys/class/backlight/fb5/max_brightness";
    public static final String NAME = "/sys/class/graphics/fb5/name";
    public static final List<String> calls = new ArrayList<String>();
    public static String mode;
    public static String value;
    private static int writes;

    public static void configure(String scenario, String initial) {
        mode = scenario;
        value = initial;
        writes = 0;
        calls.clear();
    }

    public static ISystemControl getService(boolean retry) {
        calls.add("service:" + retry);
        if (retry) throw new AssertionError("Blocking service acquisition");
        return "no-service".equals(mode) ? null : new ISystemControl();
    }

    public void readSysfs(String path, readSysfsCallback callback) {
        String text;
        if (MAXIMUM.equals(path)) {
            calls.add("read:max");
            text = "wrong-max".equals(mode) ? "1023" : "255";
        } else if (NAME.equals(path)) {
            calls.add("read:name");
            text = "wrong-name".equals(mode) ? "YNDX LED screen" : "YNDX LED ring";
        } else if (BRIGHTNESS.equals(path)) {
            calls.add("read:brightness");
            if ("missing-callback".equals(mode)) return;
            text = "null-callback".equals(mode) ? null : value;
            if (writes > 0 && "bad-readback".equals(mode)) text = "NaN";
        } else {
            throw new AssertionError("Unexpected read: " + path);
        }
        if (text != null && "whitespace".equals(mode)) text = " \n" + text + "\t";
        callback.onValues("bad-result".equals(mode) ? -1 : 0, text);
        if (BRIGHTNESS.equals(path) && "duplicate-callback".equals(mode)) {
            callback.onValues(0, value);
        }
    }

    public boolean writeSysfs(String path, String text) {
        // Every test fails loudly if production code ever broadens its write target.
        if (!BRIGHTNESS.equals(path)) throw new AssertionError("Unexpected write: " + path);
        calls.add("write:brightness:" + text);
        writes++;
        if (!"unapplied-write".equals(mode)) value = text;
        return !"false-write-applied".equals(mode);
    }
}
