package com.finsight.judgement;

import java.time.LocalDate;
import java.util.List;

public record JudgementRegradeResponse(boolean dryRun, long ready, long applied, List<Row> records) {
    public record Row(Long judgementId, String status, String reason, String targetSymbol,
            LocalDate baselineDate, LocalDate comparisonDate, Double judgementClose, Double comparisonClose,
            String actualDirection, Double actualChangePercent, String indexSymbol,
            Double indexJudgementClose, Double indexComparisonClose, Double indexChangePercent) {
        public Row(Long id, String status, String reason, String symbol, LocalDate baseline, LocalDate comparison) {
            this(id,status,reason,symbol,baseline,comparison,null,null,null,null,null,null,null,null);
        }
        public Row withStatus(String status, String reason) {
            return new Row(judgementId,status,reason,targetSymbol,baselineDate,comparisonDate,judgementClose,comparisonClose,
                    actualDirection,actualChangePercent,indexSymbol,indexJudgementClose,indexComparisonClose,indexChangePercent);
        }
    }
}
