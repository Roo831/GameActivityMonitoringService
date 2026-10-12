package com.poptsov.gameactivitymonitoringservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "api.security.key=test-key", "se.monitor.server-ip=", "rust.monitor.server-ip="})
class AnalyticsApiTest {
    @TempDir static Path logs;
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper mapper = JsonMapper.builder().build();

    @DynamicPropertySource
    static void logPaths(DynamicPropertyRegistry properties) {
        properties.add("se.monitor.log-file-path", () -> logs.resolve("engineers.txt").toString());
        properties.add("rust.monitor.log-file-path", () -> logs.resolve("rust.txt").toString());
        properties.add("watchlist.state-file", () -> logs.resolve("watchlist.json").toString());
    }

    @BeforeEach
    void writeLogs() throws Exception {
        var start = LocalDateTime.now().withNano(0).minusHours(2);
        var format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String content = "[" + start.format(format) + "] [CONNECT] Шкет\n["
                + start.plusHours(1).format(format) + "] [DISCONNECT] Шкет\n";
        Files.writeString(logs.resolve("rust.txt"), content);
        Files.writeString(logs.resolve("engineers.txt"), content);
    }

    @Test
    void servesTypedJsonForBothGamesAndAllReportEndpoints() throws Exception {
        for (String game : new String[]{"rust", "engineers"}) {
            JsonNode stats = json(get(game + "/stats?nickname=%D0%A8%D0%BA%D0%B5%D1%82&days=7", "test-key"), 200);
            assertEquals("Шкет", stats.get("nickname").asString());
            assertEquals(3600, stats.get("totalSeconds").asLong());
            assertTrue(stats.get("dailyActivity").isArray());
            assertNotNull(stats.get("timeZone"));
            JsonNode raw = json(get(game + "/raw?nickname=%D0%A8%D0%BA%D0%B5%D1%82&days=7", "test-key"), 200);
            assertEquals(2, raw.get("events").size());
            assertEquals("CONNECT", raw.get("events").get(0).get("action").asString());
            assertEquals("Шкет", json(get(game + "/nicknames?days=7", "test-key"), 200).get("nicknames").get(0).asString());
            var similar = json(get(game + "/similar?nickname=%D0%A8%D0%BA%D0%B5%D1%82&days=7", "test-key"), 200);
            assertEquals("Шкет", similar.get("nickname").asString());
            assertEquals(3600, similar.get("totalSeconds").asLong());
            assertEquals(0, similar.get("players").size());
        }
    }

    @Test
    void rejectsInvalidPeriodsWithAJsonError() throws Exception {
        var error = json(get("rust/stats?nickname=Player&days=0", "test-key"), 400);
        assertEquals("INVALID_REQUEST", error.get("code").asString());
        assertNotNull(error.get("message"));
        assertEquals("INVALID_REQUEST", json(get("engineers/similar?nickname=Player&days=366", "test-key"), 400)
                .get("code").asString());
    }

    @Test
    void rejectsMissingAndIncorrectApiKeysWithAJsonError() throws Exception {
        for (String key : new String[]{"", "wrong"}) {
            var error = json(get("rust/nicknames?days=7", key), 401);
            assertEquals("UNAUTHORIZED", error.get("code").asString());
        }
    }

    private HttpResponse<String> get(String path, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/analytics/" + path));
        if (!key.isEmpty()) request.header("X-API-KEY", key);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        return mapper.readTree(response.body());
    }
}
