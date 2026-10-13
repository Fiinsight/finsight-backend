# 판단 결과 예정 시각 API

POST /api/judgements ACK에 `judgementId`, `expectedResultDate`(YYYY-MM-DD), `expectedResultAt`(ISO-8601 Instant)를 추가합니다. 신규 제출과 수정 모두 저장된 createdAt의 KST 날짜에서 KoreanTradingCalendar.next를 사용합니다. 결과 예정은 다음 거래일 15:40 KST입니다. 확정 결과를 미리 반환하지 않습니다.

history/history/page/detail에는 `relatedSymbol`, `relatedSymbolName`과 결과 대기 행의 예정 날짜/시각을 추가합니다. 이미 확정된 행의 예정 시각은 null입니다. 확인되지 않은 달력 연도도 null로 반환하여 오래된 이력이 전체 조회를 깨거나 잘못된 휴장일을 알려주지 않게 합니다. 기존 필드와 엔드포인트는 유지합니다.

프런트는 날짜를 그대로 표시하고 16:00 KST 로컬 알림 예약에 사용합니다. 확인 전 예약 알림은 ‘결과를 확인하고 있어요’이며 실제 도착 배너는 서버의 방향/변동률/비교일이 있는 결과만 사용합니다. DB·스케줄러·외부 호출 상한은 변경하지 않습니다.

검증: 금요일 10/16→10/19, 추석 9/23→9/28, 10/2→10/6, 한글날 연휴 10/8→10/12 및 정확한 15:40 KST. 신규/수정 ACK의 예정 시각 테스트. 구버전 JSON 호환 PR #110을 먼저 병합했고 프런트 결과 알림 PR에서 사용합니다.
