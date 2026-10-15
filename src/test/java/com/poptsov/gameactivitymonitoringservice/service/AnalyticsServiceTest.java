package com.poptsov.gameactivitymonitoringservice.service;

import com.poptsov.gameactivitymonitoringservice.repository.LogFileActivityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class AnalyticsServiceTest {
    @TempDir Path directory;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC);

    private AnalyticsService service(String logLines) throws Exception {
        Path log = directory.resolve("activity.txt");
        Files.writeString(log, logLines);
        return new AnalyticsService(new LogFileActivityRepository(log), clock);
    }

    @Test
    void playerStatisticsAreStructuredDataWithoutDiscordFormatting() throws Exception {
        Object report = service("[2026-10-07 10:00:00] [CONNECT] Alice\n"
                + "[2026-10-07 11:30:00] [DISCONNECT] Alice\n").getPlayerReport("Alice", 7);
        JsonMapper mapper = JsonMapper.builder().build();
        var json = mapper.readTree(mapper.writeValueAsString(report));

        assertTrue(json.isObject(), "The API must return a JSON object, not a formatted Discord string");
        assertEquals("Alice", json.get("nickname").asString());
        assertEquals(5400, json.get("totalSeconds").asLong());
        assertEquals(1, json.get("dailyActivity").size());
        assertEquals(5400, json.get("dailyActivity").get(0).get("totalSeconds").asLong());
        assertEquals("2026-10-07", json.get("dailyActivity").get(0).get("date").asString());
        assertEquals("2026-10-07T10:00:00", json.get("dailyActivity").get(0).get("sessions").get(0).get("start").asString());
        assertFalse(mapper.writeValueAsString(report).contains("TARGET ACTIVITY SUMMARY"));
    }

    @Test
    void countsOnlyThePartOfASessionInsideTheRequestedPeriod() throws Exception {
        var report = service("[2026-10-01 12:00:00] [CONNECT] Alice\n"
                + "[2026-10-03 12:00:00] [DISCONNECT] Alice\n").getPlayerReport("alice", 7);
        assertEquals(86400, report.totalSeconds());
        assertEquals(2, report.dailyActivity().size());
        assertEquals(43200, report.dailyActivity().get(0).totalSeconds());
        assertEquals("2026-10-02T12:00", report.dailyActivity().get(0).sessions().get(0).start().toString());
    }

    @Test
    void splitsMidnightSessionsIntoTheCorrectDays() throws Exception {
        var report = service("[2026-10-07 23:00:00] [CONNECT] Alice\n"
                + "[2026-10-08 02:00:00] [DISCONNECT] Alice\n").getPlayerReport("Alice", 7);
        assertEquals(10800, report.totalSeconds());
        assertEquals(3600, report.dailyActivity().get(0).totalSeconds());
        assertEquals(7200, report.dailyActivity().get(1).totalSeconds());
        assertEquals(0, report.peakHour());
    }

    @Test
    void marksAnUnclosedSessionAsOngoingAndIncludesItsNickname() throws Exception {
        var service = service("[2026-10-09 11:30:00] [CONNECT] Alice\n");
        var report = service.getPlayerReport("Alice", 7);
        assertEquals(1800, report.totalSeconds());
        assertTrue(report.dailyActivity().get(0).sessions().get(0).ongoing());
        assertEquals(java.util.List.of("Alice"), service.buildListOfAllNames(7).nicknames());
    }

    @Test
    void returnsEmptyDataBeforeTheFirstLogIsCreated() {
        var service = new AnalyticsService(new LogFileActivityRepository(directory.resolve("missing.txt")), clock);
        var report = service.getPlayerReport("Alice", 7);
        assertEquals(0, report.totalSeconds());
        assertNull(report.peakHour());
        assertTrue(report.dailyActivity().isEmpty());
        assertTrue(service.getRawDataReport("Alice", 7).events().isEmpty());
        assertTrue(service.buildListOfAllNames(7).nicknames().isEmpty());
    }

    @Test
    void rawEventsAreNotTruncatedToDiscordMessageLimits() throws Exception {
        String lines = "[2026-10-08 10:00:00] [CONNECT] Шкет\n"
                + "[2026-10-08 11:00:00] [DISCONNECT] Шкет\n";
        var report = service(lines.repeat(50)).getRawDataReport("шкет", 7);
        assertEquals(100, report.events().size());
        assertEquals("CONNECT", report.events().get(0).action());
        assertEquals("Шкет", report.events().get(99).nickname());
    }

    @Test
    void rejectsInvalidPeriodsAndBlankNicknames() throws Exception {
        var service = service("");
        assertThrows(IllegalArgumentException.class, () -> service.getPlayerReport("Alice", 0));
        assertThrows(IllegalArgumentException.class, () -> service.getRawDataReport("Alice", 366));
        assertThrows(IllegalArgumentException.class, () -> service.buildListOfAllNames(-1));
        assertThrows(IllegalArgumentException.class, () -> service.getPlayerReport(" ", 7));
    }

    @Test
    void ignoresMalformedAndFutureEvents() throws Exception {
        var service = service("invalid line\n[2026-99-07 10:00:00] [CONNECT] Alice\n"
                + "[2026-10-10 10:00:00] [CONNECT] Future\n"
                + "[2026-10-07 10:00:00] [CONNECT] Alice\n"
                + "[2026-10-07 11:00:00] [DISCONNECT] Alice\n");
        assertEquals(3600, service.getPlayerReport("Alice", 7).totalSeconds());
        assertEquals(java.util.List.of("Alice"), service.buildListOfAllNames(7).nicknames());
    }
}
