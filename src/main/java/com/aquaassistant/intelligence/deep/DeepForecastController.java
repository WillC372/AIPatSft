package com.aquaassistant.intelligence.deep;

import com.aquaassistant.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeepForecastController {

    private final DeepForecastService service;

    public DeepForecastController(DeepForecastService service) {
        this.service = service;
    }

    /** Forecast with the LSTM network. {@code includeSimulated} is only for tests: the simulator is not real water. */
    @GetMapping("/api/v1/intelligence/deep/forecast")
    public DeepForecastResponse forecast(@RequestParam(defaultValue = "false") boolean includeSimulated,
                                         @RequestParam(required = false) String station) {
        if (station != null && !station.isBlank()) {
            CurrentUser.id();
            return service.forecastStation(station);
        }
        return service.forecast(CurrentUser.id(), includeSimulated);
    }

    /** Official USGS stations with enough real data to train the network (to try it without days of measurements). */
    @GetMapping("/api/v1/intelligence/deep/stations")
    public java.util.List<OfficialSeries.Station> stations() {
        CurrentUser.id();
        return service.officialStations();
    }
}
