package com.finsight.news;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final NewsRepository newsRepository;

    public NewsController(NewsRepository newsRepository) {
        this.newsRepository = newsRepository;
    }

    @GetMapping("/{id}")
    public NewsDetailResponse detail(@PathVariable Long id) {
        News news = newsRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "존재하지 않는 뉴스 id 입니다: " + id));
        return NewsDetailResponse.from(news);
    }
}
