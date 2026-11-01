package com.finsight.auth;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Add every new app_user FK here; the PostgreSQL integration test checks schema coverage. */
@Component
public class AccountDataDeletion {
    public record OwnedTable(String name, boolean optional) {}
    public static final List<OwnedTable> TABLES = List.of(
            new OwnedTable("term_mastery",false),
            new OwnedTable("learning_progress",false),
            new OwnedTable("article_note",false),
            new OwnedTable("daily_note",false),
            new OwnedTable("judgement",false),
            new OwnedTable("user_watchlist",false),
            new OwnedTable("learning_mastery_credit",true),
            new OwnedTable("reading_attendance",true));
    private final JdbcTemplate jdbc;
    public AccountDataDeletion(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void deleteForUser(Long userId) {
        for(var table:TABLES) {
            if(table.optional() && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL",Boolean.class,table.name()))) continue;
            jdbc.update("DELETE FROM " + table.name() + " WHERE user_id=?",userId);
        }
    }
}
