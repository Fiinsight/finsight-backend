# 관심종목 기반 맞춤 학습 1단계 구현 — 2026-10-15

요청한 다섯 범위를 구현했습니다. 실데이터의 V16 적용·서버 재시작·뉴스 remap apply는 하지 않았습니다. 사용자 요청에 따라 **8080 백엔드만 종료**했고 8001 AI 서버는 계속 실행 중입니다.

## 1. 구현 결과와 변경 파일

기존 작업 중이던 판단 코칭/재채점 수정은 보존했습니다. 아래는 이번 작업에서 추가·수정한 파일만입니다.
`B`는 `/Users/subin/Desktop/Finsight/finsight-backend`, `F`는 `/Users/subin/Desktop/Finsight/finsight-frontend`입니다.

| 파일 | 변경 |
|---|---|
| B/src/main/resources/db/migration/V16__create_user_watchlist.sql | id/user_id/symbol/created_at, 사용자·종목 unique, user_id index, app_user FK. 계정 삭제 시 해당 watchlist만 cascade |
| B/src/main/java/com/finsight/watchlist/WatchlistEntry.java | JPA 매핑. 종목코드 길이16, 생성 시각 |
| B/src/main/java/com/finsight/watchlist/WatchlistRepository.java | 본인 조회·추가 순서·개수·조건부 삭제 |
| B/src/main/java/com/finsight/watchlist/WatchlistService.java | StockCatalog 검증·멱등·20개 제한·사용자 행 잠금 |
| B/src/main/java/com/finsight/watchlist/WatchlistController.java | 본인 전용 GET/PUT/DELETE |
| B/src/main/java/com/finsight/auth/UserRepository.java | 관심종목 동시 변경의 사용자별 잠금 쿼리 |
| B/src/main/java/com/finsight/news/NewsRepository.java | 본인 watchlist와 관련 종목이 일치하는 최근48시간 뉴스 쿼리 |
| B/src/main/java/com/finsight/news/WatchlistNewsController.java | 뉴스 목록과 동일 DTO, 최신순·최대5개 |
| B/src/main/java/com/finsight/briefing/BriefingService.java | 기존 뉴스 목록 DTO 변환을 재사용 가능하게 공개. 기존 피드 정렬 로직은 유지 |
| B/src/main/java/com/finsight/learning/LearningRecommendationService.java | 기존 추천 순서를 그룹 내에서 유지하며 관심종목을 앞에 배치. 읽은/활동한 기사 및 중복 사건 제외 유지. cold start의 WATCHLIST 이유 제공 |
| B/src/main/java/com/finsight/news/collect/NewsSymbolMatcher.java | catalog 이름·별칭, 제목 우선, 제목 후보 중 본문 최다 언급, 동률 결정, 짧은 별칭 경계·일반명사/자회사 오탐 보호 |
| B/src/main/java/com/finsight/news/collect/NewsAssembler.java | 새 기사에서 새 매칭 사용. 기존 reprocess/refreshMetadata는 저장된 종목을 보존하여 미승인 재매핑 방지 |
| B/scripts/remap_news_symbols.py | 기존 preview/apply 승인 방식 유지. clean 빌드 후에도 catalog CSV를 읽도록 classpath에 build/resources/main 추가 |
| B/src/main/resources/application.yml | Hibernate ddl-auto를 update→validate로 전환. 새 테이블을 Hibernate가 자동 생성하지 않도록 강제. Flyway는 아래 명시한 기동 방법으로 실행 |
| B/README.md | API, V16 기동, 기존 로컬 DB baseline 주의, preview/apply 안내 |
| B/src/test/java/com/finsight/watchlist/WatchlistServiceTest.java | 검증·상한·멱등·추가 순서·계정 분리 |
| B/src/test/java/com/finsight/watchlist/WatchlistSecurityTest.java | 익명 거부, 요청 userId 무시·토큰 소유자 사용,48시간·개수 |
| B/src/test/java/com/finsight/news/collect/WatchlistSymbolMatcherTest.java | 별칭·제목 우선·빈도·동률·오탐·명시 종목코드 |
| B/src/test/java/com/finsight/ProdMigrationStartupTest.java | V16 migration/validate, 실제 API·조회 범위·48시간·멱등·20개,19개에서 동시 추가2건 중1건만 성공 |
| B/src/test/java/com/finsight/learning/LearningRecommendationServiceTest.java | 관심 우선·읽은 기사 제외·관심 기사 없으면 기존 응답 동일 |
| B/src/test/java/com/finsight/news/collect/NewsAssemblerTest.java | 기존 종목 보존과 새 기사만 매칭 적용 |
| B/src/test/java/com/finsight/news/collect/NewsPipelineQualityTest.java | 복수 종목·본문 연결 정책 변경에 맞춘 기대값 |
| F/src/types/api.ts, F/src/lib/api.ts | WatchlistItem, getWatchlist/addWatchlist/removeWatchlist/getWatchlistNews |
| F/src/lib/watchlist.ts | 사용자별 저장 키·첫 종목 우선순위·피드 중복 제거·빈 상태 |
| F/src/screens/chart/ChartScreen/index.tsx | 목록+저장값을 기다린 후 차트 선택. 낙관적 토글/실패 복원/오류, 관련 query invalidate, 관심종목 선택 저장 |
| F/src/screens/chart/ChartScreen/StockHeader.tsx | 종목명 옆 ☆/★,44px 터치 영역·접근성, 지수 버튼 숨김 |
| F/src/screens/chart/ChartScreen/WatchlistChips.tsx | 관심 칩을 인기 칩보다 먼저.0개이면 행 없음 |
| F/src/screens/home/HomeScreen/index.tsx | 독립 쿼리·관심 뉴스 섹션·피드 ID 제외 전달 |
| F/src/screens/home/HomeScreen/WatchlistNewsSection.tsx | 세 상태, 로딩/오류/재시도, 차트 이동 |
| F/src/screens/home/HomeScreen/NewsSection.tsx | 첫 목록·이전 페이지 모두 관심 섹션의 표시 기사 제외. 전체 피드 단독 로딩/오류 유지 |
| F/src/screens/learn/LearnScreen/index.tsx | WATCHLIST 추천도 활동 원문ID 없이 표시·현재 읽기 수준으로 열기 |
| F/src/lib/watchlist.test.mjs | 초기 종목 우선순위·깜빡임 방지·계정별 저장키·낙관적 복원·세 상태·두 피드 중복 제거·오류 격리 |
| F/src/lib/chartDocent.test.mjs, judgementResults.test.mjs, painPointUi.test.mjs | 새 query/helper 의존성에 맞춘 기존 테스트 harness 갱신 |
| F/src/lib/learnScreenUi.test.mjs | 활동이 없는 WATCHLIST 기사도 보이고 현재 수준으로 여는지 확인 |

새 의존성, 관심종목 관리 화면, 종목별 적중률, 개념 추천, 온보딩 선택은 추가하지 않았습니다. AI 제품 코드는 변경하지 않았습니다.

## 2. API 명세

모든 API는 Bearer 로그인 세션을 요구하고 `@AuthenticationPrincipal Long userId`만 사용합니다. query/body의 userId로 다른 계정을 지정할 수 없습니다.

| 메서드 / 경로 | 성공 | 검증·동작 |
|---|---|---|
| GET /api/watchlist |200, `[{symbol,name,addedAt}]` | created_at 오름차순, 같은 시각이면 id 오름차순 |
| PUT /api/watchlist/005930 |200, `{symbol:"005930",name:"삼성전자",addedAt:"..."}` | 이미 있으면 기존 시각 그대로.20개일 때 기존 종목 PUT도200 |
| DELETE /api/watchlist/005930 |204, 본문 없음 | 없는 종목도204 |
| GET /api/news/watchlist?limit=5 |200, 기존 NewsBrief 목록 | 본인 관심종목 related_symbol, 현재~48시간 전, 최신순·id 역순, 최대5. limit 1~5, 범위 밖400 |

StockCatalog에 없는 코드,6자리가 아닌 코드, KOSPI/KOSDAQ는 PUT/DELETE에서400입니다. 새 종목을21번째 추가하면409과 `{"message":"관심종목은 20개까지 추가할 수 있어요"}`를 반환합니다. 같은 사용자 행 잠금으로 동시 요청도 상한을 지킵니다.

`GET /api/learning/recommendations`의 기존 readNewsIds·학습 행동 규칙은 유지합니다. 관심 기사 후보가 없으면 이전 Response를 그대로 반환합니다. 관심 기사 자체로 추가하는 추천은 item.basis=WATCHLIST, reason은 관심종목 이름을 사용하고 reasonNewsId는 null입니다. 기존 활동 기반 추천의 이유·순위는 각 그룹 안에서 유지합니다.

프론트 캐시 키는 `['watchlist']`이고 변경 후 watchlist/watchlist-news/learning-recommendations를 invalidate합니다. 로그인 종료 시 기존 App의 queryClient.clear로 타 계정 캐시를 비웁니다. 마지막 종목은 기존 storage 함수를 통해 `finsight:lastWatchlistSymbol:{userId}`에 저장하고, 아직 관심종목인지 검사한 다음 사용합니다.

## 3. 기존 뉴스 재매핑 dry-run — 적용하지 않음

현재 DB411개를 읽어 기존 스크립트로 계산했습니다. 원본 preview: [remap-preview.json](watchlist-2026-10-15/remap-preview.json), [집계](watchlist-2026-10-15/dry-run-summary.json).

| 기준 | 저장된 현재 | preview 적용 가정 |
|---|---:|---:|
| 전체 연결(종목+지수) | 136/411 = 33.09% | 130/411 = 31.63% |
|6자리 종목코드 연결 | 71/411 = 17.27% | 78/411 = 18.98% |
|관심종목으로 등록 가능한 StockCatalog 종목 연결 | 65/411 = 15.82% | 78/411 = 18.98% |

변경 제안 **96건**: 미연결→연결32건, 연결→미연결38건, 연결 대상 변경26건. 그중 판단이 연결된 기사는9건입니다. 개별 관심종목 연결은 늘지만 **전체 non-NULL 비율은 감소합니다.** 기존 해외 기사→KOSPI 및 catalog 밖/불명확한 연결을 해제하는 제안이 포함되기 때문입니다. 숫자를 늘리려고 지수나 불확실한 기업에 임의 연결하지 않았습니다.

preview는 정답 라벨이 아닙니다. 특히 제목에 없는 기업을 본문 빈도로 고른 기사(예: 채권시장 분석 중 증권사 언급, 지역 투자 기사)는 관련 기업과 발언 출처가 혼동됐는지 수동 검토해야 합니다. 원문 오염도 매칭 함수만으로 고칠 수 없습니다. **이96건 전체가 자동 적용에 적합하다고 승인한 것이 아닙니다.**

변경 예시20개(파일의 id 순 첫20건):

| 뉴스ID | 제목 | 이전 | 제안 | 연결된 판단 |
|---|---|---|---|---|
| 6 | 미·중 정상회담 앞두고 트럼프 보란 듯…中, 최강 AI 칩 공개 [강경주의 테크X] | 미연결 | SK하이닉스 (000660) | 없음 |
| 8 | 연속 유가 하락에 美증시 상승…나스닥 개장초 사상최고 경신 | KOSPI (KOSPI) | 미연결 | 있음 |
| 12 | 연휴 뒤 채권시장 변수는…미중 정상회담·유가·10월 발행물량 | 미연결 | 미래에셋증권 (006800) | 없음 |
| 16 | 주식·채권도 코인으로…STO 제도화에 조각투자 시대 '성큼' | 미연결 | 삼성증권 (016360) | 없음 |
| 24 | 美는 물가, 中은 경기…글로벌 증시 변수 쏟아진다 | KOSPI (KOSPI) | 미연결 | 없음 |
| 28 | 한·멕시코, 정상회담 계기 경협 강화…무역협정 개시도 준비 | 미연결 | 포스코인터내셔널 (047050) | 없음 |
| 32 | 안산시, ASV 내 첨단로봇 400억원대 대규모 투자 유치 | 미연결 | 카카오 (035720) | 없음 |
| 37 | [마켓인사이트] 추석 연휴서 돌아온 韓증시, 상승흐름 이어갈까 | KOSPI (KOSPI) | 미연결 | 없음 |
| 42 | 전통 제조업에서 반도체·AI로…전남광주 산업지도 다시 그린다 | 미연결 | 삼성전자 (005930) | 없음 |
| 44 | '역대급 실적' 증권가, 하반기 채용 활기…대형사도 가세 | 미연결 | NH투자증권 (005940) | 없음 |
| 56 | "4년뒤 전세계 반도체시장 3100조" | 미연결 | SK하이닉스 (000660) | 없음 |
| 57 | 반도체 초호황에 성과급 기대감...삼성·SK하닉 육아휴직률 첫 동반 하락 | 미연결 | SK하이닉스 (000660) | 없음 |
| 58 | 불량품만 잡아내도 뜬다…몸값 오른 반도체 검사장비기업[반도체 플러스] | 미연결 | 한미반도체 (042700) | 없음 |
| 59 | 45년간 주가상승 1등 … 엔비디아도 애플도 아닙니다 | KOSPI (KOSPI) | 미연결 | 없음 |
| 63 | 인도네시아, 증시 거래 주가 하한 98% 인하…MSCI 강등 경고 대응 | KOSPI (KOSPI) | 미연결 | 없음 |
| 66 | IBK증권 "씨엠티엑스, 성장 기대 실적 입증 구간" | 미연결 | 씨엠티엑스 (388210) | 없음 |
| 71 | 한투PE, 1조원 규모 투자 플랫폼 구축…"데이터센터 등 투자 대상" | 미연결 | 한국금융지주 (071050) | 없음 |
| 80 | "아무리 할인해도 과자가 안팔려요"…유명 식품기업 주가 '뚝' | KOSPI (KOSPI) | 미연결 | 없음 |
| 81 | 포스코인터, '알짜' 에너지 사업 투자 확대...美 가스전까지 진출 | 미연결 | 포스코인터내셔널 (047050) | 있음 |
| 89 | 한달여 앞 美중간선거, 박스권 韓증시 촉매될까…증권가 촉각 | KOSPI (KOSPI) | 미연결 | 없음 |

제목 내 여러 종목이면 그 후보들 중 본문 언급 수로 결정하고, 동률은 제목의 첫 등장→코드 순으로 고정합니다. 제목에 회사가 없고 본문 최다 언급이 동률이면 미연결입니다. 지수 중심 제목의 기존 보호, 해외/거시 및 공공기관 제목 보호를 유지합니다.2글자 이하 별칭은 한글/영문/숫자 경계를 요구하고, 카카오톡·카카오 분말·자회사 이름·현대차증권·일반 명사 기아 등은 보호합니다.

기존 기사 remap은 아래처럼 **사용자 검토 후에만** 실행합니다. 이번에는 첫 preview 명령만 실행했습니다. 기존 스크립트는 현재 계산 결과와 파일 전체가 같지 않으면 apply를 거부하므로 일부 행만 지운 파일은 승인 파일로 쓸 수 없습니다.

```sh
cd /Users/subin/Desktop/Finsight/finsight-backend
# 읽기 전용 재확인
python3 scripts/remap_news_symbols.py --output docs/watchlist-2026-10-15/remap-preview.json
# 사용자 결정 후에만 실행할 예시 — 미실행
python3 scripts/remap_news_symbols.py --apply docs/watchlist-2026-10-15/remap-preview.json
```

조건부 UPDATE는 related_symbol만 바꿉니다. 판단값/종가/피드백/원문은 수정하지 않지만 기존 기사 화면의 대상 해석에는 영향이 있으므로 판단 연결9건과 종목→지수/미연결 변경을 꼭 검토해야 합니다.

## 4. 검증과 데이터 보호

- backend `./gradlew clean test`: **258건, 실패0, 일반 실행에서 migration 전용8건 제외**. Python 보조 도구19건도 통과했습니다. build의 ` 2` 사본 파일은 없었고 clean 후 정상 통과했습니다.
- 제외된 migration 전용8건은 **분리된 임시 PostgreSQL**에 Flyway V1~V16을 적용하고 실제 Spring/JPA/MockMvc로 별도 실행해 모두 통과했습니다.19개 상태에서 두 동시 추가 요청이200/409 한 건씩 반환하며20개를 넘지 않는 테스트도 포함합니다. 임시 DB와 임의 포트 테스트 서버는 종료·정리했습니다. 본인 로컬 `finsight` DB에는 migration을 실행하지 않았습니다. [별도 실행 로그](watchlist-2026-10-15/migration-test.log)
- frontend `npm test && npx tsc --noEmit`: **101건 통과, 타입 검사 통과**. 기존 피드백 스냅샷 변경 없음.
- AI `.venv/bin/python -m pytest -q`: **231건 통과**. 새 유료 호출 없음.
- [무변경 확인](watchlist-2026-10-15/integrity.json): main DB의 news 전체·judgement 전체·user8 행 hash 동일. 실제 user_watchlist 테이블은 아직 없습니다. `.env` 수정·실계정 로그인·시세 강제 조회·재채점·백필을 하지 않았습니다.

### 375px 확인의 범위

8080을 재시작하지 않는 조건 때문에 실데모 계정으로 새 API까지 연결한 Expo 화면 확인은 하지 못했습니다. 대신 실제 새 TSX 컴포넌트를 메모리 fixture로 렌더링한 별도 페이지에서 버튼을 직접 클릭했습니다. 캡처 상단에도 **“실DB/API 연결 없음”**을 표시했습니다. 저장·인증·상한은 위 실제 API 통합 테스트로, 첫 진입·낙관적 복원·사용자별 storage는 실제 화면 로직 테스트로 따로 확인했습니다.

| 시나리오 | 근거 |
|---|---|
|☆ 추가/★ 해제·칩 우선 표시 | [추가](watchlist-2026-10-15/02-chart-star-added-mobile.jpg), [해제](watchlist-2026-10-15/04-chart-star-removed-mobile.jpg) |
|마지막 관심종목 재진입 | [SK하이닉스 재진입 fixture](watchlist-2026-10-15/03-chart-last-watchlist-mobile.jpg) + 실제 ChartScreen storage/우선순위 테스트 |
|관심종목 없음 | [375px](watchlist-2026-10-15/05-home-no-watchlist-mobile.jpg) |
|관심종목 있지만 최근 기사 없음 | [375px](watchlist-2026-10-15/06-home-no-recent-news-mobile.jpg) |
|관심 기사 있음·아래 피드 중복 제거 | [375px](watchlist-2026-10-15/07-home-watchlist-news-mobile.jpg), [desktop](watchlist-2026-10-15/08-home-watchlist-news-desktop.jpg) |

fixture는 합성 기사·검증용 가격만 사용했습니다. 실데모 DB 저장으로 오인하면 안 됩니다. 실제 연결 검증은 아래 기동 후 사용자 demo 계정에서 추가/해제 → 카카오 등 두번째 종목 선택 → 홈/차트 재진입 → 홈 세 상태를 확인하면 됩니다. user8은 사용하지 마세요.

## 5. 사용자에게 남긴 기동 방법

8080은 종료된 상태입니다.8001은 원래 프로세스를 유지했습니다. **AI 서버 재시작은 필요 없습니다.**

현재 로컬 DB에는 Flyway 이력 테이블이 없고 기존 기능은 V15까지의 스키마를 사용하고 있습니다. 아래의 baseline-version15는 **그 로컬 DB가 V15와 동일한지 확인했을 때만** 사용하세요. 이미 Flyway 이력이 있는 DB에는 baseline 설정이 기존 이력을 덮어쓰지 않습니다. 다른 미확인 DB에 이 명령을 그대로 적용하면 안 됩니다.

```sh
cd /Users/subin/Desktop/Finsight/finsight-backend
./gradlew bootRun --args='--spring.flyway.enabled=true --spring.flyway.baseline-on-migrate=true --spring.flyway.baseline-version=15 --spring.jpa.hibernate.ddl-auto=validate --finsight.news.collection.enabled=false --finsight.news.collection.on-startup=false --finsight.feedback.on-startup=false'
```

이 기동에서 V16이 적용되어 user_watchlist를 만들고 이후 Hibernate가 검증합니다. 기존처럼 뉴스 자동 수집·시작 시 채점은 꺼 둡니다. 정기 채점 스케줄 자체는 변경하지 않았습니다. 기본 로컬 설정은 Flyway=false이고 이제 ddl-auto=validate이므로 위 Flyway 활성 옵션 없이 단순 bootRun을 하면 V16 미적용 상태에서 검증 오류가 납니다. 이 제한은 Hibernate가 승인되지 않은 테이블을 자동 생성하는 일을 막기 위한 것입니다. `.env`에 플래그를 추가할 필요는 없습니다.

관심종목을 저장해도 기존 뉴스411건은 자동 재매핑되지 않으며, 읽기 학습의 추천/화면만 본인 관심목록을 이용합니다. 관심종목으로 등록 가능한 catalog 밖 종목은 이번 단계에서 확장하지 않았습니다.

다음 단계 후보는 **종목별 적중률**, **관심종목 기반 개념 추천**, **온보딩 종목 선택**입니다. 이번 구현에는 포함하지 않았습니다.

## 6. GitHub 이슈·PR 병합 — 2026-10-15

- backend [이슈 #131](https://github.com/Fiinsight/finsight-backend/issues/131) → [PR #132](https://github.com/Fiinsight/finsight-backend/pull/132), merge `1f3c5282fe62bcc8285c4a684bbf56e2d7942618`
- frontend [이슈 #144](https://github.com/Fiinsight/finsight-frontend/issues/144) → [PR #145](https://github.com/Fiinsight/finsight-frontend/pull/145), merge `c0cb7391e0bea975526e73d086dd89b94503f849`
- 두 저장소 모두 `feature/watchlist-learning` 브랜치를 사용했습니다. 이슈는 병합으로 닫혔습니다.
- 별도 checkout에서 이번 관심종목 변경만 분리했습니다. 기존 작업 디렉터리의 코칭·재채점 미커밋 변경은 보존하고 이번 PR에 포함하지 않았습니다.
- 분리 브랜치 backend Java 218건(기본 실행에서 migration 전용 7건 제외), Python 보조 도구 19건 통과. 별도 임시 PostgreSQL에서 migration 7건 모두 통과. frontend 100건·타입 검사·web export 통과. 위 기존 작업 디렉터리의 258/101건과 차이는 이전 미커밋 테스트를 PR에서 제외했기 때문입니다.
- 최신 PR head CI와 REST로 확인한 정확한 merge SHA의 main CI까지 전부 성공했습니다: backend test/prod-migrations, frontend Typecheck/tests/web export.
- 이번 공개 문서에는 로컬 계정 자료·무변경 해시·실데이터 preview 원본·컴퓨터 경로를 포함하지 않았습니다. 20개 기사 제목과 집계, API·기동 주의, fixture 검증 한계만 보고서에 실었습니다.
- 기존 DB 적용, 서버 재시작, 뉴스 remap apply는 병합 과정에서도 수행하지 않았습니다. 실제 데모 API와 Expo 종단 검증은 기동 후 남아 있습니다.
