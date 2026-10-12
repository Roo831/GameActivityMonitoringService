package com.poptsov.gameactivitymonitoringservice.core;

import com.poptsov.gameactivitymonitoringservice.service.WatchlistService;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class PlayerActivityTracker {

    private final String gameName;
    private final String serverIp;
    private final int queryPort;
    private final String logFilePath;
    private final WatchlistService watchlistService;

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Map<String, String> lastOnlinePlayers = new HashMap<>();

    public PlayerActivityTracker(String gameName, String serverIp, int queryPort, String logFilePath, WatchlistService watchlistService) {
        this.gameName = gameName;
        this.serverIp = serverIp;
        this.queryPort = queryPort;
        this.logFilePath = logFilePath;
        this.watchlistService = watchlistService;
    }

    @Scheduled(fixedDelay = 60000)
    public synchronized void trackOnline() {
        if (serverIp == null || serverIp.trim().isEmpty()) {
            return;
        }

        try {
            System.out.println("[DEBUG] [" + gameName + "] Connecting to " + serverIp + ":" + queryPort);

            SourceServerQuery query = new SourceServerQuery(serverIp, queryPort, 5000);
            Set<String> currentOnline = query.getPlayers();
            try {
                watchlistService.successfulPoll(gameName, currentOnline);
            } catch (WatchlistService.StorageException error) {
                System.err.println("[ERROR] [" + gameName + "] Watchlist update failed: " + error.getMessage());
            }

            String currentTimestamp = LocalDateTime.now().format(formatter);
            Map<String, String> currentByName = new HashMap<>();

            for (String player : currentOnline) {
                String normalizedName = Normalizer.normalize(player, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
                if (!lastOnlinePlayers.containsKey(normalizedName) && !currentByName.containsKey(normalizedName)) {
                    writeLog(currentTimestamp, "CONNECT", player);
                }
                currentByName.putIfAbsent(normalizedName, lastOnlinePlayers.getOrDefault(normalizedName, player));
            }

            for (var player : lastOnlinePlayers.entrySet()) {
                if (!currentByName.containsKey(player.getKey())) {
                    writeLog(currentTimestamp, "DISCONNECT", player.getValue());
                }
            }

            lastOnlinePlayers.clear();
            lastOnlinePlayers.putAll(currentByName);

        } catch (Exception e) {
            System.err.println("[ERROR] [" + gameName + "] Game server polling error: " +
                    e.getClass().getSimpleName() + " - " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    @PreDestroy
    public synchronized void onShutdown() {
        if (serverIp == null || serverIp.trim().isEmpty() || lastOnlinePlayers.isEmpty()) {
            return;
        }

        String shutdownTimestamp = LocalDateTime.now().format(formatter);
        System.out.println("[INFO] Shutdown [" + gameName + "]: Writing DISCONNECT for " + lastOnlinePlayers.size() + " active players...");

        for (String player : lastOnlinePlayers.values()) {
            writeLog(shutdownTimestamp, "DISCONNECT", player);
        }
    }

    private void writeLog(String timestamp, String action, String nickname) {
        String logLine = String.format("[%s] [%s] %s\n", timestamp, action, nickname);
        System.out.print("[NEW EVENT " + gameName + "] " + logLine);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFilePath, true))) {
            writer.write(logLine);
        } catch (IOException e) {
            System.err.println("[ERROR] [" + gameName + "] Failed to write the log to the file: " + e.getMessage());
        }
    }
}
