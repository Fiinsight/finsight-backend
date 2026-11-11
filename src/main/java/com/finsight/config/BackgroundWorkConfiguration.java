package com.finsight.config;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
@Configuration
public class BackgroundWorkConfiguration {
    @Bean ThreadPoolTaskExecutor newsBackgroundExecutor() { return pool("news-io-"); }
    @Bean ThreadPoolTaskExecutor feedbackBackgroundExecutor() { return pool("feedback-io-"); }
    private ThreadPoolTaskExecutor pool(String prefix) {
        var pool=new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);pool.setMaxPoolSize(1);pool.setQueueCapacity(16);
        pool.setThreadNamePrefix(prefix);
        pool.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        return pool;
    }
}
