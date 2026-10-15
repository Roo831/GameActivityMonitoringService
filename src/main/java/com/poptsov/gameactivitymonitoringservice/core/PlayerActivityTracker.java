package com.poptsov.gameactivitymonitoringservice.core;

import com.poptsov.gameactivitymonitoringservice.service.WatchlistService;
import com.poptsov.gameactivitymonitoringservice.repository.ActivityRepository;
import com.poptsov.gameactivitymonitoringservice.model.RawDataReport.ActivityEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class PlayerActivityTracker {
    private static final Logger log = LogManager.getLogger(PlayerActivityTracker.class);

    private final String gameName;
    private final String serverIp;
    private final int queryPort;
    private final ActivityRepository activityRepository;
    private final WatchlistService watchlistService;

    private final Map<String, String> lastOnlinePlayers = new HashMap<>();

    public PlayerActivityTracker(String gameName, String serverIp, int queryPort, ActivityRepository activityRepository, WatchlistService watchlistService) {
        this.gameName = gameName;
        this.serverIp = serverIp;
        this.queryPort = queryPort;
        this.activityRepository = activityRepository;
        this.watchlistService = watchlistService;
    }

    @Scheduled(fixedDelay = 60000)
    public synchronized void trackOnline() {
        if (serverIp == null || serverIp.trim().isEmpty()) {
            return;
        }

        try {
            log.debug("[{}] Polling {}:{}", gameName, serverIp, queryPort);

            SourceServerQuery query = new SourceServerQuery(serverIp, queryPort, 5000);
            Set<String> currentOnline = query.getPlayers();
            try {
                watchlistService.successfulPoll(gameName, currentOnline);
            } catch (WatchlistService.StorageException error) {
                log.error("[{}] Watchlist update failed", gameName, error);
            }

            var currentTimestamp = LocalDateTime.now();
            var events = new ArrayList<ActivityEvent>();
            Map<String, String> currentByName = new HashMap<>();

            for (String player : currentOnline) {
                String normalizedName = Normalizer.normalize(player, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
                if (!lastOnlinePlayers.containsKey(normalizedName) && !currentByName.containsKey(normalizedName)) {
                    events.add(new ActivityEvent(currentTimestamp, "CONNECT", player));
                }
                currentByName.putIfAbsent(normalizedName, lastOnlinePlayers.getOrDefault(normalizedName, player));
            }

            for (var player : lastOnlinePlayers.entrySet()) {
                if (!currentByName.containsKey(player.getKey())) {
                    events.add(new ActivityEvent(currentTimestamp, "DISCONNECT", player.getValue()));
                }
            }

            activityRepository.saveAll(events);
            // Keep the last committed presence until the journal accepts the batch, so writes can be retried.
            lastOnlinePlayers.clear();
            lastOnlinePlayers.putAll(currentByName);
            for (var event : events) log.info("[{}] {} {}", gameName, event.action(), event.nickname());

        } catch (Exception e) {
            log.error("[{}] Polling or activity persistence failed", gameName, e);
        }
    }

    @PreDestroy
    public synchronized void onShutdown() {
        if (serverIp == null || serverIp.trim().isEmpty() || lastOnlinePlayers.isEmpty()) {
            return;
        }

        var shutdownTimestamp = LocalDateTime.now();
        try {
            activityRepository.saveAll(lastOnlinePlayers.values().stream()
                    .map(player -> new ActivityEvent(shutdownTimestamp, "DISCONNECT", player)).toList());
            lastOnlinePlayers.clear();
            log.info("[{}] Closed active sessions on shutdown", gameName);
        } catch (Exception error) {
            log.error("[{}] Could not persist shutdown sessions", gameName, error);
        }
    }
}
