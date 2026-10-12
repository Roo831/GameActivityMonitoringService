package com.poptsov.gameactivitymonitoringservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import com.poptsov.gameactivitymonitoringservice.service.WatchlistService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "api.security.key=test-key", "se.monitor.server-ip=", "rust.monitor.server-ip="})
class WatchlistApiTest {
    @TempDir static Path directory;
    @LocalServerPort int port;
    @Autowired WatchlistService watchlistService;
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry properties) {
        properties.add("watchlist.state-file", () -> directory.resolve("watchlist.json").toString());
        properties.add("rust.monitor.log-file-path", () -> directory.resolve("rust.txt").toString());
        properties.add("se.monitor.log-file-path", () -> directory.resolve("engineers.txt").toString());
    }

    @Test
    void registersIndependentSubscribersAndListsOnlyOwnerAndChannel() throws Exception {
        var first = json(request("POST", "rust/watchlist?nickname=Player&userId=100&channelId=200"), 200);
        assertNotNull(first.get("subscription"));
        String id = first.get("subscription").get("id").asString();
        assertEquals("WAITING", first.get("subscription").get("status").asString());
        assertFalse(first.get("alreadyOnline").asBoolean());
        var duplicate = json(request("POST", "rust/watchlist?nickname=player&userId=100&channelId=200"), 200);
        assertEquals(id, duplicate.get("subscription").get("id").asString());
        var second = json(request("POST", "rust/watchlist?nickname=Player&userId=101&channelId=200"), 200);
        assertNotEquals(id, second.get("subscription").get("id").asString());
        assertEquals(1, json(request("GET", "rust/watchlist?userId=100&channelId=200"), 200).size());
        assertEquals(0, json(request("GET", "rust/watchlist?userId=100&channelId=201"), 200).size());
        assertEquals(0, json(request("GET", "engineers/watchlist?userId=100&channelId=200"), 200).size());
        assertEquals(204, request("DELETE", "rust/watchlist/" + id + "?userId=101&channelId=200").statusCode());
        assertEquals(204, request("DELETE", "rust/watchlist/" + id + "?userId=100&channelId=201").statusCode());
        assertEquals(1, json(request("GET", "rust/watchlist?userId=100&channelId=200"), 200).size());
        assertEquals(204, request("DELETE", "rust/watchlist/" + id + "?userId=100&channelId=200").statusCode());
        assertEquals(204, request("DELETE", "rust/watchlist/" + id + "?userId=100&channelId=200").statusCode());
        assertEquals(0, json(request("GET", "rust/watchlist?userId=100&channelId=200"), 200).size());
    }

    @Test
    void rejectsInvalidRegistrationValuesWithJsonErrors() throws Exception {
        for (String query : new String[]{"nickname=&userId=300&channelId=400",
                "nickname=Player&userId=abc&channelId=400", "nickname=Player&userId=300&channelId=0",
                "nickname=Player&userId=300",
                "nickname=" + "x".repeat(129) + "&userId=300&channelId=400",
                "nickname=Player&userId=18446744073709551616&channelId=400"}) {
            assertEquals("INVALID_REQUEST", json(request("POST", "rust/watchlist?" + query), 400).get("code").asString());
        }
    }

    @Test
    void limitsEachUsersSubscriptionsPerGameAndKeepsDuplicatesIdempotentAtLimit() throws Exception {
        for (int i = 0; i < 25; i++) {
            json(request("POST", "rust/watchlist?nickname=Limit" + i + "&userId=500&channelId=600"), 200);
        }
        json(request("POST", "rust/watchlist?nickname=Limit0&userId=500&channelId=600"), 200);
        json(request("POST", "rust/watchlist?nickname=Extra&userId=500&channelId=601"), 400);
        json(request("POST", "engineers/watchlist?nickname=Extra&userId=500&channelId=600"), 200);
    }

    @Test
    void servesDurableNotificationJsonAndGameScopedAcknowledgementsForBothGames() throws Exception {
        for (String game : new String[]{"rust", "engineers"}) {
            var registration = json(request("POST", game + "/watchlist?nickname=Notify&userId=700&channelId=800"), 200);
            watchlistService.successfulPoll(game, Set.of("Notify"));
            var pending = json(request("GET", game + "/watchlist/pending"), 200);
            var event = pending.get(0);
            assertEquals(registration.get("subscription").get("id").asString(), event.get("subscriptionId").asString());
            assertEquals("700", event.get("userId").asString());
            assertEquals("800", event.get("channelId").asString());
            assertTrue(event.get("observedAt").asString().endsWith("Z"));
            assertEquals(pending, json(request("GET", game + "/watchlist/pending"), 200));
            assertEquals(0, json(request("GET", game + "/watchlist/pending?afterObservedAt=" + event.get("observedAt").asString()
                    + "&afterEventId=" + event.get("eventId").asString()), 200).size());
            String eventId = event.get("eventId").asString();
            String otherGame = game.equals("rust") ? "engineers" : "rust";
            assertEquals(204, request("POST", otherGame + "/watchlist/pending/" + eventId + "/ack").statusCode());
            assertEquals(pending, json(request("GET", game + "/watchlist/pending"), 200));
            assertEquals(204, request("POST", game + "/watchlist/pending/" + eventId + "/ack").statusCode());
            assertEquals(204, request("POST", game + "/watchlist/pending/" + eventId + "/ack").statusCode());
            assertEquals(0, json(request("GET", game + "/watchlist?userId=700&channelId=800"), 200).size());
        }
    }

    @Test
    void watchlistEndpointsRequireApiKey() throws Exception {
        for (String method : new String[]{"GET", "POST", "DELETE"}) {
            String path = method.equals("DELETE") ? "rust/watchlist/subscription?userId=900&channelId=901"
                    : "rust/watchlist?nickname=Private&userId=900&channelId=901";
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/analytics/" + path))
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("UNAUTHORIZED", json(response, 401).get("code").asString());
        }
    }

    @Test
    void rejectsIncompleteOrMalformedPendingCursorsWithJsonErrors() throws Exception {
        for (String query : new String[]{"afterObservedAt=2026-10-12T12:00:00Z", "afterEventId=event",
                "afterObservedAt=invalid&afterEventId=1234", "afterObservedAt=2026-10-12T12:00:00Z&afterEventId=event"}) {
            assertEquals("INVALID_REQUEST", json(request("GET", "rust/watchlist/pending?" + query), 400).get("code").asString());
        }
    }

    private HttpResponse<String> request(String method, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/analytics/" + path))
                .header("X-API-KEY", "test-key").method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        return mapper.readTree(response.body());
    }
}
