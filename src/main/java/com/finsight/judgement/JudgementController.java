package com.finsight.judgement;

import java.util.List;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/judgements")
public class JudgementController {

    private final JudgementService judgementService;

    public JudgementController(JudgementService judgementService) {
        this.judgementService = judgementService;
    }

    @PostMapping
    public JudgementFeedbackResponse create(@Valid @RequestBody JudgementRequest request) {
        return judgementService.recordJudgement(request);
    }

    @GetMapping("/history")
    public List<JudgementHistoryResponse> history() {
        return judgementService.getHistory();
    }
}

