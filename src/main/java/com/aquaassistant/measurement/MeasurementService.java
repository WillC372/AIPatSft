package com.aquaassistant.measurement;

import com.aquaassistant.common.BusinessException;
import com.aquaassistant.measurement.dto.MeasurementResponse;
import com.aquaassistant.measurement.dto.NewReading;
import com.aquaassistant.measurement.dto.PageResponse;
import com.aquaassistant.measurement.dto.SeriesPoint;
import com.aquaassistant.measurement.validation.MeasurementValidationChain;
import com.aquaassistant.measurement.validation.ReadingContext;
import com.aquaassistant.measurement.validation.ValidationReport;
import com.aquaassistant.profile.Classification;
import com.aquaassistant.profile.PhClassifier;
import com.aquaassistant.profile.PhStatus;
import com.aquaassistant.profile.ProfileService;
import com.aquaassistant.profile.WaterProfile;
import com.aquaassistant.profile.WaterProfileRepository;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.aquaassistant.measurement.MeasurementSpecifications.fromSource;
import static com.aquaassistant.measurement.MeasurementSpecifications.measuredFrom;
import static com.aquaassistant.measurement.MeasurementSpecifications.measuredUntil;
import static com.aquaassistant.measurement.MeasurementSpecifications.inPlace;
import static com.aquaassistant.measurement.MeasurementSpecifications.withStatus;

/**
 * The person's measurements, read from the shared database for the analyses. They are recorded by the main backend
 * (a photo reading is sent to it: see BackendClient), which also raises the alerts.
 */
@Service
public class MeasurementService {

    private static final int MAX_PAGE_SIZE = 100;

    private final MeasurementRepository repository;
    private final MeasurementValidationChain validationChain;
    private final PhClassifier classifier;
    private final ProfileService profileService;
    private final WaterProfileRepository profileRepository;
    private final com.aquaassistant.place.PlaceAccess places;

    public MeasurementService(MeasurementRepository repository, MeasurementValidationChain validationChain,
                              PhClassifier classifier, ProfileService profileService,
                              WaterProfileRepository profileRepository, com.aquaassistant.place.PlaceAccess places) {
        this.repository = repository;
        this.validationChain = validationChain;
        this.classifier = classifier;
        this.profileService = profileService;
        this.profileRepository = profileRepository;
        this.places = places;
    }

    @Transactional(readOnly = true)
    public Preview preview(Long userId, NewReading reading) {
        WaterProfile profile = profileService.activeProfile(userId);
        ValidationReport report = validationChain.validate(contextFor(userId, reading));
        return new Preview(classifier.classify(reading.ph(), profile), report, profile);
    }

    @Transactional(readOnly = true)
    public PageResponse<MeasurementResponse> page(Long userId, Instant from, Instant to, MeasurementSource source,
                                                  PhStatus status, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Specification<Measurement> spec = Specification.where(inPlace(places.activeId(userId)))
            .and(measuredFrom(from)).and(measuredUntil(to)).and(fromSource(source)).and(withStatus(status));
        Page<Measurement> result = repository.findAll(spec,
            PageRequest.of(Math.max(page, 0), safeSize, Sort.by("measuredAt").descending()));
        return new PageResponse<>(toResponses(userId, result.getContent()), result.getNumber(), result.getSize(),
            result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public Optional<MeasurementResponse> latest(Long userId) {
        return repository.findTopByPlaceIdOrderByMeasuredAtDesc(places.activeId(userId)).map(m -> toResponses(userId, List.of(m)).get(0));
    }

    @Transactional(readOnly = true)
    public List<Measurement> between(Long userId, Instant from, Instant to) {
        return repository.findTop2000ByPlaceIdAndMeasuredAtBetweenOrderByMeasuredAtAsc(places.activeId(userId), from, to);
    }

    @Transactional(readOnly = true)
    public List<SeriesPoint> series(Long userId, Instant from, Instant to) {
        return between(userId, from, to).stream()
            .map(m -> new SeriesPoint(m.getId(), m.getMeasuredAt(), m.getPh(), m.getStatus().name(), m.getSource().name()))
            .toList();
    }

    @Transactional(readOnly = true)
    public Iterator<Measurement> iterate(Long userId, Instant from, Instant to) {
        return new MeasurementIterator(repository, places.activeId(userId), from, to);
    }

    @Transactional(readOnly = true)
    public MeasurementResponse get(Long userId, Long id) {
        return toResponses(userId, List.of(owned(userId, id))).get(0);
    }

    @Transactional(readOnly = true)
    public MeasurementResponse toResponse(Long userId, Measurement measurement) {
        return toResponses(userId, List.of(measurement)).get(0);
    }

    @Transactional(readOnly = true)
    public List<MeasurementResponse> toResponses(Long userId, List<Measurement> measurements) {
        Set<Long> profileIds = measurements.stream().map(Measurement::getProfileId).collect(Collectors.toSet());
        Map<Long, WaterProfile> profiles = profileRepository.findAllById(profileIds).stream()
            .collect(Collectors.toMap(WaterProfile::getId, Function.identity()));
        return measurements.stream().map(m -> {
            WaterProfile profile = profiles.get(m.getProfileId());
            String explanation = classifier.explain(m.getPh(), m.getNature(), m.getStatus(), profile);
            return new MeasurementResponse(m.getId(), m.getPh(), m.getTemperatureC(), m.getMeasuredAt(),
                m.getSource().name(), m.getSource().label(), m.getNature().name(), m.getNature().label(),
                m.getStatus().name(), m.getStatus().label(), m.getValidationStatus().name(),
                m.getValidationStatus().label(), m.getValidationNotes(), explanation, profile.getId(),
                profile.getName(), m.getDeviceId(), null,
                m.getNote(), m.getPlaceId(), m.getParams());
        }).toList();
    }

    private Measurement owned(Long userId, Long id) {
        return repository.findById(id).filter(m -> m.getUserId().equals(userId)
                || places.roleIn(userId, places.visible(userId, m.getPlaceId())).isPresent())
            .orElseThrow(() -> BusinessException.notFound("La medición no existe."));
    }

    private ReadingContext contextFor(Long userId, NewReading reading) {
        return new ReadingContext(reading.ph(), reading.temperatureC(), reading.measuredAt(), reading.source(),
            repository.findTopByPlaceIdOrderByMeasuredAtDesc(places.activeId(userId)));
    }

    public record Preview(Classification classification, ValidationReport report, WaterProfile profile) {
    }
}
