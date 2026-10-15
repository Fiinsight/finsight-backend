package com.finsight.watchlist;

import com.finsight.auth.UserRepository;
import com.finsight.stock.StockCatalog;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WatchlistService {
    public record Item(String symbol, String name, Instant addedAt) {}
    private final WatchlistRepository entries;
    private final UserRepository users;
    public WatchlistService(WatchlistRepository entries, UserRepository users) { this.entries=entries; this.users=users; }
    @Transactional(readOnly=true)
    public List<Item> list(Long userId) {
        requireOwner(userId);
        return entries.findByUserIdOrderByCreatedAtAscIdAsc(userId).stream().map(this::view).toList();
    }
    @Transactional
    public Item add(Long userId, String symbol) {
        requireOwner(userId); validate(symbol); lockOwner(userId);
        var existing=entries.findByUserIdAndSymbol(userId,symbol);
        if (existing.isPresent()) return view(existing.get());
        if (entries.countByUserId(userId)>=20) throw new ResponseStatusException(HttpStatus.CONFLICT,"관심종목은 20개까지 추가할 수 있어요");
        return view(entries.saveAndFlush(new WatchlistEntry(userId,symbol)));
    }
    @Transactional
    public void remove(Long userId, String symbol) {
        requireOwner(userId); validate(symbol); lockOwner(userId);
        entries.deleteByUserIdAndSymbol(userId,symbol);
    }
    private void lockOwner(Long userId) {
        // Serialize this owner's mutations, including two simultaneous additions at the 20-item boundary.
        users.lockWatchlistOwner(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }
    private void requireOwner(Long userId) { if(userId==null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
    private void validate(String symbol) {
        if(symbol==null || !symbol.matches("\\d{6}") || !StockCatalog.entries().containsKey(symbol))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"등록된 6자리 종목코드를 선택해주세요");
    }
    private Item view(WatchlistEntry entry) { return new Item(entry.getSymbol(),StockCatalog.name(entry.getSymbol()),entry.getCreatedAt()); }
}
