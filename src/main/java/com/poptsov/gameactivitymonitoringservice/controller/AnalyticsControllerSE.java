package com.poptsov.gameactivitymonitoringservice.controller;

import com.poptsov.gameactivitymonitoringservice.service.AnalyticsService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/analytics/engineers")
public class AnalyticsControllerSE {

    private final AnalyticsService seAnalyticsService;

    public AnalyticsControllerSE(@Qualifier("seAnalyticService") AnalyticsService seAnalyticsService) {
        this.seAnalyticsService = seAnalyticsService;
    }

    @GetMapping("/stats")
    public String getStats(
            @RequestParam String nickname,
            @RequestParam(defaultValue = "7") int days) {
        return seAnalyticsService.getPlayerReport(nickname, days);
    }

    @GetMapping("/raw")
    public String getRawData(
            @RequestParam String nickname,
            @RequestParam(defaultValue = "3") int days) {
        return seAnalyticsService.getRawDataReport(nickname, days);
    }

    @GetMapping("/nicknames")
    public String getAllNicknames(
            @RequestParam(defaultValue = "7") int days) {
        return seAnalyticsService.buildListOfAllNames(days);
    }
}