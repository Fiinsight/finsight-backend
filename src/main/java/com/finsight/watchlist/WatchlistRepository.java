package com.finsight.watchlist;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchlistRepository extends JpaRepository<WatchlistEntry,Long> {
    List<WatchlistEntry> findByUserIdOrderByCreatedAtAscIdAsc(Long userId);
    Optional<WatchlistEntry> findByUserIdAndSymbol(Long userId, String symbol);
    long countByUserId(Long userId);
    void deleteByUserIdAndSymbol(Long userId, String symbol);
}
