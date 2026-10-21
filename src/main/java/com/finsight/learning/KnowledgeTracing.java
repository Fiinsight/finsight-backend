package com.finsight.learning;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Deterministic BKT; parameters are assumptions, not fitted population estimates. */
public final class KnowledgeTracing {
    public static final double P_INIT = 0.3;
    public static final double P_LEARN = 0.15;
    private KnowledgeTracing() {}

    public static double update(double prior, boolean correct, String level) {
        if (!Double.isFinite(prior) || prior < 0 || prior > 1) throw new IllegalArgumentException("Invalid probability");
        double guess, slip;
        switch (level) {
            case "beginner" -> { guess = 0.33; slip = 0.1; }
            case "normal" -> { guess = 0.30; slip = 0.15; }
            case "analyst" -> { guess = 0.25; slip = 0.20; }
            default -> throw new IllegalArgumentException("Invalid level");
        }
        double known = prior * (correct ? 1 - slip : slip);
        double unknown = (1 - prior) * (correct ? guess : 1 - guess);
        double posterior = known / (known + unknown);
        return posterior + (1 - posterior) * P_LEARN;
    }

    public static double decay(double probability, long elapsedDays) {
        if (!Double.isFinite(probability) || probability < 0 || probability > 1) throw new IllegalArgumentException("Invalid probability");
        return P_INIT + (probability - P_INIT) * Math.pow(0.97, Math.max(0, elapsedDays));
    }

    public static String select(List<String> terms, Map<String, Double> probabilities) {
        return terms.stream().min(Comparator
                .comparingInt((String term) -> { double p = probabilities.getOrDefault(term, P_INIT); return p >= 0.4 && p <= 0.7 ? 0 : 1; })
                .thenComparingDouble(term -> probabilities.getOrDefault(term, P_INIT))
                // Original article relevance order is retained when probabilities tie.
                .thenComparingInt(terms::indexOf)).orElse(null);
    }
}
