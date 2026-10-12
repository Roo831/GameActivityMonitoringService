package com.poptsov.gameactivitymonitoringservice.controller;

import com.poptsov.gameactivitymonitoringservice.service.AnalyticsService;
import com.poptsov.gameactivitymonitoringservice.service.WatchlistService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.poptsov.gameactivitymonitoringservice.model.*;

import java.util.List;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/analytics/engineers")
public class AnalyticsControllerSE {

    private final AnalyticsService seAnalyticsService;
    private final WatchlistService watchlistService;

    public AnalyticsControllerSE(@Qualifier("seAnalyticService") AnalyticsService seAnalyticsService,
                                 WatchlistService watchlistService) {
        this.seAnalyticsService = seAnalyticsService;
        this.watchlistService = watchlistService;
    }

    @GetMapping("/stats")
    public ResponseEntity<PlayerActivityReport> getStats(
            @RequestParam String nickname,
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(seAnalyticsService.getPlayerReport(nickname, days));
    }

    @PostMapping("/watchlist")
    public ResponseEntity<WatchRegistration> addToWatchlist(@RequestParam String nickname,
            @RequestParam String userId, @RequestParam String channelId) {
        return ResponseEntity.ok(watchlistService.register("engineers", nickname, userId, channelId));
    }

    @GetMapping("/watchlist")
    public ResponseEntity<List<WatchSubscription>> getWatchlist(@RequestParam String userId, @RequestParam String channelId) {
        return ResponseEntity.ok(watchlistService.list("engineers", userId, channelId));
    }

    @DeleteMapping("/watchlist/{subscriptionId}")
    public ResponseEntity<Void> removeFromWatchlist(@PathVariable String subscriptionId,
            @RequestParam String userId, @RequestParam String channelId) {
        watchlistService.cancel("engineers", subscriptionId, userId, channelId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/watchlist/pending")
    public ResponseEntity<List<WatchNotification>> getPendingNotifications(
            @RequestParam(required = false) Instant afterObservedAt,
            @RequestParam(required = false) String afterEventId) {
        return ResponseEntity.ok(watchlistService.pending("engineers", afterObservedAt, afterEventId));
    }

    @PostMapping("/watchlist/pending/{eventId}/ack")
    public ResponseEntity<Void> acknowledgeNotification(@PathVariable String eventId) {
        watchlistService.acknowledge("engineers", eventId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/similar")
    public ResponseEntity<SimilarActivityReport> getSimilar(@RequestParam String nickname,
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(seAnalyticsService.getSimilarActivityReport(nickname, days));
    }
    @GetMapping("/raw")
    public ResponseEntity<RawDataReport> getRawData(
            @RequestParam String nickname,
            @RequestParam(defaultValue = "3") int days) {
        return ResponseEntity.ok(seAnalyticsService.getRawDataReport(nickname, days));
    }

    @GetMapping("/nicknames")
    public ResponseEntity<NicknameReport> getAllNicknames(
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(seAnalyticsService.buildListOfAllNames(days));
    }
}
