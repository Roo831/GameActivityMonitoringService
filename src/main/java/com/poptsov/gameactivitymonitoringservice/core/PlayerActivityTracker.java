package com.poptsov.gameactivitymonitoringservice.core;

import com.github.koraktor.steamcondenser.steam.SteamPlayer;
import com.github.koraktor.steamcondenser.steam.servers.SourceServer;
import com.poptsov.gameactivitymonitoringservice.service.AnalyticsService;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public class PlayerActivityTracker {

    private final String gameName;
    private final String serverIp;
    private final int queryPort;
    private final String logFilePath;
    private final AnalyticsService analyticsService;

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Set<String> lastOnlinePlayers = new HashSet<>();

    public PlayerActivityTracker(String gameName, String serverIp, int queryPort, String logFilePath, AnalyticsService analyticsService) {
        this.gameName = gameName;
        this.serverIp = serverIp;
        this.queryPort = queryPort;
        this.logFilePath = logFilePath;
        this.analyticsService = analyticsService;
    }

    @Scheduled(fixedDelay = 60000)
    public void trackOnline() {
        if (serverIp == null || serverIp.trim().isEmpty()) {
            return;
        }

        try {
            SourceServer server = new SourceServer(serverIp, queryPort);
            HashMap<String, SteamPlayer> playersMap = server.getPlayers();

            Set<String> currentOnline = new HashSet<>();
            for (SteamPlayer player : playersMap.values()) {
                String nickname = player.getName();
                if (nickname != null && !nickname.trim().isEmpty()) {
                    currentOnline.add(nickname.trim());
                }
            }

            String currentTimestamp = LocalDateTime.now().format(formatter);


            for (String player : currentOnline) {
                if (!lastOnlinePlayers.contains(player)) {
                    writeLog(currentTimestamp, "CONNECT", player);
                }
            }

            for (String player : lastOnlinePlayers) {
                if (!currentOnline.contains(player)) {
                    writeLog(currentTimestamp, "DISCONNECT", player);
                }
            }

            lastOnlinePlayers.clear();
            lastOnlinePlayers.addAll(currentOnline);

        } catch (Exception e) {
            System.err.println("[ERROR] [" + gameName + "] Game server polling error: " +
                    e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    @PreDestroy
    public void onShutdown() {
        if (serverIp == null || serverIp.trim().isEmpty() || lastOnlinePlayers.isEmpty()) {
            return;
        }

        String shutdownTimestamp = LocalDateTime.now().format(formatter);
        System.out.println("[INFO] Shutdown [" + gameName + "]: Writing DISCONNECT for " + lastOnlinePlayers.size() + " active players...");

        for (String player : lastOnlinePlayers) {
            writeLog(shutdownTimestamp, "DISCONNECT", player);
        }
    }

    private void writeLog(String timestamp, String action, String nickname) {
        String logLine = String.format("[%s] [%s] %s\n", timestamp, action, nickname);
        System.out.print("[NEW EVENT " + gameName + "] " + logLine);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFilePath, true))) {
            writer.write(logLine);
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to write the log to the file: " + e.getMessage());
        }
    }
}