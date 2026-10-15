package com.poptsov.gameactivitymonitoringservice.model;

import java.time.LocalDateTime;
import java.util.List;

public record SimilarActivityReport(String nickname, int days, LocalDateTime periodStart,
                                    LocalDateTime periodEnd, String timeZone, long totalSeconds,
                                    List<SimilarPlayer> players) {
    public record SimilarPlayer(String nickname, long totalSeconds, long overlapSeconds, int sharedDays,
                                double targetOverlapPercent, double playerOverlapPercent,
                                double similarityPercent, int sharedSessions, double rankingScore) {}
}
