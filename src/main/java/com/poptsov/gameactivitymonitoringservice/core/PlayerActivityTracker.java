package com.poptsov.gameactivitymonitoringservice.core;

import com.github.koraktor.steamcondenser.steam.SteamPlayer;
import com.github.koraktor.steamcondenser.steam.servers.SourceServer;
import jakarta.annotation.PostConstruct;
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

    @PostConstruct
    public void init() {
        writeLog("Logging initialized\n");
    }

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
                System.out.println("[INFO] Первый запуск выполнен. Онлайн синхронизирован без записи CONNECT.");
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
            System.err.println("[ERROR] Ошибка опроса игрового сервера: " + e.getMessage());
        }
    }

    private void writeLog(String timestamp, String action, String nickname) {
        String logLine = String.format("[%s] [%s] %s\n", timestamp, action, nickname);
        System.out.print("[NEW EVENT] " + logLine);

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFileName, true))) {
            writer.write(logLine);
        } catch (IOException e) {
            System.err.println("[ERROR] Не удалось записать лог в файл: " + e.getMessage());
        }
    }

    private void writeLog(String message) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(logFileName, true))) {
            writer.write(message);
        } catch (IOException e) {
            System.err.println("[ERROR] Не удалось записать сообщение в файл: " + e.getMessage());
        }
    }
}
