package com.finsight.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class UserProfileDtos {
    private UserProfileDtos() {}

    public record OnboardingAnswer(@Size(max = 40) String questionId,
                                   @NotBlank @Size(max = 200) String question,
                                   @NotBlank @Size(max = 200) String answer) {}

    public record OnboardingRequest(@NotEmpty @Size(max = 10) List<@Valid OnboardingAnswer> answers) {}

    public record OnboardingResponse(List<OnboardingAnswer> answers, String learningLevel,
                                     String learningPace, String learningFocus, String dailyGoal,
                                     Instant completedAt) {}
}
