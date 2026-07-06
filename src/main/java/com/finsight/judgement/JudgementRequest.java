package com.finsight.judgement;

import jakarta.validation.constraints.NotNull;

public record JudgementRequest(
        @NotNull Long newsId,
        @NotNull JudgementChoice choice,
        String reason
) {
}

