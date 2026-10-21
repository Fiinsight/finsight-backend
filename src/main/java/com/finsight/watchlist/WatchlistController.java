package com.finsight.watchlist;

import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {
    private final WatchlistService service;
    public WatchlistController(WatchlistService service) { this.service=service; }
    @GetMapping public List<WatchlistService.Item> list(@AuthenticationPrincipal Long userId) { return service.list(userId); }
    @PutMapping("/{symbol}") public WatchlistService.Item add(@AuthenticationPrincipal Long userId,@PathVariable String symbol) { return service.add(userId,symbol); }
    @DeleteMapping("/{symbol}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Long userId,@PathVariable String symbol) { service.remove(userId,symbol); }
}
