package com.poptsov.gameactivitymonitoringservice.repository;

import com.poptsov.gameactivitymonitoringservice.model.RawDataReport.ActivityEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LogFileActivityRepositoryTest {
    @TempDir Path directory;

    @Test
    void createsDirectoriesAndAppendsUtf8EventsInTheExistingLogFormat() throws Exception {
        var path = directory.resolve("nested/activity.txt");
        ActivityRepository repository = new LogFileActivityRepository(path);
        var first = new ActivityEvent(LocalDateTime.of(2026, 10, 8, 10, 0), "CONNECT", "Шкет 🦀");
        var second = new ActivityEvent(first.timestamp().plusHours(1), "DISCONNECT", first.nickname());
        repository.saveAll(List.of(first));
        repository.saveAll(List.of(second));
        assertEquals("[2026-10-08 10:00:00] [CONNECT] Шкет 🦀\n"
                + "[2026-10-08 11:00:00] [DISCONNECT] Шкет 🦀\n", Files.readString(path));
        try (var events = repository.findAll()) {
            assertEquals(List.of(first, second), events.toList());
        }
    }

    @Test
    void readsLegacyLogsAndSkipsMalformedRecordsWithoutChangingTheFile() throws Exception {
        var path = directory.resolve("activity.txt");
        var content = """
                unrelated diagnostic line
                [2026-02-31 10:00:00] [CONNECT] InvalidDate
                [2026-10-08 10:00:00] [CONNECT] Roo
                [2026-10-08 11:00:00] [DISCONNECT] Roo
                """;
        Files.writeString(path, content);
        try (var events = new LogFileActivityRepository(path).findAll()) {
            assertEquals(2, events.count());
        }
        assertEquals(content, Files.readString(path));
    }

    @Test
    void missingLogIsEmptyAndAnUnwritablePathFailsInsteadOfPretendingToSave() throws Exception {
        var repository = new LogFileActivityRepository(directory.resolve("new.txt"));
        try (var events = repository.findAll()) { assertEquals(0, events.count()); }
        var blocked = new LogFileActivityRepository(directory);
        var event = new ActivityEvent(LocalDateTime.of(2026, 10, 8, 10, 0), "CONNECT", "Roo");
        assertThrows(UncheckedIOException.class, () -> blocked.saveAll(List.of(event)));
    }

    @Test
    void rejectsInjectedLinesBeforeWritingAnyPartOfABatch() throws Exception {
        var path = directory.resolve("activity.txt");
        var repository = new LogFileActivityRepository(path);
        var at = LocalDateTime.of(2026, 10, 8, 10, 0);
        assertThrows(IllegalArgumentException.class, () -> repository.saveAll(List.of(
                new ActivityEvent(at, "CONNECT", "Roo"), new ActivityEvent(at, "CONNECT", "Bad\nName"))));
        assertFalse(Files.exists(path));
    }
}
