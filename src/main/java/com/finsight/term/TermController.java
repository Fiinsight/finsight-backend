package com.finsight.term;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/terms")
@Tag(name = "용어", description = "투자 용어 기본 정의 + 뉴스 문맥 설명(용어 팝업)")
public class TermController {

    private final TermService termService;

    public TermController(TermService termService) {
        this.termService = termService;
    }

    @PostMapping("/explain")
    @Operation(summary = "용어 설명 조회",
            description = "기본 정의는 로컬 사전에서, newsId가 있으면 AI 서비스를 통해 해당 뉴스 문맥 설명/시장 영향까지 함께 반환합니다.")
    public TermExplainResponse explain(@Valid @RequestBody TermExplainRequest request) {
        return termService.explain(request);
    }
}
