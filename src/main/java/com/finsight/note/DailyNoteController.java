package com.finsight.note;

import com.finsight.auth.User;
import com.finsight.auth.UserRepository;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notes")
public class DailyNoteController {
    private final DailyNoteService noteService;
    private final UserRepository users;

    public DailyNoteController(DailyNoteService noteService, UserRepository users) {
        this.noteService = noteService;
        this.users = users;
    }

    @GetMapping
    public List<DailyNoteDtos.Response> list(@AuthenticationPrincipal Long userId) {
        return noteService.list(user(userId));
    }

    @PutMapping("/today")
    public DailyNoteDtos.Response saveToday(@AuthenticationPrincipal Long userId,
                                            @Valid @RequestBody DailyNoteDtos.SaveRequest request) {
        return noteService.saveToday(user(userId), request);
    }

    private User user(Long userId) { return users.findById(userId).orElseThrow(); }
}
