package com.aquaassistant.intelligence.ai;

import com.aquaassistant.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI usage and costs: each user sees their own; administration sees the global view (route protected by the ADMIN role).
 */
@RestController
public class AiUsageController {

    private final AiUsageService service;

    private final com.aquaassistant.user.UserRepository users;

    public AiUsageController(AiUsageService service, com.aquaassistant.user.UserRepository users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping("/api/v1/intelligence/ai-usage")
    public AiUsageReport mine(@RequestParam(defaultValue = "30") int days) {
        return service.forUser(CurrentUser.id(), days);
    }

    @GetMapping("/api/v1/admin/ai-usage")
    public AiUsageReport global(@RequestParam(defaultValue = "30") int days) {
        boolean demo = users.findById(CurrentUser.id()).map(com.aquaassistant.user.User::isDemo).orElse(true);
        return service.global(days, demo);
    }
}
