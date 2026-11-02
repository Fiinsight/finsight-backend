# P2-8 판단 대기 사유 — 2026-11-02

`history`, detail, paged history에 pendingReason을 추가했다. 완료 결과는 null. 미완료 결과의 판정 순서는 로컬 QA 제목 → 달력 지원 여부 → 비교 거래일 15:40 KST 전/후다. 읽기 API가 KIS 조회를 동기 실행하지 않는다. 예정 시각이 지났으나 아직 가격을 확정하지 못한 기록은 PRICE_UNAVAILABLE로 응답한다(첫 catch-up 실행 대기도 포함). 화면은 실패를 확정적으로 단정하지 않고 “시세 확인 중 · 자동으로 다시 시도해요”로 표시한다.

- NOT_DUE: expectedResultDate/At에 확인된 다음 거래일·15:40 KST 제공.
- PRICE_UNAVAILABLE: 대상 종목 또는 비교 지수 종가가 미확정. 기존 자동 catch-up/scheduler 재시도 유지.
- EXCLUDED_QA: 제목에 “로컬 QA”. 프론트 대기·적중 집계와 polling 제외, 별도 테스트 기록 표시.
- CALENDAR_UNSUPPORTED: 판단 연도 또는 비교 연도가 2026/2027 달력 범위 밖. 예상일/시각 null, 자동/수동 재채점 보류. 공통 평일 계산 helper는 다른 용도로 유지하지만 채점은 미지원 연도 평일 추정에 의존하지 않는다.

기존 PRICE_ADJUSTMENT 표시가 가격 미확정 사유보다 우선한다. 운영 데이터 수정, 새 마이그레이션, 외부 LLM 호출, 배포 없음.

검증: Java 319개 실패·제외 0, 실제 PostgreSQL prod-migrations 13개 포함. Python 스크립트 20개 성공. 토요일 2026-11-07 판단 → 11월 9일(월) 15:40 KST, 15:39:59/15:40 경계, QA/미지원/완료 상태, history 응답 필드 검사. 기존 미지원 연도 채점 테스트는 새 보류 정책으로 변경하고 KIS/AI 호출 0회를 확인했다.
