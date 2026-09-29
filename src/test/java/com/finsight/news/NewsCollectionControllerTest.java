package com.finsight.news;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class NewsCollectionControllerTest {

    @Test
    void rejectsManualCollectionWithinTenMinutes() {
        NewsCollectionScheduler scheduler = mock(NewsCollectionScheduler.class);
        when(scheduler.runOnce()).thenReturn(new NewsCollectionResult(1, 1, 1, 1));
        NewsCollectionController controller = new NewsCollectionController(scheduler);

        controller.collectNow();

        assertThatThrownBy(controller::collectNow)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("10분");
    }
}
