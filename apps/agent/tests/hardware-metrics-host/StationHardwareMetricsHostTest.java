package local.station.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

public final class StationHardwareMetricsHostTest {
    private static void check(boolean valid, String label) {
        if (!valid) throw new AssertionError(label);
    }

    private static StationHardwareMetrics.CpuSample idle(int cores, long at, long first, long second, long third) {
        Map<String, Long> states = new LinkedHashMap<>();
        states.put("0/state0", first);
        states.put("0/state1", second);
        if (third >= 0) states.put("1/state0", third);
        return new StationHardwareMetrics.CpuSample(cores, at, states);
    }

    public static void main(String[] args) throws Exception {
        JSONObject mode = StationHardwareMetrics.displayMode(1920, 1080, 60.000004f, true);
        check(mode.getInt("width") == 1920 && mode.getInt("height") == 1080 && mode.getDouble("refreshHz") == 60
                && mode.getBoolean("secureCapable"), "Android output mode keeps physical size and rounded refresh rate");
        check(StationHardwareMetrics.displayMode(0, 1080, 60, false) == null
                && StationHardwareMetrics.displayMode(1920, 1080, Float.NaN, false) == null,
                "unavailable display mode is not fabricated");
        StationHardwareMetrics.CpuSample first = StationHardwareMetrics.parseProcStat("cpu  5 0 5 50 0 0 0 0 0 0");
        StationHardwareMetrics.CpuSample next = StationHardwareMetrics.parseProcStat("cpu  25 0 15 100 20 0 0 0 4 5");
        check(next.percentSince(first) == 30.0, "global load uses aggregate busy delta and excludes guest duplication");
        check(next.percentSince(null) == null, "global load needs two samples");
        check(first.percentSince(next) == null, "backward global counter is invalid");
        check(next.percentSince(next) == null, "zero total delta is invalid");
        check(StationHardwareMetrics.parseProcStat("cpu0 40 0 12 150") == null, "aggregate CPU line required");
        check(StationHardwareMetrics.parseProcStat("cpu 40 0 12 -1") == null, "malformed counter unavailable");

        first = idle(2, 10_000_000, 600_000, 300_000, 100_000);
        next = idle(2, 12_000_000, 1_600_000, 700_000, 1_300_000);
        check(next.percentSince(first) == 35.0, "idle estimate combines both cores and all readable states");
        check(next.percentSince(idle(1, 10_000_000, 600_000, 300_000, -1)) == null,
                "CPU hotplug resets comparison");
        check(next.percentSince(idle(2, 10_000_000, 600_000, 900_000, 100_000)) == null,
                "counter reset is invalid even if summed idle increased");
        check(next.percentSince(idle(2, 10_000_000, 600_000, 300_000, -1)) == null,
                "missing idle state resets comparison");
        check(idle(2, 11_000_000, 2_600_000, 700_000, 1_300_000).percentSince(first) == 0.0,
                "idle interval accounted at wake clips negative busy time");
        check(next.percentSince(StationHardwareMetrics.parseProcStat("cpu 40 0 12 150")) == null,
                "different counter sources never mix units");
        System.out.println("Hardware sampling assertions passed");
    }
}
