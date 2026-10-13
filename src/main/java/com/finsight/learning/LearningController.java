package com.finsight.learning;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/learning")
public class LearningController {
    public record Answer(@NotBlank @Pattern(regexp = "beginner|normal|analyst") String level,
                         @NotBlank @Size(max = 100) String term, @NotNull @Min(0) @Max(2) Integer answerIndex) {}
    private final LearningService service;
    private final LearningProgressService progress;
    private final LearningRecommendationService recommendations;
    public LearningController(LearningService service, LearningProgressService progress, LearningRecommendationService recommendations) { this.service = service; this.progress = progress; this.recommendations = recommendations; }
    @GetMapping("/recommendations")
    public LearningRecommendationService.Response recommendations(@AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue="") String readNewsIds) {
        if (userId==null) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
        try {
            if(readNewsIds.length()>4000) throw new IllegalArgumentException();
            var ids=readNewsIds.isBlank()?List.<Long>of():java.util.Arrays.stream(readNewsIds.split(",",-1)).map(Long::parseLong).distinct().toList();
            if(ids.size()>200 || ids.stream().anyMatch(id->id<=0)) throw new IllegalArgumentException();
            return recommendations.get(userId,ids);
        } catch(IllegalArgumentException invalid) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"올바른 읽은 기사 목록을 보내주세요.");
        }
    }
    @GetMapping("/progress")
    public LearningProgressService.Progress progress(@AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "") String readDays,
            @RequestParam(defaultValue = "") String reviewDays) {
        try {
            if (readDays.length()>4025 || reviewDays.length()>4025) throw new IllegalArgumentException();
            List<java.time.LocalDate> dates = readDays.isBlank() ? List.of() : java.util.Arrays.stream(readDays.split(",", -1)).map(java.time.LocalDate::parse).toList();
            if (dates.size() > 366) throw new IllegalArgumentException();
            List<java.time.LocalDate> views = reviewDays.isBlank() ? List.of() : java.util.Arrays.stream(reviewDays.split(",", -1)).map(java.time.LocalDate::parse).toList();
            if (views.size() > 366) throw new IllegalArgumentException();
            return progress.get(userId, dates, views);
        } catch (java.time.format.DateTimeParseException | IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "올바른 읽기 날짜를 보내주세요.");
        }
    }
    @GetMapping("/news/{newsId}")
    public LearningService.Lesson lesson(@AuthenticationPrincipal Long userId, @PathVariable Long newsId,
                                         @RequestParam(required = false) String level) { return service.lesson(userId, newsId, level); }
    @PostMapping("/news/{newsId}/answers")
    public LearningService.AnswerResult answer(@AuthenticationPrincipal Long userId, @PathVariable Long newsId,
                                                @Valid @RequestBody Answer request) {
        return service.answer(userId, newsId, request.level(), request.term(), request.answerIndex());
    }
    @GetMapping("/terms/today")
    public List<LearningService.Glossary> dailyTerms() { return service.dailyTerms(); }
    @GetMapping("/reviews")
    public List<LearningService.Review> reviews(@AuthenticationPrincipal Long userId) { return service.reviews(userId); }
}
