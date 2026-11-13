package local.station.voicebridge;

import java.util.concurrent.atomic.AtomicInteger;

/** Final-ASR timer contract only. No HTTP, device, audio or wall-clock waits. */
public final class VoiceTimerParserHostTest {
    private static int assertions;
    private static void check(boolean value, String label) {
        assertions++;
        if (!value) throw new AssertionError(label);
    }
    private static void accepts(String phrase, long duration) {
        LocalVoiceCommands.Decision decision = LocalVoiceCommands.parseFinal(phrase);
        check(decision.kind == LocalVoiceCommands.Kind.MATCH && decision.handled(), "timer match: " + phrase);
        check(decision.command.action == LocalVoiceCommands.Action.CREATE_TIMER, "typed timer action");
        check(decision.command.durationMs == duration, "exact timer duration: " + phrase);
        check(decision.command.percent == -1 && "".equals(decision.command.query), "no free-form actuator arguments");
        AtomicInteger calls = new AtomicInteger();
        LocalVoiceCommands.Result result = LocalVoiceCommands.execute(decision, command -> {
            calls.incrementAndGet();
            check(command.durationMs == duration, "actuator receives bounded duration");
            return LocalVoiceCommands.Result.accepted(command);
        });
        check(result.accepted && calls.get() == 1 && "station_schedule".equals(result.target), "one fake schedule operation");
    }
    private static void rejects(String phrase) {
        LocalVoiceCommands.Decision decision = LocalVoiceCommands.parseFinal(phrase);
        check(decision.kind == LocalVoiceCommands.Kind.REJECTED && decision.handled(), "reject timer: " + phrase);
        AtomicInteger calls = new AtomicInteger();
        LocalVoiceCommands.Result result = LocalVoiceCommands.execute(decision, command -> {
            calls.incrementAndGet(); return LocalVoiceCommands.Result.accepted(command);
        });
        check(!result.accepted && calls.get() == 0, "malformed duration has no effect");
    }
    public static void main(String[] args) {
        accepts("Алиса, поставь таймер на пять минут", 300000L);
        accepts("таймер на 30 секунд", 30000L);
        accepts("таймер 5 секунд", 5000L);
        accepts("засеки две минуты", 120000L);
        accepts("УСТАНОВИ ТАЙМЕР НА ДВАДЦАТЬ ОДНУ МИНУТУ!", 1260000L);
        accepts("Алиса пожалуйста запусти таймер на час и тридцать минут.", 5400000L);
        accepts("включи таймер на одну минуту и пять секунд пожалуйста", 65000L);
        accepts("заведи таймер на один час две минуты три секунды", 3723000L);
        accepts("поставь таймер на полчаса", 1800000L);
        accepts("таймер на полтора часа", 5400000L);
        accepts("засеки полторы минуты", 90000L);
        accepts("таймер на минуту", 60000L);
        accepts("таймер на триста двадцать одну секунду", 321000L);
        accepts("таймер на сто одну минуту", 6060000L);
        accepts("таймер на двадцать четыре часа", 86400000L);
        accepts("таймер на 86400 секунд", 86400000L);
        accepts("таймер на 1440 минут", 86400000L);
        accepts("таймер на 23 часа 59 минут 59 секунд", 86399000L);
        for (String phrase : new String[]{
                "таймер", "таймер на", "засеки", "поставь таймер", "таймер на пять", "таймер на секунд",
                "таймер на ноль минут", "таймер на ноль минут пять секунд", "таймер на четыре секунды",
                "таймер на секунду", "таймер на полторы секунды", "таймер на 0 секунд", "таймер на 00005 секунд",
                "таймер на -5 минут", "таймер на 5.5 минут", "таймер на 5,5 минут", "таймер на +5 минут",
                "таймер на 25 часов", "таймер на 24 часа одну секунду", "таймер на 86401 секунд",
                "таймер на 999999999999999999999 секунд", "таймер на сто сто секунд", "таймер на двадцать десять минут",
                "таймер на пять минут пять минут", "таймер на пять секунд час", "таймер на пять минут час",
                "таймер на пять минут и", "таймер на и пять минут", "таймер на пять минут и и секунду",
                "таймер на пять минут и один", "таймер на пять минут завтра", "таймер на пять минут?",
                "таймер на пять минут и включи музыку", "таймер на пять минут потом домой",
                "не поставь таймер на пять минут", "не надо таймер на пять минут", "не засеки пять минут",
                "\"таймер на пять минут\"", "таймер на пять минут\n", "таймер на пять минут\u0000",
                "засеки 5e3 секунд", "засеки 0x10 минут", "засеки пять минут && домой",
                "поставь таймер на http://127.0.0.1/", "таймер на пять минут пожалуйста пожалуйста"}) rejects(phrase);
        for (String phrase : new String[]{"как поставить таймер на пять минут", "сколько осталось до конца таймера",
                "почему таймер не работает", "расскажи про таймеры"}) {
            check(LocalVoiceCommands.parseFinal(phrase).kind == LocalVoiceCommands.Kind.NO_MATCH, "question remains a question");
        }
        LocalVoiceCommands.Decision music = LocalVoiceCommands.parseFinal("включи песню группа крови");
        check(music.command.action == LocalVoiceCommands.Action.PLAY_QUERY && music.command.durationMs == -1L,
                "music duration sentinel preserved");
        LocalVoiceCommands.Decision volume = LocalVoiceCommands.parseFinal("громкость двадцать один процент");
        check(volume.command.action == LocalVoiceCommands.Action.VOLUME_SET && volume.command.percent == 21
                && volume.command.durationMs == -1L, "percentage grammar preserved");
        System.out.println(assertions + " timer parser assertions passed; fake actions only");
    }
}
