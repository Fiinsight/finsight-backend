package com.finsight.judgement;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/judgements")
public class JudgementController {

    @PostMapping
    public JudgementFeedbackResponse create(@Valid @RequestBody JudgementRequest request) {
        return new JudgementFeedbackResponse(
                request.newsId(),
                request.choice(),
                "판단 근거가 뉴스의 실적 영향과 연결되어 있습니다. 다음 단계에서는 실제 주가 흐름과 거래량 변화도 함께 확인해보세요."
        );
    }
}

