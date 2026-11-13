package local.station.remote;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Ordered RFCOMM commands; Bluetooth permission and pairing UI belong to the activity. */
final class BluetoothRemoteClient {
    interface Callback {
        void done(JSONObject value, String error);
    }

    private static final UUID SERVICE_UUID =
            UUID.fromString("6f1d2a7e-9b3c-4a5d-8e7f-1a2b3c4d5e6f");
    private static final String PREFS = "station_bluetooth_remote";
    private static final String ADDRESS = "station_address";
    private static final int MAX_REPLY_CHARS = 1024 * 1024;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object connectionLock = new Object();
    private final SharedPreferences preferences;
    private final BluetoothAdapter adapter;
    private final ThreadPoolExecutor commands = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "BluetoothRemoteCommands");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1,
            runnable -> {
                Thread thread = new Thread(runnable, "BluetoothRemoteTimeout");
                thread.setDaemon(true);
                return thread;
            });
    private volatile String address;
    private volatile long generation;
    private volatile BluetoothSocket socket;
    private BufferedReader reader;
    private Writer writer;

    BluetoothRemoteClient(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        address = preferences.getString(ADDRESS, "");
        adapter = BluetoothAdapter.getDefaultAdapter();
        deadlines.setRemoveOnCancelPolicy(true);
    }

    String selectedAddress() { return address; }

    void selectStation(String selected) {
        String value = selected == null ? "" : selected.trim().toUpperCase(Locale.US);
        if (!BluetoothAdapter.checkBluetoothAddress(value)) {
            throw new IllegalArgumentException("Некорректный Bluetooth-адрес Станции");
        }
        if (!value.equals(address)) {
            disconnect();
            address = value;
            preferences.edit().putString(ADDRESS, value).apply();
        }
    }

    boolean isConnected() {
        BluetoothSocket current = socket;
        return !closed.get() && current != null && current.isConnected();
    }

    void connect(Callback callback) { request("PING", callback); }

    void request(String command, Callback callback) {
        if (closed.get() || callback == null) return;
        final long requestGeneration = generation;
        String value = command == null ? "" : command.trim();
        if (value.isEmpty() || value.length() > 512 || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0) {
            deliver(callback, null, "Некорректная команда пульта", requestGeneration);
            return;
        }
        final long queuedAt = SystemClock.elapsedRealtime();
        final String selected = address;
        try {
            commands.execute(() -> perform(value, selected, requestGeneration, queuedAt, callback));
        } catch (RejectedExecutionException error) {
            deliver(callback, null, "Предыдущая команда ещё выполняется", requestGeneration);
        }
    }

    private void perform(String command, String selected, long expected, long queuedAt, Callback callback) {
        if (closed.get()) return;
        if (SystemClock.elapsedRealtime() - queuedAt > 5000) {
            deliver(callback, null, "Команда устарела. Повторите нажатие", expected);
            return;
        }
        try {
            ensureCurrent(expected);
            if (adapter == null) throw new CommandFailure("Телефон не поддерживает Bluetooth");
            if (!adapter.isEnabled()) throw new CommandFailure("Включите Bluetooth на телефоне");
            if (selected.isEmpty()) throw new CommandFailure("Выберите сопряжённую Станцию");
            ensureConnected(selected, expected);
            ensureCurrent(expected);
            // A slow connection attempt must not execute a now-stale state-changing request.
            if (SystemClock.elapsedRealtime() - queuedAt > 5000)
                throw new CommandRejected("Команда устарела. Повторите нажатие");
            BluetoothSocket current = socket;
            if (current == null) throw new IOException();
            AtomicBoolean expired = new AtomicBoolean();
            ScheduledFuture<?> timeout = deadlines.schedule(() -> {
                expired.set(true);
                closeSocket(current);
            }, command.startsWith("VOLUME ") ? 15 : 5, TimeUnit.SECONDS);
            try {
                writer.write(command + "\n");
                writer.flush();
                String reply;
                try {
                    reply = readLine(reader);
                } catch (IOException error) {
                    if (expired.get()) throw new CommandFailure("Станция не ответила вовремя");
                    throw error;
                }
                if (expired.get()) throw new CommandFailure("Станция не ответила вовремя");
                ensureCurrent(expected);
                JSONObject result = parseReply(reply, command);
                deliver(callback, result, null, expected);
            } finally {
                timeout.cancel(false);
            }
        } catch (SecurityException error) {
            resetConnection();
            deliver(callback, null, "Разрешите приложению доступ к Bluetooth", expected);
        } catch (CommandRejected error) {
            // A complete ERR reply has consumed its line. Keep this healthy ordered channel;
            // unsupported features and invalid player state are not transport failures.
            deliver(callback, null, error.getMessage(), expected);
        } catch (CommandFailure error) {
            resetConnection();
            deliver(callback, null, error.getMessage(), expected);
        } catch (IOException | JSONException | RuntimeException error) {
            resetConnection();
            deliver(callback, null, "Нет ответа по Bluetooth. Проверьте Станцию и повторите", expected);
        }
    }

    private void ensureConnected(String selected, long expected) throws IOException {
        if (isConnected()) return;
        resetConnection();
        BluetoothDevice device = adapter.getRemoteDevice(selected);
        if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
            throw new CommandFailure("Сначала сопрягите телефон со Станцией");
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            ensureCurrent(expected);
            if (attempt > 0) {
                try { Thread.sleep(attempt == 1 ? 250 : 750); }
                catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException();
                }
            }
            ensureCurrent(expected);
            BluetoothSocket candidate = device.createRfcommSocketToServiceRecord(SERVICE_UUID);
            synchronized (connectionLock) {
                ensureCurrent(expected);
                socket = candidate;
            }
            ScheduledFuture<?> timeout = deadlines.schedule(() -> closeSocket(candidate),
                    6, TimeUnit.SECONDS);
            try {
                candidate.connect();
                ensureCurrent(expected);
                reader = new BufferedReader(new InputStreamReader(candidate.getInputStream(),
                        StandardCharsets.UTF_8));
                writer = new OutputStreamWriter(candidate.getOutputStream(), StandardCharsets.UTF_8);
                return;
            } catch (IOException error) {
                closeSocket(candidate);
                synchronized (connectionLock) {
                    if (socket == candidate) socket = null;
                }
                if (attempt == 2) throw new CommandFailure("Не удалось подключиться к Станции по Bluetooth");
            } finally {
                timeout.cancel(false);
            }
        }
    }

    private void ensureCurrent(long expected) throws IOException {
        if (closed.get() || Thread.currentThread().isInterrupted()) throw new IOException();
        if (expected != generation) throw new CommandFailure("Станция изменена. Повторите команду");
    }

    private static String readLine(BufferedReader input) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') return line.toString();
            if (value != '\r') line.append((char) value);
            if (line.length() > MAX_REPLY_CHARS) throw new IOException();
        }
        throw new IOException();
    }

    private static JSONObject parseReply(String reply, String command) throws JSONException, CommandFailure {
        if (reply.startsWith("ERR ")) {
            String error = reply.substring(4).trim();
            if (error.startsWith("{")) {
                JSONObject envelope = new JSONObject(error);
                if (typedDisplayFailure(envelope, command)) return envelope;
                error = envelope.optString("error", "Команда не выполнена");
            }
            throw new CommandRejected(error.isEmpty() ? "Команда не выполнена" : error);
        }
        if (!reply.startsWith("OK ")) throw new CommandFailure("Неизвестный ответ Станции");
        String payload = reply.substring(3).trim();
        JSONObject result = payload.startsWith("{") ? new JSONObject(payload)
                : new JSONObject().put("ok", true).put("reply", payload);
        // DISPLAY has a typed failure envelope carrying native readback and a fixed code.
        // Preserve it only for its own command and after validating the complete contract.
        if (typedDisplayFailure(result, command)) return result;
        if (result.has("error") && !result.isNull("error")
                && !result.optString("error", "").isEmpty()) {
            throw new CommandRejected(result.optString("error"));
        }
        if (result.has("ok") && !result.optBoolean("ok", true)) {
            throw new CommandRejected("Команда не выполнена");
        }
        return result;
    }

    private static boolean typedDisplayFailure(JSONObject result, String command) throws CommandRejected {
        if (!command.startsWith("DISPLAY ") || !Boolean.FALSE.equals(result.opt("ok"))) return false;
        try { new DisplayModel.Snapshot(result); return true; }
        catch (IllegalArgumentException invalid) { throw new CommandRejected("Неполный ответ настроек дисплея"); }
    }

    void disconnect() {
        generation++;
        resetConnection();
    }

    private void resetConnection() {
        BluetoothSocket current;
        synchronized (connectionLock) {
            current = socket;
            socket = null;
        }
        closeSocket(current);
    }

    private static void closeSocket(BluetoothSocket current) {
        if (current != null) {
            try { current.close(); } catch (IOException ignored) { }
        }
    }

    private void deliver(Callback callback, JSONObject value, String error, long callbackGeneration) {
        if (closed.get()) return;
        main.postAtTime(() -> {
            if (!closed.get() && callbackGeneration == generation) callback.done(value, error);
        }, this, SystemClock.uptimeMillis());
    }

    void close() {
        if (!closed.compareAndSet(false, true)) return;
        commands.shutdownNow();
        deadlines.shutdownNow();
        disconnect();
        main.removeCallbacksAndMessages(this);
    }

    private static class CommandFailure extends IOException {
        private static final long serialVersionUID = 1L;
        CommandFailure(String message) { super(message); }
    }
    private static final class CommandRejected extends CommandFailure {
        private static final long serialVersionUID = 1L;
        CommandRejected(String message) { super(message); }
    }
}
