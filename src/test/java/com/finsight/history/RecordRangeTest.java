package com.finsight.history;

import com.finsight.auth.User;
import com.finsight.auth.UserRepository;
import com.finsight.judgement.*;
import com.finsight.note.*;
import com.finsight.news.NewsRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class RecordRangeTest {
    private final User owner = mock(User.class);
    private final UserRepository users = mock(UserRepository.class);
    private final JudgementRepository judgements = mock(JudgementRepository.class);
    private final ArticleNoteRepository notes = mock(ArticleNoteRepository.class);
    private final JudgementService judgementService = new JudgementService(judgements, mock(NewsRepository.class));
    private final ArticleNoteService noteService = new ArticleNoteService(notes, mock(NewsRepository.class));

    private MockMvc mvc() {
        when(owner.getId()).thenReturn(7L);
        when(users.findById(7L)).thenReturn(Optional.of(owner));
        return standaloneSetup(new JudgementController(judgementService, mock(FeedbackScheduler.class), users),
                new ArticleNoteController(noteService, users)).setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
            public boolean supportsParameter(MethodParameter parameter) { return parameter.hasParameterAnnotation(AuthenticationPrincipal.class); }
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container, NativeWebRequest request,
                                          org.springframework.web.bind.support.WebDataBinderFactory factory) { return 7L; }
        }).build();
    }

    @Test void rangesUseKstAndPreviousHeaderForBothLists() throws Exception {
        var range = RecordDateRange.parse("2026-09-30", "2026-10-07");
        assertEquals(Instant.parse("2026-09-29T15:00:00Z"), range.from());
        assertEquals(Instant.parse("2026-10-06T15:00:00Z"), range.to());
        when(judgements.findInRange(owner, range.from(), range.to())).thenReturn(List.of());
        when(notes.findInRange(7L, range.from(), range.to())).thenReturn(List.of());
        // Empty intervening weeks: header jumps directly to the last record, in KST.
        when(judgements.previousRecord(owner, range.from())).thenReturn(Instant.parse("2026-09-02T23:00:00Z"));
        when(notes.previousRecord(7L, range.from())).thenReturn(Instant.parse("2026-09-02T23:00:00Z"));
        var mvc = mvc();
        for (String path : List.of("/api/judgements/history", "/api/article-notes")) {
            mvc.perform(get(path).param("from", "2026-09-30").param("to", "2026-10-07"))
                    .andExpect(status().isOk()).andExpect(content().json("[]"))
                    .andExpect(header().string(RecordDateRange.PREVIOUS_HEADER, "2026-09-03"));
        }
        verify(judgements).findInRange(owner, range.from(), range.to());
        verify(notes).findInRange(7L, range.from(), range.to());
    }

    @Test void noEarlierRecordsOmitsHeaderAndLegacyRequestsKeepTheirRepositories() throws Exception {
        var mvc = mvc();
        for (String path : List.of("/api/judgements/history", "/api/article-notes")) {
            mvc.perform(get(path).param("from", "2026-09-30").param("to", "2026-10-07"))
                    .andExpect(status().isOk()).andExpect(header().doesNotExist(RecordDateRange.PREVIOUS_HEADER));
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(header().doesNotExist(RecordDateRange.PREVIOUS_HEADER));
        }
        verify(judgements).findAllByUserOrderByCreatedAtDesc(owner);
        verify(notes).findTop50ByUser_IdOrderByUpdatedAtDesc(7L);
    }

    @Test void invalidRangesReturn400WithoutDatabaseReads() throws Exception {
        var mvc = mvc();
        for (String path : List.of("/api/judgements/history", "/api/article-notes")) {
            for (String[] dates : List.of(new String[]{"2026-10-07", "2026-10-07"}, new String[]{"2026-10-08", "2026-10-07"},
                    new String[]{"2026-09-29", "2026-10-07"}, new String[]{"2026-02-30", "2026-03-01"},
                    new String[]{"2026-9-30", "2026-10-07"}, new String[]{"invalid", "2026-10-07"})) {
                mvc.perform(get(path).param("from", dates[0]).param("to", dates[1])).andExpect(status().isBadRequest());
            }
            mvc.perform(get(path).param("from", "2026-09-30")).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(judgements, notes);
    }
}
