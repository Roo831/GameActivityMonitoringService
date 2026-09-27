package com.poptsov.gameactivitymonitoringservice.core;

import com.github.koraktor.steamcondenser.steam.SteamPlayer;
import com.github.koraktor.steamcondenser.steam.servers.SourceServer;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

@Component
public class PlayerActivityTracker {

    @Value("${se.monitor.server-ip}")
    private String serverIp;

    @Value("${se.monitor.query-port}")
    private int queryPort;

    @Value("${se.monitor.log-file-path}")
    private String logFileName;

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final Set<String> lastOnlinePlayers = new HashSet<>();

    private boolean isFirstScan = true;

    @Scheduled(fixedDelay = 60000)
    public void trackOnline() {
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

            if (isFirstScan) {
                lastOnlinePlayers.addAll(currentOnline);
                isFirstScan = false;
                System.out.println("[INFO] Monitoring successfully started.");
                return;
            }

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
            System.err.println("[ERROR] Game server polling error: " + e.getMessage());
        }
    }


    @PreDestroy
    public void onShutdown() {
        if (lastOnlinePlayers.isEmpty()) {
            System.out.println("[INFO] Shutdown: No active players to disconnect.");
            return;
        }

        String shutdownTimestamp = LocalDateTime.now().format(formatter);
        System.out.println("[INFO] Shutdown: Writing DISCONNECT for " + lastOnlinePlayers.size() + " active players...");

        for (String player : lastOnlinePlayers) {
            writeLog(shutdownTimestamp, "DISCONNECT", player);
        }

        System.out.println("[INFO] Shutdown: All active players disconnected successfully.");
    }

    private void writeLog(String timestamp, String action, String nickname) {
        String logLine = String.format("[%s] [%s] %s\n", timestamp, action, nickname);
        System.out.print("[NEW EVENT] " + logLine);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFileName, true))) {
            writer.write(logLine);
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to write the log to the file: " + e.getMessage());
        }
    }
}