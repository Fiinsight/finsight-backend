package com.finsight.learning;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/learning/reads")
@org.springframework.validation.annotation.Validated
public class ReadingController {
    public record Import(@NotBlank @Pattern(regexp="[a-zA-Z0-9_-]{16,80}") String deviceId, @NotNull @Size(max=3660) Map<String,List<Long>> days) {}
    private final ReadingService service;
    public ReadingController(ReadingService service) { this.service=service; }
    @GetMapping public Map<String,List<Long>> get(@AuthenticationPrincipal Long userId) { return service.log(userId); }
    @PutMapping("/{newsId}") public Map<String,List<Long>> read(@AuthenticationPrincipal Long userId,@PathVariable @Positive Long newsId) { return service.read(userId,newsId); }
    @PostMapping("/merge") public Map<String,List<Long>> merge(@AuthenticationPrincipal Long userId,@RequestBody @Valid Import request) { return service.merge(userId,request.deviceId(),request.days()); }
}
