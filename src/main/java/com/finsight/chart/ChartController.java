package com.finsight.chart;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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
            description = "해당 종목의 일봉 캔들(KIS)과, 같은 기간 relatedSymbol이 일치하는 뉴스 마커를 함께 반환합니다.")
    public ChartResponse chart(@Parameter(description = "종목코드, 예: 005930") @PathVariable String symbol) {
        return chartService.getChart(symbol);
    }
}
