package com.finsight.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
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
            String experience = answer(answers, "experience", "투자 여정");
            String interest = answer(answers, "interest", "알고 싶은");
            String paceAnswer = answer(answers, "pace", "공부 방식");
            String goal = answer(answers, "goal", "습관");

            String level = experience.contains("직접 투자") ? "analyst"
                    : experience.contains("조금씩") ? "normal" : "beginner";
            String pace = paceAnswer.contains("깊이") ? "deep"
                    : paceAnswer.contains("짧게") || paceAnswer.contains("매일 조금씩") ? "short" : "flexible";
            String focus = interest.contains("판단") || goal.contains("판단") ? "judgement"
                    : interest.contains("시장") ? "market"
                    : interest.contains("기록") || goal.contains("메모") ? "routine" : "news";
            Instant completedAt = Instant.now();
            user.saveOnboardingProfile(answersJson, level, pace, focus, goal, completedAt);
            return new UserProfileDtos.OnboardingResponse(answers, level, pace, focus, goal, completedAt);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "온보딩 저장에 실패했습니다.", e);
        }
    }

    private String answer(List<UserProfileDtos.OnboardingAnswer> answers, String id, String questionFragment) {
        return answers.stream()
                .filter(item -> id.equals(item.questionId()) || item.question().contains(questionFragment))
                .map(UserProfileDtos.OnboardingAnswer::answer)
                .findFirst()
                .orElse("");
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
