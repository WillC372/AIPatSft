package com.aquaassistant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aquaassistant.support.TestAccounts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The LSTM trained with an official station series (here a test series in the test database: in production, the USGS
 * readings that the backend imports). The answer says where the data comes from and that it is not the person's water.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OfficialSeriesForecastIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private TestAccounts accounts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;

    @Test
    void theNetworkTrainsWithAStationAndSaysItIsNotYourWater() throws Exception {
        String id = "USGS-TEST" + (System.nanoTime() % 100000);
        jdbc.update("insert into reference_stations (id, agency, name, state, updated_at) values (?, 'USGS', 'RIO DE PRUEBA', 'TX', ?)", id, Timestamp.from(Instant.now()));
        Instant start = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofDays(16));
        for (int h = 0; h < 16 * 24; h++) {
            double ph = 7.6 + 0.25 * Math.sin(2 * Math.PI * h / 24.0) + 0.0005 * h;
            jdbc.update("insert into reference_readings (station_id, measured_at, ph) values (?, ?, ?)", id,
                Timestamp.from(start.plus(Duration.ofHours(h))), Math.round(ph * 100) / 100.0);
        }
        String token = accounts.register();
        JsonNode stations = mapper.readTree(mvc.perform(get("/api/v1/intelligence/deep/stations").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(stations.toString()).contains(id).contains("RIO DE PRUEBA");

        JsonNode r = mapper.readTree(mvc.perform(get("/api/v1/intelligence/deep/forecast").param("station", id)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(r.path("trained").asBoolean()).isTrue();
        assertThat(r.path("dataSource").asText()).contains("Datos oficiales del USGS").contains(id).contains("No son tus mediciones");
        assertThat(r.path("disclaimer").asText()).contains("No describe tu agua");
        assertThat(r.path("forecast").path("points")).hasSize(12);
        assertThat(r.path("includesSimulated").asBoolean()).isFalse();

        mvc.perform(get("/api/v1/intelligence/deep/forecast").param("station", "nada; drop table")
            .header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/intelligence/deep/forecast").param("station", "USGS-NOEXISTE")
            .header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    }
}
