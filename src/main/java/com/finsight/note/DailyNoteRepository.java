package com.finsight.note;

import com.finsight.auth.User;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DailyNoteRepository extends JpaRepository<DailyNote, Long> {
    Optional<DailyNote> findByUserAndNoteDate(User user, LocalDate noteDate);
    List<DailyNote> findTop30ByUserOrderByNoteDateDesc(User user);
}
