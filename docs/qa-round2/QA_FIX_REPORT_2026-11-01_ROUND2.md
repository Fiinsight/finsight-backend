# FinSight 2차 코드 점검 결과

## 검증 결과와 제약

- 로컬 **backend 310개 모두 통과(제외 0)**. 기존 환경 의존 PostgreSQL 테스트도 별도의 loopback DB/Redis에서 실행했다. Python 스크립트 검사 20개 통과.
- frontend typecheck 및 **143개 테스트**, 웹 export 통과. AI pytest **242개 통과**.
- 운영 DB 쓰기·마이그레이션·백필, main 머지, Render/Expo 배포, 환경변수/키 변경, 외부 LLM 호출 없음.
- 기존 Desktop build의 동기화 중복 class가 다시 발견돼 빌드 출력 전체를 `/tmp/finsight-round2-build`로 분리해 검증했다. 원본 소스나 기존 사용자 산출물은 삭제하지 않았다.

## P0-6 머지·운영 반영 대조

[커밋별 상태와 배포 체크리스트](MERGE_DEPLOY_STATUS_ROUND2.md)에 세 저장소의 main 앞선 기존 커밋을 전부 나열했다. 미머지와 미배포를 동일하게 보지 않았다.

| 서비스 | 현재 운영 근거 | 판단 |
|---|---|---|
| Render backend | 대시보드 Live `295110f8bd4401ae179173d277a35371c344d539`, 특정 커밋 배포, Free, main 추적·Auto-Deploy disabled | 카카오 20초와 KIS 토큰 지연 수정 등은 main 미머지여도 운영에 포함. 이번 QA/내부 인증 PR은 미반영 |
| Expo frontend | 공개 HTML의 `index-3ac01bd9f79f88ad9fc2c70d0e34d11f.js`가 기존 `da0870e` 배포 기록과 일치 | 홈 뉴스 3개·이전 핫픽스까지 포함. 이번 QA PR의 새 기능은 미반영. EAS API 커밋 메타데이터는 직접 확인하지 않음 |
| AI | 운영 backend `295110f`의 Dockerfile pin `6aa00e9` | 내부 인증 `78b71b0` 및 기사 근거 수정은 미반영. 로컬 pin `514a47e`와 혼동하지 않음 |

내부 AI 토큰 테스트:

- 양쪽 모두 없음: 새 AI도 loopback/private 전제에서 인증을 요구하지 않아 호출 성공. 누락 시 반드시 실패하는 구조는 아니다.
- AI만 설정 또는 값 불일치: 401. 백엔드 rewrite는 Optional.empty, 수집은 원문·출처·발행시각을 보존하고 EXCERPT 상태를 남긴다. 이는 재작성 성공이 아니며 재작성 본문은 null이다. feedback/docent의 기존 RULE fallback을 유지한다.
- backend만 설정, AI 미설정 또는 구 AI 6aa00e9: AI가 헤더를 검증하지 않아 성공할 수 있다. 성공 응답만으로 내부 인증 적용을 판단하면 안 된다.
- 같은 값 설정: 인증 통과. 비용 스위치·호출 예산은 변하지 않는다. 단일 Render 컨테이너 환경변수는 기존 launcher를 통해 두 프로세스에 상속된다.

테스트 파일: `AiInternalAuthenticationTest`, `tests/test_service_boundary.py`. 실제 유료 API 대신 MockWebServer/TestClient 사용. **머지와 새 AI pin·토큰 설정·배포는 사용자 결정 필요**.

## P1-6 재시도와 BKT

원인: `learning_progress`가 최신 답만 덮어써 원래 오답이 사라지고, 같은 답을 다시 제출할 때마다 관찰값으로 처리했다. 서버의 고정 보기 순서를 무작위로 바꾸기만 하면 GET과 POST의 index가 달라져 잘못 채점할 수 있었다.

수정:

- `TermMasteryService`의 일일 원장 경로는 `(user_id,news_id,term,quiz_day)` 유일 키로 첫 응답만 저장한다. KST 날짜를 서버가 계산하고 progress·BKT와 같은 트랜잭션으로 처리한다.
- `LearningService`는 본인 row lock으로 같은 사용자의 동시 진행 갱신도 직렬화한다. `learning_progress`에는 마지막 연습 응답을 남기고, BKT 및 최근 개념 정오답의 근거는 불변 원장에서 읽는다.
- 정의형 첫 응답만 BKT 갱신. 나머지는 `practice:true`로 반환하고 화면에 연습임을 표시한다. 정답 설명과 무제한 연습 자체는 유지한다.
- 모든 현재 3지선다 문항의 guess는 정확히 **1/3**. 정의형의 기존 slip=.1 유지. 과거 V17 migration의 .33 스냅샷은 변경하지 않았다.
- `quizOptions.ts`와 `LearningPanel`은 재시도 시 표시 순서를 다시 섞고, 제출은 원래 서버 options의 index로 한다. 이전 순서와 우연히 같아지면 순환해 다른 순서를 보장한다.

실제 PostgreSQL 테스트: 오답 → 같은 사용자/기사/용어의 재시도 정답 후 p_known이 `KnowledgeTracing.update(.3,false,"beginner")` 그대로이고 attempts=1이다. 동일 퀴즈를 세 번 맞혀도 승급 제안이 생기지 않는 테스트도 고정했다. Mockito 테스트와 실제 DB 테스트를 함께 통과했다.

### 반드시 필요한 사용자 결정

원장 SQL은 **`LEARNING_MASTERY_CREDIT_DRAFT.sql` 초안**이고 Flyway 경로에 넣지 않았다. 운영에는 실행하지 않았다. 승인한 스키마를 준비하고 `finsight.learning.daily-credit-enabled=true`를 설정하기 전까지 이 변경 버전의 퀴즈 API는 **모든 응답을 연습으로 저장하고 새 BKT 반영을 멈춘다**. 기존 운영은 이 코드가 아직 미배포이므로 바뀌지 않았다. 기본값을 조용히 기존의 반복 갱신으로 되돌리지 않는다.

기존 p_known 백필·재계산 없음. 기존 수동 `backfillMissing`은 최종 답만 복원하는 과거 관리 도구이므로 새 연습 기록에 실행하지 않는다. 운영 적용은 원장 스키마/활성화 정책을 검토한 뒤 별도 결정해야 한다.

## P2-4 기기별 출석

현재 로컬 저장은 유지하고 AttendanceCard의 compact/full 표시와 학습의 읽기·출석 연속 기록에 **“이 기기 기준”**을 추가했다. 서버 동기화가 구현된 것처럼 표시하지 않는다.

- `READING_ATTENDANCE_API_DRAFT.md`: JWT 소유권, 서버 KST 날짜, 고유 기사 하루 1회 PUT, 기간 제한 GET, 오프라인 중복과 기존 로그 가져오기 정책, 다기기/재설치/자정 검증 계획.
- `READING_ATTENDANCE_MIGRATION_DRAFT.sql`: 사용자·기사·날짜 PK와 계정 삭제 cascade. 초안만 작성, 실행·Flyway 등록·로컬 로그 백필 없음.

## P2-5 오래된 기사

두 선택지 중 **복습용으로 전환**하는 쪽을 골랐다. 오래된 기사에서 이미 알려진 후속 정보를 본 뒤 새 판단을 남기는 문제와, 기사 발행일·판단일 기준의 혼선을 함께 줄일 수 있기 때문이다.

`ArticleJudgementPolicy`는 발행일 뒤 두 번째 거래일의 KST 00:00부터 복습용으로 판정한다. 주말·휴일은 공통 KoreanTradingCalendar로 건너뛴다. 발행일이 없거나 미래인 경우도 새 판단을 허용하지 않는다.

- `NewsDetailResponse.judgementReviewOnly`를 통해 프런트에 같은 판정 결과를 전달한다.
- 뉴스 상세는 판단 버튼 대신 복습용 안내를 표시한다. 직접 판단 URL로 들어가도 제출 UI를 숨긴다.
- `JudgementService.recordJudgement`는 같은 정책으로 신규 제출과 수정 모두 저장 전 400으로 거절한다. 기존 완료 기록을 삭제하거나 재채점하지 않는다.
- 프런트는 구 API에 필드가 없을 때 판단 허용으로 추정하지 않는다. backend 변경을 먼저 반영해야 한다.
- 금요일→월요일은 허용, 화요일 KST 00:00부터 복습용, 명절 연휴 건너뜀, 날짜 없음, 서버 우회 제출 거절 테스트 통과.

## 수정 PR

순차 스택이며 기존 1차 QA PR 위에 쌓았다. main 머지는 하지 않았다.

| 저장소 | 이슈 브랜치 | 선행 base | PR |
|---|---|---|---|
| backend | `fix/p1-6-daily-mastery` | `fix/qa-fix-report` | https://github.com/Fiinsight/finsight-backend/pull/155 |
| backend | `fix/p2-5-article-review-policy` | `fix/p1-6-daily-mastery` | https://github.com/Fiinsight/finsight-backend/pull/156 |
| backend | `fix/p2-4-attendance-api-draft` | `fix/p2-5-article-review-policy` | https://github.com/Fiinsight/finsight-backend/pull/157 |
| backend | `fix/p0-6-ai-token-fallback` | `fix/p2-4-attendance-api-draft` | https://github.com/Fiinsight/finsight-backend/pull/158 |
| frontend | `fix/p1-6-quiz-practice` | `fix/qa-evidence` | https://github.com/Fiinsight/finsight-frontend/pull/184 |
| frontend | `fix/p2-4-device-attendance` | `fix/p1-6-quiz-practice` | https://github.com/Fiinsight/finsight-frontend/pull/185 |
| frontend | `fix/p2-5-review-only-ui` | `fix/p2-4-device-attendance` | https://github.com/Fiinsight/finsight-frontend/pull/186 |
| ai | `fix/p0-6-token-matrix` | `fix/p1-2-excerpt-quality` | https://github.com/Fiinsight/finsight-ai/pull/67 |

## 남은 한계

1. 원장과 출석 스키마는 운영에 적용하지 않았다. BKT 활성화·main 머지·배포는 사용자 결정 필요.
2. Expo 운영 커밋은 기존 배포 기록과 현재 번들 해시 일치로 대조했다. Render Live 커밋은 현재 대시보드에서 직접 확인했다.
3. 2차 화면 변경은 타입·렌더링 테스트/웹 export로 검증했으며 실제 iPhone/Android에서 재검증하지 않았다.
4. KRX 미등록 연도 달력 폴백, KIS 15:40 종가 확정 여부와 기존 Expo moderate 취약점은 이전 보고서의 잔여 항목 그대로다.
