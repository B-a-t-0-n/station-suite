package local.station.agent;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Paired RFCOMM remote and recovery channel; no commands depend on the PC. */
final class BluetoothRecoveryServer implements Runnable {
    private static final String TAG = "StationBtRecovery";
    static final UUID SERVICE_UUID = UUID.fromString("6f1d2a7e-9b3c-4a5d-8e7f-1a2b3c4d5e6f");
    private static final String SERVICE_NAME = "StationLabRecovery";
    private static final int MAX_COMMAND_CHARS = 512;
    private static final long IDLE_MILLIS = 30000;

    private final AgentService service;
    private final Object commandLock = new Object();
    private final Set<Client> clients = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile boolean running;
    private volatile BluetoothServerSocket server;
    private Thread thread;
    private ThreadPoolExecutor workers;
    private ScheduledThreadPoolExecutor idleTimer;

    BluetoothRecoveryServer(AgentService service) { this.service = service; }

    synchronized void start() {
        if (running) return;
        running = true;
        workers = new ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), runnable -> daemon(runnable, "StationBtClient"),
                new ThreadPoolExecutor.AbortPolicy());
        idleTimer = new ScheduledThreadPoolExecutor(1,
                runnable -> daemon(runnable, "StationBtIdle"));
        idleTimer.scheduleAtFixedRate(() -> {
            long now = android.os.SystemClock.elapsedRealtime();
            for (Client client : clients) {
                if (now - client.lastActivity > IDLE_MILLIS) close(client.socket);
            }
        }, 5, 5, TimeUnit.SECONDS);
        thread = daemon(this, "StationBtRecovery");
        thread.start();
    }

    synchronized void stop() {
        running = false;
        BluetoothServerSocket current = server;
        server = null;
        if (current != null) {
            try { current.close(); } catch (IOException ignored) { }
        }
        for (Client client : clients) close(client.socket);
        if (workers != null) workers.shutdownNow();
        if (idleTimer != null) idleTimer.shutdownNow();
        if (thread != null) thread.interrupt();
        thread = null;
    }

    @Override public void run() {
        while (running) {
            BluetoothServerSocket listener = null;
            try {
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter == null) return;
                if (!adapter.isEnabled()) {
                    pauseBeforeRetry();
                    continue;
                }
                listener = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID);
                server = listener;
                Log.i(TAG, "RFCOMM recovery listening");
                while (running) {
                    BluetoothSocket socket = listener.accept();
                    Client client = new Client(socket);
                    clients.add(client);
                    try {
                        workers.execute(() -> handle(client));
                    } catch (RejectedExecutionException error) {
                        clients.remove(client);
                        close(socket);
                    }
                }
            } catch (IOException | SecurityException error) {
                if (running) Log.w(TAG, "RFCOMM listener unavailable: " + error.getClass().getSimpleName());
            } finally {
                if (server == listener) server = null;
                if (listener != null) {
                    try { listener.close(); } catch (IOException ignored) { }
                }
            }
            if (running) pauseBeforeRetry();
        }
    }

    private void handle(Client client) {
        try {
            BufferedReader input = new BufferedReader(new InputStreamReader(
                    client.socket.getInputStream(), StandardCharsets.UTF_8));
            Writer output = new OutputStreamWriter(client.socket.getOutputStream(), StandardCharsets.UTF_8);
            String line;
            while (running && (line = readCommand(input)) != null) {
                client.lastActivity = android.os.SystemClock.elapsedRealtime();
                String reply;
                if (line.trim().startsWith("DISPLAY ")) reply = execute(line.trim());
                else synchronized (commandLock) { reply = execute(line.trim()); }
                output.write(reply + "\n");
                output.flush();
                client.lastActivity = android.os.SystemClock.elapsedRealtime();
            }
        } catch (IOException error) {
            if (running) Log.i(TAG, "RFCOMM client disconnected");
        } finally {
            clients.remove(client);
            close(client.socket);
        }
    }

    private String execute(String line) {
        try {
            if (line.startsWith("DISPLAY ")) {
                JSONObject request;
                try { request = StationDisplaySettings.parseObject(line.substring(8)); }
                catch (Exception invalid) { request = new JSONObject(); }
                JSONObject response = service.displayCommand(request);
                return (response.optBoolean("ok", false) ? "OK " : "ERR ") + response;
            }
            if (line.startsWith("EQUALIZER ")) {
                JSONObject request;
                try {
                    JSONTokener input = new JSONTokener(line.substring(10));
                    Object decoded = input.nextValue();
                    if (!(decoded instanceof JSONObject) || input.nextClean() != 0)
                        throw new IllegalArgumentException("invalid_equalizer_request");
                    request = (JSONObject) decoded;
                }
                catch (Exception invalid) { request = new JSONObject(); }
                JSONObject response = service.equalizerCommand(request);
                return (response.optBoolean("ok", false) ? "OK " : "ERR ") + response;
            }
            if (line.startsWith("SCHEDULE ")) {
                JSONObject request;
                try { request = new JSONObject(line.substring(9)); }
                catch (Exception invalid) { return failure("Неверные параметры часов"); }
                JSONObject response = service.scheduleCommand(request);
                return (response.optBoolean("ok", false) ? "OK " : "ERR ") + response;
            }
            String[] words = line.split("\\s+");
            String command = words[0].toUpperCase(Locale.US);
            String media = BluetoothMediaCommands.execute(words, service);
            if (media != null) return media;
            if ("PING".equals(command) && words.length == 1) return "OK PONG";
            if ("STATUS".equals(command) && words.length == 1) return "OK " + status();
            if ("APPS".equals(command) && words.length == 1) {
                JSONObject result = new JSONObject(service.apps());
                if (result.has("error")) return failure("Не удалось получить список приложений");
                return "OK " + result.put("ok", true);
            }
            if ("HOME".equals(command) && words.length == 1) {
                service.goHomeFromBluetooth();
                return success("HOME");
            }
            if ("STOP_VPN".equals(command) && words.length == 1) {
                service.stopVpnFromBluetooth();
                return "OK " + new JSONObject().put("ok", true).put("accepted", true)
                        .put("reply", "VPN_STOP_REQUESTED");
            }
            if ("START_AGENT".equals(command) && words.length == 1) {
                service.startAgentFromBluetooth();
                return success("AGENT");
            }
            if ("LAUNCH".equals(command) && words.length == 3) {
                if (!words[1].matches("[A-Za-z0-9_.]+")
                        || !words[2].matches("[A-Za-z0-9_.$]+")) return failure("Некорректное приложение");
                JSONObject result = new JSONObject(service.launch(words[1], words[2]));
                if (!result.optBoolean("ok")) return failure("Приложение недоступно для запуска");
                return "OK " + result;
            }
            if ("VOLUME".equals(command) && words.length == 2) {
                int value;
                try { value = Integer.parseInt(words[1]); }
                catch (NumberFormatException error) { return failure("Неверная громкость"); }
                if (value < 0 || value > 100) return failure("Громкость должна быть от 0 до 100");
                return volumeResult(service.setVolumePercent(value));
            }
            if (("KEY".equals(command) || "MEDIA".equals(command)) && words.length == 2) {
                return key(words[1].toUpperCase(Locale.US));
            }
            return failure("Неизвестная команда пульта");
        } catch (Exception error) {
            return failure("Станция не смогла выполнить команду");
        }
    }

    private JSONObject status() throws Exception {
        JSONObject value = service.bluetoothStatus();
        if (value.has("error")) return value;
        // Bluetooth metadata is sufficient to render the player without exposing its MP3 URL.
        value.remove("url");
        value.put("title", AgentMediaQueue.label(value.optString("title", "")));
        value.put("artist", AgentMediaQueue.label(value.optString("artist", "")));
        value.put("ok", true);
        value.put("agent", true);
        value.put("protocol", 2);
        value.put("legacyVpn", StationVpnService.isActive());
        value.put("dnsVpn", DnsGuardVpnService.isActive());
        value.put("vpn", StationVpnService.isActive() || DnsGuardVpnService.isActive());
        value.put("capabilities", new JSONObject().put("navigation", false).put("home", true)
                .put("apps", true).put("launch", true).put("volume", true)
                .put("player", true).put("seek", true).put("queue", true).put("rescue", true)
                .put("equalizer", true)
                .put("displaySettings", true)
                .put("schedules", value.optJSONObject("scheduleRuntime") != null
                        && value.optJSONObject("scheduleRuntime").optBoolean("ready", false)));
        return value;
    }

    private String key(String key) throws Exception {
        if ("HOME".equals(key)) {
            service.goHomeFromBluetooth();
            return success("HOME");
        }
        if ("VOLUME_UP".equals(key)) return volumeResult(service.adjustVolume(1));
        if ("VOLUME_DOWN".equals(key)) return volumeResult(service.adjustVolume(-1));
        if ("MUTE".equals(key) || "VOLUME_MUTE".equals(key)) {
            return volumeResult(service.mute(service.status().optInt("volume", 0) > 0));
        }
        if ("UNMUTE".equals(key)) return volumeResult(service.mute(false));
        if ("PLAY_PAUSE".equals(key)) {
            if (!service.status().optBoolean("prepared")) return failure("В плеере нет подготовленного трека");
            if (service.status().optBoolean("playing")) service.pause(); else service.resume();
        } else if ("PAUSE".equals(key)) service.pause();
        else if ("PLAY".equals(key) || "RESUME".equals(key)) {
            if (!service.status().optBoolean("prepared")) return failure("В плеере нет подготовленного трека");
            service.resume();
        } else if ("STOP".equals(key)) service.stop();
        else if ("NEXT".equals(key)) {
            if (!service.status().optBoolean("hasNext")) return failure("Следующего трека нет");
            service.next();
        } else if ("PREVIOUS".equals(key)) {
            if (!service.status().optBoolean("hasPrevious")) return failure("Предыдущего трека нет");
            service.previous();
        } else if ("UP".equals(key) || "DOWN".equals(key) || "LEFT".equals(key)
                || "RIGHT".equals(key) || "OK".equals(key) || "CENTER".equals(key)
                || "BACK".equals(key) || "MENU".equals(key)) {
            return failure("Для стрелок и системных кнопок подключите Bluetooth HID-пульт");
        } else return failure("Неизвестная кнопка");
        return "OK " + new JSONObject().put("ok", true).put("accepted", true)
                .put("target", "agent_player");
    }

    private static String success(String reply) throws Exception {
        return "OK " + new JSONObject().put("ok", true).put("reply", reply);
    }

    private static String volumeResult(int volume) throws Exception {
        return "OK " + new JSONObject().put("ok", true).put("volume", volume);
    }

    private static String failure(String message) { return "ERR " + message; }

    private static String readCommand(BufferedReader input) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') return line.toString();
            if (value != '\r') line.append((char) value);
            if (line.length() > MAX_COMMAND_CHARS) throw new IOException("Command too long");
        }
        return null;
    }

    private void pauseBeforeRetry() {
        try { Thread.sleep(2000); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void close(BluetoothSocket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static final class Client {
        final BluetoothSocket socket;
        volatile long lastActivity = android.os.SystemClock.elapsedRealtime();
        Client(BluetoothSocket socket) { this.socket = socket; }
    }
}
