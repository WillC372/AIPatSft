package com.aquaassistant.alert;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The person's alerts, read for the reports and the agent. They are created by the main backend when a measurement
 * is recorded (it also sends the notices); this service only reads them from the shared database.
 */
@Service
public class AlertService {

    private final AlertRepository repository;

    public AlertService(AlertRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<AlertResponse> list(Long userId, boolean unreadOnly, int limit) {
        PageRequest page = PageRequest.of(0, Math.min(Math.max(limit, 1), 200));
        List<Alert> alerts = unreadOnly ? repository.findByUserIdAndReadFalseOrderByCreatedAtDesc(userId, page)
            : repository.findByUserIdOrderByCreatedAtDesc(userId, page);
        return alerts.stream().map(AlertResponse::from).toList();
    }
}
