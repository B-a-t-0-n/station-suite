package local.station.agent;

import android.app.ActivityManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.CpuUsageInfo;
import android.os.Environment;
import android.os.HardwarePropertiesManager;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;
import android.view.Display;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** On-demand local metrics, shared by all web clients. */
final class StationHardwareMetrics {
    private static final long MIN_SAMPLE_MS = 2_000;
    private static final long MAX_SAMPLE_MS = 20_000;
    private static final int MAX_CORES = 64;
    private static final int MAX_IDLE_STATES = 16;
    private static final String CPU_SYSFS = "/sys/devices/system/cpu";
    private static final Pattern IDLE_STATE = Pattern.compile("state[0-9]{1,2}");
    private final ActivityManager activity;
    private final DisplayManager displays;
    private HardwarePropertiesManager hardware;
    private boolean procStatReadable = true;
    private long previousAtMs = -1;
    private long previousAgentCpuMs;
    private int previousCores;
    private CpuSample previousCpu;
    private String cached;

    StationHardwareMetrics(Context context) {
        activity = context.getSystemService(ActivityManager.class);
        displays = context.getSystemService(DisplayManager.class);
        hardware = context.getSystemService(HardwarePropertiesManager.class);
    }

    synchronized String snapshot() throws JSONException {
        long uptimeMs = SystemClock.elapsedRealtime();
        long interval = uptimeMs - previousAtMs;
        if (cached != null && interval >= 0 && interval < MIN_SAMPLE_MS) return cached;

        int cores = Runtime.getRuntime().availableProcessors();
        long agentCpuMs = Process.getElapsedCpuTime();
        CpuSample sample = readCpu();
        Double systemPercent = null;
        Double agentPercent = null;
        if (previousAtMs >= 0 && interval >= MIN_SAMPLE_MS && interval <= MAX_SAMPLE_MS
                && cores == previousCores) {
            if (sample != null) systemPercent = sample.percentSince(previousCpu);
            if (agentCpuMs >= 0 && previousAgentCpuMs >= 0)
                agentPercent = percent(agentCpuMs - previousAgentCpuMs, (double) interval * cores);
        }
        JSONObject cpu = new JSONObject().put("cores", cores)
                .put("percent", value(systemPercent)).put("agentPercent", value(agentPercent))
                .put("source", value(sample == null ? null : sample.source));
        cached = new JSONObject().put("ok", true).put("sampledAtMs", System.currentTimeMillis())
                .put("uptimeMs", uptimeMs).put("cpu", cpu).put("memory", readMemory())
                .put("storage", readStorage()).put("temperatureC", value(readTemperature()))
                .put("display", value(readDisplay())).toString();
        previousAtMs = uptimeMs;
        previousCores = cores;
        previousAgentCpuMs = agentCpuMs;
        previousCpu = sample;
        return cached;
    }

    private JSONObject readMemory() throws JSONException {
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        if (activity != null) {
            try { activity.getMemoryInfo(memory); }
            catch (RuntimeException unavailable) { memory = new ActivityManager.MemoryInfo(); }
        }
        boolean valid = memory.totalMem > 0 && memory.availMem >= 0 && memory.availMem <= memory.totalMem;
        return capacity(memory.totalMem, memory.availMem)
                .put("low", valid ? memory.lowMemory : JSONObject.NULL);
    }

    private static JSONObject readStorage() throws JSONException {
        try {
            StatFs data = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            return capacity(data.getTotalBytes(), data.getAvailableBytes());
        } catch (RuntimeException unavailable) {
            return capacity(-1, -1);
        }
    }

    private JSONObject readDisplay() throws JSONException {
        if (displays == null) return null;
        try {
            Display display = displays.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null || !display.isValid()) return null;
            Display.Mode mode = display.getMode();
            return displayMode(mode.getPhysicalWidth(), mode.getPhysicalHeight(), mode.getRefreshRate(),
                    (display.getFlags() & Display.FLAG_SECURE) != 0);
        } catch (RuntimeException unavailable) { return null; }
    }

    static JSONObject displayMode(int width, int height, float refreshHz, boolean secureCapable) throws JSONException {
        if (width <= 0 || width > 16384 || height <= 0 || height > 16384
                || !Float.isFinite(refreshHz) || refreshHz < 1 || refreshHz > 500) return null;
        return new JSONObject().put("width", width).put("height", height)
                .put("refreshHz", Math.round(refreshHz * 100.0) / 100.0).put("secureCapable", secureCapable);
    }

    private static JSONObject capacity(long total, long available) throws JSONException {
        boolean valid = total > 0 && available >= 0 && available <= total;
        return new JSONObject().put("totalBytes", valid ? total : JSONObject.NULL)
                .put("availableBytes", valid ? available : JSONObject.NULL);
    }

    private Double readTemperature() {
        if (hardware == null) return null;
        try {
            double highest = Double.NEGATIVE_INFINITY;
            for (float celsius : hardware.getDeviceTemperatures(HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU,
                    HardwarePropertiesManager.TEMPERATURE_CURRENT)) {
                if (celsius >= -20 && celsius <= 150) highest = Math.max(highest, celsius);
            }
            return Double.isFinite(highest) ? highest : null;
        } catch (SecurityException denied) { hardware = null; }
        catch (RuntimeException unavailable) { /* Unsupported hardware. */ }
        return null;
    }

    private CpuSample readCpu() {
        if (hardware != null) {
            try {
                long active = 0, total = 0;
                for (CpuUsageInfo core : hardware.getCpuUsages()) {
                    if (core == null) continue;
                    active += core.getActive();
                    total += core.getTotal();
                }
                if (total > 0 && active >= 0 && active <= total)
                    return new CpuSample("hardware_properties", active, total);
            } catch (SecurityException denied) { hardware = null; }
            catch (RuntimeException unavailable) { /* Try kernel counters below. */ }
        }
        if (procStatReadable) {
            try {
                CpuSample cpu = parseProcStat(readLine(new File("/proc/stat")));
                if (cpu != null) return cpu;
                procStatReadable = false;
            } catch (IOException | SecurityException denied) { procStatReadable = false; }
        }
        return readCpuIdle();
    }

    static CpuSample parseProcStat(String line) {
        if (line == null) return null;
        String[] fields = line.trim().split("\\s+");
        if (fields.length < 5 || !"cpu".equals(fields[0])) return null;
        long total = 0, idle = 0;
        try {
            for (int i = 1; i < Math.min(fields.length, 9); i++) {
                long ticks = Long.parseLong(fields[i]);
                if (ticks < 0) return null;
                total = Math.addExact(total, ticks);
                if (i == 4 || i == 5) idle += ticks;
            }
        } catch (IllegalArgumentException | ArithmeticException invalid) { return null; }
        return new CpuSample("proc_stat", total - idle, total);
    }

    private static CpuSample readCpuIdle() {
        long started = System.nanoTime();
        try {
            File cpu = new File(CPU_SYSFS);
            String online = readLine(new File(cpu, "online"));
            boolean[] selected = onlineCores(online);
            if (selected == null) return null;
            Map<String, Long> counters = new LinkedHashMap<>();
            int cores = 0;
            for (int index = 0; index < selected.length; index++) {
                if (!selected[index]) continue;
                File directory = new File(cpu, "cpu" + index + "/cpuidle");
                String[] entries = directory.list();
                if (entries == null) return null;
                int stateCount = 0;
                for (String entry : entries) {
                    if (!IDLE_STATE.matcher(entry).matches()) continue;
                    if (++stateCount > MAX_IDLE_STATES) return null;
                    String measurement = readLine(new File(directory, entry + "/time"));
                    if (measurement == null) return null;
                    long idleMicros = Long.parseLong(measurement);
                    if (idleMicros < 0) return null;
                    counters.put(index + "/" + entry, idleMicros);
                }
                if (stateCount == 0) return null;
                cores++;
            }
            if (!online.equals(readLine(new File(cpu, "online")))) return null;
            long midpointMicros = (started + (System.nanoTime() - started) / 2) / 1000;
            return new CpuSample(cores, midpointMicros, counters);
        } catch (IOException | IllegalArgumentException | SecurityException unavailable) {
            return null;
        }
    }

    private static boolean[] onlineCores(String list) {
        if (list == null || !list.matches("[0-9,\\-]{1,192}")) return null;
        boolean[] result = new boolean[MAX_CORES];
        for (String group : list.split(",", -1)) {
            String[] range = group.split("-", -1);
            if (range.length > 2) return null;
            int first = Integer.parseInt(range[0]);
            int last = range.length == 1 ? first : Integer.parseInt(range[1]);
            if (first > last || last >= MAX_CORES) return null;
            for (int core = first; core <= last; core++) result[core] = true;
        }
        return result;
    }

    private static String readLine(File file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(file), 256)) { return reader.readLine(); }
    }

    private static Object value(Object number) { return number == null ? JSONObject.NULL : number; }

    private static Double percent(long active, double total) {
        if (total <= 0 || active < 0) return null;
        return Math.round(Math.min(100, 100.0 * active / total) * 10.0) / 10.0;
    }

    static final class CpuSample {
        final String source;
        private final long active, total;
        private final int onlineCores;
        private final Map<String, Long> idleStates;

        CpuSample(String source, long active, long total) {
            this(source, active, total, 0, null);
        }

        CpuSample(int onlineCores, long monotonicMicros, Map<String, Long> idleStates) {
            this("cpuidle", 0, monotonicMicros, onlineCores, idleStates);
        }

        private CpuSample(String source, long active, long total, int onlineCores, Map<String, Long> idleStates) {
            this.source = source;
            this.active = active;
            this.total = total;
            this.onlineCores = onlineCores;
            this.idleStates = idleStates;
        }

        Double percentSince(CpuSample previous) {
            if (previous == null || !source.equals(previous.source)) return null;
            if (idleStates == null) {
                long busyDelta = active - previous.active;
                long totalDelta = total - previous.total;
                return busyDelta > totalDelta ? null : percent(busyDelta, totalDelta);
            }
            if (onlineCores != previous.onlineCores || !idleStates.keySet().equals(previous.idleStates.keySet()))
                return null;
            long capacity = (total - previous.total) * onlineCores;
            if (capacity <= 0) return null;
            long idleDelta = 0;
            for (Map.Entry<String, Long> state : idleStates.entrySet()) {
                long delta = state.getValue() - previous.idleStates.get(state.getKey());
                if (delta < 0) return null;
                idleDelta += delta;
            }
            // A state can record its whole sleep interval after the first observation.
            return percent(Math.max(0, capacity - idleDelta), capacity);
        }
    }
}
