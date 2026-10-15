package com.poptsov.gameactivitymonitoringservice.config;

import com.poptsov.gameactivitymonitoringservice.core.PlayerActivityTracker;
import com.poptsov.gameactivitymonitoringservice.repository.ActivityRepository;
import com.poptsov.gameactivitymonitoringservice.repository.LogFileActivityRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import java.nio.file.Path;
import com.poptsov.gameactivitymonitoringservice.service.AnalyticsService;
import com.poptsov.gameactivitymonitoringservice.service.WatchlistService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MonitorConfig {

    @Bean
    public ActivityRepository seActivityRepository(@Value("${se.monitor.log-file-path}") String logFile) {
        return new LogFileActivityRepository(Path.of(logFile));
    }

    @Bean
    public ActivityRepository rustActivityRepository(@Value("${rust.monitor.log-file-path}") String logFile) {
        return new LogFileActivityRepository(Path.of(logFile));
    }

    @Bean
    public WatchlistService watchlistService(@Value("${watchlist.state-file}") String stateFile) {
        return new WatchlistService(stateFile, Clock.systemUTC());
    }

    @Bean
    public PlayerActivityTracker seTracker(
            @Value("${se.monitor.server-ip}") String ip,
            @Value("${se.monitor.query-port}") int port,
            WatchlistService watchlistService,
            @Qualifier("seActivityRepository") ActivityRepository repository) {
        return new PlayerActivityTracker("engineers", ip, port, repository, watchlistService);
    }

    @Bean
    public PlayerActivityTracker rustTracker(
            @Value("${rust.monitor.server-ip}") String ip,
            @Value("${rust.monitor.query-port}") int port,
            WatchlistService watchlistService,
            @Qualifier("rustActivityRepository") ActivityRepository repository) {
        return new PlayerActivityTracker("rust", ip, port, repository, watchlistService);
    }

    @Bean
    public AnalyticsService seAnalyticService(@Qualifier("seActivityRepository") ActivityRepository repository) {
        return new AnalyticsService(repository);
    }

    @Bean
    public AnalyticsService rustAnalyticService(@Qualifier("rustActivityRepository") ActivityRepository repository) {
        return new AnalyticsService(repository);
    }
}
