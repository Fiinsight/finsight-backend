package com.finsight.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserProfileServiceTest {

    @Test
    void mapsEachPreferenceOnlyFromItsOwnOnboardingQuestion() {
        UserRepository users = mock(UserRepository.class);
        User user = new User("user@example.com", null, "User", AuthProvider.LOCAL);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        UserProfileService service = new UserProfileService(users, new ObjectMapper());

        UserProfileDtos.OnboardingResponse response = service.saveOnboarding(7L,
                new UserProfileDtos.OnboardingRequest(List.of(
                        answer("experience", "투자 여정", "직접 투자하고 있어요"),
                        answer("interest", "알고 싶은 것", "시장 흐름을 읽고 싶어요"),
                        answer("pace", "공부 방식", "짧게, 매일 조금씩"),
                        answer("difficulty", "어려운 점", "용어가 어려워요"),
                        answer("goal", "시작할 습관", "매일 5분 이어가기"))));

        assertEquals("analyst", response.learningLevel());
        assertEquals("short", response.learningPace());
        assertEquals("market", response.learningFocus());
        assertEquals("매일 5분 이어가기", response.dailyGoal());
        assertEquals("experience", response.answers().get(0).questionId());
    }

    @Test
    void mapsMemoGoalToReflectionWithoutChangingExperienceLevel() {
        UserRepository users = mock(UserRepository.class);
        User user = new User("user@example.com", null, "User", AuthProvider.LOCAL);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        UserProfileService service = new UserProfileService(users, new ObjectMapper());

        UserProfileDtos.OnboardingResponse response = service.saveOnboarding(7L,
                new UserProfileDtos.OnboardingRequest(List.of(
                        answer("experience", "투자 여정", "이제 막 시작했어요"),
                        answer("interest", "알고 싶은 것", "내 투자 기록을 돌아보고 싶어요"),
                        answer("pace", "공부 방식", "한 번에 깊이 있게"),
                        answer("difficulty", "어려운 점", "판단할 근거가 부족해요"),
                        answer("goal", "시작할 습관", "기사 메모 남기고 복습하기"))));

        assertEquals("beginner", response.learningLevel());
        assertEquals("deep", response.learningPace());
        assertEquals("routine", response.learningFocus());
        assertEquals("기사 메모 남기고 복습하기", response.dailyGoal());
    }

    @Test
    void supportsOlderAnswersWithoutQuestionIds() {
        UserRepository users = mock(UserRepository.class);
        User user = new User("user@example.com", null, "User", AuthProvider.LOCAL);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        UserProfileService service = new UserProfileService(users, new ObjectMapper());

        UserProfileDtos.OnboardingResponse response = service.saveOnboarding(7L,
                new UserProfileDtos.OnboardingRequest(List.of(
                        new UserProfileDtos.OnboardingAnswer(null, "지금 투자 여정을 어디쯤 걷고 있나요?", "조금씩 알아가고 있어요"),
                        new UserProfileDtos.OnboardingAnswer(null, "투자할 때 가장 알고 싶은 것은 무엇인가요?", "투자 판단 기준을 만들고 싶어요"),
                        new UserProfileDtos.OnboardingAnswer(null, "나에게 맞는 투자 공부 방식은 어떤 모습인가요?", "궁금한 것부터 자유롭게"),
                        new UserProfileDtos.OnboardingAnswer(null, "오늘 어떤 습관을 시작해볼까요?", "투자 판단 돌아보기"))));

        assertEquals("normal", response.learningLevel());
        assertEquals("flexible", response.learningPace());
        assertEquals("judgement", response.learningFocus());
    }

    private UserProfileDtos.OnboardingAnswer answer(String id, String question, String value) {
        return new UserProfileDtos.OnboardingAnswer(id, question, value);
    }
}
