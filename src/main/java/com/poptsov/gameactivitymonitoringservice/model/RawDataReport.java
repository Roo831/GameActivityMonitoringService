package com.poptsov.gameactivitymonitoringservice.model;

import java.time.LocalDateTime;
import java.util.List;

public record RawDataReport(String nickname, int days, LocalDateTime periodStart,
                            LocalDateTime periodEnd, String timeZone, List<ActivityEvent> events) {
    public record ActivityEvent(LocalDateTime timestamp, String action, String nickname) {}
}
