package com.poptsov.gameactivitymonitoringservice.model;

import java.time.Instant;

public record WatchNotification(String eventId, String subscriptionId, String nickname,
                                String userId, String channelId, Instant observedAt) { }
