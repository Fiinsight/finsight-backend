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

## 앞으로의 계획

1. 인증·온보딩 선택값을 사용자 프로필과 연결하고 개인화 조회에 반영
2. 뉴스 수집·시장 데이터의 운영 로그와 재시도 정책 보강
3. 실제 데이터가 없는 경우를 명확한 빈 상태로 표시하고 목업 데이터와 분리
4. 테스트 데이터베이스 기반 통합 테스트와 API 성능 측정 추가
5. AI 서비스의 뉴스 분석 결과와 사용자 피드백을 저장해 학습 데이터로 축적

