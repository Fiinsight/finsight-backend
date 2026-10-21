package com.finsight.judgement;

import com.finsight.external.kis.*;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KisHistoricalRegradeContractTest {
    @Test void historicalStockAndKospiRequestsMatchOfficialDateRangeContractWithoutLiveNetwork() {
        var token=mock(KisTokenProvider.class);when(token.getAccessToken()).thenReturn(Optional.of("test-token"));
        when(token.quoteFilter()).thenReturn((request,next) -> next.exchange(request));
        var sent=new AtomicReference<ClientRequest>();
        ExchangeFunction network=request -> {
            sent.set(request);
            String fields=request.url().getPath().contains("indexchartprice")
                    ? "\"bstp_nmix_oprc\":\"1000\",\"bstp_nmix_hgpr\":\"1000\",\"bstp_nmix_lwpr\":\"1000\",\"bstp_nmix_prpr\":\"1000\""
                    : "\"stck_oprc\":\"100\",\"stck_hgpr\":\"100\",\"stck_lwpr\":\"100\",\"stck_clpr\":\"100\"";
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json")
                    .body("{\"rt_cd\":\"0\",\"output2\":[{\"stck_bsop_date\":\"20260928\","+fields+"},{\"stck_bsop_date\":\"20260929\","+fields+"}]}").build());
        };
        var client=new KisDailyCandleClient(WebClient.builder().exchangeFunction(network),token,"https://example.invalid","test-key","test-secret");
        LocalDate before=LocalDate.parse("2026-09-28"),after=LocalDate.parse("2026-09-29");
        for(String symbol : new String[]{"047050","KOSPI"}) {
            var result=client.getClosingCandles(symbol,before,after);
            assertThat(result.fallback()).isFalse();assertThat(ClosingPrices.from(result,before,after)).isPresent();
            var request=sent.get();String query=request.url().getQuery();
            assertThat(query).contains("FID_INPUT_DATE_1=20260928","FID_INPUT_DATE_2=20260929","FID_PERIOD_DIV_CODE=D");
            if(symbol.equals("KOSPI")) {
                assertThat(request.url().getPath()).endsWith("inquire-daily-indexchartprice");
                assertThat(request.headers().getFirst("tr_id")).isEqualTo("FHKUP03500100");
                assertThat(query).contains("FID_INPUT_ISCD=0001","FID_COND_MRKT_DIV_CODE=U").doesNotContain("FID_ORG_ADJ_PRC");
            } else {
                assertThat(request.url().getPath()).endsWith("inquire-daily-itemchartprice");
                assertThat(request.headers().getFirst("tr_id")).isEqualTo("FHKST03010100");
                assertThat(query).contains("FID_INPUT_ISCD=047050","FID_COND_MRKT_DIV_CODE=J","FID_ORG_ADJ_PRC=1");
            }
        }
    }
}
