package com.aquaassistant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aquaassistant.alert.Alert;
import com.aquaassistant.alert.AlertRepository;
import com.aquaassistant.measurement.MeasurementRepository;
import com.aquaassistant.profile.ProfileType;
import com.aquaassistant.support.FakeAiServer;
import com.aquaassistant.support.TestAccounts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Extended explanation of an alarm: written by the AI with the system's facts, or by the rules when the AI fails. */
@SpringBootTest
@AutoConfigureMockMvc
class AlarmExplanationIntegrationTest {

    private static final FakeAiServer AI;

    static {
        try {
            AI = new FakeAiServer();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("aquasense.ai.providers[0].id", () -> "groq");
        registry.add("aquasense.ai.providers[0].label", () -> "Groq");
        registry.add("aquasense.ai.providers[0].base-url", AI::baseUrl);
        registry.add("aquasense.ai.providers[0].api-key", () -> "fake");
        registry.add("aquasense.ai.providers[0].model", () -> "openai/gpt-oss-120b");
        registry.add("aquasense.ai.providers[0].timeout-seconds", () -> 5);
        registry.add("aquasense.ai.daily-quota-free", () -> 10);
    }

    @AfterAll
    static void stop() {
        AI.close();
    }

    @Autowired private MockMvc mvc;
    @Autowired private TestAccounts accounts;
    @Autowired private AlertRepository alerts;
    @Autowired private MeasurementRepository measurements;
    @Autowired private ObjectMapper mapper;

    /** A pool that was fine and dropped to 5.0, with the alert the backend creates for it. */
    private long poolAlarm(String token) {
        accounts.activate(token, ProfileType.POOL);
        accounts.measure(token, 7.5);
        accounts.measure(token, 5.0);
        Long userId = accounts.userId(token);
        Long last = measurements.findTop50ByUserIdOrderByMeasuredAtDesc(userId).get(0).getId();
        return alerts.save(new Alert(userId, last, "CRITICAL", "Alteración del pH en el agua",
            "El pH bajó de 7.50 a 5.00 en 1 minuto. Es peligroso: está en nivel crítico para Piscina.")).getId();
    }

    private JsonNode explain(String token, long alertId) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1/intelligence/alarm/" + alertId + "/explain")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void theAiExplainsCausesStepsAndWhenItIsSafeWithTheSystemVerdict() throws Exception {
        String token = accounts.register();
        long alertId = poolAlarm(token);
        AI.replyContent("openai/gpt-oss-120b", """
            {"summary":"Es peligroso: salgan del agua. El pH bajó a 5.0, en nivel crítico para la piscina.",
             "likelyCauses":["Ácido vertido por error","Lluvia ácida intensa"],
             "steps":["Que nadie se bañe","Vuelve a medir","Corrige con la calculadora de dosis"],
             "safeAgain":"Cuando el pH vuelva al rango ideal en dos mediciones seguidas.",
             "citedSourceIds":[]}""");
        JsonNode r = explain(token, alertId);
        assertThat(r.path("source").asText()).isEqualTo("AI");
        assertThat(r.path("dangerous").asBoolean()).isTrue();
        assertThat(r.path("summary").asText()).contains("salgan del agua");
        assertThat(r.path("likelyCauses")).hasSize(2);
        assertThat(r.path("steps")).hasSize(3);
        assertThat(AI.requests().get(AI.requests().size() - 1)).contains("dangerous").contains("readingsLastHour");
    }

    @Test
    void withoutTheAiTheRulesOfTheProfileExplainIt() throws Exception {
        String token = accounts.register();
        long alertId = poolAlarm(token);
        AI.reply(503, "{\"error\":{\"message\":\"high demand\"}}");
        JsonNode r = explain(token, alertId);
        assertThat(r.path("source").asText()).isEqualTo("RULES");
        assertThat(r.path("dangerous").asBoolean()).isTrue();
        assertThat(r.path("steps").get(0).asText()).contains("No uses el agua");
        assertThat(r.path("safeAgain").asText()).contains("dos mediciones seguidas");
        assertThat(r.path("note").asText()).contains("reglas del perfil");
    }

    @Test
    void someoneElsesAlarmIsNotFound() throws Exception {
        String owner = accounts.register();
        long alertId = poolAlarm(owner);
        mvc.perform(post("/api/v1/intelligence/alarm/" + alertId + "/explain").header("Authorization", "Bearer " + accounts.register()))
            .andExpect(status().isNotFound());
    }
}
