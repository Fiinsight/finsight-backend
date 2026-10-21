package com.finsight.learning;

public enum QuestionType {
    DEFINITION, DIRECTION, FACT_CHECK, LEGACY;

    public static QuestionType historical(String level) {
        return switch (level) {
            case "beginner" -> DEFINITION;
            case "normal" -> DIRECTION;
            case "analyst" -> FACT_CHECK;
            default -> LEGACY;
        };
    }
}
