# 피드백 설명 보강

기사 원문과 관측 결과가 같은 방향이라는 확인에 가격 연결 원리를 덧붙였습니다. 이익 기대, 매수 수요·매도 공급, 자금 조달 비용, 환율의 업종별 차이를 일반 학습 설명으로 제시하고 기사 원인이라고 확정하지 않습니다. Gemini opt-in 프롬프트에도 동일한 구조를 요구하지만 실제 호출/환경 설정 변경은 없습니다.

화면의 판단14/news153은 카카오로 당일 비교한 기존 기록입니다. 현재 strict target은 KOSPI이므로 TARGET_MISMATCH로 안내하고 옛 칭찬과 차트를 숨깁니다. 데이터는 변경하지 않습니다. 기존 정상 구조화 기록의 짧은 규칙 문구는 읽기 응답에만 설명을 덧붙이고 저장값은 유지합니다.

검증: JUnit 194개 중 189 통과(로컬 prod 전용5 제외), Python seed7/백필5/dry-run2 통과. 매핑 오류·당일 비교·읽기 시 보강 모두 저장 호출이 없음을 검사합니다.

참고 학습 자료: [SEC Investor.gov 주식 안내](https://www.investor.gov/introduction-investing/investing-basics/investment-products/stocks). 기대와 주가의 관계를 학습용으로 설명하며 특정 기사의 인과관계를 입증하는 자료로 사용하지 않습니다.
