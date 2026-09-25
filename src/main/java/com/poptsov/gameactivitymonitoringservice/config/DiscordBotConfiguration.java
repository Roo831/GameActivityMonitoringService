package com.poptsov.gameactivitymonitoringservice.config;

import com.poptsov.gameactivitymonitoringservice.service.AnalyticsService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;

@Configuration
public class DiscordBotConfiguration extends ListenerAdapter {

    @Value("${discord.bot.token}")
    private String botToken;

    private final AnalyticsService analyticsService;

    public DiscordBotConfiguration(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @PostConstruct
    public void startBot() throws Exception {
        if (botToken == null || botToken.trim().isEmpty() || botToken.equals("NOT_SET")) {
            System.err.println("[DISCORD ERROR] Токен бота не настроен в конфигурации!");
            return;
        }

        JDA jda = JDABuilder.createDefault(botToken.trim())
                .addEventListeners(this)
                .build();

        jda.awaitReady();
        jda.updateCommands().addCommands(
                Commands.slash("stats_week", "Получить аналитику игрока за неделю")
                        .addOption(OptionType.STRING, "nickname", "Никнейм игрока в Steam", true),
                Commands.slash("stats_month", "Получить аналитику игрока за месяц")
                        .addOption(OptionType.STRING, "nickname", "Никнейм игрока в Steam", true),

                // Наша новая команда raw_data
                Commands.slash("raw_data", "Получить сырые записи лога по игроку")
                        .addOption(OptionType.STRING, "nickname", "Никнейм игрока в Steam", true)
                        .addOption(OptionType.INTEGER, "days", "За сколько дней собрать логи (по умолчанию 3)", false)
        ).queue();

        System.out.println("[DISCORD INFO] Бот успешно запущен и зарегистрировал слэш-команды!");
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String nickname = event.getOption("nickname").getAsString();
        String report = "";

        if (event.getName().equals("stats_week")) {
            report = analyticsService.getPlayerReport(nickname, 7);
        } else if (event.getName().equals("stats_month")) {
            report = analyticsService.getPlayerReport(nickname, 30);
        } else if (event.getName().equals("raw_data")) {
            // Проверяем, ввёл ли пользователь кастомное число дней, если нет — берем дефолтные 3 дня
            OptionMapping daysOption = event.getOption("days");
            int days = (daysOption != null) ? daysOption.getAsInt() : 3;

            report = analyticsService.getRawDataReport(nickname, days);
        } else {
            return;
        }

        event.reply(report).queue();
    }
}
