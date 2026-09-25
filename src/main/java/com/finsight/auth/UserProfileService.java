package com.finsight.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Arrays;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class UserProfileService {
    private final UserRepository users;
    private final ObjectMapper mapper;

    public UserProfileService(UserRepository users, ObjectMapper mapper) {
        this.users = users;
        this.mapper = mapper;
    }

    @Transactional
    public UserProfileDtos.OnboardingResponse saveOnboarding(Long userId, UserProfileDtos.OnboardingRequest request) {
        User user = users.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        List<UserProfileDtos.OnboardingAnswer> answers = request.answers();
        try {
            String answersJson = mapper.writeValueAsString(answers);
            String all = answers.stream().map(UserProfileDtos.OnboardingAnswer::answer).reduce("", (left, right) -> left + " " + right).toLowerCase(Locale.ROOT);
            String level = all.contains("직접") || all.contains("깊") || all.contains("분석") ? "analyst" : all.contains("기본") || all.contains("핵심") ? "normal" : "beginner";
            String pace = all.contains("짧") || all.contains("5분") ? "short" : all.contains("깊") ? "deep" : "flexible";
            String focus = all.contains("판단") ? "judgement" : all.contains("시장") ? "market" : all.contains("뉴스") ? "news" : "routine";
            String goal = answers.get(answers.size() - 1).answer();
            Instant completedAt = Instant.now();
            user.saveOnboardingProfile(answersJson, level, pace, focus, goal, completedAt);
            return new UserProfileDtos.OnboardingResponse(answers, level, pace, focus, goal, completedAt);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "온보딩 저장에 실패했습니다.", e);
        }
    }

    @Transactional(readOnly = true)
    public UserProfileDtos.OnboardingResponse getOnboarding(Long userId) {
        User user = users.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
        try {
            List<UserProfileDtos.OnboardingAnswer> answers = user.getOnboardingAnswersJson() == null
                    ? List.of()
                    : Arrays.asList(mapper.readValue(user.getOnboardingAnswersJson(), UserProfileDtos.OnboardingAnswer[].class));
            return new UserProfileDtos.OnboardingResponse(answers, user.getLearningLevel(), user.getLearningPace(),
                    user.getLearningFocus(), user.getDailyGoal(), user.getOnboardingCompletedAt());
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "온보딩을 읽지 못했습니다.", e);
        }
    }
}
