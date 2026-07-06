package com.finsight.news;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NewsCollectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(NewsCollectionScheduler.class);

    @Scheduled(cron = "0 0 */3 * * *")
    public void collectNewsCandidates() {
        log.info("Collecting news candidates at {}", Instant.now());
    }
}

