package com.finsight.news;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Header-token protected automation endpoint; no user JWT or public collection permission. */
@RestController
@RequestMapping("/api/internal/news")
@io.swagger.v3.oas.annotations.Hidden
public class NewsCollectionTriggerController {
    private final NewsCollectionScheduler scheduler;
    private final String token;
    public NewsCollectionTriggerController(NewsCollectionScheduler scheduler,
            @Value("${finsight.news.collection.trigger-token:}") String token) {
        this.scheduler=scheduler;this.token=token;
    }
    private void authenticate(String provided) {
        if(token.length()<32) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Collection automation is not configured");
        if(provided==null || provided.length()>256 || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),provided.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid collection credentials");
    }
    public record TriggerResponse(String status,NewsCollectionScheduler.CollectionStatus collection) {}
    @PostMapping("/collect")
    public ResponseEntity<TriggerResponse> collect(@RequestHeader(value="X-Collection-Token",required=false) String provided) {
        authenticate(provided);
        if(!scheduler.startupReady()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER,"30").body(new TriggerResponse("STARTING",scheduler.collectionStatus()));
        boolean queued=scheduler.requestScheduledCatchUp();
        return ResponseEntity.status(queued?HttpStatus.ACCEPTED:HttpStatus.OK)
                .body(new TriggerResponse(queued?"QUEUED":"BUSY_OR_COOLDOWN",scheduler.collectionStatus()));
    }
    @GetMapping("/status")
    public NewsCollectionScheduler.CollectionStatus status(@RequestHeader(value="X-Collection-Token",required=false) String provided) {
        authenticate(provided);return scheduler.collectionStatus();
    }
}
