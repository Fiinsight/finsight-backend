package com.finsight.briefing;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/briefings")
@Tag(name = "브리핑", description = "홈 화면 \"오늘의 핵심 뉴스\" 3건")
public class BriefingController {

    private final BriefingService briefingService;

    public BriefingController(BriefingService briefingService) {
        this.briefingService = briefingService;
    }

    @GetMapping("/today")
    @Operation(summary = "오늘의 브리핑 조회",
            description = "최신 뉴스 3건을 반환합니다. DB에 아직 데이터가 없으면(예: 스케줄러 미실행) 샘플 데이터로 대체합니다.")
    public List<NewsBriefResponse> today() {
        return briefingService.getTodayBriefing();
    }
}

