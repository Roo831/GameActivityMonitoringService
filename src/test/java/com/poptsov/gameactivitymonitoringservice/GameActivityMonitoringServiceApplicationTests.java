package com.poptsov.gameactivitymonitoringservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.Path;

@SpringBootTest(properties = {"api.security.key=test-key", "se.monitor.server-ip=", "rust.monitor.server-ip="})
class GameActivityMonitoringServiceApplicationTests {
    @TempDir static Path directory;

    @DynamicPropertySource
    static void stateFile(DynamicPropertyRegistry properties) {
        properties.add("watchlist.state-file", () -> directory.resolve("watchlist.json").toString());
    }

    @Test
    void contextLoads() {
    }

}
