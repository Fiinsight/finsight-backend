package com.finsight.market;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/market")
@Tag(name = "시장 현황", description = "홈 화면 상단 코스피/코스닥/기준금리/원달러 요약")
public class MarketController {

    private final MarketSummaryService marketSummaryService;

    public MarketController(MarketSummaryService marketSummaryService) {
        this.marketSummaryService = marketSummaryService;
    }

    @GetMapping("/summary")
    @Operation(summary = "시장 현황 요약",
            description = "코스피/코스닥 지수(KIS)와 기준금리/원달러 환율(ECOS)을 조합해 반환합니다. 키가 없으면 각 필드가 개별적으로 폴백됩니다.")
    public MarketSummaryResponse summary() {
        return marketSummaryService.getSummary();
    }
}
