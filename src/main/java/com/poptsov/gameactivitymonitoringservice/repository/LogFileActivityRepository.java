package com.poptsov.gameactivitymonitoringservice.repository;

import com.poptsov.gameactivitymonitoringservice.model.RawDataReport.ActivityEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class LogFileActivityRepository implements ActivityRepository {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern LINE = Pattern.compile("^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\] \\[(CONNECT|DISCONNECT)\\]\\s+(.+)$");
    private final Path file;

    public LogFileActivityRepository(Path file) {
        this.file = file.toAbsolutePath();
    }

    @Override
    public Stream<ActivityEvent> findAll() {
        if (Files.notExists(file)) return Stream.empty();
        try {
            return Files.lines(file, StandardCharsets.UTF_8).map(LogFileActivityRepository::parse)
                    .filter(Objects::nonNull);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot read activity journal: " + file, error);
        }
    }

    @Override
    public synchronized void saveAll(List<ActivityEvent> events) {
        if (events.isEmpty()) return;
        var text = new StringBuilder();
        for (var event : events) {
            if (event == null || event.timestamp() == null || event.nickname() == null || event.nickname().isBlank()
                    || event.nickname().chars().anyMatch(Character::isISOControl)
                    || !("CONNECT".equals(event.action()) || "DISCONNECT".equals(event.action()))) {
                throw new IllegalArgumentException("Invalid activity event.");
            }
            text.append('[').append(FORMAT.format(event.timestamp())).append("] [")
                    .append(event.action()).append("] ").append(event.nickname()).append('\n');
        }
        try {
            Files.createDirectories(file.getParent());
            try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                long originalSize = channel.size();
                channel.position(originalSize);
                try {
                    var buffer = ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8));
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(false);
                } catch (IOException error) {
                    try { channel.truncate(originalSize); channel.force(false); }
                    catch (IOException rollbackError) { error.addSuppressed(rollbackError); }
                    throw error;
                }
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot append activity journal: " + file, error);
        }
    }

    private static ActivityEvent parse(String line) {
        var match = LINE.matcher(line.trim());
        if (!match.matches()) return null;
        try {
            String nickname = match.group(3).trim();
            if (nickname.isEmpty() || nickname.chars().anyMatch(Character::isISOControl)) return null;
            return new ActivityEvent(LocalDateTime.parse(match.group(1), FORMAT), match.group(2), nickname);
        } catch (DateTimeParseException error) {
            return null;
        }
    }
}
