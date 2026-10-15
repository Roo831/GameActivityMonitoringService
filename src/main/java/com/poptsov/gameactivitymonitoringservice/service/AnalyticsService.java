package com.poptsov.gameactivitymonitoringservice.service;

import com.poptsov.gameactivitymonitoringservice.model.NicknameReport;
import com.poptsov.gameactivitymonitoringservice.model.PlayerActivityReport;
import com.poptsov.gameactivitymonitoringservice.model.PlayerActivityReport.DayActivity;
import com.poptsov.gameactivitymonitoringservice.model.PlayerActivityReport.Session;
import com.poptsov.gameactivitymonitoringservice.model.RawDataReport;
import com.poptsov.gameactivitymonitoringservice.model.RawDataReport.ActivityEvent;
import com.poptsov.gameactivitymonitoringservice.model.SimilarActivityReport;
import com.poptsov.gameactivitymonitoringservice.model.SimilarActivityReport.SimilarPlayer;
import com.poptsov.gameactivitymonitoringservice.repository.ActivityRepository;

import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;

public class AnalyticsService {
    // shortcut: co-presence is a teammate heuristic; verified identity/team data is needed for confirmed membership.
    private static final long MIN_SHARED_SESSION_SECONDS = 15 * 60;
    private final ActivityRepository activityRepository;
    private final Clock clock;

    public AnalyticsService(ActivityRepository activityRepository) {
        this(activityRepository, Clock.systemDefaultZone());
    }

    public AnalyticsService(ActivityRepository activityRepository, Clock clock) {
        this.activityRepository = activityRepository;
        this.clock = clock;
    }

    public PlayerActivityReport getPlayerReport(String nickname, int days) {
        nickname = validNickname(nickname);
        validDays(days);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime threshold = now.minusDays(days);
        Map<LocalDate, List<Session>> daily = new TreeMap<>();
        long[] hourly = new long[24];
        LocalDateTime login = null;

        try (Stream<ActivityEvent> events = readEvents()) {
            Iterator<ActivityEvent> iterator = events.iterator();
            while (iterator.hasNext()) {
                ActivityEvent event = iterator.next();
                if (!event.nickname().equalsIgnoreCase(nickname) || event.timestamp().isAfter(now)) continue;
                if (event.action().equals("CONNECT")) {
                    login = event.timestamp();
                } else if (login != null) {
                    addSession(daily, hourly, login, event.timestamp(), threshold, false);
                    login = null;
                }
            }
        }
        if (login != null) addSession(daily, hourly, login, now, threshold, true);

        List<DayActivity> activity = daily.entrySet().stream()
                .map(entry -> new DayActivity(entry.getKey(), entry.getValue().stream()
                        .mapToLong(Session::durationSeconds).sum(), List.copyOf(entry.getValue())))
                .toList();
        Integer peak = null;
        for (int hour = 0; hour < 24; hour++) {
            if (hourly[hour] > 0 && (peak == null || hourly[hour] > hourly[peak])) peak = hour;
        }
        return new PlayerActivityReport(nickname, days, threshold, now, clock.getZone().getId(),
                activity.stream().mapToLong(DayActivity::totalSeconds).sum(), peak, activity);
    }

    public RawDataReport getRawDataReport(String nickname, int days) {
        nickname = validNickname(nickname);
        validDays(days);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime threshold = now.minusDays(days);
        String target = nickname;
        try (Stream<ActivityEvent> events = readEvents()) {
            return new RawDataReport(nickname, days, threshold, now, clock.getZone().getId(), events
                    .filter(event -> event.nickname().equalsIgnoreCase(target))
                    .filter(event -> !event.timestamp().isBefore(threshold) && !event.timestamp().isAfter(now))
                    .toList());
        }
    }

    public SimilarActivityReport getSimilarActivityReport(String nickname, int days) {
        nickname = validNickname(nickname);
        validDays(days);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime threshold = now.minusDays(days);
        Map<String, PlayerTimeline> timelines = new HashMap<>();
        try (Stream<ActivityEvent> events = readEvents()) {
            events.filter(event -> !event.timestamp().isAfter(now)).forEach(event -> {
                PlayerTimeline timeline = timelines.computeIfAbsent(nicknameKey(event.nickname()),
                        ignored -> new PlayerTimeline(event.nickname()));
                if (event.action().equals("CONNECT")) {
                    if (timeline.login == null) timeline.login = event.timestamp();
                } else if (timeline.login != null) {
                    addInterval(timeline, event.timestamp(), threshold, now);
                    timeline.login = null;
                }
            });
        }
        for (PlayerTimeline timeline : timelines.values()) {
            if (timeline.login != null) addInterval(timeline, now, threshold, now);
            timeline.intervals = mergeIntervals(timeline.intervals);
        }

        String targetKey = nicknameKey(nickname);
        PlayerTimeline target = timelines.get(targetKey);
        long totalSeconds = target == null ? 0 : totalSeconds(target.intervals);
        List<SimilarPlayer> players = new ArrayList<>();
        if (totalSeconds > 0) {
            for (var entry : timelines.entrySet()) {
                if (entry.getKey().equals(targetKey)) continue;
                PlayerTimeline candidate = entry.getValue();
                Overlap overlap = overlap(target.intervals, candidate.intervals);
                if (overlap.sharedSessions() == 0) continue;
                long candidateSeconds = totalSeconds(candidate.intervals);
                double similarity = 100.0 * overlap.seconds() / (totalSeconds + candidateSeconds - overlap.seconds());
                players.add(new SimilarPlayer(candidate.nickname, candidateSeconds, overlap.seconds(), overlap.sharedDays(),
                        100.0 * overlap.seconds() / totalSeconds,
                        100.0 * overlap.seconds() / candidateSeconds,
                        similarity, overlap.sharedSessions(), similarity * overlap.sharedSessions()));
            }
        }
        players.sort(Comparator.comparingDouble(SimilarPlayer::rankingScore).reversed()
                .thenComparing(Comparator.comparingDouble(SimilarPlayer::similarityPercent).reversed())
                .thenComparing(Comparator.comparingLong(SimilarPlayer::overlapSeconds).reversed())
                .thenComparing(SimilarPlayer::nickname));
        return new SimilarActivityReport(nickname, days, threshold, now, clock.getZone().getId(),
                totalSeconds, List.copyOf(players));
    }

    private static String nicknameKey(String nickname) {
        return Normalizer.normalize(nickname, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static void addInterval(PlayerTimeline timeline, LocalDateTime logout,
                                    LocalDateTime threshold, LocalDateTime now) {
        LocalDateTime start = timeline.login.isBefore(threshold) ? threshold : timeline.login;
        LocalDateTime end = logout.isAfter(now) ? now : logout;
        if (start.isBefore(end)) timeline.intervals.add(new Interval(start, end));
    }

    private static List<Interval> mergeIntervals(List<Interval> intervals) {
        intervals.sort(Comparator.comparing(Interval::start));
        List<Interval> merged = new ArrayList<>();
        for (Interval interval : intervals) {
            if (merged.isEmpty() || interval.start().isAfter(merged.getLast().end())) {
                merged.add(interval);
            } else if (interval.end().isAfter(merged.getLast().end())) {
                merged.set(merged.size() - 1, new Interval(merged.getLast().start(), interval.end()));
            }
        }
        return merged;
    }

    private static long totalSeconds(List<Interval> intervals) {
        return intervals.stream().mapToLong(interval -> Duration.between(interval.start(), interval.end()).getSeconds()).sum();
    }

    private static Overlap overlap(List<Interval> target, List<Interval> candidate) {
        int targetIndex = 0;
        int candidateIndex = 0;
        long seconds = 0;
        long[] sessionSeconds = new long[target.size()];
        Set<LocalDate> dates = new HashSet<>();
        while (targetIndex < target.size() && candidateIndex < candidate.size()) {
            Interval left = target.get(targetIndex);
            Interval right = candidate.get(candidateIndex);
            LocalDateTime start = left.start().isAfter(right.start()) ? left.start() : right.start();
            LocalDateTime end = left.end().isBefore(right.end()) ? left.end() : right.end();
            if (start.isBefore(end)) {
                long sharedSeconds = Duration.between(start, end).getSeconds();
                seconds += sharedSeconds;
                sessionSeconds[targetIndex] += sharedSeconds;
                for (LocalDate date = start.toLocalDate(); date.atStartOfDay().isBefore(end); date = date.plusDays(1)) {
                    dates.add(date);
                }
            }
            if (!left.end().isAfter(right.end())) targetIndex++;
            else candidateIndex++;
        }
        int sharedSessions = 0;
        for (int i = 0; i < sessionSeconds.length; i++) {
            if (sessionSeconds[i] >= MIN_SHARED_SESSION_SECONDS) {
                sharedSessions++;
            }
        }
        return new Overlap(seconds, dates.size(), sharedSessions);
    }

    private static class PlayerTimeline {
        final String nickname;
        LocalDateTime login;
        List<Interval> intervals = new ArrayList<>();

        PlayerTimeline(String nickname) {
            this.nickname = nickname;
        }
    }

    private record Interval(LocalDateTime start, LocalDateTime end) {}
    private record Overlap(long seconds, int sharedDays, int sharedSessions) {}

    public NicknameReport buildListOfAllNames(int days) {
        validDays(days);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime threshold = now.minusDays(days);
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, String> online = new HashMap<>();
        try (Stream<ActivityEvent> events = readEvents()) {
            events.filter(event -> !event.timestamp().isAfter(now)).forEach(event -> {
                if (!event.timestamp().isBefore(threshold)) names.add(event.nickname());
                String key = event.nickname().toLowerCase(Locale.ROOT);
                if (event.action().equals("CONNECT")) online.put(key, event.nickname());
                else online.remove(key);
            });
        }
        names.addAll(online.values());
        return new NicknameReport(days, threshold, now, clock.getZone().getId(), List.copyOf(names));
    }

    private void addSession(Map<LocalDate, List<Session>> daily, long[] hourly,
                            LocalDateTime login, LocalDateTime logout, LocalDateTime threshold, boolean ongoing) {
        LocalDateTime start = login.isBefore(threshold) ? threshold : login;
        while (start.isBefore(logout)) {
            LocalDateTime midnight = start.toLocalDate().plusDays(1).atStartOfDay();
            LocalDateTime end = logout.isBefore(midnight) ? logout : midnight;
            long seconds = Duration.between(start, end).getSeconds();
            daily.computeIfAbsent(start.toLocalDate(), ignored -> new ArrayList<>())
                    .add(new Session(start, end, seconds, ongoing && end.equals(logout)));
            LocalDateTime hourStart = start;
            while (hourStart.isBefore(end)) {
                LocalDateTime nextHour = hourStart.withMinute(0).withSecond(0).withNano(0).plusHours(1);
                LocalDateTime hourEnd = end.isBefore(nextHour) ? end : nextHour;
                hourly[hourStart.getHour()] += Duration.between(hourStart, hourEnd).getSeconds();
                hourStart = hourEnd;
            }
            start = end;
        }
    }

    private Stream<ActivityEvent> readEvents() {
        return activityRepository.findAll();
    }

    private static String validNickname(String nickname) {
        if (nickname == null || nickname.isBlank() || nickname.trim().length() > 128) {
            throw new IllegalArgumentException("Nickname must contain 1 to 128 characters.");
        }
        return nickname.trim();
    }

    private static void validDays(int days) {
        if (days < 1 || days > 365) throw new IllegalArgumentException("Days must be between 1 and 365.");
    }
}
