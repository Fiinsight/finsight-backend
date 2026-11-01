package com.finsight.judgement;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class PriceAdjustmentTest {
 @Test void exactLimitAllowedButSplitLikeMovesDeferred() {
  assertThat(new ClosingPrices.Pair(100,130).needsAdjustmentReview()).isFalse();
  assertThat(new ClosingPrices.Pair(100,70).needsAdjustmentReview()).isFalse();
  assertThat(new ClosingPrices.Pair(100,130.01).needsAdjustmentReview()).isTrue();
  assertThat(new ClosingPrices.Pair(100,50).needsAdjustmentReview()).isTrue();
 }
}
