package com.finsight.stock;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stocks")
@Tag(name = "인기 종목", description = "AI 차트 화면 상단 인기 종목 칩")
public class StockController {

    private final PopularStockService popularStockService;

    public StockController(PopularStockService popularStockService) {
        this.popularStockService = popularStockService;
    }

    @GetMapping("/popular")
    @Operation(summary = "인기 종목 현재가 목록", description = "고정된 5개 종목의 실시간 현재가(KIS)를 반환합니다.")
    public List<PopularStockView> popular() {
        return popularStockService.getPopularStocks();
    }
}
