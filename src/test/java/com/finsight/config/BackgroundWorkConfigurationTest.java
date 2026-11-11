package com.finsight.config;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class BackgroundWorkConfigurationTest {
    @Test void poolsAreSeparateSingleThreadAndRejectAnOverflowingQueue() throws Exception {
        var config=new BackgroundWorkConfiguration();var news=config.newsBackgroundExecutor();var feedback=config.feedbackBackgroundExecutor();
        var release=new java.util.concurrent.CountDownLatch(1);var started=new java.util.concurrent.CountDownLatch(1);
        news.initialize();feedback.initialize();
        try {
            news.execute(()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertThat(started.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            for(int i=0;i<16;i++) news.execute(()->{});
            assertThatThrownBy(()->news.execute(()->{})).isInstanceOf(org.springframework.core.task.TaskRejectedException.class);
            assertThat(news.getMaxPoolSize()).isEqualTo(1);assertThat(feedback.getMaxPoolSize()).isEqualTo(1);
            assertThat(feedback.getThreadNamePrefix()).isEqualTo("feedback-io-");
            assertThat(feedback.submit(()->42).get(2,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(42);
        } finally {release.countDown();news.shutdown();feedback.shutdown();}
    }
}
