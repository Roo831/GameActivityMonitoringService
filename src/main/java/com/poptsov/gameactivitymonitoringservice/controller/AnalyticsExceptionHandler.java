package com.poptsov.gameactivitymonitoringservice.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.poptsov.gameactivitymonitoringservice.service.WatchlistService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.UncheckedIOException;
import java.util.Map;

@RestControllerAdvice
public class AnalyticsExceptionHandler {
    private static final Logger log = LogManager.getLogger(AnalyticsExceptionHandler.class);
    @ExceptionHandler(WatchlistService.StorageException.class)
    public ResponseEntity<Map<String, String>> unavailableWatchlist(WatchlistService.StorageException error) {
        log.error("Watchlist storage request failed", error);
        return ResponseEntity.internalServerError().body(Map.of("code", "WATCHLIST_UNAVAILABLE",
                "message", "Watchlist storage is temporarily unavailable."));
    }

    @ExceptionHandler({IllegalArgumentException.class, MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, String>> invalidRequest(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", error.getMessage()));
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<Map<String, String>> unavailableLogs(UncheckedIOException error) {
        log.error("Activity repository request failed", error);
        return ResponseEntity.internalServerError().body(Map.of("code", "LOGS_UNAVAILABLE",
                "message", "Activity logs are temporarily unavailable."));
    }
}
