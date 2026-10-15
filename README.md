# FinSight 백엔드

> 2026 졸업작품 FinSight의 Spring Boot API 서버

FinSight는 경제 뉴스를 읽고, 핵심 내용을 이해하고, 자신의 투자 판단을 기록하며 결과를 돌아보도록 돕는 모바일 서비스입니다. 이 저장소는 Expo 모바일 앱과 AI 서비스를 연결하는 중심 서버입니다.

## 담당 범위

`뉴스 수집 → 시장 데이터 조회 → 앱 제공 → 사용자 판단 기록 → 실제 결과 비교 → 피드백`

- 경제 뉴스 RSS 수집, 중복 제거, 중요도·카테고리 분류
- PostgreSQL 기반 뉴스·용어·판단 이력 저장
- Redis 기반 외부 API 토큰·중복·캐시 관리
- KIS 모의투자 API, 한국은행 ECOS, OpenDART 연동
- `finsight-ai` 호출을 통한 뉴스 수준별 재작성, 금융 용어 설명, 판단 피드백
- 회원가입·로그인·JWT 세션과 온보딩 프로필 매핑

## 기술과 구조

- Java 21 / Spring Boot / Spring Data JPA / Gradle
- PostgreSQL / Redis
- 외부 연동 실패 시 서비스가 중단되지 않도록 클라이언트별 폴백과 타임아웃 적용
- 도메인별 패키지 분리: `news`, `briefing`, `market`, `chart`, `term`, `judgement`, `auth`, `external`
- 뉴스 수집과 피드백은 스케줄러가 실행하고, 실제 업무는 각 서비스가 담당

## 주요 API

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/briefings/today` | 오늘의 핵심 뉴스 브리핑 |
| GET | `/api/news/{id}` | 뉴스 상세와 수준별 콘텐츠 |
| GET | `/api/article-notes` | 로그인 사용자의 최근 기사 메모 50건 |
| GET | `/api/article-notes/news/{newsId}` | 로그인 사용자가 해당 기사에 남긴 메모 |
| POST | `/api/article-notes` | 기사 메모 작성 (`newsId`, `content`) |
| PUT | `/api/article-notes/{noteId}` | 본인 메모 수정 |
| DELETE | `/api/article-notes/{noteId}` | 본인 메모 삭제 |
| POST | `/api/terms/explain` | 금융 용어 설명 |
| POST | `/api/judgements` | 사용자의 투자 판단 저장 |
| GET | `/api/judgements/history` | 판단 이력과 결과 피드백 |
| GET | `/api/market/summary` | 지수·금리·환율 요약 |
| GET | `/api/charts/{symbol}` | 종목 캔들 및 관련 뉴스 |

## 실행

```bash
docker compose up -d postgres redis
./gradlew bootRun
```

- API: `http://localhost:8080`
- Swagger: `http://localhost:8080/swagger-ui/index.html`
- OpenAPI: `http://localhost:8080/v3/api-docs`

Java 17 또는 21을 사용하세요. 외부 API 키가 없어도 로컬 개발과 화면 시연이 가능하도록 안전한 폴백을 제공합니다. 실제 비밀 값은 `.env`에만 두고 커밋하지 않습니다.

## 테스트와 외부 연동 실패 응답

```bash
./gradlew test --no-daemon
```

GitHub Actions runs this command for pull requests and pushes to `main`. The tests use local mocks and do not require Kakao, KIS, ECOS, Redis, database, or paid AI credentials.

- Kakao is not configured: `/api/auth/kakao` returns `503 Service Unavailable`.
- Kakao rejects the authorization code or profile request: the API returns `502 Bad Gateway`; a stalled Kakao request returns `504 Gateway Timeout`.
- KIS minute data is unavailable, times out, or returns a non-zero `rt_cd`: the chart endpoint returns fallback candles and sets `fallback: true`.

## 예전 UNKNOWN 판단 재채점

`scripts/regrade_judgements.py`는 `actual_direction='UNKNOWN' AND comparison_date IS NULL`인 기록만 계획합니다. 제목에 `로컬 QA`가 포함된 기록은 제외합니다. 저장된 종가로 다음 거래일 결과를 계산한 `READY`만 적용할 수 있으며, 가격 부족·대상 없음·비교일 전 기록은 그대로 둡니다. 서버 시작, 시세 조회, AI 호출은 하지 않습니다. Java 21, Python 3, `psql`과 로컬 DB 연결 설정이 필요합니다.

저장소 루트에서 먼저 변경 전후를 확인하세요. 기본 실행도 dry-run입니다.

```bash
python3 scripts/regrade_judgements.py --dry-run
```

출력에는 대상 건수·상태와 READY 기록의 아래 9개 컬럼 변경 전후가 나옵니다. 내용을 확인한 뒤 사용자가 직접 적용합니다.

```bash
python3 scripts/regrade_judgements.py --apply
```

`--apply`도 새 스냅샷으로 계획과 변경 전후를 다시 출력한 뒤 READY만 한 트랜잭션으로 적용합니다. 갱신 컬럼은 `target_symbol`, `actual_direction`, `actual_change_percent`, `comparison_date`, `judgement_close`, `comparison_close`, `feedback_text`, `feedback_headline`, `feedback_reasons`입니다. 기존 `feedback_generated_at`은 보존합니다. 계획 이후 판단·기사가 변경됐거나 다른 실행이 먼저 재채점했다면 전체 적용을 롤백합니다. 기존 도구와 같이 loopback의 `finsight` / `finsight_demo` DB만 허용합니다. READY가 0건이면 DB 쓰기를 하지 않습니다.

대기 중인 NULL 대상 기록은 백엔드 스케줄러가 뉴스에서 대상을 찾아 채점 결과와 함께 고정합니다. 대상 자체가 없는 기사는 비교 시점이 지난 뒤 UNKNOWN과 사유로 종료합니다. 이미 UNKNOWN으로 끝난 기록은 스케줄러가 다시 채점하지 않으며 위 수동 도구를 사용해야 합니다.

코드 변경을 실행 중 서비스에 반영하려면 **백엔드(8080)와 AI 서버(8001)를 재시작**해야 합니다. 백엔드는 시작 시 대기 기록 채점을 수행할 수 있으므로, 단순 화면 점검만 할 때는 기존 `finsight.feedback.on-startup=false` 설정을 사용하세요. 과거 UNKNOWN 적용은 서버 재시작으로 실행되지 않습니다.

## 앞으로의 계획

1. 인증·온보딩 선택값을 사용자 프로필과 연결하고 개인화 조회에 반영
2. 뉴스 수집·시장 데이터의 운영 로그와 재시도 정책 보강
3. 실제 데이터가 없는 경우를 명확한 빈 상태로 표시하고 목업 데이터와 분리
4. 테스트 데이터베이스 기반 통합 테스트와 API 성능 측정 추가
5. AI 서비스의 뉴스 분석 결과와 사용자 피드백을 저장해 학습 데이터로 축적

## 사용자별 무료 학습 API

- `GET /api/learning/news/{newsId}?level=beginner|normal|analyst`: 기본값은 계정의 온보딩 수준. 짧은 기사 설명과 일반 개념 설명을 구분합니다. 무료 규칙 설명은 `RULE_FALLBACK`, AI 서버가 없으면 `UNAVAILABLE`로 표시합니다.
- `POST /api/learning/news/{newsId}/answers`: `level`, `term`, `answerIndex`. 정답은 서버가 채점하고 동일 사용자·기사·개념의 최신 결과를 저장합니다.
- `GET /api/learning/reviews`: 로그인한 사용자의 최근 오답 개념 최대 50개. 정답으로 다시 답하면 복습 목록에서 제외됩니다. 읽은 횟수나 주가 예측 적중률로 수준을 자동 변경하지 않습니다.

차트는 완성된 응답을 1분 재사용하고, 분봉 캐시는 Caffeine의 키별 중복 요청 합치기를 사용합니다. 일봉·주가 외부 응답 대기는 5초로 제한합니다. KIS 시세 요청은 이 서버 프로세스에서 1.1초 간격으로 예약하며 2초를 넘는 대기열은 요청하지 않습니다. HTTP 오류 후 30초 쉬고 토큰 발급 실패 후 최소 60초 쉬며 자동 재시도하지 않습니다. 다른 프로그램이나 여러 서버가 같은 앱키를 쓰면 합산 제한은 별도로 관리해야 합니다. 실제 시세가 없으면 빈 캔들과 `fallback=true`를 반환합니다.

뉴스 선별은 오늘 기사 우선 → 제목의 중요도 점수 → 실제 발행시간 순서입니다. 같은 출처·날짜의 제목에서 속보·종합 표기만 다른 기사는 한 번만 표시하며, 추가 뉴스는 동일 순위의 네 번째부터 이어집니다. 부족한 개수는 가짜 기사로 채우지 않습니다.

## 관심종목 기반 학습 1단계 (V16)

로그인 사용자 본인만 `GET /api/watchlist`, `PUT /api/watchlist/{symbol}`,
`DELETE /api/watchlist/{symbol}`를 사용할 수 있습니다. StockCatalog의 6자리 종목만,
계정당 최대 20개이며 추가/삭제는 멱등입니다. 홈 뉴스는
`GET /api/news/watchlist?limit=5`로 최근 48시간의 본인 관심종목 뉴스를 최신순으로 받습니다.
학습 추천도 읽은 기사 제외 규칙을 유지하면서 관심종목 소식을 먼저 보여줍니다.

스키마는 `V16__create_user_watchlist.sql`로만 만듭니다. Hibernate의 자동 생성·수정은
`validate`로 전환했으므로 **V16 적용 없이 기본 bootRun만 실행하면 새 테이블 검증이 실패합니다.**
`.env`는 변경하지 않습니다. 이미 Flyway 이력이 있는 환경은 정상 Flyway 기동으로 V16이 적용됩니다.

Flyway 이력 없이 V15까지의 스키마를 사용하던 로컬 DB라면, **V15 스키마와 동일한지
확인한 다음** 아래 명령으로 기존 스키마를 15로 baseline하고 V16을 적용할 수 있습니다.
이력 없는 다른 DB에 버전 15 baseline을 임의로 사용하지 마세요.

```sh
cd finsight-backend
./gradlew bootRun --args='--spring.flyway.enabled=true --spring.flyway.baseline-on-migrate=true --spring.flyway.baseline-version=15 --spring.jpa.hibernate.ddl-auto=validate --finsight.news.collection.enabled=false --finsight.news.collection.on-startup=false --finsight.feedback.on-startup=false'
```

위 명령은 기존처럼 뉴스 자동 수집과 시작 시 채점을 하지 않습니다. 이번 구현 작업에서는 실행하지 않았습니다.
8001 AI 서버는 재시작할 필요가 없습니다. API·검증 범위·재매핑 dry-run 결과는
[관심종목 구현 보고서](docs/WATCHLIST_PHASE1_2026-10-15.md)를 참고하세요.

기존 뉴스 종목은 자동 변경하지 않습니다. `NewsAssembler.reprocess/refreshMetadata`도 저장된 종목을 보존합니다.
아래 첫 명령은 읽기 전용 preview입니다. 두 번째는 **사용자가 파일을 검토하고 적용하기로 결정했을 때만** 실행합니다.

```sh
python3 scripts/remap_news_symbols.py --output docs/watchlist-2026-10-15/remap-preview.json
# 아래는 실행하지 않은 적용 예시입니다.
python3 scripts/remap_news_symbols.py --apply docs/watchlist-2026-10-15/remap-preview.json
```

apply는 현재 계산 결과와 검토한 preview가 완전히 같을 때만 기존 조건부 UPDATE를 수행합니다.
원문·판단 기록을 수정하지 않지만 뉴스의 related_symbol 변경은 기존 화면의 대상 해석에 영향을 줄 수 있으므로
판단이 연결된 기사와 종목→지수/미연결 변경도 함께 검토하세요.
