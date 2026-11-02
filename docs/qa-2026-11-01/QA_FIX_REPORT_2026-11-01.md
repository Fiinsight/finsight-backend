# FinSight QA 후속 수정 보고서 — 2026-11-01

## 결과와 검증 범위

요청된 P0 5개, P1 코드/문서, P2, P3 화면 단순화를 수정하고 이슈별 fix/ 브랜치와 PR로 분리했다. main 병합, 운영 배포·환경변수·DB 쓰기·마이그레이션·백필, 외부/유료 LLM 호출은 하지 않았다. 기존 미커밋 작업은 시작 시 patch로 보존하고 인증·AI 경계·투자 포인트·의존성별 PR로 분리했다.

최종 코드 스택 검사: **backend Java 304개 중 실패 0·환경 의존 11개 제외, Python 스크립트 20개 통과 / frontend typecheck 및 141개 테스트 통과 / AI pytest 241개 통과**. 웹 export 성공. 개별 중간 브랜치마다 전체 검사를 반복한 것은 아니며, 최종 스택 전체를 검증했다. 필요한 회귀 테스트는 해당 기능 PR에 포함되도록 재정렬했다. GitHub CI 상태는 로컬 통과와 별도로 아래 후속 확인 파일에 기록한다.

## P0

| 항목 | 원인 / 수정 파일 | 테스트 / 근거 | 남은 한계 / PR |
|---|---|---|---|
| P0-1 거래일 | KoreanTradingCalendar가 2026 외 연도를 예외로 종료. `KoreanTradingCalendar.java`에 2027 공휴일·대체공휴일·연말 휴장, 미등록 연도 평일 폴백과 연도별 WARN 1회 추가. | 12/30·12/31 → 2027-01-04, 설 연휴 → 2/10, 노동절·제헌절 대체휴일, 미등록 2028년·종가 누락 후 재시도 테스트. | 공식 공휴일 자료에 근거했지만 2027 KRX 특별 휴장 확정 공지는 아직 확인하지 못함. 폴백 연도의 실제 휴장일은 종가가 없으면 대기하며, 달력 보완 전 자동으로 다른 날짜를 만들지 않음. https://github.com/Fiinsight/finsight-backend/pull/144 |
| P0-2 채점 재시도 | Render 절전으로 cron/startup 1회에만 의존. `FeedbackScheduler.java`, `JudgementController.java`, `JudgementResultsProvider.tsx` 수정. 본인 이력/상세/페이지 조회 후 비동기 due 검사·runNow, 사용자별 5분 쿨다운·실행 중 중복 방지. | 병렬 20회 요청 1개 작업, 쿨다운 후 재실행, 완료 결과 덮어쓰기 없음. overdue일 때만 60초 polling, 화면이 열린 채 예정 시각을 넘기는 경우에도 재조회 시작. | 사용자 요청이 전혀 없으면 절전 중 즉시 채점할 수 없음. 쿨다운은 단일 Free 인스턴스의 메모리 기준. https://github.com/Fiinsight/finsight-backend/pull/145 / https://github.com/Fiinsight/finsight-frontend/pull/172 / https://github.com/Fiinsight/finsight-frontend/pull/182 |
| P0-3 URL 복원 | item/ack 객체가 query string에서 문자열이 됨. `App.tsx`, `navigation/types.ts`, 상세/완료 화면·호출부·렌더링 테스트 수정. URL에 scalar ID만 전달하고 GET detail로 복원. | 실제 로컬 UI 로그인→홈→뉴스→판단→완료 새로고침→기록→상세 새로고침 통과. `/history/judgements/not-a-number`, 없는 ID 안내 통과. 단위 테스트에서 forbidden/error도 안전한 기록 복귀 확인. | 실제 다른 사용자 계정 403은 브라우저로 만들지 않았으며 에러 분기는 단위 테스트로 확인. https://github.com/Fiinsight/finsight-frontend/pull/173 |
| P0-4 뉴스 수집 | 정각 cron·on-startup=false로 오래된 목록 유지. `NewsCollectionScheduler.java`, `BriefingService.java` 수정. KST cron, Redis 마지막 성공 시각, 홈 요청 후 3시간 stale이면 40개 제한 비동기 수집. 시작 45초 유예·10분 실패 재시도 쿨다운·전역 중복 방지·heap 로그. | 동시 호출 1회, 시작 유예/쿨다운/최근 성공 미실행, 추출 실패 시 성공 시각 미갱신 테스트. | 요청 없는 절전 중 수집은 불가능. RSS 공급자 수집 경로는 기존 설정 유지. 실행/재시작 시각 저장은 Redis가 없으면 메모리만 사용. 운영 수집은 실행하지 않음. https://github.com/Fiinsight/finsight-backend/pull/146 |
| P0-5 브리핑 | 1000개 원문·비교자 반복 점수·null 발행일. `BriefingService.java`, `NewsRepository.java` 수정. 최근 7일·최대 300개, 비교 전 1회 점수화, null/미래 발행일 제외. | 순위·페이지 연속성·캐시 재사용·null 발행일 테스트. | 300건 원문 로드는 남지만 범위를 줄임. 일일 고정 3개는 검토 후 도입 보류: 오래된 기사를 하루 종일 고정하는 문제와 재시작 후 일관성·별도 영속화 설계가 필요. 이번에는 60초 캐시와 새 기사 반영 유지. https://github.com/Fiinsight/finsight-backend/pull/147 |

### 거래일 근거

- [우주항공청 2027년 월력요항 공식 발표](https://www.kasa.go.kr/prog/plcyBrf/brief/kor/sub01_01_04/view.do?plcyBrfNo=431)
- [한국천문연구원 날짜표](https://astro.kasi.re.kr/kor/life/post/calendarData?year=2027)
- [KRX 휴장일 안내](https://open.krx.co.kr/contents/MKD/01/0110/01100305/MKD01100305.jsp) — 직접 조회는 403. 2027 KRX 확정 특별 공지로 과장하지 않았다. 연말 휴장 규칙 적용은 공지 후 재확인이 필요하다.

## P1

| 항목 | 원인 / 수정 파일 | 테스트 / 근거 | 한계 / PR |
|---|---|---|---|
| P1-1 실태 SQL | 숫자로 운영 상태를 확인할 근거가 없음. `QA_READONLY_CHECKS_2026-11-01.sql` 작성, backend `docs/qa-2026-11-01/`에도 보존. | READ ONLY 트랜잭션과 SELECT만 포함: 최근 14일 일별 기사·rewrite mode·retry, 감성/신뢰도 분포, 판단 방향·2일 초과 pending·마지막 채점·가상 결과 수. | 실행하지 않았으며 운영 수치는 확인 못 함. 사용자 직접 실행. https://github.com/Fiinsight/finsight-backend/pull/150 |
| P1-2 발췌 품질 | risk 패턴 부족, 리드와 같다는 이유로 위험 문장 누락, 종목코드·퍼센트 나열, 수준별 구분 부족. `llm_client.py`, `test_excerpt_quality.py`, 기존 품질 테스트 수정. | 부담·규제·변동성·제재·관세 등 위험과 다만/그러나/하지만 우선, 실제 없을 때만 약한 한계 안내. 초보자 코드 제거·목록 종목 수 한국어 요약·기존 사전 풀이, 일반 [사실]/[전망]. 원문 숫자 검증 유지. AI 전체 241개 통과. | EXCERPT는 생성 모델 리라이팅이 아니며 원문 발췌+규칙 변환의 한계가 있음. 개수 요약은 새 수치 추정을 피하려고 한국어 수사로 표현. https://github.com/Fiinsight/finsight-ai/pull/66 |
| P1-3 AI 활성화 | `AI_ENABLE_OPTIONS.md`에 Gemini 수집 전용/로컬 Ollama 배치의 비용·실패·검증 절차 작성. | 무료 티어의 모델/계정 한도와 앱의 하루 30회 설정은 다름을 명시. 현재 free 이미지에는 google-genai도 없으므로 변수만 켜는 것으로 끝나지 않음을 명시. | 모두 문서만. 키·환경변수·모델 다운로드·운영 배치 적용 없음. https://github.com/Fiinsight/finsight-backend/pull/150 |
| P1-4 표시 정직성 | REWRITTEN도 AI 여부 불명확, feedback mode 저장 누락. `LearningPanel`, `ChartDocentPanel`, 판단 상세·API 타입/정규화, backend `Judgement.FeedbackContent`·역직렬화·scheduler·response 수정. | REWRITTEN/EXCERPT/LLM/RULE/생성방식 불명확 구분. 기존 JSON에 mode가 없어도 AI로 가장하지 않음. mode round-trip·화면 테스트. | 기존 기록을 백필하지 않음. 예전 mode 없는 설명은 확인되지 않았다고 표시. https://github.com/Fiinsight/finsight-backend/pull/148 / https://github.com/Fiinsight/finsight-frontend/pull/174 |
| P1-5 이미지 pin | 운영 이미지 AI는 6aa00e9이며 로컬 경계/품질 개선이 미포함. 기존 미커밋 경계 작업을 별도 AI PR #64로 분리하고, Dockerfile.render-free pin을 514a47e로 변경하는 PR 준비. | AI 전체 테스트·backend 빌드/테스트 통과. 514a47e는 공개로 push된 AI 품질 수정 커밋이며 경계 작업도 포함. | 실제 Docker 이미지 build/운영 실행은 하지 않음. AI #64→#65→#66을 검토한 후 pin을 반영해야 함. https://github.com/Fiinsight/finsight-backend/pull/149 |

## P2

| 항목 | 원인 / 수정 파일 | 테스트 / 근거 | 한계 / PR |
|---|---|---|---|
| P2-1 요약 | 판단 화면이 공통 summary를 사용. `text.ts`, `JudgementScreen`에서 선택한 수준→프로필 수준의 첫 1~2문장 우선. 원문 fallback은 기자 바이라인·현황판·딜링룸 캡션 제거. | 선택 수준 우선·캡션 제외 회귀 테스트. 실제 로컬 판단 화면 확인. | 모든 언론사 사진 양식을 포괄하는 NLP가 아니라 보수적 규칙. https://github.com/Fiinsight/finsight-frontend/pull/175 |
| P2-2 기준 종가 | 당일/직전 거래일 기준을 화면에서 설명하지 않음. `NewsDetailResponse`에 현재 판단의 baseline/comparison 날짜 추가, 화면에 두 종가 기준·예정 비교일 표시. | 휴일/연말 거래일과 날짜 테스트. PARTIAL은 방향 일치지만 ±1% 이내임을 배너·상세에서 표시하고 방향 일치 집계에는 포함하는 기존 계약을 고정. | 장중에도 그날 종가 기준임을 명시. 실제 15:40 종가 공급 확정 여부는 별도 관찰 필요. https://github.com/Fiinsight/finsight-backend/pull/151 / https://github.com/Fiinsight/finsight-frontend/pull/175 |
| P2-3 데모 결과 | 표시된 가상 결과가 실제 적중으로 집계됨. `historyRecords.ts`, `HistoryItem`, 상세에 예시 배지와 집계 제외, backend 학습 통계 SQL에서도 해당 표식 제외. 정리 SQL 초안 작성. | 예시 label·hits=0·verdict=null 테스트. 운영 DB 삭제 없음. | 표식이 없는 다른 합성 데이터는 식별하지 않음. 삭제/백필은 사용자 결정. https://github.com/Fiinsight/finsight-backend/pull/152 / https://github.com/Fiinsight/finsight-frontend/pull/176 |

## P3

- **온보딩:** 수준·목표·학습 방식 3문항. 선택만으로 이동하지 않고 모두 다음 버튼 사용, 첫 문항 이전 숨김, 1/3부터 진행. 기존 5문항 응답의 저장/매핑은 호환 유지. 새 온보딩에서 어려움/세부 습관은 기본 설정을 사용한다. https://github.com/Fiinsight/finsight-frontend/pull/177
- **기록:** `AttendanceCard` compact 모드로 `이번 주 N/7 · 오늘 N/3` 한 줄, 날짜 선택을 펼치면 기존 필터 유지. https://github.com/Fiinsight/finsight-frontend/pull/178
- **뉴스 상세:** 프로필 수준을 기본으로 읽기 표시, 한 개 칩으로 초보/일반/분석 순환. 상단의 원문/쉽게읽기 이중 탭 제거, 원문은 하단 출처 링크. https://github.com/Fiinsight/finsight-frontend/pull/179
- **학습:** 기존 진행 수치와 접근성 기준 안내를 한 체크리스트형 영역으로 정리, ⓘ 세 개와 개별 카드 중첩 축소. 전체 학습 정보·복습·퀴즈는 유지한다. https://github.com/Fiinsight/finsight-frontend/pull/180
- **홈:** market/briefing의 기존 query key를 재사용하는 하나의 초기 skeleton, 10초 뒤 서버 준비 안내. 추가 keep-alive 없음. https://github.com/Fiinsight/finsight-frontend/pull/181
- **차트:** 기사 없음 상태에도 같은 날 지수 변동이 기존 종가 캐시에 있으면 수치를 표시하고 확정 원인으로 말하지 않는다. 없으면 확인 불가라고 표시하며 새 외부 요청을 만들지 않음. https://github.com/Fiinsight/finsight-backend/pull/153
- **공통:** 완료 화면의 반복 투자 권유 아님 문구를 제거하고 판단 화면 하단에 유지. 기존 약관은 유지. 도슨트의 인과관계 한계 안내는 투자 권유 문구와 목적이 달라 유지했다.

## 실제 UI·로컬 흐름

스크린샷: `qa-screenshots/ui-simplify-2026-11-01/`에 온보딩·기록·뉴스 각각 before/after 375×844, 390×844, 1440×1000(18장)과 상세 새로고침 1장. PNG를 직접 확인했으며 작은 화면에서 주요 버튼·칩·출석 한 줄이 잘리지 않는다.

https://github.com/Fiinsight/finsight-frontend/pull/183

실제 실행: 로컬 `npm run web`(8081), Spring(18080), FastAPI(18001), 별도 PostgreSQL(15432)/Redis(16379). `.env.qa.local`의 계정으로 **로컬에만** 계정을 만들어 UI 로그인했다. 화면 fixture는 `LOCAL_QA_FIXTURE`로 명시했으며 실제 금융기사/실시간 가격으로 가장하지 않았다. 외부 시세 URL은 loopback의 비활성 포트로 설정했다.

검증: 홈 → 뉴스 → 판단 제출 → `/news/1/judgement/done?judgementId=1` → 완료 새로고침 → 기록 → `/history/judgements/1` → 상세 새로고침. 잘못된 ID·없는 ID 안내. 브라우저 pageerror 0. 운영 API 읽기 시도는 HTTP403이었으며 운영 전체 흐름으로 간주하지 않는다. 실제 iPhone/Android·카카오 인증은 이번에 검증하지 않았다.

## 삭제 검토

`learning/DemoQuizCatalog.java`는 `scripts/seed_demo_personas/seed.py:85`의 `demoQuizCatalog` Gradle 호출과 `DemoQuizCatalogTest`에서 사용한다. 실제 미사용 코드라는 전제가 성립하지 않아 보존했다.

## 남은 사용자 결정·확인

1. PR을 선행 base 순서로 검토할지 결정. 이번에 main을 머지하지 않았다. 첫 backend/frontend PR은 기존 배포 작업 브랜치를 base로 하므로 그 브랜치의 main 반영 정책도 별도 결정해야 한다.
2. 2027 KRX 확정 특별 휴장일 공지가 나오면 재대조. 현재 확인한 것은 공식 공휴일 자료와 일반 휴장 규칙이다.
3. **KIS 15:40 당일 종가 확정 여부는 확인 못 했다.** 검사 시점이 비거래일이라 시간대별 실제 값을 재현할 수 없다. 15:40/16:00 동일 일봉의 값·공급 상태를 다음 거래일에 관찰한 후 cron 시작을 16:00으로 옮길지 결정한다. 근거 없이 cron을 바꾸지 않았다.
4. Gemini 무료 티어/로컬 Ollama 선택과 이미지 의존성 변경, 비밀 키/토큰, Render 설정과 배포는 별도 결정. 현재 모든 실제 LLM 스위치는 변경하지 않았다.
5. 읽기 전용 SQL 초안은 사용자가 직접 실행한다. 일일 고정 브리핑 스냅샷·합성 기록 DB 삭제/백필은 이번에 도입하지 않았다.
6. Expo 도구 체인 moderate 11개는 남아 있다. high/critical은 제거했고 강제 major 업그레이드는 하지 않았다. 실제 iOS/Android 기기 검증은 별도로 필요하다.

## 전체 PR 목록

| 저장소 | 브랜치 | base | PR |
|---|---|---|---|
| ai | `fix/ai-service-boundary` | `main` | https://github.com/Fiinsight/finsight-ai/pull/64 |
| ai | `fix/article-grounded-points` | `fix/ai-service-boundary` | https://github.com/Fiinsight/finsight-ai/pull/65 |
| ai | `fix/p1-2-excerpt-quality` | `fix/article-grounded-points` | https://github.com/Fiinsight/finsight-ai/pull/66 |
| backend | `fix/auth-hardening` | `codex/single-free-instance` | https://github.com/Fiinsight/finsight-backend/pull/141 |
| backend | `fix/ai-service-boundary-client` | `fix/auth-hardening` | https://github.com/Fiinsight/finsight-backend/pull/142 |
| backend | `fix/article-evidence-backend` | `fix/ai-service-boundary-client` | https://github.com/Fiinsight/finsight-backend/pull/143 |
| backend | `fix/p0-1-trading-calendar` | `fix/article-evidence-backend` | https://github.com/Fiinsight/finsight-backend/pull/144 |
| backend | `fix/p0-2-feedback-catchup` | `fix/p0-1-trading-calendar` | https://github.com/Fiinsight/finsight-backend/pull/145 |
| backend | `fix/p0-4-news-catchup` | `fix/p0-2-feedback-catchup` | https://github.com/Fiinsight/finsight-backend/pull/146 |
| backend | `fix/p0-5-briefing-ranking` | `fix/p0-4-news-catchup` | https://github.com/Fiinsight/finsight-backend/pull/147 |
| backend | `fix/p1-4-feedback-provenance` | `fix/p0-5-briefing-ranking` | https://github.com/Fiinsight/finsight-backend/pull/148 |
| backend | `fix/p1-5-ai-image-pin` | `fix/p1-4-feedback-provenance` | https://github.com/Fiinsight/finsight-backend/pull/149 |
| backend | `fix/p1-ai-options` | `fix/p1-5-ai-image-pin` | https://github.com/Fiinsight/finsight-backend/pull/150 |
| backend | `fix/p2-judgement-dates` | `fix/p1-ai-options` | https://github.com/Fiinsight/finsight-backend/pull/151 |
| backend | `fix/p2-demo-statistics` | `fix/p2-judgement-dates` | https://github.com/Fiinsight/finsight-backend/pull/152 |
| backend | `fix/p3-chart-market-context` | `fix/p2-demo-statistics` | https://github.com/Fiinsight/finsight-backend/pull/153 |
| frontend | `fix/expo-dependencies` | `codex/render-free-deploy` | https://github.com/Fiinsight/finsight-frontend/pull/170 |
| frontend | `fix/level-investment-points` | `fix/expo-dependencies` | https://github.com/Fiinsight/finsight-frontend/pull/171 |
| frontend | `fix/p0-2-feedback-poll` | `fix/level-investment-points` | https://github.com/Fiinsight/finsight-frontend/pull/172 |
| frontend | `fix/p0-3-judgement-links` | `fix/p0-2-feedback-poll` | https://github.com/Fiinsight/finsight-frontend/pull/173 |
| frontend | `fix/p1-4-generation-labels` | `fix/p0-3-judgement-links` | https://github.com/Fiinsight/finsight-frontend/pull/174 |
| frontend | `fix/p2-judgement-context` | `fix/p1-4-generation-labels` | https://github.com/Fiinsight/finsight-frontend/pull/175 |
| frontend | `fix/p2-demo-labels` | `fix/p2-judgement-context` | https://github.com/Fiinsight/finsight-frontend/pull/176 |
| frontend | `fix/p3-onboarding` | `fix/p2-demo-labels` | https://github.com/Fiinsight/finsight-frontend/pull/177 |
| frontend | `fix/p3-history-compact` | `fix/p3-onboarding` | https://github.com/Fiinsight/finsight-frontend/pull/178 |
| frontend | `fix/p3-news-reading` | `fix/p3-history-compact` | https://github.com/Fiinsight/finsight-frontend/pull/179 |
| frontend | `fix/p3-learning-checklist` | `fix/p3-news-reading` | https://github.com/Fiinsight/finsight-frontend/pull/180 |
| frontend | `fix/p3-home-loading` | `fix/p3-learning-checklist` | https://github.com/Fiinsight/finsight-frontend/pull/181 |
| frontend | `fix/p0-2-poll-due-transition` | `fix/p3-home-loading` | https://github.com/Fiinsight/finsight-frontend/pull/182 |
| frontend | `fix/qa-evidence` | `fix/p0-2-poll-due-transition` | https://github.com/Fiinsight/finsight-frontend/pull/183 |

## 증거와 환경 한계

- 로컬 전체 Gradle 테스트에서 Desktop의 이전 중복 class/report와 XML 결과 쓰기 문제가 있어 원본을 보존하고 새 class 및 `/tmp` 보고서 경로로 검증했다. 코드 실패를 통과로 숨기지 않았으며 최종 실행은 BUILD SUCCESSFUL이다.
- 안전한 로그·결과 복사본은 `/Users/subin/Documents/핀사이트/output/finsight-qa-followup-2026-11-01/`에 보존한다.
- 스크린샷·SQL·보고서에 토큰·비밀번호·환경파일을 넣지 않았다. 운영 DB 연결이나 쓰기 명령은 실행하지 않았다.

## GitHub 후속 확인

기능·화면 증거 PR 30개의 최신 head CI가 모두 성공한 것을 확인했다(backend test/prod-migrations, frontend typecheck/tests/web export, AI pytest). 보고서 PR은 아래 링크이며 문서 커밋의 검사 상태는 별도 CI snapshot으로 보존한다.

- 보고서 PR: https://github.com/Fiinsight/finsight-backend/pull/154
- CI snapshot: `/Users/subin/Documents/핀사이트/output/finsight-qa-followup-2026-11-01/pr-statuses.json`


## 2차 코드 점검 후속

P0-6/P1-6/P2-4/P2-5의 변경·검증·PR·운영 결정은 [2차 보고서](../qa-round2/QA_FIX_REPORT_2026-11-01_ROUND2.md)에 정리했습니다. 로컬 원장 SQL 승인 전에는 새 버전의 BKT 반영이 연습 모드로 일시 중지된다는 배포 조건을 먼저 확인하세요.
