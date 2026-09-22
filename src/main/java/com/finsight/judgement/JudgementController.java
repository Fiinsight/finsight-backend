package com.finsight.judgement;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.finsight.auth.User;
import com.finsight.auth.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/judgements")
@Tag(name = "판단", description = "사용자의 상승/중립/하락 판단 기록 및 실제 결과 대비 피드백 이력")
public class JudgementController {

    private final JudgementService judgementService;
    private final FeedbackScheduler feedbackScheduler;
    private final UserRepository userRepository;

    public JudgementController(JudgementService judgementService, FeedbackScheduler feedbackScheduler, UserRepository userRepository) {
        this.judgementService = judgementService; this.feedbackScheduler = feedbackScheduler; this.userRepository = userRepository;
    }

    private User user(Long id) { return userRepository.findById(id).orElseThrow(); }

    @PostMapping
    @Operation(summary = "판단 제출",
            description = "뉴스에 대한 UP/NEUTRAL/DOWN 판단을 기록합니다. 실제 결과 대비 피드백은 다음 거래일 장 마감 후 스케줄러가 채워줍니다.")
    public JudgementFeedbackResponse create(@AuthenticationPrincipal Long userId, @Valid @RequestBody JudgementRequest request) {
        return judgementService.recordJudgement(user(userId), request);
    }

    @GetMapping("/history")
    @Operation(summary = "판단 이력 조회", description = "과거 판단과 (있다면) 실제 결과/피드백을 최신순으로 반환합니다.")
    public List<JudgementHistoryResponse> history(@AuthenticationPrincipal Long userId) {
        return judgementService.getHistory(user(userId));
    }

    @GetMapping("/history/page")
    @Operation(summary = "판단 이력 페이지 조회", description = "대량의 판단 이력을 페이지 단위로 최신순 조회합니다.")
    public Page<JudgementHistoryResponse> historyPage(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Long userId) {
        return judgementService.getHistoryPage(user(userId), page, size);
    }

    @PostMapping("/generate-feedback-now")
    @Operation(summary = "피드백 즉시 생성 (수동)",
            description = "원래는 평일 15:40에 자동 실행되지만(하루 지난 판단만 대상), 데모/테스트용으로 " +
                    "하루가 안 지났어도 피드백이 아직 없는 모든 판단을 즉시 처리합니다.")
    public String generateFeedbackNow() {
        int processed = feedbackScheduler.runNow();
        return "%d건 처리 완료".formatted(processed);
    }
}
