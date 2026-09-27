package com.poptsov.gameactivitymonitoringservice.controller;

import com.poptsov.gameactivitymonitoringservice.service.AnalyticsService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/stats")
    public String getStats(
            @RequestParam String nickname,
            @RequestParam(defaultValue = "7") int days) {
        return analyticsService.getPlayerReport(nickname, days);
    }

    @GetMapping("/raw")
    public String getRawData(
            @RequestParam String nickname,
            @RequestParam(defaultValue = "3") int days) {
        return analyticsService.getRawDataReport(nickname, days);
    }

    @GetMapping("/nicknames")
    public String getAllNicknames(
            @RequestParam(defaultValue = "7") int days) {
        return analyticsService.buildListOfAllNames(days);
    }
}