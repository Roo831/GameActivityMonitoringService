package com.poptsov.gameactivitymonitoringservice.controller;

import com.poptsov.gameactivitymonitoringservice.service.AnalyticsService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.poptsov.gameactivitymonitoringservice.model.*;
import java.util.Map;

import java.util.List;

@RestController
@RequestMapping("/api/v1/analytics/engineers")
public class AnalyticsControllerSE {

    private final AnalyticsService seAnalyticsService;

    public AnalyticsControllerSE(@Qualifier("seAnalyticService") AnalyticsService seAnalyticsService) {
        this.seAnalyticsService = seAnalyticsService;
    }

    @GetMapping("/stats")
    public ResponseEntity<PlayerActivityReport> getStats(
            @RequestParam String nickname,
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(seAnalyticsService.getPlayerReport(nickname, days));
    }

    @PostMapping("/watchlist")
    public ResponseEntity<Map<String, Object>> addToWatchlist(@RequestParam String nickname) {
        seAnalyticsService.addToWatchlist(nickname);
        return ResponseEntity.ok(Map.of("nickname", nickname.trim(), "watching", true));
    }

    @GetMapping("/watchlist/pending")
    public ResponseEntity<List<String>> getPendingNotifications() {
        List<String> pending = seAnalyticsService.getAndResetPendingNotifications();
        return ResponseEntity.ok(pending);

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
