package com.aquaassistant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aquaassistant.support.FakeAiServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Photo reading with two fake vision providers: the first one reads 6.0 and the second 7.0.
 * Checks the second opinion, the adjustment for disagreement and saving with the PHOTO source.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PhotoReadingIntegrationTest {

    private static final FakeAiServer FIRST;
    private static final FakeAiServer SECOND;
    /** The main backend: the reading is recorded there (alerts and notices included). */
    private static final com.aquaassistant.support.FakeBackend BACKEND;

    static {
        try {
            FIRST = new FakeAiServer();
            SECOND = new FakeAiServer();
            BACKEND = new com.aquaassistant.support.FakeBackend();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        register(registry, 0, "Google Gemini", FIRST, "gemini-3.5-flash");
        register(registry, 1, "Groq", SECOND, "qwen/qwen3.8-27b");
        registry.add("aquasense.ai.daily-quota-free", () -> 20);
        registry.add("aquasense.backend.url", BACKEND::baseUrl);
    }

    private static void register(DynamicPropertyRegistry registry, int i, String label, FakeAiServer server, String model) {
        String p = "aquasense.ai.providers[" + i + "].";
        registry.add(p + "id", () -> "p" + i);
        registry.add(p + "label", () -> label);
        registry.add(p + "base-url", server::baseUrl);
        registry.add(p + "api-key", () -> "fake");
        registry.add(p + "model", () -> model);
        registry.add(p + "timeout-seconds", () -> 5);
        registry.add(p + "vision", () -> true);
    }

    @AfterAll
    static void stop() {
        FIRST.close();
        SECOND.close();
        BACKEND.close();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private com.aquaassistant.support.TestAccounts accounts;

    @Autowired
    private ObjectMapper mapper;

    private static String reading(String kind, boolean readable, double ph) {
        return "{\"readable\":" + readable + ",\"kind\":\"" + kind + "\",\"ph\":" + ph + ",\"phLow\":" + ph + ",\"phHigh\":" + ph
            + ",\"nearestReferenceValues\":[" + ph + "],\"confidence\":0.95,\"referenceChartVisible\":true,"
            + "\"temperatureVisible\":false,\"temperatureC\":0,\"issues\":[],\"observations\":\"Comparación con la carta.\"}";
    }

    @Test
    void stripReadingUsesSecondOpinionAndSavesAsPhotoSource() throws Exception {
        String token = register();
        MockMultipartFile photo = new MockMultipartFile("file", "tira.png", "image/png",
            com.aquaassistant.support.TestImages.png(1600, 1200));

        FIRST.replyContent("gemini-3.5-flash", reading("TEST_STRIP", true, 6.0));
        SECOND.replyContent("qwen/qwen3.8-27b", reading("TEST_STRIP", true, 7.0));
        JsonNode result = json(mvc.perform(multipart("/api/v1/vision/ph-reading").file(photo).param("kind", "TEST_STRIP")
            .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.path("agreement").asText()).isEqualTo("DISAGREE");
        assertThat(result.path("ph").asDouble()).isEqualTo(6.5);
        assertThat(result.path("confidence").asDouble()).isLessThanOrEqualTo(0.35);
        assertThat(result.path("opinions").size()).isEqualTo(2);
        assertThat(result.path("phLow").asDouble()).isLessThanOrEqualTo(5.5);
        assertThat(FIRST.requests().get(0)).contains("image_url").contains("data:image/jpeg;base64,");

        // A real answer of the backend's internal endpoint.
        BACKEND.reply("{\"id\":41,\"ph\":6.0,\"temperatureC\":null,\"measuredAt\":\"2026-11-19T00:31:03.886551505Z\",\"source\":\"PHOTO\",\"sourceLabel\":\"Foto analizada con IA\",\"nature\":\"ACIDIC\",\"natureLabel\":\"Ácida\",\"status\":\"CAUTION\",\"statusLabel\":\"Precaución\",\"validationStatus\":\"WARNING\",\"validationLabel\":\"Validada con observaciones\",\"validationNotes\":\"Valor estimado por IA a partir de una foto; confírmalo con un medidor calibrado cuando sea posible.\",\"explanation\":\"pH 6.00: agua ácida. Ligeramente por debajo del rango recomendado. Conviene vigilar la tendencia. Rango recomendado para Uso general: 6.5 - 8.5.\",\"profileId\":1,\"profileName\":\"Uso general\",\"deviceId\":null,\"deviceName\":null,\"note\":\"Foto (tira) leída por IA\",\"placeId\":1,\"params\":{\"freeChlorine\":null,\"totalAlkalinity\":null,\"calciumHardness\":null,\"cyanuricAcid\":null,\"empty\":true}}");
        JsonNode saved = json(mvc.perform(post("/api/v1/vision/ph-reading/" + result.path("pendingId").asText() + "/save")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ph\":6.0,\"note\":\"piscina\"}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        // The value (corrected by the person) goes to the backend, which records it like any other measurement.
        JsonNode sent = mapper.readTree(BACKEND.bodies().get(BACKEND.bodies().size() - 1));
        assertThat(sent.path("userId").asLong()).isEqualTo(accounts.userId(token));
        assertThat(sent.path("ph").asDouble()).isEqualTo(6.0);
        assertThat(sent.path("source").asText()).isEqualTo("PHOTO");
        assertThat(sent.path("note").asText()).contains("corregido por el usuario").contains("piscina");
        assertThat(BACKEND.keys()).last().isEqualTo("test-internal-key-0123456789");
        assertThat(saved.path("id").asLong()).isEqualTo(41);
        assertThat(saved.path("validationStatus").asText()).isEqualTo("WARNING");
        assertThat(saved.path("source").asText()).isEqualTo("PHOTO");
        assertThat(saved.path("profileName").asText()).isEqualTo("Uso general");

        mvc.perform(post("/api/v1/vision/ph-reading/" + result.path("pendingId").asText() + "/save")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void unrelatedPhotoIsNotSavableAndMeterDisplaySkipsSecondOpinion() throws Exception {
        String token = register();
        MockMultipartFile photo = new MockMultipartFile("file", "x.png", "image/png", com.aquaassistant.support.TestImages.png(800, 600));
        FIRST.replyContent("gemini-3.5-flash", reading("OTHER", false, 0));
        JsonNode unreadable = json(mvc.perform(multipart("/api/v1/vision/ph-reading").file(photo)
            .header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString());
        assertThat(unreadable.path("readable").asBoolean()).isFalse();
        assertThat(unreadable.path("pendingId").isNull()).isTrue();

        photo = new MockMultipartFile("file", "medidor.png", "image/png", com.aquaassistant.support.TestImages.png(900, 700));
        int before = SECOND.requests().size();
        FIRST.replyContent("gemini-3.5-flash", reading("METER_DISPLAY", true, 7.42));
        JsonNode meter = json(mvc.perform(multipart("/api/v1/vision/ph-reading").file(photo)
            .header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString());
        assertThat(meter.path("ph").asDouble()).isEqualTo(7.42);
        assertThat(meter.path("agreement").asText()).isEqualTo("SINGLE");
        assertThat(SECOND.requests()).hasSize(before);
        // The reader is told to take only the number marked "pH", never the temperature or another value.
        assertThat(FIRST.requests().get(FIRST.requests().size() - 1))
            .contains("Read only the number that has the label or unit \\\"pH\\\" next to it")
            .contains("Never take the temperature, mV or any other number as the pH");
    }

    private String register() {
        return accounts.register();
    }

    private JsonNode json(String body) throws Exception {
        return mapper.readTree(body);
    }
}
