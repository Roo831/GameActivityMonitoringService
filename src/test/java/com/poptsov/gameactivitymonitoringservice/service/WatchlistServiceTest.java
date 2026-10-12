package com.poptsov.gameactivitymonitoringservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class WatchlistServiceTest {
    @TempDir Path directory;
    private final MutableClock clock = new MutableClock();
    private WatchlistService service;
    private String stateFile;

    @BeforeEach
    void createStore() {
        stateFile = directory.resolve("watchlist.json").toString();
        service = new WatchlistService(stateFile, clock);
    }

    @Test
    void givesEachSubscriberItsOwnStableEventAndReadDoesNotConsumeIt() {
        var first = service.register("rust", "Player", "100", "200").subscription();
        var second = service.register("rust", "player", "101", "200").subscription();
        service.register("engineers", "Player", "100", "200");
        service.successfulPoll("rust", Set.of("PLAYER"));
        var pending = service.pending("rust");
        assertEquals(2, pending.size());
        assertEquals(pending, service.pending("rust"));
        assertEquals(Set.of(first.id(), second.id()), Set.of(pending.get(0).subscriptionId(), pending.get(1).subscriptionId()));
        assertNotEquals(pending.get(0).eventId(), pending.get(1).eventId());
        assertEquals(0, service.pending("engineers").size());
    }

    @Test
    void duplicatePendingRegistrationAndRepeatedPollsPreserveEvent() {
        var original = service.register("rust", "Éclair", "100", "200").subscription();
        service.successfulPoll("rust", Set.of("E\u0301CLAIR"));
        var event = service.pending("rust").getFirst();
        var duplicate = service.register("rust", "e\u0301clair", "100", "200");
        assertEquals(original.id(), duplicate.subscription().id());
        assertEquals("PENDING", duplicate.subscription().status());
        assertTrue(duplicate.alreadyOnline());
        service.successfulPoll("rust", Set.of());
        service.successfulPoll("rust", Set.of("éclair"));
        assertEquals(event, service.pending("rust").getFirst());
    }

    @Test
    void ackIsIdempotentGameScopedAndRemovesOnlyItsOneShotSubscription() {
        service.register("rust", "Player", "100", "200");
        service.register("rust", "Player", "101", "200");
        service.successfulPoll("rust", Set.of("Player"));
        String id = service.pending("rust").stream().filter(event -> event.userId().equals("100")).findFirst().orElseThrow().eventId();
        service.acknowledge("engineers", id);
        assertEquals(2, service.pending("rust").size());
        service.acknowledge("rust", id);
        service.acknowledge("rust", id);
        assertEquals(1, service.pending("rust").size());
        assertTrue(service.list("rust", "100", "200").isEmpty());
        assertEquals(1, service.list("rust", "101", "200").size());
    }

    @Test
    void restartPreservesWaitingAndPendingWithoutPretendingPresenceIsFresh() {
        service.register("rust", "Online", "100", "200");
        service.register("rust", "Waiting", "101", "200");
        service.successfulPoll("rust", Set.of("Online"));
        var event = service.pending("rust").getFirst();
        service = new WatchlistService(stateFile, clock);
        assertEquals(event, service.pending("rust").getFirst());
        assertEquals("WAITING", service.list("rust", "101", "200").getFirst().status());
        assertFalse(service.register("rust", "Online", "102", "200").alreadyOnline());
        service.successfulPoll("rust", Set.of("Online", "Waiting"));
        assertEquals(3, service.pending("rust").size());
    }

    @Test
    void cancellationRequiresMatchingGameOwnerAndChannelAndRemovesPendingEvent() {
        var subscription = service.register("rust", "Player", "100", "200").subscription();
        service.successfulPoll("rust", Set.of("Player"));
        service.cancel("rust", subscription.id(), "101", "200");
        service.cancel("rust", subscription.id(), "100", "201");
        service.cancel("engineers", subscription.id(), "100", "200");
        assertEquals(1, service.pending("rust").size());
        service.cancel("rust", subscription.id(), "100", "200");
        service.cancel("rust", subscription.id(), "100", "200");
        assertTrue(service.pending("rust").isEmpty());
    }

    @Test
    void freshAlreadyOnlineRegistrationQueuesOneEventImmediately() {
        service.successfulPoll("rust", Set.of("Player"));
        var registration = service.register("rust", "player", "100", "200");
        assertTrue(registration.alreadyOnline());
        assertEquals("PENDING", registration.subscription().status());
        assertEquals(1, service.pending("rust").size());
        assertEquals(clock.instant(), service.pending("rust").getFirst().observedAt());
    }

    @Test
    void stalePresenceWaitsForNextSuccessfulPoll() {
        service.successfulPoll("rust", Set.of("Player"));
        clock.advanceSeconds(121);
        var registration = service.register("rust", "player", "100", "200");
        assertFalse(registration.alreadyOnline());
        assertEquals("WAITING", registration.subscription().status());
        assertTrue(service.pending("rust").isEmpty());
        service.successfulPoll("rust", Set.of("Player"));
        assertEquals(1, service.pending("rust").size());
    }

    @Test
    void channelAndGameAreIndependentSubscriptions() {
        service.register("rust", "Player", "100", "200");
        service.register("rust", "Player", "100", "201");
        service.register("engineers", "Player", "100", "200");
        service.successfulPoll("rust", Set.of("Player"));
        assertEquals(2, service.pending("rust").size());
        assertTrue(service.pending("engineers").isEmpty());
    }

    @Test
    void pendingReadsAreBoundedAndOldestEventsAreAcknowledgedBeforeNextBatch() {
        for (int i = 1; i <= 101; i++) {
            service.successfulPoll("rust", Set.of());
            service.register("rust", "Player", Integer.toString(i), "200");
            clock.advanceSeconds(1);
            service.successfulPoll("rust", Set.of("Player"));
        }
        var batch = service.pending("rust");
        assertEquals(100, batch.size());
        assertEquals("1", batch.getFirst().userId());
        assertEquals("100", batch.getLast().userId());
        for (var event : batch) service.acknowledge("rust", event.eventId());
        assertEquals("101", service.pending("rust").getFirst().userId());
    }

    @Test
    void cursorReachesLaterEventsEvenWhenFirstHundredCannotBeDelivered() {
        for (int i = 1; i <= 105; i++) service.register("rust", "Player", Integer.toString(i), "200");
        service.successfulPoll("rust", Set.of("Player"));
        var first = service.pending("rust");
        var cursor = first.getLast();
        var second = service.pending("rust", cursor.observedAt(), cursor.eventId());
        assertEquals(100, first.size());
        assertEquals(5, second.size());
        assertTrue(second.stream().noneMatch(first::contains));
        assertEquals(first, service.pending("rust"));
        // Ack removes the cursor row; the next page must remain stable without looking that row up.
        for (var event : first) service.acknowledge("rust", event.eventId());
        assertEquals(second, service.pending("rust", cursor.observedAt(), cursor.eventId()));
    }

    @Test
    void cursorRequiresBothFieldsAndAnEventId() {
        assertThrows(IllegalArgumentException.class, () -> service.pending("rust", clock.instant(), null));
        assertThrows(IllegalArgumentException.class, () -> service.pending("rust", null, "event"));
        assertThrows(IllegalArgumentException.class, () -> service.pending("rust", clock.instant(), "event"));
    }

    @Test
    void concurrentDuplicatesCreateOneSubscriptionAndEvent() throws Exception {
        service.successfulPoll("rust", Set.of("Player"));
        try (var executor = Executors.newFixedThreadPool(4)) {
            var first = executor.submit(() -> service.register("rust", "Player", "100", "200"));
            var second = executor.submit(() -> service.register("rust", "player", "100", "200"));
            assertEquals(first.get().subscription().id(), second.get().subscription().id());
        }
        assertEquals(1, service.pending("rust").size());
    }

    @Test
    void writesOrdinaryJsonAndRestoresStableSubscriptionAndPendingEvent() throws Exception {
        var subscription = service.register("rust", "Шкет", "100", "200").subscription();
        service.successfulPoll("rust", Set.of("Шкет"));
        assertEquals('[', Files.readAllBytes(Path.of(stateFile))[0]);
        var text = Files.readString(Path.of(stateFile));
        assertTrue(text.contains("Шкет"));
        assertTrue(text.contains("PENDING"));
        var restored = new WatchlistService(stateFile, clock);
        assertEquals(subscription.id(), restored.register("rust", "шкет", "100", "200").subscription().id());
        assertEquals(service.pending("rust"), restored.pending("rust"));
    }

    @Test
    void refusesCorruptedJsonInsteadOfResettingExistingFile() throws Exception {
        String corrupted = "[{broken";
        Files.writeString(Path.of(stateFile), corrupted);
        assertThrows(WatchlistService.StorageException.class, () -> new WatchlistService(stateFile, clock));
        assertEquals(corrupted, Files.readString(Path.of(stateFile)));
    }

    @Test
    void refusesIncompleteEntriesInsteadOfSilentlyDroppingThem() throws Exception {
        String corrupted = "[{}]";
        Files.writeString(Path.of(stateFile), corrupted);
        assertThrows(WatchlistService.StorageException.class, () -> new WatchlistService(stateFile, clock));
        assertEquals(corrupted, Files.readString(Path.of(stateFile)));
    }

    @Test
    void refusesTrailingContentAfterValidJson() throws Exception {
        String corrupted = "[] {\"unexpected\":true}";
        Files.writeString(Path.of(stateFile), corrupted);
        assertThrows(WatchlistService.StorageException.class, () -> new WatchlistService(stateFile, clock));
        assertEquals(corrupted, Files.readString(Path.of(stateFile)));
    }

    @Test
    void failedAckCancelAndRegistrationPreservePublishedStateAndSurviveRestart() throws Exception {
        var subscription = service.register("rust", "Player", "100", "200").subscription();
        service.successfulPoll("rust", Set.of("Player"));
        var event = service.pending("rust").getFirst();
        Path saved = directory.resolve("saved.json");
        Path state = Path.of(stateFile);
        Files.move(state, saved);
        Files.createDirectory(state);
        assertThrows(WatchlistService.StorageException.class, () -> service.acknowledge("rust", event.eventId()));
        assertThrows(WatchlistService.StorageException.class, () -> service.cancel("rust", subscription.id(), "100", "200"));
        assertThrows(WatchlistService.StorageException.class, () -> service.register("rust", "Other", "100", "200"));
        assertEquals(java.util.List.of(event), assertDoesNotThrow(() -> service.pending("rust")));
        assertEquals(1, assertDoesNotThrow(() -> service.list("rust", "100", "200")).size());
        Files.delete(state);
        Files.move(saved, state);
        assertEquals(java.util.List.of(event), new WatchlistService(stateFile, clock).pending("rust"));
        service.acknowledge("rust", event.eventId());
        assertTrue(new WatchlistService(stateFile, clock).pending("rust").isEmpty());
    }

    @Test
    void failedPollKeepsWaitingStateUntilSuccessfulWrite() throws Exception {
        service.register("rust", "Player", "100", "200");
        Path state = Path.of(stateFile);
        Path saved = directory.resolve("saved.json");
        Files.move(state, saved);
        Files.createDirectory(state);
        assertThrows(WatchlistService.StorageException.class, () -> service.successfulPoll("rust", Set.of("Player")));
        assertEquals("WAITING", assertDoesNotThrow(() -> service.list("rust", "100", "200")).getFirst().status());
        assertTrue(assertDoesNotThrow(() -> service.pending("rust")).isEmpty());
        Files.delete(state);
        Files.move(saved, state);
        service.successfulPoll("rust", Set.of("Player"));
        assertEquals(1, new WatchlistService(stateFile, clock).pending("rust").size());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-12T12:00:00Z");
        void advanceSeconds(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
