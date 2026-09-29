package com.finsight.note;

import com.finsight.auth.User;
import com.finsight.auth.UserRepository;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/article-notes")
public class ArticleNoteController {
    private final ArticleNoteService service;
    private final UserRepository users;

    public ArticleNoteController(ArticleNoteService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public List<ArticleNoteDtos.Response> list(@AuthenticationPrincipal Long userId) {
        return service.list(user(userId));
    }

    @GetMapping("/news/{newsId}")
    public List<ArticleNoteDtos.Response> listForNews(@AuthenticationPrincipal Long userId,
                                                       @PathVariable Long newsId) {
        return service.listForNews(user(userId), newsId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ArticleNoteDtos.Response create(@AuthenticationPrincipal Long userId,
                                           @Valid @RequestBody ArticleNoteDtos.CreateRequest request) {
        return service.create(user(userId), request);
    }

    @PutMapping("/{noteId}")
    public ArticleNoteDtos.Response update(@AuthenticationPrincipal Long userId, @PathVariable Long noteId,
                                            @Valid @RequestBody ArticleNoteDtos.SaveRequest request) {
        return service.update(user(userId), noteId, request);
    }

    @DeleteMapping("/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Long userId, @PathVariable Long noteId) {
        service.delete(user(userId), noteId);
    }

    private User user(Long userId) { return users.findById(userId).orElseThrow(); }
}
