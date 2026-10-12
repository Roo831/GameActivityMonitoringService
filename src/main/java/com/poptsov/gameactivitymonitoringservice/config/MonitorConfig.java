package com.poptsov.gameactivitymonitoringservice.config;

import com.poptsov.gameactivitymonitoringservice.core.PlayerActivityTracker;
import com.poptsov.gameactivitymonitoringservice.service.AnalyticsService;
import com.poptsov.gameactivitymonitoringservice.service.WatchlistService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MonitorConfig {

    @Bean
    public WatchlistService watchlistService(@Value("${watchlist.state-file}") String stateFile) {
        return new WatchlistService(stateFile, Clock.systemUTC());
    }

    @Bean
    public PlayerActivityTracker seTracker(
            @Value("${se.monitor.server-ip}") String ip,
            @Value("${se.monitor.query-port}") int port,
            WatchlistService watchlistService,
            @Value("${se.monitor.log-file-path}") String logPath) {
        return new PlayerActivityTracker("engineers", ip, port, logPath, watchlistService);
    }

    @Bean
    public PlayerActivityTracker rustTracker(
            @Value("${rust.monitor.server-ip}") String ip,
            @Value("${rust.monitor.query-port}") int port,
            WatchlistService watchlistService,
            @Value("${rust.monitor.log-file-path}") String logPath) {
        return new PlayerActivityTracker("rust", ip, port, logPath, watchlistService);
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
