package com.finsight.watchlist;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="user_watchlist", uniqueConstraints=@UniqueConstraint(name="uk_user_watchlist_symbol", columnNames={"user_id","symbol"}))
public class WatchlistEntry {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="user_id",nullable=false) private Long userId;
    @Column(nullable=false,length=16) private String symbol;
    @Column(nullable=false) private Instant createdAt;
    protected WatchlistEntry() {}
    public WatchlistEntry(Long userId, String symbol) { this.userId=userId; this.symbol=symbol; this.createdAt=Instant.now(); }
    public String getSymbol() { return symbol; }
    public Instant getCreatedAt() { return createdAt; }
}
