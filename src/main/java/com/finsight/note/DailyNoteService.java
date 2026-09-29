package com.finsight.note;

import com.finsight.auth.User;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DailyNoteService {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final DailyNoteRepository notes;

    public DailyNoteService(DailyNoteRepository notes) { this.notes = notes; }

    @Transactional
    public DailyNoteDtos.Response saveToday(User user, DailyNoteDtos.SaveRequest request) {
        String content = request.content().trim();
        DailyNote note = notes.findByUserAndNoteDate(user, today()).map(existing -> {
            existing.updateContent(content);
            return existing;
        }).orElseGet(() -> new DailyNote(user, today(), content));
        return DailyNoteDtos.Response.from(notes.save(note));
    }

    @Transactional(readOnly = true)
    public List<DailyNoteDtos.Response> list(User user) {
        return notes.findTop30ByUserOrderByNoteDateDesc(user).stream().map(DailyNoteDtos.Response::from).toList();
    }

    private LocalDate today() { return LocalDate.now(KST); }
}
