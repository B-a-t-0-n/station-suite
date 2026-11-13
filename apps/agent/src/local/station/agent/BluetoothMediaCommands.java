package local.station.agent;

import org.json.JSONObject;

/** Strict additions to RFCOMM protocol 2. No URL, arbitrary intent or slow catalog operation. */
final class BluetoothMediaCommands {
    interface Target {
        JSONObject queue() throws Exception;
        JSONObject seek(String revision, int positionMs) throws Exception;
        JSONObject queuePlay(String revision, int index) throws Exception;
    }
    private BluetoothMediaCommands() { }

    /** Null means that the legacy command dispatcher should handle this command. */
    static String execute(String[] words, Target target) throws Exception {
        if (words.length == 0) return null;
        if ("QUEUE".equalsIgnoreCase(words[0])) {
            if (words.length != 1) return failure("bad_arguments");
            return response(target.queue());
        }
        boolean seek = "SEEK".equalsIgnoreCase(words[0]);
        if (!seek && !"QUEUE_PLAY".equalsIgnoreCase(words[0])) return null;
        if (words.length != 3 || !words[1].matches("[a-f0-9-]{36}:[1-9][0-9]{0,18}"))
            return failure("bad_arguments");
        int number;
        try {
            if (!words[2].matches("0|[1-9][0-9]{0,9}")) return failure("bad_arguments");
            number = Integer.parseInt(words[2]);
        } catch (NumberFormatException invalid) { return failure("bad_arguments"); }
        return response(seek ? target.seek(words[1], number) : target.queuePlay(words[1], number));
    }
    private static String response(JSONObject value) throws Exception {
        return Boolean.TRUE.equals(value.opt("ok")) ? "OK " + value : "ERR " + value;
    }
    static JSONObject rejected(String code) throws Exception {
        String message = "queue_stale".equals(code) ? "Очередь изменилась. Обновите список"
                : "queue_index".equals(code) ? "Трек отсутствует в очереди"
                : "queue_track_unavailable".equals(code) ? "Трек недоступен"
                : "media_unavailable".equals(code) ? "Нет подготовленного трека"
                : "duration_unavailable".equals(code) ? "Длительность трека пока неизвестна"
                : "main_timeout".equals(code) ? "Не удалось подтвердить действие. Проверьте состояние плеера"
                : "seek_position".equals(code) ? "Неверная позиция трека"
                : "media_failure".equals(code) ? "Плеер не смог выполнить команду" : "Неверные параметры команды";
        return new JSONObject().put("ok", false).put("code", code).put("error", message);
    }
    private static String failure(String code) throws Exception { return "ERR " + rejected(code); }
}
