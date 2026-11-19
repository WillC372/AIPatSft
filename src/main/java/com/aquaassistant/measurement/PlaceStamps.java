package com.aquaassistant.measurement;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tells a model cache that a place has new (or deleted) measurements since it last looked. Measurements are recorded
 * by the main backend, so this service is not told when one arrives: it compares the place's newest measurement and
 * count with what it saw last time. One instance per cache (each one consumes its own changes).
 */
public class PlaceStamps {

    private final MeasurementRepository repository;
    private final Map<Long, String> seen = new ConcurrentHashMap<>();

    public PlaceStamps(MeasurementRepository repository) {
        this.repository = repository;
    }

    /** True the first time the place's measurements are seen different from before. */
    public boolean changed(Long placeId) {
        String now = repository.findTopByPlaceIdOrderByMeasuredAtDesc(placeId).map(m -> String.valueOf(m.getId())).orElse("-")
            + ":" + repository.countByPlaceId(placeId);
        String before = seen.put(placeId, now);
        return before != null && !before.equals(now);
    }
}
