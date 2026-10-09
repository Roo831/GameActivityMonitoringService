package com.poptsov.gameactivitymonitoringservice.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record PlayerActivityReport(String nickname, int days, LocalDateTime periodStart,
                                   LocalDateTime periodEnd, String timeZone, long totalSeconds,
                                   Integer peakHour, List<DayActivity> dailyActivity) {
    public record DayActivity(LocalDate date, long totalSeconds, List<Session> sessions) {}
    public record Session(LocalDateTime start, LocalDateTime end, long durationSeconds, boolean ongoing) {}
}
