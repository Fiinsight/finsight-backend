package com.finsight.watchlist;

import com.finsight.auth.*;
import com.finsight.config.*;
import com.finsight.news.*;
import com.finsight.briefing.BriefingService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({WatchlistController.class,WatchlistNewsController.class})
@Import({SecurityConfig.class,WebConfig.class,JwtAuthenticationFilter.class})
class WatchlistSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean WatchlistService service;
    @MockitoBean NewsRepository news;
    @MockitoBean BriefingService briefings;
    @MockitoBean UserRepository users;
    @MockitoBean JwtService jwt;
    @Test void anonymousRequestsCannotReadOrMutate() throws Exception {
        mvc.perform(get("/api/watchlist")).andExpect(status().is4xxClientError());
        mvc.perform(put("/api/watchlist/005930")).andExpect(status().is4xxClientError());
        mvc.perform(delete("/api/watchlist/005930")).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/news/watchlist")).andExpect(status().is4xxClientError());
        verifyNoInteractions(service,news);
    }
    @Test void ownerComesOnlyFromTokenAndNewsIsLimitedTo48Hours() throws Exception {
        when(jwt.isValid("demo")).thenReturn(true);when(jwt.userId("demo")).thenReturn(31L);
        when(service.list(31L)).thenReturn(List.of());
        mvc.perform(get("/api/watchlist").param("userId","8").header("Authorization","Bearer demo")).andExpect(status().isOk());
        mvc.perform(put("/api/watchlist/005930").param("userId","8").header("Authorization","Bearer demo")).andExpect(status().isOk());
        mvc.perform(delete("/api/watchlist/005930").header("Authorization","Bearer demo")).andExpect(status().isNoContent());
        verify(service).list(31L);verify(service).add(31L,"005930");verify(service).remove(31L,"005930");verifyNoMoreInteractions(service);
        mvc.perform(get("/api/news/watchlist").header("Authorization","Bearer demo")).andExpect(status().isOk());
        var since=org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);var now=org.mockito.ArgumentCaptor.forClass(java.time.Instant.class);
        verify(news).findWatchlistNews(eq(31L),since.capture(),now.capture(),eq(org.springframework.data.domain.PageRequest.of(0,5)));
        org.junit.jupiter.api.Assertions.assertEquals(java.time.Duration.ofHours(48),java.time.Duration.between(since.getValue(),now.getValue()));
        mvc.perform(get("/api/news/watchlist").param("limit","6").header("Authorization","Bearer demo")).andExpect(status().isBadRequest());
    }
}
