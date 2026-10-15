package com.poptsov.gameactivitymonitoringservice.core;

import com.poptsov.gameactivitymonitoringservice.repository.LogFileActivityRepository;
import com.poptsov.gameactivitymonitoringservice.service.WatchlistService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Clock;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerActivityTrackerTest {
    @TempDir Path directory;

    @Test
    void failedLogWriteIsRetriedOnTheNextSuccessfulPoll() throws Exception {
        var store = mock(WatchlistService.class);
        Path log = directory.resolve("rust.txt");
        Files.createDirectory(log);
        var tracker = new PlayerActivityTracker("rust", "127.0.0.1", 27015, new LogFileActivityRepository(log), store);
        try (var queries = mockConstruction(SourceServerQuery.class, (query, context) ->
                when(query.getPlayers()).thenReturn(Set.of("Player")))) {
            tracker.trackOnline();
            Files.delete(log);
            tracker.trackOnline();
            assertTrue(Files.exists(log), "A failed journal append must not lose the connection event");
            assertTrue(Files.readString(log).contains("[CONNECT] Player"));
        }
    }

    @Test
    void unsuccessfulPollPreservesPresenceAndSuccessfulPollFulfillsOnlyMatchingGame() throws Exception {
        var store = new WatchlistService(directory.resolve("watchlist.json").toString(), Clock.systemUTC());
        store.register("rust", "Player", "100", "200");
        store.register("engineers", "Player", "100", "200");
        Path log = directory.resolve("rust.txt");
        var tracker = new PlayerActivityTracker("rust", "127.0.0.1", 27015, new LogFileActivityRepository(log), store);
        try (var queries = mockConstruction(SourceServerQuery.class, (query, context) -> {
            switch (context.getCount()) {
                case 1 -> when(query.getPlayers()).thenReturn(Set.of("Player"));
                case 2 -> when(query.getPlayers()).thenThrow(new SocketTimeoutException("Unavailable"));
                default -> when(query.getPlayers()).thenReturn(Set.of("Player"));
            }
        })) {
            tracker.trackOnline();
            var pending = store.pending("rust");
            assertEquals(1, pending.size());
            assertTrue(store.pending("engineers").isEmpty());
            tracker.trackOnline();
            assertEquals(pending, store.pending("rust"));
            tracker.trackOnline();
            assertEquals(1, Files.readAllLines(log).size());
            assertTrue(Files.readString(log).contains("[CONNECT] Player"));
            assertFalse(Files.readString(log).contains("DISCONNECT"));
        }
    }

    @Test
    void disabledPollerDoesNotPublishEmptyPresence() throws Exception {
        var store = new WatchlistService(directory.resolve("watchlist.json").toString(), Clock.systemUTC());
        store.successfulPoll("rust", Set.of("Player"));
        var tracker = new PlayerActivityTracker("rust", "", 27015, new LogFileActivityRepository(directory.resolve("rust.txt")), store);
        tracker.trackOnline();
        assertTrue(store.register("rust", "Player", "100", "200").alreadyOnline());
        assertFalse(Files.exists(directory.resolve("rust.txt")));
    }

    @ParameterizedTest
    @CsvSource({"Alice,ALICE", "Éclair,E\u0301CLAIR"})
    void caseOrUnicodeSpellingChangeKeepsSessionOpenUntilActualExit(String firstName, String nextName) throws Exception {
        var store = new WatchlistService(directory.resolve("watchlist.json").toString(), Clock.systemUTC());
        Path log = directory.resolve("rust.txt");
        var tracker = new PlayerActivityTracker("rust", "127.0.0.1", 27015, new LogFileActivityRepository(log), store);
        try (var queries = mockConstruction(SourceServerQuery.class, (query, context) -> {
            when(query.getPlayers()).thenReturn(switch (context.getCount()) {
                case 1 -> Set.of(firstName);
                case 2 -> Set.of(nextName);
                default -> Set.of();
            });
        })) {
            tracker.trackOnline();
            tracker.trackOnline();
            assertEquals(1, Files.readAllLines(log).size());
            tracker.trackOnline();
            var lines = Files.readAllLines(log);
            assertEquals(2, lines.size());
            assertTrue(lines.get(0).endsWith("[CONNECT] " + firstName));
            assertTrue(lines.get(1).endsWith("[DISCONNECT] " + firstName));
        }
    }

    @Test
    void stateFileFailureDoesNotHideValidConnectAndDisconnectFromActivityLog() throws Exception {
        var store = mock(WatchlistService.class);
        doThrow(new WatchlistService.StorageException(new IOException("Unavailable")))
                .when(store).successfulPoll(eq("rust"), anySet());
        Path log = directory.resolve("rust.txt");
        var tracker = new PlayerActivityTracker("rust", "127.0.0.1", 27015, new LogFileActivityRepository(log), store);
        try (var queries = mockConstruction(SourceServerQuery.class, (query, context) ->
                when(query.getPlayers()).thenReturn(context.getCount() == 1 ? Set.of("Player") : Set.of()))) {
            tracker.trackOnline();
            assertTrue(Files.exists(log));
            tracker.trackOnline();
            var lines = Files.readAllLines(log);
            assertEquals(2, lines.size());
            assertTrue(lines.get(0).endsWith("[CONNECT] Player"));
            assertTrue(lines.get(1).endsWith("[DISCONNECT] Player"));
        }
    }
}
