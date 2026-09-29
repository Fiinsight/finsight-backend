package com.finsight.note;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsight.auth.User;
import com.finsight.news.News;
import com.finsight.news.NewsRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ArticleNoteServiceTest {
    @Mock ArticleNoteRepository notes;
    @Mock NewsRepository news;
    @Mock User user;
    @Mock News article;
    private ArticleNoteService service;

    @BeforeEach
    void setUp() {
        service = new ArticleNoteService(notes, news);
        lenient().when(user.getId()).thenReturn(7L);
        lenient().when(article.getId()).thenReturn(12L);
        lenient().when(article.getTitle()).thenReturn("반도체 수출 회복");
        lenient().when(article.getSource()).thenReturn("연합뉴스");
    }

    @Test
    void createTrimsContentAndAssociatesCurrentUserAndArticle() {
        when(news.findById(12L)).thenReturn(Optional.of(article));
        when(notes.save(any(ArticleNote.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ArticleNoteDtos.Response response = service.create(user,
                new ArticleNoteDtos.CreateRequest(12L, "  수출 지표와 기업 실적을 함께 확인  "));

        ArgumentCaptor<ArticleNote> captor = ArgumentCaptor.forClass(ArticleNote.class);
        verify(notes).save(captor.capture());
        assertEquals(user, captor.getValue().getUser());
        assertEquals(article, captor.getValue().getNews());
        assertEquals("수출 지표와 기업 실적을 함께 확인", response.content());
        assertEquals(12L, response.newsId());
        assertEquals("반도체 수출 회복", response.newsTitle());
    }

    @Test
    void createRejectsMissingArticle() {
        when(news.findById(999L)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class,
                () -> service.create(user, new ArticleNoteDtos.CreateRequest(999L, "메모")));
    }

    @Test
    void updateOnlyFindsMemoOwnedByCurrentUser() {
        when(notes.findByIdAndUser_Id(3L, 7L)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class,
                () -> service.update(user, 3L, new ArticleNoteDtos.SaveRequest("수정")));
    }

    @Test
    void updateTrimsAndPersistsOwnedMemo() {
        ArticleNote existing = new ArticleNote(user, article, "기존 메모");
        when(notes.findByIdAndUser_Id(3L, 7L)).thenReturn(Optional.of(existing));
        when(notes.save(existing)).thenReturn(existing);

        ArticleNoteDtos.Response response = service.update(user, 3L,
                new ArticleNoteDtos.SaveRequest("  다시 확인할 내용  "));

        assertEquals("다시 확인할 내용", response.content());
        verify(notes).save(existing);
    }

    @Test
    void deleteOnlyDeletesMemoOwnedByCurrentUser() {
        when(notes.findByIdAndUser_Id(3L, 7L)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.delete(user, 3L));
        verify(notes, never()).delete(any(ArticleNote.class));
    }

    @Test
    void articleMemoQueryIsScopedByBothArticleAndUser() {
        when(news.existsById(12L)).thenReturn(true);
        when(notes.findAllByNews_IdAndUser_IdOrderByUpdatedAtDesc(12L, 7L))
                .thenReturn(java.util.List.of());

        assertEquals(0, service.listForNews(user, 12L).size());
        verify(notes).findAllByNews_IdAndUser_IdOrderByUpdatedAtDesc(12L, 7L);
    }

    @Test
    void listIsScopedToCurrentUserAndNewestFirst() {
        when(notes.findTop50ByUser_IdOrderByUpdatedAtDesc(7L)).thenReturn(java.util.List.of());
        assertEquals(0, service.list(user).size());
        verify(notes).findTop50ByUser_IdOrderByUpdatedAtDesc(7L);
    }
}
