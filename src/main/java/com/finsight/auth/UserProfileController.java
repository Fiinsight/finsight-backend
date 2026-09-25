package com.finsight.auth;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
public class UserProfileController {
    private final UserProfileService profileService;

    public UserProfileController(UserProfileService profileService) { this.profileService = profileService; }

    @PutMapping("/onboarding")
    @ResponseStatus(HttpStatus.OK)
    public UserProfileDtos.OnboardingResponse saveOnboarding(@AuthenticationPrincipal Long userId,
                                                               @Valid @RequestBody UserProfileDtos.OnboardingRequest request) {
        return profileService.saveOnboarding(userId, request);
    }

    @GetMapping("/onboarding")
    public UserProfileDtos.OnboardingResponse getOnboarding(@AuthenticationPrincipal Long userId) {
        return profileService.getOnboarding(userId);
    }
}
