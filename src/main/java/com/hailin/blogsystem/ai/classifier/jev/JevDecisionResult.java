package com.hailin.blogsystem.ai.classifier.jev;

import java.util.Map;
import java.util.Set;

public record JevDecisionResult(
        String model,
        String provider,
        String primaryIntent,
        double primaryConfidence,
        Map<String, Double> primaryProbabilities,
        Map<String, Double> goalProbabilities,
        Set<String> acceptedGoals,
        double needsThinkingProbability,
        boolean needsThinking,
        int inputTokens,
        int outputTokens,
        double costUsd,
        long elapsedMs
) {
    public JevDecisionResult {
        primaryProbabilities = Map.copyOf(primaryProbabilities);
        goalProbabilities = Map.copyOf(goalProbabilities);
        acceptedGoals = Set.copyOf(acceptedGoals);
    }
}
