package com.poptsov.gameactivitymonitoringservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"api.security.key=test-key", "se.monitor.server-ip=", "rust.monitor.server-ip="})
class GameActivityMonitoringServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
