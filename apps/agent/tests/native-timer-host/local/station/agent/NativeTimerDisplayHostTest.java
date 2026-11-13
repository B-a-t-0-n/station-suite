package local.station.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Produces the real IPC payloads for an independent Python wire decoder. No sockets are opened. */
public final class NativeTimerDisplayHostTest {
    public static void main(String[] args) throws Exception {
        String id = "01234567-89ab-cdef-0123-456789abcdef";
        Path output = Paths.get(args[0]);
        long[] remaining = {1, 999, 1000, 1001, 599999, 600000, 86399999, 86400000};
        long[] wall = {1794559755475L, 1794559755999L};
        for (long now : wall) {
            for (long left : remaining) {
                Files.write(output.resolve("add-" + now + "-" + left + ".bin"),
                        NativeTimerDisplay.encodeAdd(id, now, left));
            }
        }
        Files.write(output.resolve("remove-1.bin"), NativeTimerDisplay.encodeRemove(NativeTimerDisplay.ownedId(id)));
        Files.write(output.resolve("remove-2.bin"), NativeTimerDisplay.encodeRemove(NativeTimerDisplay.ownedId(id)));
        long[] invalid = {Long.MIN_VALUE, -1, 0, 86400001, Long.MAX_VALUE};
        for (long left : invalid) {
            try {
                NativeTimerDisplay.encodeAdd(id, wall[0], left);
                throw new AssertionError("Invalid remaining time accepted: " + left);
            } catch (IllegalArgumentException expected) { }
        }
        long[] invalidWall = {Long.MIN_VALUE, -1, 0, Long.MAX_VALUE};
        for (long now : invalidWall) {
            try {
                NativeTimerDisplay.encodeAdd(id, now, 1000);
                throw new AssertionError("Invalid wall/deadline accepted: " + now);
            } catch (IllegalArgumentException expected) { }
        }
        for (String invalidId : new String[]{null, "", "foreign-timer", "../unsafe", "01234567-89ab-cdef-0123-456789abcdeg"}) {
            try { NativeTimerDisplay.encodeAdd(invalidId, wall[0], 1000); throw new AssertionError("foreign add accepted"); }
            catch (IllegalArgumentException expected) { }
            try { NativeTimerDisplay.encodeRemove(invalidId); throw new AssertionError("foreign remove accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        System.out.println("Native timer packet generation and 9 boundary rejections passed; no sockets");
    }
}
