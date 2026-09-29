package com.poptsov.gameactivitymonitoringservice.controller;

import com.poptsov.gameactivitymonitoringservice.service.AnalyticsService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics/rust")
public class AnalyticsControllerRust {

    private final AnalyticsService rustAnalyticsService;

    public AnalyticsControllerRust(@Qualifier("rustAnalyticService") AnalyticsService rustAnalyticsService) {
        this.rustAnalyticsService = rustAnalyticsService;
    }

    @GetMapping("/stats")
    public String getStats(
            @RequestParam String nickname,
            @RequestParam(defaultValue = "7") int days) {
        return rustAnalyticsService.getPlayerReport(nickname, days);
    }

    @GetMapping("/raw")
    public String getRawData(
            @RequestParam String nickname,
            @RequestParam(defaultValue = "3") int days) {
        return rustAnalyticsService.getRawDataReport(nickname, days);
    }

    @GetMapping("/nicknames")
    public String getAllNicknames(
            @RequestParam(defaultValue = "7") int days) {
        return rustAnalyticsService.buildListOfAllNames(days);
    }
}