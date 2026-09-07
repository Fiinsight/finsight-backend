package com.finsight.briefing;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping("/more")
    @Operation(summary = "홈 화면 \"더보기\" 추가 뉴스 조회",
            description = "오늘의 핵심 3건 다음으로 이어지는 과거 뉴스를 페이지 단위로 반환합니다. page=0이 4번째 항목부터.")
    public List<NewsBriefResponse> more(
            @Parameter(description = "0부터 시작하는 페이지 번호") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "페이지당 개수") @RequestParam(defaultValue = "10") int size) {
        return briefingService.getMoreBriefing(page, size);
    }
}

