package com.aquaassistant.support;

import com.aquaassistant.measurement.Measurement;
import com.aquaassistant.measurement.MeasurementRepository;
import com.aquaassistant.measurement.MeasurementSource;
import com.aquaassistant.measurement.validation.ValidationStatus;
import com.aquaassistant.place.PlaceAccess;
import com.aquaassistant.profile.Classification;
import com.aquaassistant.profile.PhClassifier;
import com.aquaassistant.profile.ProfileService;
import com.aquaassistant.profile.WaterProfile;
import com.aquaassistant.security.JwtService;
import com.aquaassistant.user.Role;
import com.aquaassistant.user.SessionHistoryService;
import com.aquaassistant.user.User;
import com.aquaassistant.user.UserRepository;
import com.aquaassistant.user.UserSession;
import com.aquaassistant.user.UserSettings;
import com.aquaassistant.user.UserSettingsRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accounts and measurements for the tests. In production both are created by the main backend in the shared database
 * (sign-up, sessions, measurements); here they are written to the test database the same way, and the token is
 * signed like the backend signs it, so the AI service accepts it.
 */
@Component
public class TestAccounts {

    private final UserRepository users;
    private final UserSettingsRepository settings;
    private final ProfileService profiles;
    private final SessionHistoryService sessions;
    private final JwtService jwt;
    private final MeasurementRepository measurements;
    private final PlaceAccess places;
    private final PhClassifier classifier;
    private final TransactionTemplate tx;
    private final Map<String, Long> ids = new ConcurrentHashMap<>();

    public TestAccounts(UserRepository users, UserSettingsRepository settings, ProfileService profiles,
                        SessionHistoryService sessions, JwtService jwt, MeasurementRepository measurements, PlaceAccess places,
                        PhClassifier classifier, TransactionTemplate tx) {
        this.users = users;
        this.settings = settings;
        this.profiles = profiles;
        this.sessions = sessions;
        this.jwt = jwt;
        this.measurements = measurements;
        this.places = places;
        this.classifier = classifier;
        this.tx = tx;
    }

    /** A new person with their first place, signed in: returns the bearer token. */
    public String register() {
        return register(Role.USER);
    }

    public String register(Role role) {
        return tx.execute(status -> {
            String email = "t" + UUID.randomUUID().toString().substring(0, 8) + "@aquasense.test";
            User user = users.save(new User(email, "Prueba", "-", role));
            settings.save(UserSettings.defaultsFor(user.getId(), profiles.defaultProfile().getId()));
            profiles.provisionFirstPlace(user.getId());
            Instant now = Instant.now();
            Instant expires = now.plus(Duration.ofHours(8));
            UserSession session = sessions.start(user.getId(), now, expires, false, "Pruebas");
            String token = jwt.issue(user.getId(), email, role.name(), user.getTokenVersion(), session.getId(), now, expires).token();
            ids.put(token, user.getId());
            return token;
        });
    }

    public Long userId(String token) {
        return ids.get(token);
    }

    /** Uses the system profile of that type in the person's active place (as "Activar" does in the app). */
    public void activate(String token, com.aquaassistant.profile.ProfileType type) {
        Long userId = ids.get(token);
        tx.executeWithoutResult(status -> profiles.activate(userId, profiles.listFor(userId).stream()
            .filter(p -> p.getType() == type && p.isSystemProfile()).findFirst().orElseThrow().getId()));
    }

    /** A manual measurement in the person's active place, a little after the previous one. */
    public void measure(String token, double ph) {
        Long userId = ids.get(token);
        tx.executeWithoutResult(status -> {
            WaterProfile profile = profiles.activeProfile(userId);
            Classification c = classifier.classify(ph, profile);
            Long placeId = places.activeId(userId);
            Instant at = Instant.now().minusSeconds(3600L - measurements.countByPlaceId(placeId) * 60);
            measurements.save(new Measurement(userId, null, profile.getId(), ph, null, at, MeasurementSource.MANUAL,
                c.nature(), c.status(), ValidationStatus.VALID, null, null).in(placeId, null));
        });
    }
}
