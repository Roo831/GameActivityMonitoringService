package com.poptsov.gameactivitymonitoringservice.model;

import java.time.Instant;

public record WatchSubscription(String id, String nickname, String userId, String channelId,
                                Instant createdAt, String status) { }
