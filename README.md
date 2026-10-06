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
