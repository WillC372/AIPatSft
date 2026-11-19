package com.aquaassistant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The analyses without a generative AI configured: rules, statistics and the official sources still answer. */
@SpringBootTest
@AutoConfigureMockMvc
class AnalysisWithoutGenerativeAiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private com.aquaassistant.support.TestAccounts accounts;

    @Test
    void intelligenceWorksWithoutGenerativeAiAndRefusesUnfoundedForecasts() throws Exception {
        String token = accounts.register();
        mvc.perform(get("/api/v1/intelligence/overview").header("Authorization", bearer(token)))
            .andExpect(jsonPath("$.hasData").value(false));
        for (double ph : new double[] {7.1, 7.2, 7.15, 7.3}) {
            accounts.measure(token, ph);
        }
        mvc.perform(get("/api/v1/intelligence/forecast").header("Authorization", bearer(token)))
            .andExpect(jsonPath("$.forecast.available").value(false));
        mvc.perform(post("/api/v1/intelligence/scenario").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"hypotheticalPh\":6.0}"))
            .andExpect(jsonPath("$.hypothetical.nature").value("ACIDIC"));
        mvc.perform(post("/api/v1/intelligence/scenario/explain").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"hypotheticalPh\":6.0}"))
            .andExpect(jsonPath("$.source").value("RULES"))
            .andExpect(jsonPath("$.hydrogenFactor").value(20.0))
            .andExpect(jsonPath("$.citations.length()").value(org.hamcrest.Matchers.greaterThan(0)));
        mvc.perform(get("/api/v1/intelligence/deep/forecast").header("Authorization", bearer(token)))
            .andExpect(jsonPath("$.trained").value(false)).andExpect(jsonPath("$.reason").exists())
            .andExpect(jsonPath("$.data.measurements").value(4));
        mvc.perform(get("/api/v1/intelligence/ai-usage").header("Authorization", bearer(token)))
            .andExpect(jsonPath("$.totals.calls").value(0)).andExpect(jsonPath("$.billing").value("FREE"));
        mvc.perform(post("/api/v1/intelligence/conclusion").header("Authorization", bearer(token)))
            .andExpect(jsonPath("$.source").value("RULES"));
        mvc.perform(post("/api/v1/intelligence/chat").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"Como está mi agua?\"}"))
            .andExpect(status().isServiceUnavailable());
        var streamed = mvc.perform(post("/api/v1/intelligence/chat/stream").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"Como está mi agua?\"}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
        for (int i = 0; i < 100 && !streamed.getResponse().getContentAsString().contains("\"message\""); i++) {
            Thread.sleep(100);
        }
        assertThat(streamed.getResponse().getContentAsString())
            .contains("event:error").contains("\"status\":503");
        mvc.perform(get("/api/v1/intelligence/ml/insights").header("Authorization", bearer(token)))
            .andExpect(jsonPath("$.trained").value(false)).andExpect(jsonPath("$.reason").exists());
        mvc.perform(get("/api/v1/intelligence/ai-status").header("Authorization", bearer(token)))
            .andExpect(jsonPath("$.configured").value(false)).andExpect(jsonPath("$.maxAnalysisDays").value(30));
        mvc.perform(get("/api/v1/reports").header("Authorization", bearer(token)))
            .andExpect(jsonPath("$.measurementCount").value(4))
            .andExpect(jsonPath("$.sections[0].key").value("summary"));
    }

    @Test
    void knowledgeLibraryBacksRecommendationsWithVerbatimSources() throws Exception {
        String token = accounts.register();
        mvc.perform(get("/api/v1/knowledge/sources")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/knowledge/sources").header("Authorization", bearer(token)))
            .andExpect(jsonPath("$.sources.length()").value(4))
            .andExpect(jsonPath("$.coverage.AQUARIUM").exists());
        mvc.perform(get("/api/v1/knowledge/search").param("q", "cierre de la piscina").param("profileType", "POOL")
                .header("Authorization", bearer(token)))
            .andExpect(jsonPath("$.results[0].id").value("mahc-imminent-hazard"))
            .andExpect(jsonPath("$.results[0].organization").value("CDC (Estados Unidos)"));
        mvc.perform(get("/api/v1/knowledge/search").param("q", " ").header("Authorization", bearer(token)))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/knowledge/chunks/fao-table1").header("Authorization", bearer(token)))
            .andExpect(jsonPath("$.quote").value("pH Normal Range 6.5 – 8.4"));
        mvc.perform(get("/api/v1/knowledge/chunks/no-existe").header("Authorization", bearer(token)))
            .andExpect(status().isNotFound());

        accounts.activate(token, com.aquaassistant.profile.ProfileType.POOL);
        accounts.measure(token, 6.3);
        JsonNode rec = json(mvc.perform(get("/api/v1/intelligence/recommendations").header("Authorization", bearer(token)))
            .andReturn());
        assertThat(rec.path("sources").size()).isBetween(1, 3);
        rec.path("sources").forEach(c -> assertThat(c.path("sourceId").asText()).isEqualTo("cdc-mahc-2024"));
        assertThat(rec.path("sources").toString()).contains("mahc-imminent-hazard");
    }

    @Test
    void photoReadingRequiresAVisionProvider() throws Exception {
        String token = accounts.register();
        var photo = new org.springframework.mock.web.MockMultipartFile("file", "t.png", "image/png",
            com.aquaassistant.support.TestImages.png(400, 300));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/vision/ph-reading")
                .file(photo).header("Authorization", bearer(token)))
            .andExpect(status().isServiceUnavailable());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
