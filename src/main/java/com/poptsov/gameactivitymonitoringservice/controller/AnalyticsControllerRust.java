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
@RequestMapping("/api/v1/analytics/rust")
public class AnalyticsControllerRust {

    private final AnalyticsService rustAnalyticsService;
    private final WatchlistService watchlistService;


    public AnalyticsControllerRust(@Qualifier("rustAnalyticService") AnalyticsService rustAnalyticsService,
                                   WatchlistService watchlistService) {
        this.rustAnalyticsService = rustAnalyticsService;
        this.watchlistService = watchlistService;
    }

    @GetMapping("/stats")
    public ResponseEntity<PlayerActivityReport> getStats(
            @RequestParam String nickname,
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(rustAnalyticsService.getPlayerReport(nickname, days));
    }

    @PostMapping("/watchlist")
    public ResponseEntity<WatchRegistration> addToWatchlist(@RequestParam String nickname,
            @RequestParam String userId, @RequestParam String channelId) {
        return ResponseEntity.ok(watchlistService.register("rust", nickname, userId, channelId));
    }

    @GetMapping("/watchlist")
    public ResponseEntity<List<WatchSubscription>> getWatchlist(@RequestParam String userId, @RequestParam String channelId) {
        return ResponseEntity.ok(watchlistService.list("rust", userId, channelId));
    }

    @DeleteMapping("/watchlist/{subscriptionId}")
    public ResponseEntity<Void> removeFromWatchlist(@PathVariable String subscriptionId,
            @RequestParam String userId, @RequestParam String channelId) {
        watchlistService.cancel("rust", subscriptionId, userId, channelId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/watchlist/pending")
    public ResponseEntity<List<WatchNotification>> getPendingNotifications(
            @RequestParam(required = false) Instant afterObservedAt,
            @RequestParam(required = false) String afterEventId) {
        return ResponseEntity.ok(watchlistService.pending("rust", afterObservedAt, afterEventId));
    }

    @PostMapping("/watchlist/pending/{eventId}/ack")
    public ResponseEntity<Void> acknowledgeNotification(@PathVariable String eventId) {
        watchlistService.acknowledge("rust", eventId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/similar")
    public ResponseEntity<SimilarActivityReport> getSimilar(@RequestParam String nickname,
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(rustAnalyticsService.getSimilarActivityReport(nickname, days));
    }

    @GetMapping("/raw")
    public ResponseEntity<RawDataReport> getRawData(
            @RequestParam String nickname,
            @RequestParam(defaultValue = "3") int days) {
        return ResponseEntity.ok(rustAnalyticsService.getRawDataReport(nickname, days));
    }

    @GetMapping("/nicknames")
    public ResponseEntity<NicknameReport> getAllNicknames(
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(rustAnalyticsService.buildListOfAllNames(days));
    }
}
