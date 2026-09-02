package com.finsight.term;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds a starter set of common Korean finance/investing terms on startup.
 * Idempotent: only inserts terms that are not already present, so restarts
 * never create duplicates.
 */
@Component
public class TermSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TermSeeder.class);

    private static final List<Term> SEED_TERMS = List.of(
            new Term("기준금리", "한국은행이 시중 금리의 기준이 되도록 정하는 정책금리입니다. 기준금리가 오르면 대출과 예금 금리가 함께 오르는 경향이 있습니다."),
            new Term("환율", "한 나라의 통화를 다른 나라 통화로 교환할 때 적용되는 비율입니다. 원/달러 환율이 오르면 원화 가치는 상대적으로 떨어진 것입니다."),
            new Term("실적", "기업이 일정 기간 동안 거둔 매출, 이익 등 경영 성과를 말합니다. 실적 발표는 주가에 큰 영향을 줍니다."),
            new Term("영업이익", "매출에서 매출원가와 판매관리비 등 영업 활동에 들어간 비용을 뺀 이익입니다. 기업의 본업이 얼마나 잘되는지 보여줍니다."),
            new Term("코스피", "한국거래소 유가증권시장에 상장된 대형 기업 중심의 종합 주가지수입니다."),
            new Term("코스닥", "중소·벤처·기술 기업 중심으로 구성된 한국의 또 다른 주식시장 지수입니다."),
            new Term("PER", "주가수익비율(Price Earning Ratio)로, 주가를 주당순이익(EPS)으로 나눈 값입니다. 숫자가 낮을수록 이익 대비 주가가 저평가되었다고 볼 수 있습니다."),
            new Term("PBR", "주가순자산비율(Price Book-value Ratio)로, 주가를 주당순자산으로 나눈 값입니다. 1보다 낮으면 장부가치보다 주가가 낮다는 의미입니다."),
            new Term("배당수익률", "주가 대비 한 해 동안 받는 배당금의 비율입니다. 배당수익률이 높을수록 투자금 대비 배당 소득이 큽니다."),
            new Term("시가총액", "상장 주식 수에 현재 주가를 곱한 값으로, 기업의 시장 가치를 나타냅니다."),
            new Term("공시", "기업이 투자자 보호를 위해 경영 상황이나 중요한 정보를 시장에 공개적으로 알리는 제도입니다."),
            new Term("유상증자", "기업이 새 주식을 발행해 투자자로부터 자금을 추가로 조달하는 것입니다. 기존 주주의 지분 가치가 희석될 수 있습니다."),
            new Term("매수/매도", "매수는 주식을 사는 것, 매도는 주식을 파는 것을 말합니다."),
            new Term("변동성", "자산 가격이 얼마나 크고 빠르게 오르내리는지를 나타내는 지표입니다. 변동성이 크면 수익과 손실의 폭도 커질 수 있습니다."),
            new Term("인플레이션", "물가가 지속적으로 상승해 화폐의 실질 구매력이 떨어지는 현상입니다."),
            new Term("양적완화", "중앙은행이 시중에 통화를 풀기 위해 국채 등 자산을 대규모로 매입하는 정책입니다."),
            new Term("서킷브레이커", "주가가 급락할 때 시장 전체 거래를 일시적으로 멈춰 투자자를 보호하는 제도입니다."),
            new Term("외국인 순매수", "외국인 투자자가 일정 기간 동안 매도한 금액보다 매수한 금액이 더 많은 상태를 말합니다. 수급 방향을 보여주는 지표입니다."),
            new Term("실적 전망", "향후 분기나 연간 기업 실적이 어떻게 나올지에 대한 시장의 예상치를 말합니다."),
            new Term("밸류에이션", "기업의 현재 주가가 적정한 가치에 비해 높은지 낮은지를 평가하는 작업입니다.")
    );

    private final TermRepository termRepository;

    public TermSeeder(TermRepository termRepository) {
        this.termRepository = termRepository;
    }

    @Override
    public void run(String... args) {
        int inserted = 0;
        for (Term seed : SEED_TERMS) {
            boolean exists = termRepository.findByTermIgnoreCase(seed.getTerm()).isPresent();
            if (!exists) {
                termRepository.save(new Term(seed.getTerm(), seed.getShortDefinition()));
                inserted++;
            }
        }
        log.info("Term seeding complete: {} new term(s) inserted, {} total seed terms checked", inserted, SEED_TERMS.size());
    }
}
