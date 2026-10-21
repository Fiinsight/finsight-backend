package com.finsight.news;

import com.finsight.briefing.BriefingService;
import com.finsight.briefing.NewsBriefResponse;
import java.time.*;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/news/watchlist")
public class WatchlistNewsController {
    private final NewsRepository news;
    private final BriefingService briefings;
    public WatchlistNewsController(NewsRepository news, BriefingService briefings) { this.news=news; this.briefings=briefings; }
    @GetMapping
    public List<NewsBriefResponse> list(@AuthenticationPrincipal Long userId,@RequestParam(defaultValue="5") int limit) {
        if(userId==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if(limit<1 || limit>5) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"기사 수는 1~5개로 요청해주세요");
        Instant now=Instant.now();
        return news.findWatchlistNews(userId,now.minus(Duration.ofHours(48)),now,PageRequest.of(0,limit)).stream().map(briefings::toBriefResponse).toList();
    }
}
