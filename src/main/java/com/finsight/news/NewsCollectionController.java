package com.finsight.news;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual "collect now" trigger for the news pipeline, which otherwise only
 * runs on its 3-hour cron ({@link NewsCollectionScheduler}). Useful for
 * demos/local testing so you don't have to wait for the schedule.
 */
@RestController
@RequestMapping("/api/news")
@Tag(name = "뉴스", description = "뉴스 상세(원문 + 초급/일반/전문가 재작성)")
public class NewsCollectionController {

    private final NewsCollectionScheduler newsCollectionScheduler;

    public NewsCollectionController(NewsCollectionScheduler newsCollectionScheduler) {
        this.newsCollectionScheduler = newsCollectionScheduler;
    }

    @PostMapping("/collect")
    @Operation(summary = "뉴스 수집 즉시 실행",
            description = "3시간 주기 스케줄러를 기다리지 않고 RSS 수집→중복제거→스코어링→본문추출→AI 재작성 파이프라인을 즉시 1회 실행합니다. "
                    + "데모/로컬 테스트용입니다.")
    public NewsCollectionResult collectNow() {
        return newsCollectionScheduler.runOnce();
    }
}
