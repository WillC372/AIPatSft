package com.aquaassistant.intelligence.alarm;

import com.aquaassistant.knowledge.Citation;
import java.util.List;

/**
 * Extended explanation of an alarm for an alteration of the pH. {@code source} is AI (written by the AI with the facts
 * computed by the system) or RULES (written with the rules of the profile, when the AI is not available).
 */
public record AlarmExplanation(String source, String sourceLabel, String provider, String model, boolean dangerous,
                               String summary, List<String> likelyCauses, List<String> steps, String safeAgain,
                               List<Citation> citations, String note) {
}
