package com.poptsov.gameactivitymonitoringservice.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.UncheckedIOException;
import java.util.Map;

@RestControllerAdvice
public class AnalyticsExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", error.getMessage()));
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<Map<String, String>> unavailableLogs(UncheckedIOException error) {
        return ResponseEntity.internalServerError().body(Map.of("code", "LOGS_UNAVAILABLE",
                "message", "Activity logs are temporarily unavailable."));
    }
}
