package com.finsight.learning;

import com.finsight.auth.UserRepository;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class ReadingService {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private Clock clock = Clock.system(ZoneId.of("Asia/Seoul"));
    public ReadingService(JdbcTemplate jdbc, UserRepository users) { this.jdbc=jdbc; this.users=users; }
    @Transactional(readOnly=true)
    public Map<String,List<Long>> log(Long userId) {
        var result=new TreeMap<String,List<Long>>();
        jdbc.query("SELECT read_day,news_ids FROM reading_attendance WHERE user_id=? ORDER BY read_day", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            var ids=Arrays.stream((Long[])rs.getArray("news_ids").getArray()).toList();
            result.put(rs.getDate("read_day").toLocalDate().toString(),ids);
        },userId);
        return result;
    }
    @Transactional
    public Map<String,List<Long>> read(Long userId, Long newsId) {
        users.lockWatchlistOwner(userId);
        if(!exists(newsId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"뉴스를 찾을 수 없습니다.");
        add(userId,LocalDate.now(clock),newsId,clock.instant());
        return log(userId);
    }
    @Transactional
    public Map<String,List<Long>> merge(Long userId, String deviceId, Map<String,List<Long>> days) {
        // Validate the whole request before reserving the idempotency key.
        var parsed=new TreeMap<LocalDate,List<Long>>();
        int total=0;
        try {
            if(days.size()>3660) throw new IllegalArgumentException();
            for(var item:days.entrySet()) {
                LocalDate day=LocalDate.parse(item.getKey());
                if(!day.toString().equals(item.getKey()) || day.isAfter(LocalDate.now(clock)) || day.isBefore(LocalDate.of(2000,1,1)) || item.getValue()==null) throw new IllegalArgumentException();
                total+=item.getValue().size();
                if(total>20000 || item.getValue().stream().anyMatch(id->id==null || id<=0)) throw new IllegalArgumentException();
                parsed.put(day,item.getValue().stream().distinct().toList());
            }
        } catch(RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"올바른 읽기 기록을 보내주세요."); }
        users.lockWatchlistOwner(userId);
        if(jdbc.update("INSERT INTO reading_import(user_id,device_id,imported_at) VALUES(?,?,?) ON CONFLICT DO NOTHING",userId,deviceId,java.sql.Timestamp.from(clock.instant()))==0) return log(userId);
        for(var item:parsed.entrySet()) for(Long id:item.getValue()) if(exists(id)) {
            // Legacy storage only has a KST date, not a measured reading time.
            add(userId,item.getKey(),id,item.getKey().atTime(12,0).atZone(clock.getZone()).toInstant());
        }
        return log(userId);
    }
    private boolean exists(Long id) { return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM news WHERE id=?)",Boolean.class,id)); }
    private void add(Long userId,LocalDate day,Long newsId,Instant at) {
        var timestamp=java.sql.Timestamp.from(at);
        jdbc.update("""
            INSERT INTO reading_record(user_id,news_id,first_read_at,last_read_at) VALUES(?,?,?,?)
            ON CONFLICT(user_id,news_id) DO UPDATE SET first_read_at=LEAST(reading_record.first_read_at,EXCLUDED.first_read_at),last_read_at=GREATEST(reading_record.last_read_at,EXCLUDED.last_read_at)
            """,userId,newsId,timestamp,timestamp);
        jdbc.update("""
            INSERT INTO reading_attendance(user_id,read_day,news_ids) VALUES(?,?,ARRAY[?]::bigint[])
            ON CONFLICT(user_id,read_day) DO UPDATE SET news_ids=(SELECT array_agg(DISTINCT id ORDER BY id) FROM unnest(reading_attendance.news_ids || EXCLUDED.news_ids) id)
            """,userId,day,newsId);
    }
}
