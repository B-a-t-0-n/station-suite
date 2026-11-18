package local.station.voicebridge;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Connects finalized TV requests, live package inventory, GigaChat, and scoped Internet search. */
final class TvVoiceControl implements VoicePipeline.TvHandler {
    private static final Pattern TITLE = Pattern.compile("[\\p{L}\\p{M}\\p{N} ,.:'’!?–—-]{1,"
            + TvVoiceCommand.MAX_TITLE_CHARS + "}");
    private static final Pattern NUMBERS = Pattern.compile("\\p{N}+");
    private final Context app;
    private final TvVoiceCommand.YearFollowUp yearFollowUp = new TvVoiceCommand.YearFollowUp();

    TvVoiceControl(Context context) { this.app = context.getApplicationContext(); }

    @Override public VoicePipeline.TvResult handle(String transcript, VoicePipeline.Chat chat, BooleanSupplier cancelled)
            throws Exception {
        if (cancelled.getAsBoolean()) return null;
        TvVoiceCommand command = yearFollowUp.resolve(transcript, SystemClock.elapsedRealtime());
        if (command == null) return null;
        boolean yearReply = TvVoiceCommand.followUpYear(transcript) != 0;
        PackageManager pm = app.getPackageManager();
        Map<TvVoiceCommand.Provider, Intent> installed = new LinkedHashMap<>();
        for (TvVoiceCommand.Provider provider : TvVoiceCommand.Provider.values()) {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(provider.packageName);
            if (launch == null) launch = pm.getLaunchIntentForPackage(provider.packageName);
            if (launch != null) installed.put(provider, launch);
        }
        if (command.kind == TvVoiceCommand.Kind.LIST) {
            List<String> names = new ArrayList<>();
            for (TvVoiceCommand.Provider provider : installed.keySet()) names.add(provider.label);
            return result(names.isEmpty() ? "Не нашла знакомых видеоприложений."
                    : "На телевизоре установлены " + String.join(", ", names) + ".", "apps_listed", false);
        }
        if (command.kind == TvVoiceCommand.Kind.UNSUPPORTED_SOURCE || command.kind == TvVoiceCommand.Kind.AMBIGUOUS)
            return result("Уточни одно приложение или название фильма.", "unsupported_or_ambiguous", false);
        TvVoiceCommand.Provider requested = command.provider;
        if (requested != null && !installed.containsKey(requested))
            return result("На Станции не установлено приложение " + requested.label + ".", "app_missing", false);
        if (installed.isEmpty()) return result("Не нашла установленных видеоприложений.", "no_apps", false);
        if (command.kind == TvVoiceCommand.Kind.OPEN) {
            if (requested != null && launch(installed.get(requested), cancelled))
                return result("Открываю " + requested.label + ".", "app_opened", true);
            return result("Не получилось открыть приложение.", "launch_unavailable", false);
        }

        // Explicit source and media type come from the utterance, never from generated text.
        EnumSet<TvVoiceCommand.Provider> scope = requested == null
                ? EnumSet.copyOf(installed.keySet()) : EnumSet.of(requested);
        String title = correctedTitle(command.title, chat, cancelled);
        List<TvVideoSearch.Hit> hits = search(scope, title, command.title, command.mediaType, command.year, cancelled);
        if (cancelled.getAsBoolean()) return result("Поиск отменён.", "cancelled", false);
        hits.sort(Comparator.comparingInt((TvVideoSearch.Hit hit) -> hit.score).reversed()
                .thenComparingInt(hit -> hit.provider.ordinal()).thenComparing(hit -> hit.page));
        for (TvVideoSearch.Hit hit : hits) {
            if (hit.target == null) continue;
            String years = command.year == 0 ? conflictingYears(hits, hit) : "";
            if (!years.isEmpty()) {
                yearFollowUp.remember(command, SystemClock.elapsedRealtime());
                return result("Нашла версии " + years + " годов. Скажи, какой год нужен.", "year_ambiguous", false);
            }
            boolean play = command.kind == TvVoiceCommand.Kind.PLAY;
            String target = hit.launchTarget(play);
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(target))
                    .setPackage(hit.provider.packageName).addCategory(Intent.CATEGORY_BROWSABLE);
            if (play) view.putExtra("android.intent.extra.START_PLAYBACK", true);
            if (pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY) != null && launch(view, cancelled)) {
                if (play && !target.equals(hit.target))
                    return result("Включаю «" + hit.displayTitle + "» в Окко.", "playback_requested", true);
                return result("Нашла «" + hit.displayTitle + "» в " + hit.provider.label + ". Открываю.", "title_opened", true);
            }
        }
        if (yearReply) {
            if (cancelled.getAsBoolean()) return result("Поиск отменён.", "cancelled", false);
            yearFollowUp.remember(command.withYear(0), SystemClock.elapsedRealtime());
            return result("Не нашла «" + title + "» за " + command.year + " год. Можешь назвать другой год.",
                    "year_no_match", false);
        }
        if (requested != null) {
            Intent search = requested == TvVoiceCommand.Provider.OKKO
                    ? new Intent(Intent.ACTION_VIEW, Uri.parse("okko://search")).addCategory(Intent.CATEGORY_BROWSABLE)
                    : new Intent(Intent.ACTION_SEARCH).putExtra(SearchManager.QUERY, title);
            search.setPackage(requested.packageName);
            ResolveInfo resolution = pm.resolveActivity(search, 0);
            ActivityInfo activity = resolution == null ? null : resolution.activityInfo;
            if (activity != null && activity.exported && activity.enabled
                    && requested.packageName.equals(activity.packageName)
                    && launch(search.setClassName(activity.packageName, activity.name), cancelled))
                return result(requested == TvVoiceCommand.Provider.OKKO
                        ? "Не нашла подходящую карточку. Открываю раздел поиска в Окко."
                        : "Открываю поиск «" + title + "» в " + requested.label + ".", "provider_search_opened", true);
            if (launch(installed.get(requested), cancelled))
                return result("В интернете не нашла подходящую карточку. Открываю " + requested.label + ".",
                        "provider_opened_no_match", true);
        }
        return result("Не нашла «" + title + "» среди подключённых видеосервисов.", "no_match", false);
    }

    private static String conflictingYears(List<TvVideoSearch.Hit> hits, TvVideoSearch.Hit selected) {
        if (selected.year == 0 || selected.mediaType == null) return "";
        TreeSet<Integer> years = new TreeSet<>();
        for (TvVideoSearch.Hit hit : hits) {
            if (hit.target != null && hit.year != 0 && selected.mediaType.equals(hit.mediaType)
                    && (hit.score == selected.score || hit.provider == TvVoiceCommand.Provider.OKKO
                        && hit.provider == selected.provider))
                years.add(hit.year);
        }
        if (years.size() < 2) return "";
        List<String> labels = new ArrayList<>();
        for (int year : years) labels.add(Integer.toString(year));
        return String.join(", ", labels);
    }

    private static String correctedTitle(String original, VoicePipeline.Chat chat, BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) return original;
        try {
            String prompt = "Восстанови написание названия фильма, сериала или видео, записанного на слух. "
                    + "Исправляй фонетические ошибки, особенно безударные гласные. "
                    + "Например, простаквашено → Простоквашино. "
                    + "Не меняй произведение, не добавляй ссылку, сервис или год. "
                    + "Верни только JSON с полем title. Исходное название: "
                    + JSONObject.quote(original);
            JSONObject response = chat.ask(prompt);
            if (response == null || !Boolean.TRUE.equals(response.opt("ok"))) return original;
            String text = response.optString("text").trim();
            if (text.regionMatches(true, 0, "```json", 0, 7) && text.endsWith("```"))
                text = text.substring(7, text.length() - 3).trim();
            if (text.length() > 350 || !text.startsWith("{") || !text.endsWith("}")) return original;
            JSONObject parsed = new JSONObject(text);
            Object value = parsed.opt("title");
            if (parsed.length() != 1 || !(value instanceof String)) return original;
            String corrected = ((String) value).trim();
            if (!TITLE.matcher(corrected).matches()) return original;
            return closeTitle(original, corrected) ? corrected : original;
        } catch (Exception unavailable) {
            return original;
        }
    }

    private static boolean closeTitle(String previous, String candidate) {
        String before = TvVideoSearch.normalize(previous);
        String after = TvVideoSearch.normalize(candidate);
        if (before.equals(after)) return true;
        if (before.isEmpty() || after.isEmpty() || !sameNumbers(before, after)) return false;
        if (Math.abs(before.length() - after.length()) > Math.max(2, before.length() / 4)) return false;
        int[] row = new int[after.length() + 1];
        for (int j = 0; j < row.length; j++) row[j] = j;
        for (int i = 1; i <= before.length(); i++) {
            int previousDiagonal = row[0]; row[0] = i;
            for (int j = 1; j < row.length; j++) {
                int previousRow = row[j];
                row[j] = Math.min(Math.min(row[j] + 1, row[j-1] + 1),
                        previousDiagonal + (before.charAt(i-1) == after.charAt(j-1) ? 0 : 1));
                previousDiagonal = previousRow;
            }
        }
        return row[after.length()] <= Math.max(2, before.length() / 4);
    }

    private static boolean sameNumbers(String before, String after) {
        Matcher original = NUMBERS.matcher(before);
        Matcher corrected = NUMBERS.matcher(after);
        while (original.find())
            if (!corrected.find() || !original.group().equals(corrected.group())) return false;
        return !corrected.find();
    }

    private static List<TvVideoSearch.Hit> search(EnumSet<TvVoiceCommand.Provider> scope, String title, String original,
                                                 String mediaType, int year, BooleanSupplier cancelled)
            throws InterruptedException {
        List<TvVideoSearch.Hit> hits = new ArrayList<>();
        if (cancelled.getAsBoolean()) return hits;
        ExecutorService workers = Executors.newFixedThreadPool(scope.size(), runnable -> {
            Thread thread = new Thread(runnable, "StationTvSearch");
            thread.setDaemon(true); return thread;
        });
        CompletionService<List<TvVideoSearch.Hit>> waiting = new ExecutorCompletionService<>(workers);
        int submitted = 0;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(8000);
        long providerDeadline = deadline - TimeUnit.MILLISECONDS.toNanos(900);
        BooleanSupplier stopSearching = () -> cancelled.getAsBoolean() || System.nanoTime() >= providerDeadline;
        boolean correctionChanged = !TvVideoSearch.normalize(title).equals(TvVideoSearch.normalize(original));
        try {
            for (TvVoiceCommand.Provider provider : scope) {
                if (TvVideoSearch.domain(provider) == null) continue;
                waiting.submit(() -> {
                    List<TvVideoSearch.Hit> candidate = TvVideoSearch.search(provider, title, mediaType, year, stopSearching);
                    if (candidate.isEmpty() && correctionChanged && !stopSearching.getAsBoolean())
                        return TvVideoSearch.search(provider, original, mediaType, year, stopSearching);
                    return candidate;
                });
                submitted++;
            }
            while (submitted > 0 && !cancelled.getAsBoolean() && System.nanoTime() < deadline) {
                Future<List<TvVideoSearch.Hit>> complete = waiting.poll(100, TimeUnit.MILLISECONDS);
                if (complete == null) continue;
                submitted--;
                try { hits.addAll(complete.get()); }
                catch (java.util.concurrent.ExecutionException unavailable) { /* Another source can still answer. */ }
            }
            return hits;
        } finally { workers.shutdownNow(); }
    }

    private boolean launch(Intent intent, BooleanSupplier cancelled) {
        if (intent == null || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) return false;
        try {
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (android.content.ActivityNotFoundException | SecurityException unavailable) { return false; }
    }

    private static VoicePipeline.TvResult result(String answer, String code, boolean opened) {
        return new VoicePipeline.TvResult(answer, code, opened);
    }
}
