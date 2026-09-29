package com.poptsov.gameactivitymonitoringservice.config;

import com.poptsov.gameactivitymonitoringservice.core.PlayerActivityTracker;
import com.poptsov.gameactivitymonitoringservice.service.AnalyticsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MonitorConfig {

    @Bean
    public PlayerActivityTracker seTracker(
            @Value("${se.monitor.server-ip}") String ip,
            @Value("${se.monitor.query-port}") int port,
            @Value("${se.monitor.log-file-path}") String logPath) {
        return new PlayerActivityTracker("SpaceEngineers", ip, port, logPath);
    }

    @Bean
    public PlayerActivityTracker rustTracker(
            @Value("${rust.monitor.server-ip}") String ip,
            @Value("${rust.monitor.query-port}") int port,
            @Value("${rust.monitor.log-file-path}") String logPath) {
        return new PlayerActivityTracker("RUST", ip, port, logPath);
    }

    @Bean
    public AnalyticsService seAnalyticService (@Value ("${se.monitor.log-file-path}") String logFile) {
        return new AnalyticsService(logFile);
    }

    @Bean
    public AnalyticsService rustAnalyticService (@Value ("${rust.monitor.log-file-path}") String logFile) {
        return new AnalyticsService(logFile);
    }
}