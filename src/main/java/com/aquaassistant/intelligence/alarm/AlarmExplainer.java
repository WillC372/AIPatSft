package com.aquaassistant.intelligence.alarm;

import com.aquaassistant.alert.Alert;
import com.aquaassistant.alert.AlertRepository;
import com.aquaassistant.common.BusinessException;
import com.aquaassistant.intelligence.advice.Advice;
import com.aquaassistant.intelligence.advice.AdviceStrategyResolver;
import com.aquaassistant.intelligence.ai.AiAnswer;
import com.aquaassistant.intelligence.ai.AiPrompt;
import com.aquaassistant.intelligence.ai.AiProviderProxy;
import com.aquaassistant.intelligence.ai.AiUnavailableException;
import com.aquaassistant.knowledge.Citation;
import com.aquaassistant.knowledge.GuidelineRetriever;
import com.aquaassistant.measurement.Measurement;
import com.aquaassistant.measurement.MeasurementRepository;
import com.aquaassistant.place.Place;
import com.aquaassistant.place.PlaceRepository;
import com.aquaassistant.profile.Classification;
import com.aquaassistant.profile.PhClassifier;
import com.aquaassistant.profile.PhStatus;
import com.aquaassistant.profile.WaterProfile;
import com.aquaassistant.profile.WaterProfileRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Explains with AI an alarm for an alteration of the pH (alert PH_ALTERATION created by the backend). The system
 * gives the facts: the readings of the last hour in that place, the profile and its ranges, whether it is dangerous
 * (decided by the system: critical level = dangerous) and the guideline fragments. The AI writes, in strict JSON,
 * a short summary, likely causes for that kind of water, ordered steps and when the water is safe again; it must not
 * contradict the danger verdict or invent figures. Without AI (not configured, quota used, invalid answer) the same
 * structure comes from the rules of the profile. The person's language is added by {@link AiProviderProxy}.
 */
@Service
public class AlarmExplainer {

    static final String PURPOSE = "ALARM";
    static final String SCHEMA_NAME = "alarm_explanation";
    static final String SYSTEM_PROMPT = """
        You are Aqua, the assistant of Aqua Assistant AI, a system that monitors the pH of water. The pH of the user's water
        has just changed suddenly or reached a critical level and an alarm went off. You receive facts computed by the system.
        Write every text in clear Spanish, speaking to the user with "tú". Answer in the JSON given:
        - summary: 2 or 3 short sentences: what happened and whether it is dangerous. Use exactly the system's verdict
          (dangerous true or false); never say the opposite. If dangerous and the water is a pool or spa, say first that
          people must get out of the water.
        - likelyCauses: 2 to 4 plausible causes for this kind of water and this direction of change (for example a product
          poured by mistake, rain, a dosing pump, cleaning products, a meter that needs calibration).
        - steps: 3 to 5 short, ordered, safe steps for right now. No exact chemical doses; for corrections, refer to the
          "calculadora de dosis" of the app.
        - safeAgain: one sentence on when the water can be used again (back in the ideal range given, confirmed by
          measuring again).
        - citedSourceIds: ids from guidelineFragments you rely on; mention only the organization in the text, never the id.
        Rules: only use pH figures that appear in the facts. Be calm and direct, without alarmism and without medical
        diagnoses. It is about this place's water only.""";

    private static final Duration WINDOW = Duration.ofHours(1);

    private final AlertRepository alerts;
    private final MeasurementRepository measurements;
    private final WaterProfileRepository profiles;
    private final PlaceRepository places;
    private final PhClassifier classifier;
    private final AdviceStrategyResolver adviceResolver;
    private final GuidelineRetriever guidelines;
    private final AiProviderProxy ai;
    private final ObjectMapper mapper;

    public AlarmExplainer(AlertRepository alerts, MeasurementRepository measurements, WaterProfileRepository profiles,
                          PlaceRepository places, PhClassifier classifier, AdviceStrategyResolver adviceResolver,
                          GuidelineRetriever guidelines, AiProviderProxy ai, ObjectMapper mapper) {
        this.alerts = alerts;
        this.measurements = measurements;
        this.profiles = profiles;
        this.places = places;
        this.classifier = classifier;
        this.adviceResolver = adviceResolver;
        this.guidelines = guidelines;
        this.ai = ai;
        this.mapper = mapper;
    }

    record Facts(Alert alert, Measurement measurement, List<Measurement> lastHour, WaterProfile profile, Place place,
                 Classification reading, Advice advice, List<Citation> citations) {
        boolean dangerous() {
            return reading.status() == PhStatus.CRITICAL;
        }
    }

    public AlarmExplanation explain(Long userId, Long alertId) {
        Facts facts = facts(userId, alertId);
        if (!ai.isAvailable()) {
            return ruleBased(facts, "La IA generativa no está configurada; la explicación sale de las reglas del perfil.");
        }
        try {
            AiAnswer answer = ai.complete(new AiPrompt(userId, PURPOSE, SYSTEM_PROMPT, factsJson(facts), SCHEMA_NAME,
                schema(facts.citations().stream().map(Citation::id).toList()), null, Set.of()));
            return fromAi(facts, answer);
        } catch (AiUnavailableException | InvalidAnswer ex) {
            return ruleBased(facts, "La IA no pudo explicarla ahora; la explicación sale de las reglas del perfil.");
        } catch (BusinessException ex) {
            if (ex.getStatus().value() != 429) {
                throw ex;
            }
            return ruleBased(facts, ex.getMessage() + " La explicación sale de las reglas del perfil.");
        }
    }

    Facts facts(Long userId, Long alertId) {
        Alert alert = alerts.findById(alertId).filter(a -> a.getUserId().equals(userId))
            .orElseThrow(() -> BusinessException.notFound("La alerta no existe."));
        Measurement m = alert.getMeasurementId() == null ? null : measurements.findById(alert.getMeasurementId()).orElse(null);
        if (m == null) {
            throw BusinessException.notFound("La medición de esta alerta ya no existe.");
        }
        WaterProfile profile = profiles.findById(m.getProfileId())
            .orElseThrow(() -> BusinessException.notFound("El perfil de esta medición ya no existe."));
        Place place = m.getPlaceId() == null ? null : places.findById(m.getPlaceId()).orElse(null);
        List<Measurement> lastHour = m.getPlaceId() == null ? List.of(m)
            : measurements.findTop2000ByPlaceIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(m.getPlaceId(),
                m.getMeasuredAt().minus(WINDOW), m.getMeasuredAt());
        Classification reading = classifier.classify(m.getPh(), profile);
        Advice advice = adviceResolver.forType(profile.getType()).advise(reading, profile);
        return new Facts(alert, m, lastHour, profile, place, reading, advice, guidelines.forReading(profile, reading, 3));
    }

    String factsJson(Facts f) {
        Map<String, Object> root = new LinkedHashMap<>();
        WaterProfile p = f.profile();
        root.put("alarm", Map.of("title", f.alert().getTitle(), "message", f.alert().getMessage()));
        root.put("dangerous", f.dangerous());
        root.put("place", f.place() == null ? "Sin lugar" : Map.of("kind", f.place().getKind().label()));
        root.put("profile", Map.of("name", p.getName(), "type", p.getType().label(),
            "idealRange", List.of(p.getIdealMin(), p.getIdealMax()), "criticalOutside", List.of(p.getCautionMin(), p.getCautionMax())));
        root.put("currentReading", Map.of("ph", f.measurement().getPh(), "status", f.reading().status().label(),
            "explanation", f.reading().explanation()));
        List<Measurement> series = f.lastHour();
        int step = Math.max(1, series.size() / 12);
        List<Map<String, Object>> sampled = new ArrayList<>();
        for (int i = 0; i < series.size(); i += step) {
            Measurement m = series.get(i);
            sampled.add(Map.of("minutesBefore", Duration.between(m.getMeasuredAt(), f.measurement().getMeasuredAt()).toMinutes(),
                "ph", m.getPh()));
        }
        root.put("readingsLastHour", sampled);
        root.put("profileRules", Map.of("summary", f.advice().summary(), "knownCauses", f.advice().possibleCauses(),
            "actions", f.advice().actions()));
        root.put("guidelineFragments", f.citations().stream().map(c -> Map.of("id", c.id(), "organization", c.organization(),
            "literalQuote", c.quote(), "summary", c.summaryEs())).toList());
        try {
            return "Alarm facts (JSON):\n" + mapper.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    AlarmExplanation fromAi(Facts f, AiAnswer answer) {
        JsonNode root;
        try {
            root = mapper.readTree(answer.text());
        } catch (Exception ex) {
            throw new InvalidAnswer();
        }
        String summary = text(root.path("summary"));
        List<String> causes = texts(root.path("likelyCauses"), 1, 4);
        List<String> steps = texts(root.path("steps"), 2, 5);
        String safeAgain = text(root.path("safeAgain"));
        Set<String> offered = new LinkedHashSet<>(f.citations().stream().map(Citation::id).toList());
        List<String> cited = new ArrayList<>();
        root.path("citedSourceIds").forEach(id -> { if (offered.contains(id.asText()) && !cited.contains(id.asText())) cited.add(id.asText()); });
        return new AlarmExplanation("AI", "Explicado por IA con los datos del sistema", answer.provider(), answer.model(),
            f.dangerous(), summary, causes, steps, safeAgain, guidelines.resolve(cited),
            answer.cached() ? "Respuesta reutilizada de la caché (no consumió cuota)." : null);
    }

    AlarmExplanation ruleBased(Facts f, String note) {
        WaterProfile p = f.profile();
        String summary = f.alert().getMessage();
        List<String> causes = f.advice().possibleCauses().isEmpty()
            ? List.of("Un producto o una sustancia que cayó al agua.", "Un medidor que necesita calibrarse.")
            : f.advice().possibleCauses().stream().limit(4).toList();
        List<String> steps = new ArrayList<>();
        if (f.dangerous()) {
            steps.add("No uses el agua hasta que el pH vuelva al rango ideal.");
        }
        steps.add("Vuelve a medir para confirmar el valor (si es posible, con el medidor recién calibrado).");
        f.advice().actions().stream().limit(3).forEach(steps::add);
        String safeAgain = String.format(java.util.Locale.US,
            "El agua vuelve a ser segura cuando el pH esté entre %.1f y %.1f en dos mediciones seguidas.", p.getIdealMin(), p.getIdealMax());
        return new AlarmExplanation("RULES", "Generado con las reglas del perfil", null, null, f.dangerous(), summary, causes,
            steps.stream().limit(5).toList(), safeAgain, f.citations().stream().limit(2).toList(), note);
    }

    static Map<String, Object> schema(List<String> ids) {
        Map<String, Object> text = Map.of("type", "string");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("summary", text);
        properties.put("likelyCauses", Map.of("type", "array", "minItems", 1, "maxItems", 4, "items", text));
        properties.put("steps", Map.of("type", "array", "minItems", 2, "maxItems", 5, "items", text));
        properties.put("safeAgain", text);
        properties.put("citedSourceIds", Map.of("type", "array", "maxItems", Math.max(1, Math.min(3, ids.size())),
            "items", ids.isEmpty() ? text : Map.of("type", "string", "enum", List.copyOf(ids))));
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("type", "object");
        object.put("additionalProperties", false);
        object.put("properties", properties);
        object.put("required", List.copyOf(properties.keySet()));
        return object;
    }

    private static String text(JsonNode node) {
        String value = node.asText("").trim();
        if (value.isEmpty() || value.length() > 600) {
            throw new InvalidAnswer();
        }
        return value;
    }

    private static List<String> texts(JsonNode node, int min, int max) {
        if (!node.isArray() || node.size() < min) {
            throw new InvalidAnswer();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : node) {
            if (out.size() == max) {
                break;
            }
            out.add(text(n));
        }
        return out;
    }

    static final class InvalidAnswer extends RuntimeException {
        InvalidAnswer() {
            super("respuesta de la IA no válida");
        }
    }
}
