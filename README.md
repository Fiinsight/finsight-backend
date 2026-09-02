# FinSight Backend

FinSight는 경제 뉴스를 이해하고 스스로 투자 판단을 내리도록 돕는 초보 투자자용 AI 기반 투자 인사이트 플랫폼입니다. React Native 앱 + Spring Boot 백엔드 + FastAPI AI 서비스로 구성되어 있으며, 이 저장소는 그중 백엔드(Spring Boot)입니다. 앱은 이 백엔드하고만 통신하고, AI 관련 요청(뉴스 재작성, 용어 설명, 판단 피드백 생성)은 백엔드가 내부적으로 별도의 FastAPI 서비스(`finsight-ai`)를 호출해 처리합니다.

## 이 서비스가 하는 일

- **뉴스 수집 파이프라인**: 국내 경제 매체 RSS를 주기적으로 수집하고, Redis/DB로 중복을 제거하고, 경제·투자 키워드로 중요도를 스코어링해 상위 후보만 골라 본문을 추출합니다.
- **DB 영속화**: 선별된 뉴스, 용어 사전, 사용자 판단 기록을 PostgreSQL에 저장합니다.
- **스케줄링**: 3시간마다 뉴스 수집, 매 평일 장 마감 후 판단 결과 피드백 생성을 자동 실행합니다.
- **AI 서비스 연동**: 뉴스 3단계(초급/일반/전문가) 재작성, 용어의 문맥 설명, 판단에 대한 피드백 문구 생성을 `finsight-ai`에 위임합니다.
- **외부 금융 데이터 연동**: 한국투자증권(KIS) 모의투자 API로 지수/시세/차트를, 한국은행 ECOS로 기준금리·환율을, 금융감독원 OpenDART로 공시를 조회합니다.
- **판단 피드백**: 사용자가 뉴스에 대해 UP/NEUTRAL/DOWN 판단을 내리면 기록해두었다가, 하루 뒤 실제 주가 움직임과 비교한 피드백을 생성합니다.

## 아키텍처 / 모듈 구조

패키지는 도메인 단위로 나누고, 하나의 관심사가 커지면(예: 뉴스 수집 파이프라인, KIS 인증/조회) 그 아래에 세부 하위 패키지를 두어 클래스 하나가 여러 책임을 갖지 않도록 했습니다.

```
com.finsight
├── FinsightApplication        # 메인 클래스 (@EnableScheduling)
│
├── briefing                   # 홈 화면 "오늘의 브리핑"
│   ├── BriefingController      GET /api/briefings/today
│   ├── BriefingService         최신 뉴스 3건 조회, 부족하면 샘플 데이터로 폴백
│   ├── NewsBriefResponse
│   └── SentimentHint           POSITIVE / NEUTRAL / NEGATIVE
│
├── news                       # 뉴스 도메인
│   ├── News                    JPA 엔티티 (원문 + 3단계 재작성 + 중요도/관련종목/감성)
│   ├── NewsRepository
│   ├── NewsController           GET /api/news/{id}
│   ├── NewsDetailResponse
│   ├── NewsCollectionScheduler  3시간마다 실행되는 얇은 오케스트레이터
│   └── collect                 수집 파이프라인 세부 구현 (각자 한 가지 책임만 수행)
│       ├── NewsCandidate            RSS 항목 DTO
│       ├── RssFeedFetcher           ROME으로 RSS 피드 수집
│       ├── NewsDeduplicator         DB existsByUrl + Redis SETNX 중복 제거
│       ├── NewsRelevanceScorer      키워드 기반 스코어링/랭킹
│       ├── ArticleContentExtractor  Jsoup으로 본문 추출
│       ├── NewsCategoryClassifier   키워드 기반 카테고리 분류
│       └── NewsAssembler            AI 재작성 호출 + News 엔티티 조립
│
├── term                       # 투자 용어 사전
│   ├── Term / TermRepository
│   ├── TermSeeder               앱 시작 시 용어 20개 시딩 (중복 삽입 방지)
│   ├── TermService               기본 정의 조회 + (newsId 있으면) AI 문맥 설명 호출
│   ├── TermController            POST /api/terms/explain
│   ├── TermExplainRequest / TermExplainResponse
│
├── judgement                  # 사용자 투자 판단
│   ├── Judgement / JudgementRepository
│   ├── JudgementChoice          UP / NEUTRAL / DOWN
│   ├── JudgementService          판단 기록 저장 + 즉시 응답용 안내 문구 반환
│   ├── JudgementController       POST /api/judgements, GET /api/judgements/history
│   ├── JudgementRequest / JudgementFeedbackResponse / JudgementHistoryResponse
│   └── FeedbackScheduler         평일 장 마감 후 실제 결과 대비 피드백 생성
│
├── market                     # 홈 화면 시황 요약
│   ├── MarketController          GET /api/market/summary
│   ├── MarketSummaryService       코스피/코스닥 + 기준금리/환율 조합
│   └── MarketSummaryResponse
│
├── chart                      # AI 차트 도슨트
│   ├── ChartController            GET /api/charts/{symbol}
│   ├── ChartService                일봉 캔들 + 관련 뉴스 마커 결합
│   └── ChartResponse
│
└── external                   # 외부 API 연동 (모두 실패 시 폴백, 절대 예외를 던지지 않음)
    ├── AiServiceClient          finsight-ai 호출 (rewrite / explainTerm / generateFeedback)
    ├── Ai*Request / Ai*Response  AI 서비스 요청/응답 DTO
    ├── EcosClient / EcosRate    한국은행 ECOS (기준금리, 원/달러 환율)
    ├── kis                     한국투자증권(KIS) 모의투자 API
    │   ├── KisTokenProvider          OAuth 토큰 발급 + Redis 캐싱 (~23시간)
    │   ├── KisIndexQuoteClient       코스피/코스닥 지수 현재가
    │   ├── KisStockQuoteClient       개별 종목 현재가
    │   ├── KisDailyCandleClient      일봉(OHLC) 캔들
    │   ├── KisApiHeaders / KisJsonNumbers  공통 헤더/파싱 헬퍼
    │   └── KisIndexQuote / KisStockQuote / KisDailyCandle  응답 DTO
    └── dart                    금융감독원 OpenDART
        ├── DartCorpCodeResolver      종목코드 → DART corp_code 매핑 (zip 다운로드 + Redis 캐싱)
        ├── DartClient                최근 공시 목록 조회
        └── DartDisclosure
```

## API 엔드포인트

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/briefings/today` | 오늘의 뉴스 브리핑 3건 (DB에 충분한 데이터가 없으면 샘플로 폴백) |
| GET | `/api/news/{id}` | 뉴스 상세 (원문 + 초급/일반/전문가 재작성 + 중요도/관련종목/감성) |
| POST | `/api/terms/explain` | 용어 기본 설명 + (newsId 제공 시) AI 문맥 설명/시장 영향 |
| POST | `/api/judgements` | 판단 기록 (뉴스ID, UP/NEUTRAL/DOWN, 사유) → 접수 확인 응답 |
| GET | `/api/judgements/history` | 판단 이력 (뉴스 제목, 선택, 실제 결과/피드백 포함, 최신순) |
| GET | `/api/market/summary` | 코스피/코스닥 현재가 + 기준금리 + 원/달러 환율 |
| GET | `/api/charts/{symbol}` | 해당 종목 일봉 캔들 + 관련 뉴스 마커 |

백엔드 → AI 서비스(`finsight-ai`) 호출:

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/ai/news/rewrite` | 원문 → 초급/일반/전문가 3단계 요약 + 중요도 사유 |
| POST | `/ai/terms/explain` | 용어 + 뉴스 문맥 → 문맥 설명 + 시장 영향 |
| POST | `/ai/feedback/judgement` | 예측/사유/실제 결과 → 피드백 문구 |

## 실행 방법

1. 저장소 루트(`Finsight/`)에서 PostgreSQL, Redis를 띄웁니다.

   ```bash
   docker compose up -d postgres redis
   ```

2. `finsight-backend/.env.example`을 참고해 필요하면 `.env`를 만들고(비워둬도 무방), 앱을 실행합니다.

   ```bash
   cd finsight-backend
   ./gradlew bootRun
   ```

3. `GET http://localhost:8080/api/briefings/today` 로 정상 응답을 확인합니다. (KIS/ECOS/DART 키가 없어도, AI 서비스가 꺼져 있어도 앱은 폴백 데이터로 정상 동작합니다.)

AI 서비스(`finsight-ai`, FastAPI)까지 같이 띄우면 실제 뉴스 재작성/용어 설명/피드백 생성이 동작합니다. `finsight-ai`가 꺼져 있으면 해당 필드들은 원문 그대로 또는 기본 문구로 대체됩니다.

### 환경변수

`.env.example`에 전체 목록과 한글 설명이 있습니다. 요약:

| 변수 | 기본값 | 설명 |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/finsight` | PostgreSQL 접속 URL |
| `DB_USERNAME` / `DB_PASSWORD` | `finsight` / `finsight` | PostgreSQL 계정 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis 접속 정보 |
| `AI_BASE_URL` | `http://localhost:8001` | finsight-ai 서비스 주소 |
| `KIS_APP_KEY` / `KIS_APP_SECRET` / `KIS_ACCOUNT_NO` | (빈 값) | 한국투자증권 Open API 모의투자 앱키/시크릿/계좌번호 |
| `ECOS_API_KEY` | (빈 값) | 한국은행 ECOS Open API 인증키 |
| `DART_API_KEY` | (빈 값) | 금융감독원 OpenDART 인증키 |

## 무료 외부 API 연동과 폴백 동작

이 프로젝트는 평가자가 **API 키를 하나도 발급받지 않아도 바로 실행하고 모든 엔드포인트를 확인**할 수 있도록 설계했습니다.

- **한국투자증권(KIS)** — [apiportal.koreainvestment.com](https://apiportal.koreainvestment.com) 에서 무료로 모의투자 앱키를 발급받을 수 있습니다. `KIS_APP_KEY`/`KIS_APP_SECRET`이 비어 있으면 `KisTokenProvider`가 토큰 발급을 아예 시도하지 않고, `KisIndexQuoteClient`/`KisStockQuoteClient`/`KisDailyCandleClient`는 각각 그럴듯한 샘플 지수/시세/캔들 값을 반환합니다. tr_id/엔드포인트 경로는 공개 문서를 참고해 best-effort로 작성했으며(`KisIndexQuoteClient` 등 상단 TODO 참고), 실제 키로 검증되지는 않았습니다.
- **한국은행 ECOS** — [ecos.bok.or.kr](https://ecos.bok.or.kr) 에서 무료로 오픈API 인증키를 발급받을 수 있습니다. `ECOS_API_KEY`가 없거나 호출이 실패하면 `EcosClient`가 고정된 샘플 기준금리/환율 값을 반환합니다. (실제 발급받은 키로 검증 완료: 기준금리는 월별(`M`), 원/달러 환율은 일별(`D`)로만 조회되며, 조회 구간을 너무 좁게 요청하면 최신값이 아니라 구간 내 가장 오래된 값이 반환되는 점까지 확인해서 반영했습니다.)
- **금융감독원 OpenDART** — [opendart.fss.or.kr](https://opendart.fss.or.kr) 에서 무료로 오픈API 인증키를 발급받을 수 있습니다. `DART_API_KEY`가 없거나 corp_code를 찾지 못하면 `DartClient`가 빈 공시 목록을 반환합니다. (실제 발급받은 키로 `corpCode.xml` 다운로드/파싱과 `list.json` 공시 조회까지 end-to-end로 검증 완료.)
- **AI 서비스(finsight-ai)** — 실행되어 있지 않거나 타임아웃이 나면 `AiServiceClient`가 빈 결과를 반환하고, 호출한 쪽(`NewsAssembler`, `TermService`, `FeedbackScheduler`)이 각각 원문 그대로/기본 정의/템플릿 문구로 대체합니다.

모든 외부 클라이언트는 예외를 잡아 `warn` 로그만 남기고 폴백 값을 반환하도록 되어 있어, 키가 없거나 외부 API가 응답하지 않아도 앱 기동이나 API 응답이 실패하지 않습니다.

## 참고

- `ddl-auto: update`를 사용하며 별도 마이그레이션 도구(Flyway 등)는 도입하지 않았습니다.
- 실제 비밀 값은 이 저장소 어디에도 커밋하지 않습니다. `.env.example`에는 변수 이름과 설명만 있습니다.
