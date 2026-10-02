package com.poptsov.gameactivitymonitoringservice.controller;

import com.poptsov.gameactivitymonitoringservice.service.AnalyticsService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/analytics/rust")
public class AnalyticsControllerRust {

    private final AnalyticsService rustAnalyticsService;


    public AnalyticsControllerRust(@Qualifier("rustAnalyticService") AnalyticsService rustAnalyticsService) {
        this.rustAnalyticsService = rustAnalyticsService;
    }

    @GetMapping("/stats")
    public ResponseEntity<String> getStats(
            @RequestParam String nickname,
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(rustAnalyticsService.getPlayerReport(nickname, days));
    }

    @PostMapping("/watchlist")
    public ResponseEntity<String> addToWatchlist(@RequestParam String nickname) {
        rustAnalyticsService.addToWatchlist(nickname);
        return ResponseEntity.ok("Игрок " + nickname + " добавлен в список слежения.");
    }

    @GetMapping("/watchlist/pending")
    public ResponseEntity<List<String>> getPendingNotifications() {
        List<String> pending = rustAnalyticsService.getAndResetPendingNotifications();
        return ResponseEntity.ok(pending);
    }

    @GetMapping("/raw")
    public ResponseEntity<String> getRawData(
            @RequestParam String nickname,
            @RequestParam(defaultValue = "3") int days) {
        return ResponseEntity.ok(rustAnalyticsService.getRawDataReport(nickname, days));
    }

    @GetMapping("/nicknames")
    public ResponseEntity<String> getAllNicknames(
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(rustAnalyticsService.buildListOfAllNames(days));
    }
}