package com.finsight.external;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;
class UserAiTimeoutTest {
    @Test void delayedUserCallFallsBackWithinFourSeconds() {
        var client=new AiServiceClient(WebClient.builder().exchangeFunction(request->Mono.never()),"http://127.0.0.1");
        long started=System.nanoTime();
        assertThat(client.explainTerm(new AiTermExplainRequest("금리","기사","정의","normal"))).isEmpty();
        assertThat((System.nanoTime()-started)/1_000_000).isBetween(2500L,4500L);
    }
}
