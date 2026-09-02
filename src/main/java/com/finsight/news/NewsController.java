package com.finsight.news;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/news")
@Tag(name = "뉴스", description = "뉴스 상세(원문 + 초급/일반/전문가 재작성)")
public class NewsController {

    private final NewsRepository newsRepository;

    public NewsController(NewsRepository newsRepository) {
        this.newsRepository = newsRepository;
    }

    @GetMapping("/{id}")
    @Operation(summary = "뉴스 상세 조회", description = "원문 + 3단계 재작성 + 중요도/관련종목/감성을 반환합니다. 없는 id면 404.")
    public NewsDetailResponse detail(@Parameter(description = "News 엔티티 id") @PathVariable Long id) {
        News news = newsRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "존재하지 않는 뉴스 id 입니다: " + id));
        return NewsDetailResponse.from(news);
    }
}
