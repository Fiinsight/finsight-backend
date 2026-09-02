package com.finsight.judgement;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/judgements")
@Tag(name = "판단", description = "사용자의 상승/중립/하락 판단 기록 및 실제 결과 대비 피드백 이력")
public class JudgementController {

    private final JudgementService judgementService;

    public JudgementController(JudgementService judgementService) {
        this.judgementService = judgementService;
    }

    @PostMapping
    @Operation(summary = "판단 제출",
            description = "뉴스에 대한 UP/NEUTRAL/DOWN 판단을 기록합니다. 실제 결과 대비 피드백은 다음 거래일 장 마감 후 스케줄러가 채워줍니다.")
    public JudgementFeedbackResponse create(@Valid @RequestBody JudgementRequest request) {
        return judgementService.recordJudgement(request);
    }

    @GetMapping("/history")
    @Operation(summary = "판단 이력 조회", description = "과거 판단과 (있다면) 실제 결과/피드백을 최신순으로 반환합니다.")
    public List<JudgementHistoryResponse> history() {
        return judgementService.getHistory();
    }
}

