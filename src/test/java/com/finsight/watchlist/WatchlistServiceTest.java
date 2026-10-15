package com.finsight.watchlist;

import com.finsight.auth.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.web.server.ResponseStatusException;

class WatchlistServiceTest {
    @Test void mutationsAreIdempotentOwnerScopedAndBounded() {
        var repo=mock(WatchlistRepository.class);var users=mock(UserRepository.class);var service=new WatchlistService(repo,users);
        when(users.lockWatchlistOwner(31L)).thenReturn(Optional.of(mock(User.class)));
        var samsung=new WatchlistEntry(31L,"005930");
        when(repo.findByUserIdAndSymbol(31L,"005930")).thenReturn(Optional.of(samsung));
        when(repo.countByUserId(31L)).thenReturn(20L);
        assertEquals(samsung.getCreatedAt(),service.add(31L,"005930").addedAt());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.add(31L,"000660")).getStatusCode().value());
        assertEquals("관심종목은 20개까지 추가할 수 있어요",assertThrows(ResponseStatusException.class,()->service.add(31L,"000660")).getReason());
        verify(repo,never()).saveAndFlush(any());
        service.remove(31L,"000660");service.remove(31L,"000660");verify(repo,times(2)).deleteByUserIdAndSymbol(31L,"000660");
        when(repo.findByUserIdOrderByCreatedAtAscIdAsc(31L)).thenReturn(List.of(samsung,new WatchlistEntry(31L,"000660")));
        assertEquals(List.of("005930","000660"),service.list(31L).stream().map(WatchlistService.Item::symbol).toList());
        assertTrue(service.list(99L).isEmpty());verify(repo).findByUserIdOrderByCreatedAtAscIdAsc(99L);
    }
    @Test void addsUnderTheLimitAndRejectsUnknownCodesAndIndicesBeforeWriting() {
        var repo=mock(WatchlistRepository.class);var users=mock(UserRepository.class);var service=new WatchlistService(repo,users);
        when(users.lockWatchlistOwner(31L)).thenReturn(Optional.of(mock(User.class)));
        when(repo.saveAndFlush(any())).thenAnswer(call->call.getArgument(0));
        assertEquals("삼성전자",service.add(31L,"005930").name());
        for(String code:List.of("KOSPI","KOSDAQ","999999","12345","0059300","abc")) {
            assertEquals(400,assertThrows(ResponseStatusException.class,()->service.add(31L,code)).getStatusCode().value());
            assertEquals(400,assertThrows(ResponseStatusException.class,()->service.remove(31L,code)).getStatusCode().value());
        }
        assertEquals(401,assertThrows(ResponseStatusException.class,()->service.list(null)).getStatusCode().value());
        assertThrows(ResponseStatusException.class,()->service.add(null,"005930"));
        verify(users,times(1)).lockWatchlistOwner(31L);
    }
}
