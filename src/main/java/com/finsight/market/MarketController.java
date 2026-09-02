package com.finsight.market;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/market")
public class MarketController {

    private final MarketSummaryService marketSummaryService;

    public MarketController(MarketSummaryService marketSummaryService) {
        this.marketSummaryService = marketSummaryService;
    }

    @GetMapping("/summary")
    public MarketSummaryResponse summary() {
        return marketSummaryService.getSummary();
    }
}
