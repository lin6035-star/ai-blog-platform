package com.hailin.blogsystem.ai.classifier.jev;

import com.hailin.blogsystem.entity.dto.AiIntent;

public record JevFastPathDecision(AiIntent intent, String fallbackReason) {

    public static JevFastPathDecision accepted(AiIntent intent) {
        return new JevFastPathDecision(intent, null);
    }

    public static JevFastPathDecision fallback(String reason) {
        return new JevFastPathDecision(null, reason);
    }

    public boolean accepted() {
        return intent != null;
    }
}
