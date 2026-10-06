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
    public LearningController(LearningService service) { this.service = service; }
    @GetMapping("/news/{newsId}")
    public LearningService.Lesson lesson(@AuthenticationPrincipal Long userId, @PathVariable Long newsId,
                                         @RequestParam(required = false) String level) { return service.lesson(userId, newsId, level); }
    @PostMapping("/news/{newsId}/answers")
    public LearningService.AnswerResult answer(@AuthenticationPrincipal Long userId, @PathVariable Long newsId,
                                                @Valid @RequestBody Answer request) {
        return service.answer(userId, newsId, request.level(), request.term(), request.answerIndex());
    }
    @GetMapping("/reviews")
    public List<LearningService.Review> reviews(@AuthenticationPrincipal Long userId) { return service.reviews(userId); }
}
