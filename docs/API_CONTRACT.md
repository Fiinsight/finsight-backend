# FinSight API Contract

The frontend talks to the Spring Boot backend only. The backend calls the AI service internally.

## Mobile App -> Backend

### Get Today's Briefing

```http
GET /api/briefings/today
```

Response:

```json
[
  {
    "id": 1,
    "title": "반도체 수출 회복세, 대형주 실적 기대감 확대",
    "summary": "반도체 업황 회복 신호가 이어지며 국내 대형 기술주의 실적 기대가 커지고 있습니다.",
    "importanceReason": "수출과 실적 전망은 주가 방향을 판단하는 핵심 근거입니다.",
    "importanceReasonBeginner": "기사에서 확인된 내용은 ...예요. 실제 영향은 관련 매출 자료를 더 확인해야 해요.",
    "importanceReasonNormal": "기사에서 확인되는 사실은 ...입니다. 실제 실적 연결 여부는 후속 발표를 확인해야 합니다.",
    "importanceReasonAnalyst": "수혜 경로: ... 확인할 변수: ... 위험 요인: ...",
    "relatedSymbol": "005930",
    "sentimentHint": "POSITIVE"
  }
]
```

### Submit Judgement

```http
POST /api/judgements
Content-Type: application/json
```

Request:

```json
{
  "newsId": 1,
  "choice": "UP",
  "reason": "수출 회복은 실적 전망에 긍정적이라고 봤습니다."
}
```

Response:

```json
{
  "newsId": 1,
  "choice": "UP",
  "feedback": "판단 근거가 뉴스의 실적 영향과 연결되어 있습니다."
}
```

## Backend -> AI Service

### Rewrite News

```http
POST /ai/news/rewrite
```

The response keeps `importance_reason` for backward compatibility and also
returns `importance_reasons` with `beginner`, `normal`, and `analyst` keys.
The backend exposes the same three levels as
`importanceReasonBeginner`, `importanceReasonNormal`, and
`importanceReasonAnalyst`. The level-specific value must be selected for the
active reading-level tab; clients must not synthesize missing values.

Collection request adds `allow_llm` (default true); every newly collected usable article sets it true.
The AI daily budget decides whether to rewrite or return EXCERPT. A stored
REWRITTEN article is never regenerated.

Response adds `retryable` (default false). A quota/429 fallback has
`mode=EXCERPT,retryable=true`; the backend stores `rewrite_retry_pending` and
retries only these marked articles at the next normal collection. Ordinary
network, JSON, sentence, number-validation and cap failures stay EXCERPT.

AI rewrite collection permits Gemini only. Calls are serialized with a
minimum 6-second start interval; the KST daily limit is 400. Gemini rewriting uses a separate
rewriteProcessCalls counter; the paid-path process limit remains 20. 429 attempts do not consume the daily reservation;
three quota errors disable rewriting until the next KST day. Other AI features
retain USE_REAL_LLM=false and DOCENT_WITH_LLM=false.

`GET /health` additionally returns `rewriteEnabled`, `provider`, `rewriteDay`,
`rewriteSuccessCount`, `rewriteFailureCount`, `rewrite429Count`,
`rewriteUsedCalls`, `rewriteDailyLimit`, `rewriteStoppedToday`,
`rewriteLast429At`, `rewriteMinIntervalSeconds`, `processCallLimit`,
`processCalls`, `rewriteProcessCalls`. No credentials are included.

### Explain Term

```http
POST /ai/terms/explain
```

### Generate Judgement Feedback

```http
POST /ai/feedback/judgement
```

## Record history date ranges

Both endpoints require authentication and only return the current user's records:

```http
GET /api/judgements/history?from=2026-09-30&to=2026-10-07
GET /api/article-notes?from=2026-09-30&to=2026-10-07
```

`from` and `to` are optional as a pair, strictly `YYYY-MM-DD`, interpreted at
midnight in Asia/Seoul. The start is inclusive and the end is exclusive. A range
must span 1–7 calendar days. Missing one parameter, an invalid date/format,
`from >= to`, or a range longer than 7 days returns HTTP 400.

Judgements are filtered/sorted by `createdAt`, notes by `createdAt`; ties are
sorted by ID descending. Editing a note does not move it to a different date
bucket. Filtered requests return every record in that period without the legacy
50-note limit. Response bodies remain JSON arrays with the existing fields.

Judgement fields: `judgementId`, `newsId`, `newsTitle`, `choice`, `reasonText`,
`createdAt`, `actualDirection` (nullable), `actualChangePercent` (nullable),
`feedbackText` (nullable), `feedbackGeneratedAt` (nullable). Notes: `id`, `newsId`,
`newsTitle`, `source`, `content`, `createdAt`, `updatedAt`.

For range requests, if any record exists strictly before `from`, the response has:

```http
X-Previous-Record-Date: 2026-09-03
```

This is the most recent earlier record's **KST date**, separately calculated for
each endpoint and user. The header is omitted when no earlier record exists,
including on unfiltered legacy responses. CORS exposes this header to the web
client. An empty period can still have the header when older records exist.

The client initially requests today and the preceding six days. Clicking
“이전 기록 더 보기” uses the header date plus the preceding six days for the
next single request, skipping intervening empty weeks. There is no automatic
pagination or infinite scrolling; absence of the header hides the button.

Without parameters the existing behaviour is unchanged: all judgement history,
and the latest 50 article notes sorted by `updatedAt` descending. The existing
`/api/judgements/history/page` is unchanged.

Group summaries distinguish total judgements, confirmed hits and pending results.
Pending and UNKNOWN outcomes are excluded from hits/misses; UNKNOWN is shown as
unavailable. Attendance filtering uses actual dates of the current KST Monday–
Sunday week; future dates cannot be selected. Selecting the same date toggles the
filter off for both list tabs.

## News learning quiz answers

`GET /api/learning/news/{newsId}?level=beginner|normal|analyst` returns `question: {term,prompt,options}` without a correct answer or index.

- beginner: 용어 정의 문제. 같은 분야 정의 두 개를 오답으로 사용하며 부족하면 question=null.
- normal: 정제 본문에서 용어와 수치가 함께 있는 관측 문장의 증가/감소/변화 없음 문제. 모호한 방향·전망은 제외하고 판정할 수 없으면 beginner로 대체.
- analyst: 원문 사실 문장 두 개와 기사에 없는 문장 한 개 중 없는 사실을 찾는 문제. 충분한 짧은 사실 문장이 없으면 beginner로 대체. 원문 문장의 숫자나 조건을 잘라내지 않음.

문항과 보기 순서는 newsId·term seed로 재현합니다. 채점은 같은 생성 규칙으로 서버에서 수행하고 실제 생성된 수준을 learning_progress.level에 저장합니다. 읽기 summary의 수준은 요청한 level을 유지합니다.

`POST /api/learning/news/{newsId}/answers` request: `{level,term,answerIndex}`. Response:

```json
{"correct": false, "definition": "채점 후 정답 보기 문구", "message": "원문 수치와 증가·감소·유지 표현을 확인해보세요."}
```

`correctIndex`는 모든 응답에서 제거합니다. `definition`은 채점 후에만 반환되는 정답 보기 문구이며 초보자는 기존 용어 정의입니다. 프런트는 문구를 보기와 비교해 채점 결과를 표시합니다. 잘못된 term/index/level은400입니다.

## Learning progress — implemented (schema unchanged)

```http
GET /api/learning/progress
Authorization: Bearer <token>
```

```json
{
  "today": {"answeredCount": 0, "correctCount": 0, "accuracy": null},
  "week": {"from": "2026-10-05", "to": "2026-10-12", "answeredCount": 0, "correctCount": 0, "accuracy": null},
  "activityStreakDays": 0,
  "completedNewsIds": [],
  "totalDecidedCount": 0,
  "weakAreas": [],
  "goal": {"type": "read1", "achievedToday": false, "streakDays": 0},
  "levelSuggestion": null
}
```

The final user instruction is to **keep the schema unchanged**: no learning_attempt table or migration. Quiz counts and accuracy use the latest learning_progress row per user/news/term. The UI must explain “저장된 마지막 기록 기준”; these are not total attempt counts. accuracy is a fraction in0–1, or null if the denominator is0. Dates are KST; the week starts Monday at00:00 and ends the next Monday, exclusive.

`activityStreakDays` counts consecutive KST dates with a judgement creation, article_note creation or the last saved learning_progress answer, anchored at today if active today, otherwise yesterday. No activity today/yesterday yields0. UI label is “학습 활동 연속일” with the explanation “판단·메모·퀴즈를 한 날 기준” Quiz re-answering may replace an older quiz activity date; judgement/note creation dates are retained. Note updates do not count as new creation dates. No `basis` response field is added.

`completedNewsIds` are distinct news IDs answered within the past30days, sorted by latest answer descending and limited to100. `totalDecidedCount` and `weakAreas` use only the current user's judgements created within the recent30days, with feedback/actual percent present, comparison_date nonnull, direction UP/NEUTRAL/DOWN, and a stored related symbol matching the current title/body matching rules. Pending/UNKNOWN/old same-day results/invalid mappings are excluded. Each weak area has `category`, `decidedCount`, `hitCount`, `recentNewsIds`, `sampleSize` (=decidedCount). Return only fields with ≥5 samples and ≥60% mismatches, worst first, maximum3. Empty fields are hidden in the UI.

`levelSuggestion` is null, normal, or analyst. Recent30day latest answers at the current level must include ≥10 distinct user/news/term questions, ≥5 distinct terms, and ≥80% correct. Analyst has no promotion. Suggestions never change the profile; only an explicit user action calls PUT /api/profile/learning-level.

`goal` is `{type: read1|memo|reviewJudgement|minutes5, achievedToday:boolean, streakDays:number}`. All dates use KST. read1 = ≥1 unique article read; memo = ≥1 article_note created; reviewJudgement = ≥1 judgement created OR judgement detail opened; minutes5 = ≥3 quiz+note+judgement stored activities. UI states “활동 3개 = 5분 학습”. Streak counts consecutive achieved dates starting today when achieved, otherwise yesterday; absent both yields0. The selected goal is applied to available past activities, not a history of past goal settings. Re-answer/revision/deletion can change older totals because existing latest records are used.

Optional progress query `readDays=2026-10-11,2026-10-12&reviewDays=2026-10-12` supplies this signed-in device's local reading/detail-open dates. Each list has at most366 ISO dates and4025 characters; malformed input returns400, future dates are ignored, duplicates count once. They are used only in the response and are **not persisted**. Missing readDays means read1 cannot be confirmed; missing reviewDays still permits server judgement creations. Local dates do not affect grading or reward/payment. Device changes can change local-only results; server reading synchronization is proposal-only.

`GET /api/learning/terms/today` (authenticated) returns up to 3 `{term,definition,newsId,title,reason}` entries for the authenticated user. Candidates are CONCEPT terms from the same BriefingService core 3 articles as home; if fewer than 3 eligible terms remain, expand to the last 24 hours of articles. Prioritize last-wrong/effective probability <0.5, then unseen, then learning concepts. Exclude effective probability >=0.8 with >=3 attempts (last-wrong takes precedence). Reasons: 지난번에 틀린 개념 / 처음 보는 개념 / 익히는 중인 개념. No unrelated dictionary filler or external API/LLM call. The terms pain point remains the display condition, with definition and source-article navigation.

## News selection and empty-body handling

Briefing retains today-first priority, then uses title keyword weight3 + body weight1 + broad-market headline bonus2 − notice penalty8. Nested keywords are counted once, longest first. The first3prefer different categories among today's candidates; remaining slots use the same ranking. More pages continue after those3without duplicates. The ranking has an additional local60-second cache (one KST date key); existing external caches and their TTLs remain unchanged. Category uses industry/overseas headline context first, existing headline rules next, and body when the headline is generic.

Empty article bodies are excluded from briefing/more and local news-search candidates. Detail keeps id/title/source/link/publishedAt but returns empty body/rewrites/reasons/terms. Client shows “원문 링크에서 확인하세요” and does not display a lesson, quiz, similar-news or judgement action for a missing body. In-app article excerpts use the server600-character limit.

Manual feedback generation is owner-scoped and observes the comparison-day/market-close gate. Current quotes cannot establish an older comparison date; those outcomes remain pending rather than being fabricated. KOSPI/KOSDAQ use index quotes0001/1001. Missing, blank or invalid quote numbers are unavailable, rather than0%. No authorization/limiter/cache protections are weakened.

## 승인 대기 변경안: 기록 결과·급등락 후보 (2026-10-06)

기존 필드는 유지합니다. 승인 전 응답 계약은 변경하지 않습니다.

- `GET /api/charts/{symbol}`의 `moveInsights[]`에 `candidateNews: NewsMarker[]` 추가(최대 3건, 첫 항목이 기존 newsId/newsTitle 대표).
- 차트의 뉴스 항목에 `relationType: "DIRECT" | "MARKET"` 추가. 직접 관련 기사 우선, 시장 뉴스 분리 표시에 사용.
- `GET /api/judgements/history`(기존/기간/페이지)에 `comparisonDate: string|null`(KST YYYY-MM-DD), `judgementClose: number|null`, `comparisonClose: number|null` 추가. 종가를 확보하지 못하면 null. 과거 확정 값만으로 가격을 역산하지 않음.
- 판단일 장중 제출도 판단일 종가 대비 다음 거래일 종가로 비교. 휴장일 제출은 직전 거래일 종가를 기준으로 다음 거래일 종가와 비교. 주말 및 한국 증시 휴장일 제외. 비교일 종가 미확보 시 결과 대기.
- 일봉 뉴스 후보 기간은 전 거래일 15:30부터 해당일 15:30(KST), 주봉은 해당 주 전체. 후보 없음/수집 시작 이전은 explanation 문구로 구분.
- 일봉 ±3%, 주봉 ±5% 또는 기존 변동성 기준 중 하나라도 충족하면 급등락 포함. 절대 등락률 큰 순서 최대 5건.

DB 스키마를 변경하여 종가를 저장할지, 별도 조회로 제공할지는 승인 답변 후 기존 구조를 확인하고 결정합니다. 스키마 변경이 필요하면 별도 승인을 받습니다.

## 승인 적용: 판단 종가 저장·중복 수정·뉴스 후보 (2026-10-06)

- `judgement.comparison_date date`, `judgement_close numeric(24,8)`, `comparison_close numeric(24,8)` nullable. 로컬 적용은 V6 SQL 수동 1회 실행(기존 ddl-auto=update/Flyway=false 유지); Flyway 활성 환경에서는 V6 마이그레이션. 기존 확정 판단의 새 가격 컬럼은 null을 유지하고 역산하지 않음.
- 결과 확정은 `created_at` 일치 + 기존 actual_direction/feedback_generated_at null 조건부 UPDATE. 확정된 결과를 재호출로 덮어쓰지 않음. 종가 조회는 스케줄러만 수행하고 기록/상세 응답은 저장값만 읽음.
- 장중(09:00~15:30)·장후 제출 모두 판단일 종가 대비 다음 거래일 종가. 휴장일 판단은 직전 거래일 종가 대비 다음 거래일 종가. KST 15:40 이후 해당 비교일 또는 그 이후에 처리. 일봉에 정확한 기준일/비교일 종가가 없으면 대기. 2026 휴장일 달력 적용; 아직 확인되지 않은 다른 연도는 결과를 추정하지 않고 대기.
- 종목 일봉과 KOSPI/KOSDAQ 지수 일봉 모두 날짜 지정 조회. 지수 차트 UI 자체는 준비 중 안내 유지.
- `POST /api/judgements`: 같은 사용자/기사의 가장 최근 미확정 판단은 choice/reason/created_at 수정. 기존 응답 `feedback="판단을 수정했어요"`. 확정 결과가 있으면 **409** `이미 결과가 나온 판단이에요`. 고유 제약은 테스트 계정 정리 승인/실행 후 추가 예정.
- 차트 `moveInsights[].candidateNews` 최대3건, `relatedNews[]/newsMarkers[]/candidateNews[]` 항목 `relationType=DIRECT|MARKET`. 첫 후보가 대표. 시장 대표는 explanation에 “시장 전체 흐름”. 지점별 DIRECT/MARKET/NONE 도슨트를 제공하며 아래 C1/C2 계약을 따릅니다.
- 9/28 삼성전자 후보 기간: **9/23 15:30~9/28 15:30 KST**(9/24~27 휴장). 77번 11:23 후보, 91번16:38/100번18:00 제외. 해외59 제외.
- 기록 응답 추가 필드: `comparisonDate` KST YYYY-MM-DD|null, `judgementClose` number|null, `comparisonClose` number|null. 기존/기간/페이지 모두 같은 DB 필드 사용.

근거: [한국천문연구원 2026 월력요항 발표](https://www.kasi.re.kr/kor/post/newsMaterial/32031), [한국투자증권 공식 지수 일봉 예제](https://github.com/koreainvestment/open-trading-api/blob/main/backtester/kis_backtest/providers/kis/data.py).


## QA 오류 수정 계약 (2026-10-12)

- `POST /api/auth/signup`: Bean Validation 실패는 HTTP 400 및 `{ "message": "첫 오류 메시지", "errors": [{ "field": "email", "message": "이메일 형식이 올바르지 않아요." }] }`를 반환합니다. 닉네임은 2~30자, 비밀번호는 8~72자입니다.
- `DELETE /api/auth/me`: 인증된 본인 계정을 삭제하고 HTTP 204를 반환합니다. 한 트랜잭션에서 본인 소유 learning_progress, article_note, daily_note, judgement를 먼저 삭제합니다. 다른 사용자와 news는 삭제하지 않습니다.
- `GET /api/auth/kakao/url?redirectUri=http://localhost:8081/auth/kakao`: 복귀 주소를 정확 일치 허용 목록으로 검증하고, 서버에서 발급한 5분 TTL nonce를 OAuth `state`로 보냅니다. 기본 허용 목록에는 localhost:8081/auth/kakao가 포함됩니다. callback은 nonce를 원자적으로 한 번 소비하며, 잘못되었거나 재사용된 state는 앱 callback에 `error=invalid_state`로 복귀합니다. 실제 카카오 로그인은 QA에서 실행하지 않습니다.
- `POST /ai/terms/explain`: 기존 `term`, `article_context`에 선택 필드 `known_definition`(최대 2000자)을 추가합니다. 백엔드가 알고 있는 정의를 우선 사용하며, 문맥 설명에는 정의를 덧붙이지 않습니다.
- `POST /ai/news/rewrite`: `importance_reason` 및 `importance_reasons`의 값은 null일 수 있습니다. 투자 포인트 후보 용어가 없으면 null이며 화면에서 숨깁니다.
- `GET /api/charts/{symbol}`: `symbolName`을 포함합니다. 가격·등락률은 기존 인기 종목/현재가 캐시만 읽고 전일 종가 대비로 반환하며, 신규 quote 호출은 하지 않습니다. 미확인 가격 0은 화면에서 확인 불가로 표시합니다. D/W 캐시 키는 interval을 0으로 정규화합니다.
- 분봉은 같은 응답 거래일에서 09:00까지 역방향 페이징하며 최대 14페이지로 제한합니다(종일 390분 확보를 위한 상한). 기존 캐시 TTL 및 KIS 요청 제한은 유지합니다. 중간 제한/실패/빈 페이지에서 중단하고 확보한 캔들만 반환합니다.
- `GET /api/judgements/history/page?page=0&size=20`을 프런트 판단 기록의 이전 기록 조회에 사용합니다.


## 수집 리라이팅·기사 기반 판단 피드백 (A–D, 2026-10-12)

### 수집 리라이팅

`POST /ai/news/rewrite` 수집 요청:

```json
{"title":"기사 제목", "body":"정제 원문", "article_key":"기사 원문 URL"}
```

응답은 `beginner`, `normal`, `analyst` 세 문자열과 `mode: "REWRITTEN" | "EXCERPT"`를 포함합니다. 기존 `level` 요청과 `summary` 응답도 유지합니다. NewsAssembler는 기사당 HTTP 요청 한 번으로 세 칸과 `news.rewrite_mode`를 저장합니다. 저장 요약 중 하나라도 값이 있으면 재요청하지 않습니다. 기존 기사 재생성/일괄 갱신은 하지 않습니다.

- `REWRITE_WITH_LLM=false`가 기본값입니다. USE_REAL_LLM과 독립입니다. USE_REAL_LLM을 켜도 수집 리라이팅이 켜지지 않습니다.
- `MAX_DAILY_REWRITE_CALLS=50`: KST 일자별 상한. `REWRITE_LEDGER_PATH=data/rewrite_calls.sqlite3`의 영속 SQLite 장부가 기사별 시도와 결과를 원자적으로 저장합니다. 실패/검증 실패도 한 번의 시도로 기록하며 재호출하지 않습니다. 예산에 도달하면 EXCERPT를 반환합니다.
- 기존 `MAX_REAL_LLM_CALLS_PER_PROCESS=20`도 계속 적용하므로 한 프로세스에서 실제 이용 가능한 상한은 더 작은 쪽입니다. 기존 상한을 늘리거나 끄지 않았습니다.
- 원문과 요약의 숫자 토큰을 단위/소수까지 비교합니다. 초보자 4~5문장 해요체, 일반 3~4문장, 분석 세 칸 및 칸 사이 중복을 검증하며 실패 시 세 수준 모두 EXCERPT입니다.
- 분석 LLM JSON은 `{ "evidence": "...", "figures": "...", "risk": "..." }`입니다. API는 이를 세 칸 텍스트로 변환합니다.
- `/api/learning/news/{id}?level=beginner`는 provenance가 있는 저장 결과를 우선 반환합니다. 새 리라이팅은 REWRITTEN, 발췌는 EXCERPT입니다. 기존 provenance 없는 자료는 무료 발췌 API로 처리합니다. AI를 쓸 수 없는 경우 UNAVAILABLE을 유지합니다.
- `V7__add_news_rewrite_mode.sql`은 nullable provenance 컬럼 추가 초안이며 이번 작업에서 DB에 실행하지 않았습니다. 기존 요약을 LLM 리라이팅으로 임의 분류하지 않습니다.

### 판단 피드백

`POST /ai/feedback/judgement`:

```json
{
  "news_id": 153,
  "user_choice": "DOWN",
  "user_reason": "실적 부담을 예상했어요",
  "market_result": "DOWN",
  "news_title": "기사 제목",
  "news_summary": "정제 요약, 최대 600자",
  "related_symbol": "035720",
  "related_symbol_name": "카카오",
  "actual_change_percent": -1.76,
  "judgement_close": 100,
  "comparison_close": 98.24,
  "comparison_date": "2026-10-07"
}
```

`market_result`는 필수입니다(UP/NEUTRAL/DOWN/UNKNOWN). 누락을 NEUTRAL로 임의 처리하지 않습니다. 나머지 추가 필드는 이전 호출자 호환을 위해 선택이며 스케줄러는 실제 비교 데이터를 보냅니다. 응답 스키마는 기존 is_aligned/feedback/next_learning_point/reasons를 유지합니다.

무료 규칙 및 실제 LLM 프롬프트에 종목·날짜·변동률·종가, 근거 유무, 기사 핵심, 소폭 변동(절댓값 1.0% 이하), 지수 대상과 인과 단정 금지를 반영합니다. 실제 LLM은 USE_REAL_LLM로만 켜지며 기본 false는 유지합니다.

### 과거 기록과 매핑

comparison_date가 NULL인 확정 기록은 이전 방식 안내를 표시하고 적중/약한 분야 집계에서 제외합니다. 새 종목 매핑은 단어 경계, 본문 반복, 공공기관 제목 필터를 적용합니다. 기존 news/judgement 값은 수정하지 않습니다. 정정 SQL은 별도 보고서의 초안만 제공합니다.

## Onboarding personalization — M1/M2/M5

`GET /api/profile/onboarding` and the existing onboarding save response add `learningPainPoint: terms|overload|evidence|reflection` and `goalType: read1|memo|reviewJudgement|minutes5` alongside answers/learningLevel/learningPace/learningFocus/dailyGoal/completedAt. New saves persist app_user.learning_pain_point and app_user.goal_type (nullable VARCHAR30 columns, V8 draft); existing null values derive from saved answers/dailyGoal on read, with no bulk UPDATE. Difficulty does not alter the original experience-to-level or interest/goal-to-focus rules. Default values are terms/read1. Korean dailyGoal labels remain for display.

Difficulty: 용어→terms, 정보→overload, 근거→evidence, 기록→reflection. Goal: 뉴스 하나 읽기→read1, 기사 메모 남기고 복습하기→memo, 투자 판단 돌아보기→reviewJudgement, 매일5분 이어가기→minutes5.

- terms: raw article term underlines enabled by default with a switch, today's3 dictionary terms.
- overload: home extra news collapsed, article defaults to easy reading unless explicitly navigated to raw/level.
- evidence: recommended reason input plus two distinct article summary sentence chips quoting the original text.
- reflection: completion memo action scrolls to article notes, History defaults to notes unless navigation explicitly selects judgements.
- focus only changes home helper copy; the same core3 news selection remains.

`POST /api/terms/explain` accepts optional `level: beginner|normal|analyst` (default normal). Backend forwards level to `/ai/terms/explain`. Beginner adds an analogy; analyst uses the original contextual numeric sentence and its comparison basis, or states missing quantitative data. Personalization is local **after** the same base-cache lookup; level is not part of the base cache key. No extra paid call. Without a newsId/local AI availability, the backend returns a local explanation and does not request an external explanation.

`POST /ai/feedback/judgement` adds optional `level` (default normal). Scheduler sends the user's saved level (beginner when unset). Beginner defines 종가·변동률; analyst emphasizes percent/closing prices/comparison criteria. The real prompt carries level with the same factual context and safeguards; USE_REAL_LLM remains false by default and no extra call is added.

### Server reading synchronization — proposal only, not implemented

If synchronization is later approved, propose `article_read(user_id FK app_user,news_id FK news,read_date DATE KST,first_read_at TIMESTAMPTZ, PRIMARY KEY(user_id,news_id,read_date))` and an owner-only idempotent POST `/api/news/{id}/read`, called only after verified article content is shown. A `(user_id,read_date)` index supports daily counts/streaks. Do not trust a request user_id; use JWT principal. Existing local records would need an explicitly approved import policy. **No table, endpoint, backfill or reading migration is created in this task.**


## C1/C2 · 지점별 차트 도슨트 (2026-10-13)

`GET /api/charts/{symbol}?period=D|W|MINUTE&interval=5&level=beginner|normal|analyst`

- `level` 생략 시 로그인 사용자의 저장된 읽기 수준(비로그인 normal). 기존 시세 캐시 키와 호출 상한은 유지합니다.
- 모든 `moveInsights[]`에 `docent` 추가. `relation=DIRECT|MARKET|NONE`, `whatHappened`, `possibleReason`, `marketImpact`, `newsId`, `newsTitle`, `source`, `mode=RULE|LLM`.
- 차트 전체 `docent`는 반환된 지점 중 최대 `|changePercent|` 지점의 **동일 도슨트**입니다. 지점이 없으면 null. `whyItMoved`는 `possibleReason`의 하위호환 별칭입니다.
- DIRECT/MARKET 결정과 최대 3건 후보 선별은 기존 분류를 재사용합니다. 제목 감성 키워드는 도슨트 계산에만 적용하며 기존 news 레코드를 수정하지 않습니다.
- MARKET 지수 수치는 기존 `kisDailyCandles` 캐시의 실제 해당일/직전 거래일 종가가 있을 때만 표시합니다. 도슨트를 위한 추가 시세 호출은 없습니다. 주봉·분봉에는 일봉 지수 수치를 붙이지 않습니다.
- 백엔드 도슨트 캐시: `(symbol, date, period, 대표 newsId, level)`, TTL 14일·최대 5,000건. 기존 캐시 TTL은 그대로입니다. 분봉과 NONE은 백엔드 규칙 설명만 사용합니다.

`POST /ai/chart/docent` (내부 서비스)

```json
{
  "symbol": "005930",
  "symbol_name": "삼성전자",
  "date": "2026-10-07T00:00:00",
  "change_percent": 3.25,
  "period": "D",
  "level": "beginner",
  "news": [{
    "news_id": 1,
    "title": "삼성전자 호실적 발표",
    "summary": "영업이익이 전년 동기보다 12.5% 증가했다.",
    "published_at": "2026-10-07T10:00:00+09:00",
    "relation": "DIRECT",
    "source": "보도"
  }],
  "index_name": "코스피",
  "index_change_percent": null
}
```

- 날짜는 ISO 날짜 또는 초 단위 시각, change는 유한 숫자, 뉴스 최대 3건·정제 요약 400자. 원문 문장 단위로 자르므로 숫자를 중간에서 잘라 새 수치로 만들지 않습니다.
- 응답은 위 docent와 같은 camelCase 계약입니다. `whatHappened` 1~2문장, `possibleReason` 1~2문장, `marketImpact` 1문장. 규칙 설명은 기존 쉬운 읽기 리드 정제 함수를 재사용합니다.
- `DOCENT_WITH_LLM=false` 기본값이며 `USE_REAL_LLM`, `REWRITE_WITH_LLM`과 독립적입니다. `MAX_DAILY_DOCENT_CALLS=30` 기본값(KST 기준, 실패도 1회 집계). 기존 공통 프로세스 LLM 호출 상한도 함께 적용됩니다.
- 분봉/NONE/한도 소진/LLM 오류/숫자 또는 단정 표현 검증 실패는 RULE로 대체합니다. 실제 요청의 숫자와 원문 이외의 수치, 인과 단정·투자 권유를 허용하지 않습니다.
- SQLite `DOCENT_LEDGER_PATH`(기본 `data/chart_docent.sqlite3`)에 결과와 호출 예약을 원자적으로 저장합니다. 같은 키는 실패·RULE 포함 재호출하지 않습니다. 캐시 저장 불가 시 LLM 호출 없이 RULE을 반환합니다. 여러 프로세스가 같은 저장 경로를 공유하면 하루 한도도 공유합니다.
- 컨테이너 재시작/여러 인스턴스에서도 재생성 방지를 유지하려면 해당 파일을 지속 볼륨에 보관해야 합니다. 이미 RULE로 저장된 키는 플래그만 켜도 LLM으로 다시 생성하지 않습니다.
- cold LLM은 지점당 기존 AI timeout 30초(최대 5지점). 프런트 D/W 요청 timeout은 180초로 늘려 처음 생성 결과를 기다리고, 분봉 90초는 유지합니다. 규칙/캐시 조회는 빠르게 반환됩니다.
- 실제 유료 호출·기존 기사/DB 재처리·시연 사전 생성은 실행하지 않았습니다. 최초 실생성은 비용이 발생할 수 있고 저장 결과 재사용만 추가 호출이 없습니다.


## 구조화된 판단 결과 (2026-10-13)

`POST /ai/feedback/judgement`는 기존 B 요청 정보와 선택적 `index_name`, `index_change_percent`(동일 비교 구간, 유한 숫자)를 사용합니다. 지수 대비 수치는 기존 일봉 캐시에 실제 두 종가가 있을 때만 추가하며 외부 시세 호출을 늘리지 않습니다.

```json
{
  "verdict": "CORRECT",
  "headline": "정확한 판단이에요!",
  "summary": "삼성전자 2026-10-07 종가 기준 +1.76%. 예측과 실제 결과가 같은 방향이에요.",
  "reasons": [
    {"title": "기사 근거", "detail": "기사 리드 사실을 인용해 가격 변화와 함께 확인해보세요."},
    {"title": "판단 근거", "detail": "남긴 판단 근거를 인용해 비교 시점을 확인해보세요."},
    {"title": "실제 시장", "detail": "실제 종가·변동률·제공된 지수 비교 자료를 확인해보세요."}
  ],
  "nextTip": "같은 비교 시점의 기사와 시장 흐름을 함께 확인해보세요."
}
```

- `verdict=CORRECT|WRONG|PARTIAL`: 방향 불일치 WRONG, 일치라도 `|actualChangePercent| ≤ 1.0%`(기존 중립 ±0.5%에 0.5%p 근접 범위)이면 PARTIAL. 실제 방향 UNKNOWN은 비교 불가이며 verdict=null로 유지하고 정확/오답 판정하지 않습니다.
- 근거 2~3개(규칙 모드는 3개), 제목 8자 내외·최대 16자, detail/nextTip 각각 한 문장. 기사 리드/사용자 첫 근거/실제 데이터에 근거하며 수치는 원문과 요청 자료 밖에서 생성하지 않습니다.
- 무근거: 근거 남기기 안내를 포함하고 headline/본문에서 칭찬하지 않습니다. CORRECT 화면도 “예측 방향이 같았어요”로 표시합니다. 뉴스 인과관계와 미래 전망을 단정하지 않습니다.
- 규칙 기본, 기존 USE_REAL_LLM 플래그와 호출 상한 유지. LLM JSON 구조/판정/인용/숫자/무근거 칭찬 검증 실패 시 규칙 대체. 유료 호출 추가·실제 실행 없음.
- 구형 백엔드 호환용 `feedback`, `is_aligned`, `next_learning_point` 별칭 유지. `feedback`은 구조화된 내용의 평문이며, 신규 `summary`가 judgement.feedback_text에 저장됩니다.

### 저장 / 조회

- V9 migration은 nullable `feedback_reasons JSONB`, `feedback_headline VARCHAR(255)` 두 컬럼만 추가합니다. `feedback_reasons`는 `{verdict, reasons:[{title,detail}], nextTip}`를 저장해 추가 컬럼 없이 메타데이터를 보존합니다.
- 기존 레코드 UPDATE/재생성 없음. 기존 NULL은 history/detail에서 reasons/headline/verdict/nextTip=null, feedbackText는 기존 문단입니다.
- 기존 조건부 확정 쿼리에 summary·headline·JSONB를 함께 저장합니다. 동시 판정/수정된 판단에 대한 덮어쓰기 방지를 유지합니다. 스케줄러는 사용자 수준의 지연 로딩 오류를 막기 위해 뉴스와 사용자를 함께 읽습니다.
- `GET /api/judgements/{id}` 추가: 인증된 본인 기록만 반환하며 다른 사용자/없는 id는 404입니다.
- `/api/judgements/history`, `/history/page`, `/{id}`는 기존 필드에 `headline`, `verdict`, `reasons`(객체 배열 또는 null), `nextTip`을 추가합니다. `reasonText`는 사용자가 입력한 근거, `feedbackText`는 summary, `feedbackGeneratedAt`은 결과 도착 버전입니다.
- 프런트 내부의 기존 `reasons: string[]`는 사용자 근거로 유지하며 AI 근거는 `feedbackReasons`로 분리합니다. 레거시 null은 기존 feedbackText 문단 표시로 대체합니다.
- 대기/비교 불가/이전 방식은 정확한 판단으로 표시하지 않습니다. 날짜/가격 데이터가 없으면 만들어 넣지 않습니다. 저장된 두 종가만 있는 경우 실제 저장 기준값의 비교 차트를 표시합니다.
- “결과 도착”은 사용자별 로컬 확인 기록(id→feedbackGeneratedAt, 구형 데이터는 comparisonDate)으로 표시합니다. 대기/비교 불가/비교일 없는 옛 기록은 뱃지 대상에서 제외합니다. 상세 확인 시 사라지며 기기별 상태입니다. 화면 활성 상태에서 기존 60초 캐시 기준으로 결과를 확인합니다.

### 적용 제한

운영 DB에 migration/repair/baseline/수동 SQL을 실행하거나 기존 결과를 재생성하지 않았습니다. 사용자 지정 방침대로 `V4__add_onboarding_profile.sql`은 이름·내용을 유지하고 뒤 파일만 `V4_1__add_level_specific_importance_reasons.sql`로 이름 변경했습니다(기존 SQL 내용 불변). V10은 이전 migration에서 누락된 기존 JPA의 learning_progress/daily_note 테이블을 IF NOT EXISTS로 생성합니다. 빈 PostgreSQL 16에서 prod 프로필로 V1~V10 migrate 및 ddl-auto=validate 기동을 검증합니다. 실제 운영 Flyway 이력은 확인 전이며, 기본 방침과 다르면 배포 전에 조정해야 합니다.

## ML1 · BKT 숙련도

`GET /api/learning/progress`에 `mastery: {strongest, weakest, totalAttempts, learningCount}` 추가. 각 개념은 `{term,pKnown,effectivePKnown,attempts,lastAnsweredAt,level,lastCorrect}`이며 각 목록은 최대 3개입니다. level은 그 용어의 가장 최근 실제 문항 수준입니다. 확률은 추정이며 effectivePKnown에 한국 날짜 기준 `0.97^경과일` 망각 가중을 적용합니다.

POST 답변은 실제 채점 수준으로 term_mastery를 같은 트랜잭션에서 갱신합니다. 복습은 용어별 최근 기사 하나를 망각 가중 확률 오름차순으로 반환합니다. levelSuggestion은 현재 수준의 서로 다른 용어 5개에서 pKnown≥0.85일 때 다음 수준을 제안하며 자동 변경하지 않습니다. 백필은 자동 실행하지 않습니다. 상세 모델·대상 건수: `docs/ml-personalization.md`.

## ML3 · 로컬 금융 감성 신뢰도

V12: news.sentiment_confidence nullable double precision(0~1). 기존 행/감성값 백필 없음. AI feedback 요청 및 chart.docent 관련 뉴스에 sentiment_hint/sentiment_confidence를 선택 필드로 전달합니다. 0.6 미만 또는 NEUTRAL은 감성 방향을 근거로 쓰지 않습니다. 값이 없는 도슨트는 기존 제목 규칙을 유지합니다. confidence로 DIRECT/MARKET 관계나 실제 결과를 바꾸지 않습니다. 피드백은 기사 감성의 한계를 근거 문장에 표시하며 실제 가격 원인을 단정하지 않습니다.

감성 배치는 별도 ML 환경에서 수동 실행합니다. 기본 읽기 전용 변경 건수 확인, 명시적 --apply만 갱신하며 원래 값이 변경된 행은 건너뜁니다. 수집/요청/스케줄러에서 배치를 실행하지 않습니다. 운영 DB에 migration/배치를 적용하지 않았습니다.

## ML2 · 학습 기사 추천

인증 GET `/api/learning/recommendations?readNewsIds=1,2` → `{items:[{newsId,title,category,summary,reason,reasonNewsId,reasonTitle,basis,score}],basis}`. items 최대 3개. readNewsIds는 기기 저장소의 최근 30일 읽기 id(서버 저장 안 함), 최대 200개/4000자/양수 id만 허용합니다. 저장 행동은 인증 사용자 id로만 조회합니다.

최근 30일 퀴즈 오답 1.0/메모 0.8/판단 0.6/읽기 0.3 가중 관심 벡터를 사용합니다. 최근 후보 최대 300개에서 행동/읽은 기사와 제목 중복을 제외합니다. reasonNewsId/title은 해당 사용자 행동 기사로 서버에서 재검증합니다. basis는 LOCAL_SENTENCE_TRANSFORMER, RULE_INTEREST 또는 RULE_RECENT이며 cold start의 reasonNewsId/title은 null입니다. 인덱스·ML 의존성·AI가 없으면 규칙 fallback을 반환합니다. 홈 핵심 3개 선별 불변.

AI POST `/ai/news/recommendations`: `{behaviors:[{news_id,weight}],candidate_ids,excluded_ids}` → `{results:[{news_id,score,reason_news_id,reason_title}],basis}`. 행동 500개/후보 300개/제외 5000개 상한. 무료 파일 인덱스는 수동/크론에서만 증분 생성하며 DB 읽기 전용입니다. 모델 다운로드는 앱/CI에서 실행하지 않습니다. `/v1/search` News A의 basis 필드는 임베딩/기존 규칙을 구분하고 기존 문서·날짜·종목 필터를 유지합니다. 새 테이블·기존 데이터 재생성 없음.

## Chart representative news revision (2026-10-13)

Daily candidate news are within [trade date 00:00, next day 09:00) KST. StockCatalog aliases (삼전, 닉스, 하닉) join company and same-market-index candidates. Direction +3, headline company +2, headline/lead index movement +1, event −3, institution subject −2, opposite-only −2; scores below1 produce relation NONE. Ties use relevance then stable ID, not publication time. causeScore is this additive relevance score.

Move fields and point docent refer to the same highest-ranked article. whatHappened excludes photo notices, credits, old event captions and dates ≥365days away, preferring direction/numeric statements. MARKET reasons include index percent only from the existing candle cache. Docent keys use news-direction-v2 without invalidating quote/candle caches. Cached candles are rescored from DB news without another market client call.

## Concept learning criteria (V14)

`term.kind`: CONCEPT (default), TOPIC (반도체/AI/HBM/투자/수출/수입/원화), MARKET (코스피/코스닥/원달러). V14 adds the column idempotently and initializes only its classification metadata. Existing news, judgement, learning_progress and term_mastery values are preserved. New TermSeeder inserts carry the same classification.

Quizzes and mastery updates accept CONCEPT; MARKET is allowed only for beginner questions. TOPIC remains available to article underlines and term explanation but is excluded from quizzes, mastery queries, reviews and daily terms. No eligible question => lesson.question=null; frontend explains “이 기사는 퀴즈로 낼 개념이 없어요”. Historical TOPIC mastery is retained and excluded by read queries.

strongest: effectivePKnown >=0.8 and attempts >=3, highest first, at most3. weakest: effectivePKnown <0.5 or lastCorrect=false, lowest first, at most3. Latest wrong takes precedence to keep groups disjoint. learningCount counts the remaining concepts before either display limit. Empty groups remain empty. `/learning/reviews` uses the same <0.5-or-last-wrong criterion, sorted ascending and at most10. `lastCorrect` is null if no saved answer exists. BKT updates, forgetting weight and level promotion thresholds are unchanged.

## 판단 대상·학습 피드백 (2026-10-13)

- `GET /api/news/{id}`: `judgementTarget: {symbol: string|null, name: string|null, unavailableReason: string|null}`. 새 판단은 strict StockCatalog 별칭 매칭된 종목 우선, 국내 경제 기사 중 코스닥만 언급하면 KOSDAQ, 그 외 KOSPI. 해외만 다룬 기사·부고·인사·내용 없는 비경제 기사는 symbol=null. 대상 없음에는 POST 판단 400. 저장된 뉴스 매핑은 변경하지 않음.
- V15 nullable `judgement.target_symbol varchar(16)`은 새 판단/사용자가 수정한 미확정 판단에만 저장. 기존 행은 null 보존하고 스케줄러가 자동 재채점하지 않음.
- history/history/page/detail: `relatedSymbol/relatedSymbolName`은 저장된 판단 대상을 우선. `indexName`, `indexChangePercent`, `conceptTerm`, `conceptDefinition`, `unavailableReason` nullable 추가. 구형 피드백/배열은 기존 읽기 호환을 유지.
- 피드백 JSON 객체는 verdict/reasons/nextTip에 같은 비교 구간 indexName/indexChangePercent, CONCEPT term/definition을 추가. target과 index 차이 절댓값 <1%p이면 MARKET, 그 이상 INDIVIDUAL 가능성. 시장 비교 숫자는 같은 baseline→comparison 구간만 사용.
- KIS 기존 kisDailyCandles cache(기간/close entry)를 먼저 재사용; 없을 때만 기존 closing-range client 사용. TTL·상한 유지. 종목은 지수 종가도 확보된 뒤 확정; 시세 누락은 대기.
- `POST /ai/feedback/judgement`에 concept_term/concept_definition/judgement_id 추가. response conceptTerm/conceptDefinition/mode(RULE|LLM) 추가. 근거 없음에는 칭찬 금지, 호재/악재는 기사 표현 신호로만 사용하고 인과관계 단정 금지.
- `python3 scripts/regrade_judgements.py --dry-run`: 읽기 전용 SELECT + 오프라인 Java resolver/calendar/rules. Spring·수집·시세/AI 호출 없음. 기존 저장된 유효 종가만 사용하며 부족하면 NO_SAVED_CLOSES. --apply는 제공하지 않음; 실행 승인 전 기존 판단을 변경하지 않음.
