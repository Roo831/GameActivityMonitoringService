package com.poptsov.gameactivitymonitoringservice.repository;

import com.poptsov.gameactivitymonitoringservice.model.RawDataReport.ActivityEvent;
import java.util.List;
import java.util.stream.Stream;

public interface ActivityRepository {
    Stream<ActivityEvent> findAll();
    void saveAll(List<ActivityEvent> events);
}
