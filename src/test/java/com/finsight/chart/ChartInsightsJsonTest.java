package com.finsight.chart;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ChartInsightsJsonTest {
    private static ObjectMapper mapper() {
        return new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    private static ChartResponse chart(long newsId, double price, double change, String level, boolean fallback) {
        var day=LocalDate.parse("2026-11-05");
        var doc=new ChartResponse.DocentView("DIRECT","원문 \"인용\"·한글 😀\n다음 줄",level+" 근거를 확인해요.","숫자 +1.20%p를 확인해요.",newsId,"합성 보도 "+newsId,"합성 출처","RULE");
        var marker=new ChartResponse.NewsMarkerView(day,Instant.parse("2026-11-05T01:00:00Z"),newsId,doc.newsTitle(),doc.source(),"DIRECT");
        var move=new ChartResponse.MoveInsightView(day.atStartOfDay(),2,newsId,doc.newsTitle(),doc.source(),doc.possibleReason(),4,List.of(marker),doc);
        return new ChartResponse("005930",price,change,List.of(new ChartResponse.CandleView(day,100,104,99,102)),List.of(marker),List.of(marker),doc,"D",null,List.of(),fallback,List.of(move));
    }

    @Test void warmRequestsKeepExactJsonWithoutRepeatedRenderingOrSerialization() throws Exception {
        var charts=mock(ChartService.class);var mapper=spy(mapper());var source=chart(1,0,0,"normal",false);
        var current=chart(1,123,-.5,"normal",false);
        when(charts.withCurrentQuote(source)).thenReturn(current);
        when(charts.withDocents(current,"normal")).thenReturn(current);
        var encoder=new ChartInsightsJson(charts,mapper);
        byte[] expected=mapper().writeValueAsBytes(current);
        for(int i=0;i<15;i++) {
            var response=encoder.response(source,"normal");
            assertThat(response.getBody()).isEqualTo(expected);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        }
        verify(charts,times(15)).withCurrentQuote(source);
        verify(charts).withDocents(current,"normal");
        verify(mapper).writeValueAsBytes(current);
    }

    @Test void changedLiveQuoteAndNewSourceAreVisibleImmediately() throws Exception {
        var charts=mock(ChartService.class);var encoder=new ChartInsightsJson(charts,mapper());
        var source=chart(1,0,0,"normal",false);var first=chart(1,123,-.5,"normal",false);var next=chart(1,126,1.25,"normal",false);
        when(charts.withCurrentQuote(source)).thenReturn(first,next);
        when(charts.withDocents(any(),eq("normal"))).thenAnswer(c -> c.getArgument(0));
        assertThat(encoder.response(source,"normal").getBody()).isEqualTo(mapper().writeValueAsBytes(first));
        assertThat(encoder.response(source,"normal").getBody()).isEqualTo(mapper().writeValueAsBytes(next));
        var revised=chart(2,126,1.25,"normal",false);
        when(charts.withCurrentQuote(revised)).thenReturn(revised);
        assertThat(encoder.response(revised,"normal").getBody()).isEqualTo(mapper().writeValueAsBytes(revised));
    }

    @Test void levelsRemainSeparateAndFallbackResponsesAreNotRetained() throws Exception {
        var charts=mock(ChartService.class);var encoder=new ChartInsightsJson(charts,mapper());var source=chart(1,100,0,"normal",false);
        var analyst=chart(1,100,0,"analyst",false);
        when(charts.withCurrentQuote(source)).thenReturn(source);
        when(charts.withDocents(source,"normal")).thenReturn(source);
        when(charts.withDocents(source,"analyst")).thenReturn(analyst);
        assertThat(encoder.response(source,"normal").getBody()).isEqualTo(mapper().writeValueAsBytes(source));
        assertThat(encoder.response(source,"analyst").getBody()).isEqualTo(mapper().writeValueAsBytes(analyst));
        var fallback=chart(1,0,0,"normal",true);
        when(charts.withCurrentQuote(fallback)).thenReturn(fallback);
        when(charts.withDocents(fallback,"normal")).thenReturn(fallback);
        encoder.response(fallback,"normal");encoder.response(fallback,"normal");
        verify(charts,times(2)).withDocents(fallback,"normal");
    }

    @Test void httpRouteStillWritesTheSameJsonBytesInsteadOfEncodingAByteArrayAsJson() throws Exception {
        var charts=mock(ChartService.class);var source=chart(1,123,2,"normal",false);
        when(charts.getChart("005930","D",5)).thenReturn(source);
        when(charts.withCurrentQuote(source)).thenReturn(source);
        when(charts.withDocents(source,"normal")).thenReturn(source);
        var mvc=MockMvcBuilders.standaloneSetup(new ChartController(charts,new ChartInsightsJson(charts,mapper()))).build();
        mvc.perform(get("/api/charts/005930/insights").param("period","D").param("level","normal"))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().bytes(mapper().writeValueAsBytes(source)));
    }
}
