package com.poptsov.gameactivitymonitoringservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class AnalyticsService {

    @Value("${se.monitor.log-file-path}")
    private String logFileName;

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public String getRawDataReport(String nickname, int days) {
        if (!Files.exists(Paths.get(logFileName))) {
            return "Файл статистики отсутствует.";
        }

        LocalDateTime thresholdDate = LocalDateTime.now().minusDays(days);
        StringBuilder sb = new StringBuilder();

        sb.append(String.format("**СЫРЫЕ ЛОГИ ЦЕЛИ: %s** (За последние %d дней)\n", nickname.toUpperCase(), days));
        sb.append("--------------------------------------------------\n```text\n");

        int linesCount = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(logFileName))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || !line.startsWith("[") || !line.contains(nickname)) {
                    continue;
                }

                // Проверяем дату строки
                LocalDateTime eventTime = LocalDateTime.parse(line.substring(1, 20), formatter);
                if (eventTime.isBefore(thresholdDate)) continue;

                sb.append(line).append("\n");
                linesCount++;
            }
        } catch (IOException e) {
            return "Ошибка чтения файла логов: " + e.getMessage();
        }

        sb.append("```");

        if (linesCount == 0) {
            return String.format("Записей по игроку %s за %d дней не найдено.", nickname, days);
        }

        // Если лог слишком огромный (Дискорд не примет сообщение длиннее 2000 символов)
        if (sb.length() > 1950) {
            return sb.substring(0, 1900) + "\n...[ЛОГ ОБРЕЗАН: ПРЕВЫШЕН ЛИМИТ ДИСКОРДА]```";
        }

        return sb.toString();
    }

    public String getPlayerReport(String nickname, int days) {
        if (!Files.exists(Paths.get(logFileName))) {
            return "Файл статистики отсутствует.";
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime thresholdDate = now.minusDays(days);

        Map<LocalDate, List<GameSessionInterval>> dailySessions = new LinkedHashMap<>();
        Map<Integer, Integer> globalHourlyStats = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(logFileName))) {
            String line;
            LocalDateTime loginTime = null;

            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty() || !line.startsWith("[") || !line.contains(nickname)) {
                    continue;
                }

                // Безопасное извлечение даты
                LocalDateTime eventTime = LocalDateTime.parse(line.substring(1, 20), formatter);
                if (eventTime.isBefore(thresholdDate)) continue;

                String action = line.contains("[CONNECT]") ? "CONNECT" : "DISCONNECT";

                if (action.equals("CONNECT")) {
                    loginTime = eventTime;
                } else if (action.equals("DISCONNECT") && loginTime != null) {
                    addSession(dailySessions, loginTime, eventTime);
                    calculateHourlyActivity(globalHourlyStats, loginTime, eventTime);
                    loginTime = null;
                }
            }

            if (loginTime != null) {
                addSession(dailySessions, loginTime, now);
                calculateHourlyActivity(globalHourlyStats, loginTime, now);
            }

        } catch (IOException e) {
            return "Ошибка чтения файла логов: " + e.getMessage();
        }

        if (dailySessions.isEmpty()) {
            return String.format("Игрок %s не появлялся в сети за последние %d дней.", nickname, days);
        }

        return buildMilitaryReport(nickname, days, dailySessions, globalHourlyStats);
    }

    private void addSession(Map<LocalDate, List<GameSessionInterval>> dailySessions, LocalDateTime login, LocalDateTime logout) {
        LocalDate date = login.toLocalDate();
        dailySessions.putIfAbsent(date, new ArrayList<>());
        dailySessions.get(date).add(new GameSessionInterval(login, logout));
    }

    private void calculateHourlyActivity(Map<Integer, Integer> stats, LocalDateTime login, LocalDateTime logout) {
        LocalDateTime temp = login.withMinute(0).withSecond(0);
        while (temp.isBefore(logout)) {
            int hour = temp.getHour();
            stats.put(hour, stats.getOrDefault(hour, 0) + 1);
            temp = temp.plusHours(1);
        }
    }

    private String buildMilitaryReport(String nickname, int days,
                                       Map<LocalDate, List<GameSessionInterval>> dailySessions,
                                       Map<Integer, Integer> globalHourlyStats) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("**СВОДКА АКТИВНОСТИ ЦЕЛИ: %s** (Период: %d дней)\n", nickname.toUpperCase(), days));
        sb.append("--------------------------------------------------\n\n");

        double totalPeriodHours = 0;

        sb.append("**ХРОНОЛОГИЯ ПО ДНЯМ:**\n");
        for (Map.Entry<LocalDate, List<GameSessionInterval>> entry : dailySessions.entrySet()) {
            LocalDate date = entry.getKey();
            List<GameSessionInterval> sessions = entry.getValue();

            double dayHours = 0;
            StringBuilder intervalsSb = new StringBuilder();

            for (GameSessionInterval session : sessions) {
                Duration d = Duration.between(session.login, session.logout);
                dayHours += d.toMinutes() / 60.0;

                intervalsSb.append(String.format("   • %02d:%02d — %02d:%02d\n",
                        session.login.getHour(), session.login.getMinute(),
                        session.logout.getHour(), session.logout.getMinute()));
            }

            totalPeriodHours += dayHours;
            sb.append(String.format("• **%s** (Общее время: %.1f ч.)\n", date.toString(), dayHours));
            sb.append(intervalsSb);
        }

        sb.append("\n**АНАЛИЗ ПЕРИОДОВ АКТИВНОСТИ:**\n");
        sb.append(String.format("Суммарное время в онлайне за весь период: %.1f ч.\n", totalPeriodHours));

        if (!globalHourlyStats.isEmpty()) {
            int peakHourStart = globalHourlyStats.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(0);

            sb.append(String.format("Часы пикового онлайна: %02d:00 — %02d:00\n",
                    peakHourStart, (peakHourStart + 1) % 24));
        }

        return sb.toString();
    }

    private static class GameSessionInterval {
        LocalDateTime login;
        LocalDateTime logout;

        GameSessionInterval(LocalDateTime login, LocalDateTime logout) {
            this.login = login;
            this.logout = logout;
        }
    }
}
