package com.poptsov.gameactivitymonitoringservice.service;

import com.poptsov.gameactivitymonitoringservice.repository.LogFileActivityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SimilarActivityServiceTest {
    @TempDir Path directory;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC);
    private final JsonMapper mapper = JsonMapper.builder().build();

    private AnalyticsService service(String lines) throws Exception {
        Path log = directory.resolve("activity.txt");
        Files.writeString(log, lines);
        return new AnalyticsService(new LogFileActivityRepository(log), clock);
    }

    private JsonNode report(AnalyticsService service, String nickname, int days) throws Exception {
        return mapper.readTree(mapper.writeValueAsString(service.getSimilarActivityReport(nickname, days)));
    }

    private JsonNode player(JsonNode report, String nickname) {
        for (var player : report.get("players")) {
            if (player.get("nickname").asString().equals(nickname)) return player;
        }
        fail("Missing similar player: " + nickname);
        return null;
    }

    @Test
    void reportsJaccardAndBothDirectionalPercentagesFromRealSessions() throws Exception {
        var report = report(service("""
                [2026-10-08 10:00:00] [CONNECT] Alice
                [2026-10-08 11:00:00] [CONNECT] Bob
                [2026-10-08 12:00:00] [DISCONNECT] Alice
                [2026-10-08 14:00:00] [DISCONNECT] Bob
                """), " Alice ", 7);

        assertEquals("Alice", report.get("nickname").asString());
        assertEquals(7, report.get("days").asInt());
        assertEquals("2026-10-02T12:00:00", report.get("periodStart").asString());
        assertEquals("2026-10-09T12:00:00", report.get("periodEnd").asString());
        assertEquals("Z", report.get("timeZone").asString());
        assertEquals(7200, report.get("totalSeconds").asLong());
        var bob = player(report, "Bob");
        assertEquals(10800, bob.get("totalSeconds").asLong());
        assertEquals(3600, bob.get("overlapSeconds").asLong());
        assertEquals(1, bob.get("sharedDays").asInt());
        assertEquals(50, bob.get("targetOverlapPercent").asDouble(), 0.0001);
        assertEquals(100.0 / 3, bob.get("playerOverlapPercent").asDouble(), 0.0001);
        assertEquals(25, bob.get("similarityPercent").asDouble(), 0.0001);
    }

    @Test
    void groupsCaseVariantsAndExcludesSelfOrZeroIntersection() throws Exception {
        var report = report(service("""
                [2026-10-08 10:00:00] [CONNECT] ALICE
                [2026-10-08 10:00:00] [CONNECT] Bob
                [2026-10-08 10:30:00] [CONNECT] alice
                [2026-10-08 11:00:00] [DISCONNECT] bob
                [2026-10-08 11:00:00] [CONNECT] bOB
                [2026-10-08 12:00:00] [DISCONNECT] ALICE
                [2026-10-08 12:00:00] [DISCONNECT] BOB
                [2026-10-08 12:00:00] [CONNECT] Zero
                [2026-10-08 13:00:00] [DISCONNECT] Zero
                [2026-10-08 13:00:00] [DISCONNECT] Unmatched
                """), "Alice", 7);

        assertEquals(1, report.get("players").size());
        assertEquals(7200, report.get("totalSeconds").asLong());
        var bob = player(report, "Bob");
        assertEquals(7200, bob.get("totalSeconds").asLong());
        assertEquals(7200, bob.get("overlapSeconds").asLong());
        assertEquals(100, bob.get("similarityPercent").asDouble());
    }

    @Test
    void clipsBothPlayersAtTheRollingWindowBoundary() throws Exception {
        var report = report(service("""
                [2026-10-01 10:00:00] [CONNECT] Bob
                [2026-10-01 11:00:00] [CONNECT] Alice
                [2026-10-02 18:00:00] [DISCONNECT] Bob
                [2026-10-03 12:00:00] [DISCONNECT] Alice
                """), "Alice", 7);

        assertEquals(86400, report.get("totalSeconds").asLong());
        var bob = player(report, "Bob");
        assertEquals(21600, bob.get("totalSeconds").asLong());
        assertEquals(21600, bob.get("overlapSeconds").asLong());
        assertEquals(25, bob.get("similarityPercent").asDouble());
        assertEquals(1, bob.get("sharedDays").asInt());
    }

    @Test
    void duplicateConnectsKeepTheOriginalStartAndOngoingSessionsEndNow() throws Exception {
        var report = report(service("""
                [2026-10-09 10:00:00] [CONNECT] Bob
                [2026-10-09 11:00:00] [CONNECT] Alice
                [2026-10-09 11:30:00] [CONNECT] Alice
                [2026-10-09 12:00:00] [CONNECT] StartsNow
                [2026-10-09 13:00:00] [DISCONNECT] Bob
                [2026-10-10 10:00:00] [CONNECT] Future
                """), "Alice", 7);

        assertEquals(3600, report.get("totalSeconds").asLong());
        assertEquals(1, report.get("players").size());
        var bob = player(report, "Bob");
        assertEquals(7200, bob.get("totalSeconds").asLong());
        assertEquals(3600, bob.get("overlapSeconds").asLong());
        assertEquals(50, bob.get("similarityPercent").asDouble());
    }

    @Test
    void unionsReplayedAndOverlappingSessionsWithoutDoubleCounting() throws Exception {
        var report = report(service("""
                [2026-10-08 10:00:00] [CONNECT] Alice
                [2026-10-08 12:00:00] [DISCONNECT] Alice
                [2026-10-08 10:30:00] [CONNECT] Alice
                [2026-10-08 11:30:00] [DISCONNECT] Alice
                [2026-10-08 10:00:00] [CONNECT] Bob
                [2026-10-08 12:00:00] [DISCONNECT] Bob
                [2026-10-08 10:00:00] [CONNECT] Bob
                [2026-10-08 12:00:00] [DISCONNECT] Bob
                """), "Alice", 7);

        assertEquals(7200, report.get("totalSeconds").asLong());
        var bob = player(report, "Bob");
        assertEquals(7200, bob.get("totalSeconds").asLong());
        assertEquals(7200, bob.get("overlapSeconds").asLong());
        assertEquals(100, bob.get("similarityPercent").asDouble());
    }

    @Test
    void countsDistinctOverlapDatesAndExcludesAMidnightEnd() throws Exception {
        var report = report(service("""
                [2026-10-07 22:00:00] [CONNECT] Alice
                [2026-10-07 22:00:00] [CONNECT] Dave
                [2026-10-07 22:30:00] [DISCONNECT] Dave
                [2026-10-07 23:00:00] [CONNECT] Dave
                [2026-10-07 23:00:00] [CONNECT] Bob
                [2026-10-07 23:00:00] [CONNECT] Carol
                [2026-10-07 23:30:00] [DISCONNECT] Dave
                [2026-10-08 00:00:00] [DISCONNECT] Bob
                [2026-10-08 01:00:00] [DISCONNECT] Carol
                [2026-10-08 02:00:00] [DISCONNECT] Alice
                """), "Alice", 7);

        assertEquals(1, player(report, "Bob").get("sharedDays").asInt());
        assertEquals(2, player(report, "Carol").get("sharedDays").asInt());
        assertEquals(1, player(report, "Dave").get("sharedDays").asInt());
        assertEquals(3600, player(report, "Dave").get("overlapSeconds").asLong());
    }

    @Test
    void ranksBySimilarityThenOverlapThenNickname() throws Exception {
        var report = report(service("""
                [2026-10-08 10:00:00] [CONNECT] Alice
                [2026-10-08 10:00:00] [CONNECT] Zeta
                [2026-10-08 10:00:00] [CONNECT] Alpha
                [2026-10-08 10:00:00] [CONNECT] HigherOverlap
                [2026-10-08 11:00:00] [DISCONNECT] Alpha
                [2026-10-08 11:00:00] [CONNECT] Beta
                [2026-10-08 12:00:00] [DISCONNECT] Alice
                [2026-10-08 12:00:00] [DISCONNECT] Zeta
                [2026-10-08 12:00:00] [DISCONNECT] HigherOverlap
                [2026-10-08 12:00:00] [DISCONNECT] Beta
                [2026-10-08 13:00:00] [CONNECT] HigherOverlap
                [2026-10-08 15:00:00] [DISCONNECT] HigherOverlap
                """), "Alice", 7);

        List<String> names = new ArrayList<>();
        report.get("players").forEach(player -> names.add(player.get("nickname").asString()));
        assertEquals(List.of("Zeta", "HigherOverlap", "Alpha", "Beta"), names);
    }

    @Test
    void normalizesUnicodeForMatchingAndSelfExclusion() throws Exception {
        var report = report(service("""
                [2026-10-08 10:00:00] [CONNECT] José
                [2026-10-08 10:30:00] [CONNECT] JOSÉ
                [2026-10-08 10:00:00] [CONNECT] Шкет
                [2026-10-08 12:00:00] [DISCONNECT] josé
                [2026-10-08 12:00:00] [DISCONNECT] шкет
                """), "JOSÉ", 7);

        assertEquals(7200, report.get("totalSeconds").asLong());
        assertEquals(1, report.get("players").size());
        assertEquals(100, player(report, "Шкет").get("similarityPercent").asDouble());
    }

    @Test
    void returnsEmptyResultsForMissingLogsOrAnUnknownTarget() throws Exception {
        var missing = new AnalyticsService(new LogFileActivityRepository(directory.resolve("missing.txt")), clock);
        var empty = report(missing, "Alice", 7);
        assertEquals(0, empty.get("totalSeconds").asLong());
        assertEquals(0, empty.get("players").size());
        var unknown = report(service("""
                [2026-10-08 10:00:00] [CONNECT] Bob
                [2026-10-08 12:00:00] [DISCONNECT] Bob
                """), "Alice", 7);
        assertEquals(0, unknown.get("totalSeconds").asLong());
        assertEquals(0, unknown.get("players").size());
    }

    @Test
    void rejectsInvalidNicknamesOrPeriodsBeforeReadingLogs() throws Exception {
        var service = service("");
        assertThrows(IllegalArgumentException.class, () -> report(service, "Alice", 0));
        assertThrows(IllegalArgumentException.class, () -> report(service, "Alice", 366));
        assertThrows(IllegalArgumentException.class, () -> report(service, null, 7));
        assertThrows(IllegalArgumentException.class, () -> report(service, " ", 7));
        assertThrows(IllegalArgumentException.class, () -> report(service, "a".repeat(129), 7));
    }

    @Test
    void ignoresMalformedEventsAndSessionsOutsideTheWindow() throws Exception {
        var report = report(service("""
                broken line
                [2026-99-08 10:00:00] [CONNECT] Alice
                [2026-10-01 10:00:00] [CONNECT] Old
                [2026-10-02 12:00:00] [DISCONNECT] Old
                [2026-10-08 10:00:00] [CONNECT] Alice
                [2026-10-08 10:00:00] [CONNECT] Bob
                [2026-10-08 11:00:00] [DISCONNECT] Alice
                [2026-10-08 11:00:00] [DISCONNECT] Bob
                """), "Alice", 7);

        assertEquals(3600, report.get("totalSeconds").asLong());
        assertEquals(1, report.get("players").size());
        assertEquals(100, player(report, "Bob").get("similarityPercent").asDouble());
    }
}
