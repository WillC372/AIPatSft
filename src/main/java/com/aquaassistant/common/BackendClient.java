package com.aquaassistant.common;

import com.aquaassistant.measurement.dto.MeasurementResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Calls the main backend for what belongs to it. A pH read from a photo is recorded there, so it is validated,
 * classified and raises the alerts and notices like any other measurement. The call carries the internal key shared
 * by both services (INTERNAL_API_KEY); the backend refuses any other caller.
 */
@Component
public class BackendClient {

    private final String baseUrl;
    private final String key;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public BackendClient(@Value("${aquasense.backend.url:}") String baseUrl, @Value("${aquasense.internal-key:}") String key,
                         ObjectMapper mapper) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.key = key;
        this.mapper = mapper;
    }

    /** Records a measurement read from a photo in the person's active place and returns it as the app shows it. */
    public MeasurementResponse recordPhotoReading(Long userId, double ph, Double temperatureC, String note) {
        if (baseUrl.isBlank() || key == null || key.isBlank()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "El servicio de IA no está conectado con el servidor principal.");
        }
        try {
            java.util.HashMap<String, Object> body = new java.util.HashMap<>(Map.of("userId", userId, "ph", ph, "source", "PHOTO"));
            if (temperatureC != null) {
                body.put("temperatureC", temperatureC);
            }
            if (note != null) {
                body.put("note", note);
            }
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/internal/measurements"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("X-Internal-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                String message = "No se pudo guardar la medición.";
                try {
                    message = mapper.readTree(response.body()).path("message").asText(message);
                } catch (Exception ignored) {
                    // not JSON
                }
                throw new BusinessException(HttpStatus.valueOf(response.statusCode() >= 500 ? 502 : response.statusCode()), message);
            }
            return mapper.readValue(response.body(), MeasurementResponse.class);
        } catch (BusinessException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "No se pudo guardar la medición.");
        } catch (Exception ex) {
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "El servidor principal no respondió; vuelve a intentarlo.");
        }
    }
}
