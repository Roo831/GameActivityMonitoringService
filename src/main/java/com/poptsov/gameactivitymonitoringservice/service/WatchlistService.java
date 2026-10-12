package com.poptsov.gameactivitymonitoringservice.service;

import com.poptsov.gameactivitymonitoringservice.model.WatchNotification;
import com.poptsov.gameactivitymonitoringservice.model.WatchRegistration;
import com.poptsov.gameactivitymonitoringservice.model.WatchSubscription;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class WatchlistService {
    private final Path stateFile;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private Map<String, StoredSubscription> subscriptions;
    // shortcut: one backend process, add cross-process coordination before running replicas.
    private final Map<String, Presence> presence = new HashMap<>();

    public WatchlistService(String stateFile, Clock clock) {
        this.clock = clock;
        this.stateFile = Path.of(stateFile).toAbsolutePath();
        this.subscriptions = load();
    }

    public synchronized WatchRegistration register(String game, String nickname, String userId, String channelId) {
        validateGame(game);
        String displayName = validateNickname(nickname);
        String normalizedName = normalize(displayName);
        validateDiscordId(userId);
        validateDiscordId(channelId);
        Instant now = clock.instant();
        Presence current = presence.get(game);
        boolean alreadyOnline = current != null && !current.observedAt().isAfter(now)
                && Duration.between(current.observedAt(), now).compareTo(Duration.ofMinutes(2)) <= 0
                && current.players().contains(normalizedName);
        long count = 0;
        for (var stored : subscriptions.values()) {
            var subscription = stored.subscription();
            if (stored.game().equals(game) && subscription.userId().equals(userId)) {
                count++;
                if (stored.normalizedName().equals(normalizedName) && subscription.channelId().equals(channelId)) {
                    return new WatchRegistration(subscription, alreadyOnline);
                }
            }
        }
        if (count >= 25) throw new IllegalArgumentException("At most 25 subscriptions per user and game are allowed.");
        var subscription = new WatchSubscription(UUID.randomUUID().toString(), displayName, userId, channelId,
                now, alreadyOnline ? "PENDING" : "WAITING");
        var notification = alreadyOnline ? notification(subscription, current.observedAt()) : null;
        var next = new HashMap<>(subscriptions);
        next.put(subscription.id(), new StoredSubscription(game, normalizedName, subscription, notification));
        save(next);
        return new WatchRegistration(subscription, alreadyOnline);
    }

    public synchronized List<WatchSubscription> list(String game, String userId, String channelId) {
        validateGame(game);
        validateDiscordId(userId);
        validateDiscordId(channelId);
        return subscriptions.values().stream().filter(stored -> stored.game().equals(game))
                .map(StoredSubscription::subscription)
                .filter(subscription -> subscription.userId().equals(userId) && subscription.channelId().equals(channelId))
                .sorted(Comparator.comparing(WatchSubscription::createdAt).thenComparing(WatchSubscription::id)).toList();
    }

    public synchronized void cancel(String game, String subscriptionId, String userId, String channelId) {
        validateGame(game);
        validateDiscordId(userId);
        validateDiscordId(channelId);
        var stored = subscriptions.get(subscriptionId);
        if (stored == null || !stored.game().equals(game) || !stored.subscription().userId().equals(userId)
                || !stored.subscription().channelId().equals(channelId)) return;
        var next = new HashMap<>(subscriptions);
        next.remove(subscriptionId);
        save(next);
    }

    public List<WatchNotification> pending(String game) {
        return pending(game, null, null);
    }

    public synchronized List<WatchNotification> pending(String game, Instant afterObservedAt, String afterEventId) {
        validateGame(game);
        if ((afterObservedAt == null) != (afterEventId == null)) {
            throw new IllegalArgumentException("Both pending cursor fields are required together.");
        }
        String cursorId;
        try { cursorId = afterEventId == null ? null : UUID.fromString(afterEventId).toString(); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Pending cursor event ID must be a UUID."); }
        return subscriptions.values().stream().filter(stored -> stored.game().equals(game) && stored.notification() != null)
                .map(StoredSubscription::notification)
                .filter(event -> afterObservedAt == null || event.observedAt().isAfter(afterObservedAt)
                        || (event.observedAt().equals(afterObservedAt) && event.eventId().compareTo(cursorId) > 0))
                .sorted(Comparator.comparing(WatchNotification::observedAt).thenComparing(WatchNotification::eventId))
                .limit(100).toList();
    }

    public synchronized void acknowledge(String game, String eventId) {
        validateGame(game);
        for (var stored : subscriptions.values()) {
            if (stored.game().equals(game) && stored.notification() != null && stored.notification().eventId().equals(eventId)) {
                var next = new HashMap<>(subscriptions);
                next.remove(stored.subscription().id());
                save(next);
                return;
            }
        }
    }

    public synchronized void successfulPoll(String game, Set<String> players) {
        validateGame(game);
        Set<String> normalized = new HashSet<>();
        for (String player : players) normalized.add(normalize(player.trim()));
        Instant observedAt = clock.instant();
        var next = new HashMap<>(subscriptions);
        for (var stored : subscriptions.values()) {
            if (stored.game().equals(game) && stored.notification() == null && normalized.contains(stored.normalizedName())) {
                var old = stored.subscription();
                var subscription = new WatchSubscription(old.id(), old.nickname(), old.userId(), old.channelId(), old.createdAt(), "PENDING");
                next.put(old.id(), new StoredSubscription(game, stored.normalizedName(), subscription, notification(subscription, observedAt)));
            }
        }
        if (!next.equals(subscriptions)) save(next);
        presence.put(game, new Presence(Set.copyOf(normalized), observedAt));
    }

    private static WatchNotification notification(WatchSubscription subscription, Instant observedAt) {
        return new WatchNotification(UUID.randomUUID().toString(), subscription.id(), subscription.nickname(),
                subscription.userId(), subscription.channelId(), observedAt);
    }

    private Map<String, StoredSubscription> load() {
        try {
            if (Files.notExists(stateFile)) return Map.of();
            var records = mapper.readValue(Files.readAllBytes(stateFile), StoredSubscription[].class);
            if (records == null) throw new IllegalArgumentException("Watchlist state must be an array.");
            var loaded = new HashMap<String, StoredSubscription>();
            var routingKeys = new HashSet<List<String>>();
            var eventIds = new HashSet<String>();
            var ownerCounts = new HashMap<List<String>, Integer>();
            for (var stored : records) {
                if (stored == null || stored.subscription() == null) throw new IllegalArgumentException("Incomplete watchlist state.");
                validateGame(stored.game());
                var subscription = stored.subscription();
                UUID.fromString(subscription.id());
                validateNickname(subscription.nickname());
                validateDiscordId(subscription.userId());
                validateDiscordId(subscription.channelId());
                if (subscription.createdAt() == null || !normalize(subscription.nickname()).equals(stored.normalizedName())) {
                    throw new IllegalArgumentException("Invalid watchlist subscription.");
                }
                if (loaded.putIfAbsent(subscription.id(), stored) != null
                        || !routingKeys.add(List.of(stored.game(), stored.normalizedName(), subscription.userId(), subscription.channelId()))
                        || ownerCounts.merge(List.of(stored.game(), subscription.userId()), 1, Integer::sum) > 25) {
                    throw new IllegalArgumentException("Duplicate or excessive watchlist subscriptions.");
                }
                var event = stored.notification();
                if ("WAITING".equals(subscription.status()) && event == null) continue;
                if (!"PENDING".equals(subscription.status()) || event == null || event.observedAt() == null
                        || !subscription.id().equals(event.subscriptionId()) || !subscription.nickname().equals(event.nickname())
                        || !subscription.userId().equals(event.userId()) || !subscription.channelId().equals(event.channelId())) {
                    throw new IllegalArgumentException("Invalid watchlist event.");
                }
                UUID.fromString(event.eventId());
                if (!eventIds.add(event.eventId())) throw new IllegalArgumentException("Duplicate watchlist event.");
            }
            return Map.copyOf(loaded);
        } catch (IOException | RuntimeException error) {
            throw new StorageException(error);
        }
    }

    private void save(Map<String, StoredSubscription> next) {
        Map<String, StoredSubscription> snapshot = Map.copyOf(next);
        Path temporary = null;
        try {
            byte[] data = mapper.writeValueAsBytes(snapshot.values());
            Files.createDirectories(stateFile.getParent());
            temporary = Files.createTempFile(stateFile.getParent(), ".watchlist-", ".tmp");
            try (var file = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            // Unsupported atomic replacement fails safely; never fall back to truncating the live file.
            Files.move(temporary, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            subscriptions = snapshot;
        } catch (IOException | RuntimeException error) {
            throw new StorageException(error);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    private static String validateNickname(String nickname) {
        if (nickname == null) throw new IllegalArgumentException("Nickname is required.");
        String trimmed = Normalizer.normalize(nickname.trim(), Normalizer.Form.NFC);
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 128 || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Nickname must contain 1–128 characters without control characters.");
        }
        return trimmed;
    }

    private static String normalize(String nickname) {
        return Normalizer.normalize(nickname, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static void validateDiscordId(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,19}")) throw new IllegalArgumentException("Discord IDs must be decimal snowflakes.");
        try { Long.parseUnsignedLong(id); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Discord IDs must be decimal snowflakes."); }
    }

    private static void validateGame(String game) {
        if (!"rust".equals(game) && !"engineers".equals(game)) throw new IllegalArgumentException("Unknown game.");
    }

    private record StoredSubscription(String game, String normalizedName, WatchSubscription subscription, WatchNotification notification) { }
    private record Presence(Set<String> players, Instant observedAt) { }
    public static final class StorageException extends RuntimeException {
        public StorageException(Exception cause) { super("Watchlist storage is unavailable.", cause); }
    }
}
