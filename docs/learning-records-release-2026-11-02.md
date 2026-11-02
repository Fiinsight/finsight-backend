# 실시간 BKT·서버 읽기/출석 준비 (미배포)

## 기존 범위와 변경

- 기존 `finsight.learning.reading.v1:<userId>`는 기기별 KST 날짜→기사 ID 목록이었다. `AttendanceCard`는 이 목록에서 날짜별 3개 이상을 출석으로 계산했다. 웹·폰·재설치 사이에 공유되지 않았다.
- 판단 상세를 열어본 기록(`judgement-views.v1`)과 승급 제안 미루기는 계속 기기별이다. 이번 서버 저장 대상은 기사 읽기·읽기 출석이다.
- V19 `learning_mastery_credit`: PK(user_id,news_id,term,quiz_day), 사용자·기사 FK CASCADE. id는 같은 시각의 응답 순서 판별을 위한 고유 identity다. 날짜는 Clock.system(Asia/Seoul)에서 계산한다.
- prod의 `daily-credit-enabled` 기본값 true. `LEARNING_DAILY_CREDIT_ENABLED=false`로 끌 수 있다. 일반/dev 기본값은 기존 false다.
- 기존 progress를 원장/숙련도에 replay하는 migration, startup hook, backfill은 추가하지 않았다. 이미 있던 숙련도 값은 그대로 이어받고 새 응답부터 첫 일일 원장에 적립한다. 같은 날 같은 기사·개념의 재응답은 progress 연습 기록만 갱신한다. 복습 목록도 이 연습 기록의 마지막 정답이 아니라 원장에 반영된 마지막 정오답을 우선 사용하므로, 높은 숙련도에서 오답 후 연습 정답을 맞혀도 복습/승급 조건을 우회하지 않는다.
- V20 `reading_record`: PK(user_id,news_id), 기사별 최초/최근 읽기 시각. 사용자·기사 FK CASCADE.
- V20 `reading_attendance`: PK(user_id,read_day), 일별 중복 없는 기사 ID 배열. 기사 삭제 후에도 당시 출석 이력은 보존한다. 사용자 FK CASCADE.
- V20 `reading_import`: PK(user_id,device_id), 가져오기 중복 방지. 사용자 FK CASCADE.
- AccountDataDeletion에 모든 테이블 등록. 실제 prod-migration 테스트가 app_user FK 목록과 등록 범위를 비교한다.

## API

인증 주체만 사용하며 body/query의 userId는 받지 않는다.

- GET `/api/learning/reads`: 본인 날짜→기사 목록. 로그인 이후 UI 기준값.
- PUT `/api/learning/reads/{newsId}`: 서버 KST 오늘 날짜로 저장. 기사 존재 확인, 사용자 행 잠금, 같은 날짜·기사 중복 방지. 같은 기사의 다른 날 읽기는 출석에 추가되며 reading_record는 여전히 1행.
- POST `/api/learning/reads/merge`: `{deviceId,days}`. 날짜 유효성·미래 날짜 거절, 최대 3660일/20000개 ID. 존재하는 기사만 병합. 검증 후 동일 트랜잭션에서 import 토큰을 예약하고 병합하므로 실패 시 토큰도 롤백. 같은 토큰 재시도 및 다른 기기 겹치는 기사 모두 중복 집계 없음.
- 일일 출석: 서버 응답의 고유 기사 수 ≥3, 기존 판정 함수 재사용. 읽기 목표는 서버 attendance로 판단하므로 클라이언트가 readDays만 보내 달성시키지 못한다.

## 로컬 기록 이전

사용자가 로그인하면 해당 계정의 로컬 기록을 자동 병합한다(이번 사용자 요청으로 이전 초안의 수동 이전 정책을 대체). 기기+계정별 안정적인 import ID와 완료 표식을 사용한다. 응답 유실/저장 실패 시 같은 ID로 재시도하며 서버에서도 중복 방지한다. 가져오기 시각은 실제 읽기 시각이 아니므로 과거 날짜만 알 수 있는 로컬 기록의 reading_record 시각은 KST 정오로 표현한다. 과거 출석은 기기 기록을 신뢰해 가져온 자료이며 실측 타임스탬프로 주장하지 않는다.

그 뒤에는 GET/PUT 서버 응답만 표시한다. 실패를 로컬 출석 달성으로 표시하지 않으며 재접속/화면 재진입 시 재시도한다. 오프라인 새 읽기 큐는 이번 범위에 추가하지 않았다. 오래된 로컬 snapshot이 새 기기에서도 다시 들어와도 일일 기사 집합으로 합쳐지므로 출석 수가 늘어나지 않는다.

계정 전환 중 요청도 처음 캡처한 토큰으로 보내고, 완료 후 소유자가 달라졌으면 캐시·완료 표식을 저장하지 않는다. 토큰/비밀번호를 기록 파일에 남기지 않는다.

## 배포 순서 (아직 실행하지 않음)

1. 기존 품질 PR backend #170 / frontend #196 및 이 후속 스택 PR diff·CI를 확인하고 검토된 순서로 통합한다. 이 PR은 부모 개발 브랜치로 가므로 main/운영 브랜치에 자동 포함되지 않는다.
2. 정상 백업·migration 계획 확인 후 backend부터 배포: Flyway V19/V20 적용, prod 기본 활성화. 필요 시 운영자가 `LEARNING_DAILY_CREDIT_ENABLED=false`로 중지할 수 있다. 이번 작업에서 운영 설정을 바꾸지 않았다.
3. 본인 테스트 계정으로 다른 기사 정답/오답, 당일 동일 기사 재시도, 복습/숙련도/승급 조건 확인. 기존 기록 수와 p_known이 자동 재생되지 않는지 확인.
4. backend API 준비 후 frontend 배포. 로그인 자동 병합 → 두 기기 동일 계정에서 GET 값 일치 → 기사 3개 출석 → 로그아웃/다른 계정 격리 확인.
5. 탈퇴 시 신규 원장/읽기/출석/import 데이터가 모두 삭제되는지 확인.
6. 실사용 기록 수집은 그 뒤 실제 퀴즈 응답으로 진행. LLM 활성화·AI 재작성 운영 적용은 이 작업과 별개다.

운영 DB 쓰기·마이그레이션 적용·배포·Render 환경변수 변경을 하지 않는다. 차트 속도 측정은 인증 테스트 계정의 로그인과 GET만 사용하며 비즈니스 데이터 생성/수정 요청은 하지 않는다.

## 최종 로컬 검증

./gradlew test: 336 tests / 실패·오류·제외 0. prod 프로필에서 Flyway V19/V20 실제 적용, KST 경계, 오답·정답, 동일 기사 재시도, 다른 기사 적립, 복습/승급 즉시 변경, 사용자 격리·중복 기기 병합·잘못된 날짜·계정 삭제를 임시 PostgreSQL에서 확인했다. 기존 learning_progress 소급 반영은 실행하지 않았다.
