package com.poptsov.gameactivitymonitoringservice.service;

import com.poptsov.gameactivitymonitoringservice.repository.LogFileActivityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class SimilarActivityRankingTest {
    @TempDir Path directory;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void repeatedCompanionRanksBeforeASinglePerfectCoincidence() throws Exception {
        var lines = new StringBuilder();
        for (int day = 6; day <= 8; day++) {
            lines.append("[2026-10-0%d 09:00:00] [CONNECT] Target\n".formatted(day));
            lines.append("[2026-10-0%d 09:04:00] [CONNECT] Companion\n".formatted(day));
            lines.append("[2026-10-0%d 10:00:00] [DISCONNECT] Target\n".formatted(day));
            lines.append("[2026-10-0%d 15:04:00] [DISCONNECT] Companion\n".formatted(day));
        }
        lines.append("[2026-10-08 09:00:00] [CONNECT] Coincidence\n");
        lines.append("[2026-10-08 10:00:00] [DISCONNECT] Coincidence\n");
        var log = directory.resolve("activity.txt");
        Files.writeString(log, lines);
        var report = new AnalyticsService(new LogFileActivityRepository(log), clock).getSimilarActivityReport("Target", 7);
        assertEquals("Companion", report.players().getFirst().nickname());
        assertEquals(3, report.players().getFirst().sharedSessions());
        assertTrue(report.players().stream().anyMatch(player -> player.nickname().equals("Coincidence")),
                "Keep substantial one-session overlaps available rather than hiding a possible companion");
    }

    @Test
    void briefChanceEncountersDoNotFillTheCandidateList() throws Exception {
        var log = directory.resolve("activity.txt");
        Files.writeString(log, """
                [2026-10-08 09:00:00] [CONNECT] Target
                [2026-10-08 09:10:00] [CONNECT] Companion
                [2026-10-08 09:30:00] [DISCONNECT] Companion
                [2026-10-08 09:45:00] [CONNECT] BriefEncounter
                [2026-10-08 09:46:00] [DISCONNECT] BriefEncounter
                [2026-10-08 10:00:00] [DISCONNECT] Target
                """);
        var report = new AnalyticsService(new LogFileActivityRepository(log), clock).getSimilarActivityReport("Target", 7);
        assertEquals(1, report.players().size());
        assertEquals("Companion", report.players().getFirst().nickname());
    }

    @Test
    void clipsOngoingSessionsWithoutCountingUnobservedTimeOutsideTheWindow() throws Exception {
        var log = directory.resolve("activity.txt");
        Files.writeString(log, """
                [2026-10-01 08:00:00] [CONNECT] Target
                [2026-10-01 10:00:00] [CONNECT] Companion
                """);
        var report = new AnalyticsService(new LogFileActivityRepository(log), clock).getSimilarActivityReport("Target", 7);
        var player = report.players().getFirst();
        assertEquals(1, player.sharedSessions());
        assertEquals(604800, player.overlapSeconds());
    }

    @Test
    void reconnectsWithinOneTargetSessionDoNotInflateSharedSessionCount() throws Exception {
        var log = directory.resolve("activity.txt");
        Files.writeString(log, """
                [2026-10-08 09:00:00] [CONNECT] Target
                [2026-10-08 09:00:00] [CONNECT] Companion
                [2026-10-08 09:10:00] [DISCONNECT] Companion
                [2026-10-08 09:20:00] [CONNECT] Companion
                [2026-10-08 09:30:00] [DISCONNECT] Companion
                [2026-10-08 09:40:00] [CONNECT] Companion
                [2026-10-08 09:50:00] [DISCONNECT] Companion
                [2026-10-08 10:00:00] [DISCONNECT] Target
                """);
        var report = new AnalyticsService(new LogFileActivityRepository(log), clock).getSimilarActivityReport("Target", 7);
        var player = report.players().getFirst();
        assertEquals(1, player.sharedSessions());
        assertEquals(1800, player.overlapSeconds());
    }

    @Test
    void fifteenMinuteBoundaryIsInclusive() throws Exception {
        var log = directory.resolve("activity.txt");
        Files.writeString(log, """
                [2026-10-08 09:00:00] [CONNECT] Target
                [2026-10-08 09:00:00] [CONNECT] Enough
                [2026-10-08 09:00:00] [CONNECT] TooBrief
                [2026-10-08 09:14:59] [DISCONNECT] TooBrief
                [2026-10-08 09:15:00] [DISCONNECT] Enough
                [2026-10-08 10:00:00] [DISCONNECT] Target
                """);
        var report = new AnalyticsService(new LogFileActivityRepository(log), clock).getSimilarActivityReport("Target", 7);
        assertEquals(1, report.players().size());
        assertEquals("Enough", report.players().getFirst().nickname());
    }

    @Test
    void alwaysOnlinePlayerDoesNotOutrankACompanionWithFewerSharedSessions() throws Exception {
        var log = directory.resolve("activity.txt");
        Files.writeString(log, """
                [2026-10-02 12:00:00] [CONNECT] AlwaysOnline
                [2026-10-06 09:00:00] [CONNECT] Target
                [2026-10-06 09:00:00] [CONNECT] Companion
                [2026-10-06 10:00:00] [DISCONNECT] Target
                [2026-10-06 10:00:00] [DISCONNECT] Companion
                [2026-10-07 09:00:00] [CONNECT] Target
                [2026-10-07 09:00:00] [CONNECT] Companion
                [2026-10-07 10:00:00] [DISCONNECT] Target
                [2026-10-07 10:00:00] [DISCONNECT] Companion
                [2026-10-08 09:00:00] [CONNECT] Target
                [2026-10-08 10:00:00] [DISCONNECT] Target
                """);
        var report = new AnalyticsService(new LogFileActivityRepository(log), clock).getSimilarActivityReport("Target", 7);
        assertEquals("Companion", report.players().getFirst().nickname());
    }

    @Test
    void monitorStartupCoincidenceDoesNotBeatMostOfTheTargetsSession() throws Exception {
        var log = directory.resolve("activity.txt");
        Files.writeString(log, """
                [2026-10-08 10:00:00] [CONNECT] Target
                [2026-10-08 10:00:00] [CONNECT] PresentAtMonitorStartup
                [2026-10-08 10:10:00] [CONNECT] Companion
                [2026-10-08 10:50:00] [DISCONNECT] Companion
                [2026-10-08 11:00:00] [DISCONNECT] Target
                [2026-10-08 20:00:00] [DISCONNECT] PresentAtMonitorStartup
                """);
        var report = new AnalyticsService(new LogFileActivityRepository(log), clock).getSimilarActivityReport("Target", 7);
        assertEquals("Companion", report.players().getFirst().nickname());
    }
}
