package com.poptsov.gameactivitymonitoringservice.model;

import java.time.LocalDateTime;
import java.util.List;

public record NicknameReport(int days, LocalDateTime periodStart, LocalDateTime periodEnd,
                             String timeZone, List<String> nicknames) {}
