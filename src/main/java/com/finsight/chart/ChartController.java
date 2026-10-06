package com.finsight.chart;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/charts")
@Tag(name = "AI 차트 도슨트", description = "종목 일봉 캔들 + 관련 뉴스 마커")
public class ChartController {

    private final ChartService chartService;

    public ChartController(ChartService chartService) {
        this.chartService = chartService;
    }

    @GetMapping("/{symbol}")
    @Operation(summary = "종목 차트 조회",
            description = "해당 종목의 캔들(KIS)과, 같은 기간 relatedSymbol이 일치하는 뉴스 마커/도슨트를 함께 반환합니다.")
    public ChartResponse chart(
            @Parameter(description = "종목코드, 예: 005930") @PathVariable String symbol,
            @Parameter(description = "D(일봉, 기본값), W(주봉), MINUTE(분봉)") @RequestParam(defaultValue = "D") String period,
            @Parameter(description = "분봉 간격: 1, 5, 15분") @RequestParam(defaultValue = "5") int interval) {
        if (!symbol.matches("\\d{6}") || !java.util.Set.of("D", "W", "MINUTE", "M").contains(period.toUpperCase(java.util.Locale.ROOT))) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "종목코드 또는 차트 기간이 올바르지 않습니다.");
        }
        return chartService.getChart(symbol, period.toUpperCase(java.util.Locale.ROOT), interval);
    }
}
