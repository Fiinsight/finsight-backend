package com.finsight.chart;
import com.finsight.news.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
final class ChartNewsRows {
    static void stub(NewsRepository repository,List<News> rows) {
        for(int i=0;i<rows.size();i++) if(rows.get(i).getId()==null) org.springframework.test.util.ReflectionTestUtils.setField(rows.get(i),"id",10000L+i);
        when(repository.findChartCandidatesAfterId(any(),any(),anyLong(),any())).thenAnswer(call -> {
            java.time.Instant start=call.getArgument(0),end=call.getArgument(1);long after=call.getArgument(2);
            org.springframework.data.domain.Pageable page=call.getArgument(3);
            return rows.stream().filter(n->n.getId()>after && !n.getPublishedAt().isBefore(start) && !n.getPublishedAt().isAfter(end))
                    .sorted(Comparator.comparing(News::getId)).limit(page.getPageSize()).toList();
        });
        when(repository.findAllById(any())).thenAnswer(call -> {Iterable<Long> ids=call.getArgument(0);Set<Long> wanted=new HashSet<>();ids.forEach(wanted::add);return rows.stream().filter(n->wanted.contains(n.getId())).toList();});
    }
}
