package com.poptsov.gameactivitymonitoringservice;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class LoggingBackendTest {
    @Test
    void applicationAndSlf4jLibrariesUseTheSameLog4j2Backend() {
        assertInstanceOf(LoggerContext.class, LogManager.getContext(false));
        assertEquals("org.apache.logging.slf4j.Log4jLoggerFactory", LoggerFactory.getILoggerFactory().getClass().getName());
    }
}
