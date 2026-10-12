package com.poptsov.gameactivitymonitoringservice.service;

import com.poptsov.gameactivitymonitoringservice.model.NicknameReport;
import com.poptsov.gameactivitymonitoringservice.model.PlayerActivityReport;
import com.poptsov.gameactivitymonitoringservice.model.PlayerActivityReport.DayActivity;
import com.poptsov.gameactivitymonitoringservice.model.PlayerActivityReport.Session;
import com.poptsov.gameactivitymonitoringservice.model.RawDataReport;
import com.poptsov.gameactivitymonitoringservice.model.RawDataReport.ActivityEvent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class AnalyticsService {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern LOG_PATTERN = Pattern.compile("^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\] \\[(CONNECT|DISCONNECT)\\]\\s+(.+)$");
    private final Path logFile;
    private final Clock clock;
    private final Map<String, Boolean> watchlist = new ConcurrentHashMap<>();

    public AnalyticsService(String logFileName) {
        this(logFileName, Clock.systemDefaultZone());
    }

    public AnalyticsService(String logFileName, Clock clock) {
        this.logFile = Path.of(logFileName);
        this.clock = clock;
    }

    public void addToWatchlist(String nickname) {
        watchlist.put(validNickname(nickname).toLowerCase(Locale.ROOT), false);
    }

    public void updateWatchlistStatus(Set<String> currentOnline) {
        for (String player : currentOnline) {
            String name = player.toLowerCase(Locale.ROOT);
            if (watchlist.containsKey(name)) watchlist.put(name, true);
        }
    }

    public List<String> getAndResetPendingNotifications() {
        List<String> pending = new ArrayList<>();
        for (var entry : watchlist.entrySet()) {
            if (entry.getValue()) {
                pending.add(entry.getKey());
                watchlist.put(entry.getKey(), false);
            }
        }
        return pending;
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
        if (Files.notExists(logFile)) return Stream.empty();
        try {
            return Files.lines(logFile).map(this::parseEvent).filter(Objects::nonNull);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ActivityEvent parseEvent(String line) {
        var match = LOG_PATTERN.matcher(line.trim());
        if (!match.matches()) return null;
        try {
            return new ActivityEvent(LocalDateTime.parse(match.group(1), FORMAT), match.group(2), match.group(3).trim());
        } catch (DateTimeParseException e) {
            return null;
        }
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
