package com.finsight.note;

import com.finsight.auth.User;
import com.finsight.news.News;
import com.finsight.news.NewsRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ArticleNoteService {
    private final ArticleNoteRepository notes;
    private final NewsRepository news;

    public ArticleNoteService(ArticleNoteRepository notes, NewsRepository news) {
        this.notes = notes;
        this.news = news;
    }

    @Transactional(readOnly = true)
    public List<ArticleNoteDtos.Response> list(User user) {
        return notes.findTop50ByUser_IdOrderByUpdatedAtDesc(user.getId()).stream()
                .map(ArticleNoteDtos.Response::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ArticleNoteDtos.Response> listForNews(User user, Long newsId) {
        if (!news.existsById(newsId)) throw notFound("뉴스를 찾을 수 없습니다.");
        return notes.findAllByNews_IdAndUser_IdOrderByUpdatedAtDesc(newsId, user.getId()).stream()
                .map(ArticleNoteDtos.Response::from).toList();
    }

    @Transactional
    public ArticleNoteDtos.Response create(User user, ArticleNoteDtos.CreateRequest request) {
        News article = news.findById(request.newsId()).orElseThrow(() -> notFound("뉴스를 찾을 수 없습니다."));
        ArticleNote note = new ArticleNote(user, article, request.content().trim());
        return ArticleNoteDtos.Response.from(notes.save(note));
    }

    @Transactional
    public ArticleNoteDtos.Response update(User user, Long noteId, ArticleNoteDtos.SaveRequest request) {
        ArticleNote note = ownedNote(user, noteId);
        note.updateContent(request.content().trim());
        return ArticleNoteDtos.Response.from(notes.save(note));
    }

    @Transactional
    public void delete(User user, Long noteId) {
        notes.delete(ownedNote(user, noteId));
    }

    private ArticleNote ownedNote(User user, Long noteId) {
        return notes.findByIdAndUser_Id(noteId, user.getId())
                .orElseThrow(() -> notFound("메모를 찾을 수 없습니다."));
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }
}
