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
import java.io.IOException;
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
    private static final long SERIES_CONTEXT_MS = TimeUnit.MINUTES.toMillis(15);
    private static final long SERIES_REPLY_MS = TimeUnit.MINUTES.toMillis(3);
    private final Context app;
    private final TvPlaybackControl playback;
    private final TvVoiceCommand.YearFollowUp yearFollowUp = new TvVoiceCommand.YearFollowUp();
    private SeriesContext recentSeries;

    TvVoiceControl(Context context) {
        app = context.getApplicationContext();
        playback = new TvPlaybackControl(app);
    }

    @Override public VoicePipeline.TvResult handle(String transcript, VoicePipeline.Chat chat, BooleanSupplier cancelled)
            throws Exception {
        if (cancelled.getAsBoolean()) return null;
        TvVoiceCommand.Playback control = TvVoiceCommand.playback(transcript);
        if (control != null) return controlPlayback(control, cancelled);
        long now = SystemClock.elapsedRealtime();
        if (recentSeries != null && now >= recentSeries.expiresAt) recentSeries = null;
        TvVoiceCommand command = yearFollowUp.resolve(transcript, now);
        if (command == null) {
            if (recentSeries == null) return null;
            TvVoiceCommand.EpisodeChoice choice = TvVoiceCommand.episodeReply(transcript, now < recentSeries.replyUntil);
            if (choice == null) return null;
            int season = choice.season;
            int episode = choice.episode;
            if (choice.bare && recentSeries.waitingSeason && now < recentSeries.replyUntil) {
                season = episode;
                episode = recentSeries.pendingEpisode;
            }
            return continueSeries(recentSeries, season, episode, now, cancelled);
        }
        boolean yearReply = TvVoiceCommand.followUpYear(transcript) != 0;
        if ((command.kind == TvVoiceCommand.Kind.EPISODES || command.kind == TvVoiceCommand.Kind.EPISODE
                || command.kind == TvVoiceCommand.Kind.FIND)
                && command.title.isEmpty()) {
            if (recentSeries == null || command.provider != null && command.provider != recentSeries.hit.provider)
                return result("Скажи, о каком сериале речь.", "series_context_missing", false);
            return command.kind != TvVoiceCommand.Kind.EPISODE
                    ? describeEpisodes(recentSeries, command.season, command.episode, cancelled)
                    : selectEpisode(recentSeries, command.season, command.episode, cancelled);
        }
        if (command.kind == TvVoiceCommand.Kind.PLAY || command.kind == TvVoiceCommand.Kind.FIND
                || command.kind == TvVoiceCommand.Kind.EPISODES || command.kind == TvVoiceCommand.Kind.EPISODE)
            recentSeries = null;
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
            if (recentSeries != null && requested != recentSeries.hit.provider) recentSeries = null;
            if (requested != null && launch(installed.get(requested), cancelled))
                return result("Открываю " + requested.label + ".", "app_opened", true);
            return result("Не получилось открыть приложение.", "launch_unavailable", false);
        }

        // Explicit source and media type come from the utterance, never from generated text.
        EnumSet<TvVoiceCommand.Provider> scope = requested == null
                ? EnumSet.copyOf(installed.keySet()) : EnumSet.of(requested);
        String title = correctedTitle(command.title, chat, cancelled);
        boolean episodeRequest = command.kind == TvVoiceCommand.Kind.EPISODES
                || command.kind == TvVoiceCommand.Kind.EPISODE || command.season != 0 || command.episode != 0;
        String mediaType = episodeRequest && !"animation".equals(command.mediaType) ? "series" : command.mediaType;
        List<TvVideoSearch.Hit> hits = search(scope, title, command.title, mediaType, command.year, cancelled);
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
            if ("series".equals(hit.mediaType) && hit.provider == TvVoiceCommand.Provider.OKKO) {
                OkkoEpisodeCatalog catalog = loadEpisodes(hit, cancelled);
                if (cancelled.getAsBoolean()) return result("Поиск отменён.", "cancelled", false);
                if (catalog != null) {
                    recentSeries = new SeriesContext(hit, catalog);
                    if (command.kind == TvVoiceCommand.Kind.EPISODES
                            || command.kind == TvVoiceCommand.Kind.FIND && command.episode != 0)
                        return describeEpisodes(recentSeries, command.season, command.episode, cancelled);
                    if (command.episode != 0) return selectEpisode(recentSeries, command.season, command.episode, cancelled);
                    boolean opened = command.kind == TvVoiceCommand.Kind.FIND && openHit(hit, hit.target, false, cancelled);
                    return askEpisode(recentSeries, command.season, 0, opened);
                }
                if (!episodeRequest && openHit(hit, hit.target, false, cancelled))
                    return result("Открыла «" + hit.displayTitle + "». Список серий можно выбрать на экране.",
                            "series_card_opened", true);
                return result("Окко не показал список серий для «" + hit.displayTitle + "».",
                        "episodes_unavailable", false);
            }
            if (episodeRequest) {
                if (requested == null) continue;
                return result("Не удалось проверить номера серий в " + hit.provider.label + ".",
                        "episodes_unavailable", false);
            }
            boolean play = command.kind == TvVoiceCommand.Kind.PLAY;
            String target = hit.launchTarget(play);
            boolean startsOkko = play && !target.equals(hit.target);
            Integer previousPlayer = startsOkko ? playback.currentOkkoPlayer() : null;
            if (openHit(hit, target, play, cancelled)) {
                if (startsOkko) {
                    playback.rememberOkkoVideo(hit.displayTitle, null, hit.durationMs, previousPlayer);
                    return result("Включаю «" + hit.displayTitle + "» в Окко.", "playback_requested", true);
                }
                return result("Нашла «" + hit.displayTitle + "» в " + hit.provider.label + ". Открываю.", "title_opened", true);
            }
        }
        if (yearReply) {
            if (cancelled.getAsBoolean()) return result("Поиск отменён.", "cancelled", false);
            yearFollowUp.remember(command.withYear(0), SystemClock.elapsedRealtime());
            return result("Не нашла «" + title + "» за " + command.year + " год. Можешь назвать другой год.",
                    "year_no_match", false);
        }
        if (episodeRequest)
            return result("Не нашла подтверждённый список серий для «" + title + "».", "episodes_not_found", false);
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

    private static OkkoEpisodeCatalog loadEpisodes(TvVideoSearch.Hit hit, BooleanSupplier cancelled) {
        try { return OkkoEpisodeCatalog.load(hit, cancelled); }
        catch (IOException unavailable) { return null; }
    }

    private VoicePipeline.TvResult continueSeries(SeriesContext series, int season, int episode, long now,
                                                    BooleanSupplier cancelled) {
        if (series.waitingEpisodeCheck && now < series.replyUntil)
            return describeEpisodes(series, season, episode == 0 ? series.pendingEpisode : episode, cancelled);
        return selectEpisode(series, season, episode, cancelled);
    }

    private VoicePipeline.TvResult selectEpisode(SeriesContext series, int season, int episode,
                                                  BooleanSupplier cancelled) {
        series.touch();
        if (season == 0) season = series.season != 0 ? series.season : series.catalog.defaultSeason();
        if (episode == 0 && series.waitingSeason && !series.waitingEpisodeCheck
                && SystemClock.elapsedRealtime() < series.replyUntil) episode = series.pendingEpisode;
        if (season == 0 || episode == 0) return askEpisode(series, season, episode, false);
        if (!series.catalog.hasSeason(season)) return askEpisode(series, season, episode, false);
        String target = series.catalog.episodeTarget(season, episode, cancelled);
        if (cancelled.getAsBoolean()) return result("Поиск отменён.", "cancelled", false);
        if (target == null) return result("Не нашла серию " + episode + " сезона " + season + " в Окко.",
                "episode_not_found", false);
        Integer previousPlayer = playback.currentOkkoPlayer();
        if (!openHit(series.hit, target, true, cancelled))
            return result("Не получилось открыть серию в Окко.", "episode_launch_unavailable", false);
        playback.rememberOkkoVideo(series.hit.displayTitle, series.catalog.episodeTitle(season, episode),
                series.catalog.durationMs(season, episode), previousPlayer);
        series.season = season;
        series.waitingSeason = false;
        series.waitingEpisodeCheck = false;
        series.pendingEpisode = 0;
        series.replyUntil = 0;
        return result("Включаю «" + series.hit.displayTitle + "», сезон " + season + ", серия " + episode + ".",
                "episode_playback_requested", true);
    }

    private static VoicePipeline.TvResult askEpisode(SeriesContext series, int season, int episode, boolean opened) {
        series.touch();
        if (season == 0) season = series.catalog.defaultSeason();
        series.replyUntil = SystemClock.elapsedRealtime() + SERIES_REPLY_MS;
        series.waitingSeason = season == 0 || !series.catalog.hasSeason(season);
        series.waitingEpisodeCheck = false;
        series.pendingEpisode = episode;
        if (season == 0) {
            String seasons = series.catalog.seasonCount > 1 ? " Сезонов в каталоге: " + series.catalog.seasonCount + "." : "";
            String question = episode != 0 ? " Из какого сезона?" : " Какой сезон включить?";
            return result("«" + series.hit.displayTitle + "» есть в Окко." + seasons + question,
                    "season_requested", opened);
        }
        if (!series.catalog.hasSeason(season)) return unknownSeason(season);
        series.season = season;
        return result("«" + series.hit.displayTitle + "» есть в Окко. Какую серию включить?",
                "episode_requested", opened);
    }

    private static VoicePipeline.TvResult describeEpisodes(SeriesContext series, int season, int episode,
                                                           BooleanSupplier cancelled) {
        series.touch();
        if (episode != 0) {
            if (season == 0) season = series.season != 0 ? series.season : series.catalog.defaultSeason();
            if (season == 0 || !series.catalog.hasSeason(season)) {
                series.replyUntil = SystemClock.elapsedRealtime() + SERIES_REPLY_MS;
                series.waitingSeason = true;
                series.waitingEpisodeCheck = true;
                series.pendingEpisode = episode;
                return season == 0 ? result("Уточни сезон, чтобы я проверила серию " + episode + ".",
                        "episode_count_season_needed", false) : unknownSeason(season);
            }
            if (series.waitingEpisodeCheck) {
                series.replyUntil = 0;
                series.waitingSeason = false;
                series.waitingEpisodeCheck = false;
                series.pendingEpisode = 0;
            }
            boolean found = series.catalog.episodeTarget(season, episode, cancelled) != null;
            if (cancelled.getAsBoolean()) return result("Поиск отменён.", "cancelled", false);
            return result(found ? "Серия " + episode + " есть в каталоге Окко, сезон " + season + "."
                            : "Не нашла серию " + episode + " сезона " + season + " в каталоге Окко.",
                    found ? "episode_listed" : "episode_not_found", false);
        }
        if (season != 0 && !series.catalog.hasSeason(season)) return unknownSeason(season);
        int count = series.catalog.listedEpisodes(season);
        if (count < 0 && season == 0 && series.season != 0) {
            season = series.season;
            count = series.catalog.listedEpisodes(season);
        }
        if (count < 0) return result("Не удалось подтвердить число серий в каталоге Окко.",
                "episode_count_unavailable", false);
        String seasonLabel = season == 0 ? "" : ", сезон " + season;
        int free = series.catalog.freeEpisodes(season);
        String freeNote = free > 0 && free < count ? " Бесплатно в открытом каталоге — " + free + "."
                : free == count && count > 0 ? " Все отмечены как бесплатные." : "";
        return result("В каталоге Окко у «" + series.hit.displayTitle + "»" + seasonLabel + " "
                + count + " " + episodeNoun(count) + "." + freeNote,
                "episode_count", false);
    }

    private static VoicePipeline.TvResult unknownSeason(int season) {
        return result("Не нашла сезона " + season + " в каталоге Окко. Назови другой сезон.",
                "season_not_found", false);
    }

    private static String episodeNoun(int count) {
        if (count % 100 >= 11 && count % 100 <= 14) return "серий";
        if (count % 10 == 1) return "серия";
        return count % 10 >= 2 && count % 10 <= 4 ? "серии" : "серий";
    }

    private VoicePipeline.TvResult controlPlayback(TvVoiceCommand.Playback request, BooleanSupplier cancelled) {
        String answer;
        switch (request.action) {
            case PAUSE: answer = "Поставила видео на паузу."; break;
            case RESUME: answer = "Продолжаю видео."; break;
            case RESTART: answer = "Включаю с начала."; break;
            case SEEK_TO: answer = request.resumeAfterSeek ? "Включаю видео с указанного момента." : "Перемотала видео."; break;
            case SEEK_BY: answer = "Перемотала видео."; break;
            default: return null;
        }
        switch (playback.execute(request, cancelled)) {
            case APPLIED: return result(answer, "video_control_applied", true);
            case CANCELLED: return result("Команда отменена.", "cancelled", false);
            case NO_VIDEO: return result("Сначала запусти фильм или серию на телевизоре.", "video_not_active", false);
            case PERMISSION_REQUIRED:
                return result("Включи управление видео Станции в специальных возможностях телевизора.",
                        "video_permission_required", false);
            case UNCONFIRMED:
                return result("Не удалось подтвердить действие в Окко.", "video_control_unconfirmed", false);
            default: return result("Не удалось выполнить эту команду в видеоплеере.", "video_control_unavailable", false);
        }
    }

    private boolean openHit(TvVideoSearch.Hit hit, String target, boolean play, BooleanSupplier cancelled) {
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(target))
                .setPackage(hit.provider.packageName).addCategory(Intent.CATEGORY_BROWSABLE);
        if (play) view.putExtra("android.intent.extra.START_PLAYBACK", true);
        return app.getPackageManager().resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY) != null && launch(view, cancelled);
    }

    private static final class SeriesContext {
        final TvVideoSearch.Hit hit;
        final OkkoEpisodeCatalog catalog;
        long expiresAt, replyUntil;
        int season, pendingEpisode;
        boolean waitingSeason, waitingEpisodeCheck;

        SeriesContext(TvVideoSearch.Hit hit, OkkoEpisodeCatalog catalog) {
            this.hit = hit;
            this.catalog = catalog;
            touch();
        }

        void touch() { expiresAt = SystemClock.elapsedRealtime() + SERIES_CONTEXT_MS; }
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
