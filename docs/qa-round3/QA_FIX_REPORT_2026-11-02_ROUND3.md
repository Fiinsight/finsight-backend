# FinSight 3차 최종 코드 점검 — 2026-11-02

## 결과

최종 로컬 스택: backend Java **316개 통과, 실패·제외 0**(실제 PostgreSQL prod-migrations 13개 포함), Python 스크립트 20개 통과. Frontend **typecheck와 149개 테스트 통과**, AI pytest **242개 통과**. 운영 DB/Render/Expo/환경변수/키/유료 AI를 변경하지 않았다. main 머지와 운영 마이그레이션은 사용자 결정이다.

프론트의 이전 작업 ca48d45와 수정 파일은 선행 QA 브랜치에 보존되어 있었다. 이번 시작 시 세 저장소의 작업 트리는 깨끗했고, judgementResults.ts의 확장자 import는 이미 수정되어 있었다. 시작 기준 typecheck 및 frontend 143개 통과를 확인한 뒤 추가 작업했다. 사용자가 적은 과거 숫자를 맞추려고 테스트를 삭제하지 않았다.

## 항목별 수정과 근거

| 항목 | 구현 | 검증·한계 |
|---|---|---|
| P0-7 탈퇴 | AccountDataDeletion의 고정 owner 테이블 목록으로 term_mastery부터 JDBC 삭제, 사용자 삭제 flush. V18에서 실제 FK 이름을 찾아 CASCADE로 교체. optional 원장/출석 초안 테이블도 존재 시 삭제. | prod-migrations 테스트가 기존 NO ACTION FK를 복원한 상황에서 사용자 생성→실제 퀴즈 응답→숙련도·관심종목·기사/일일 메모·판단 생성→DELETE /auth/me→모든 owner 테이블 0행을 확인. pg_constraint에서 app_user FK 목록과 중앙 목록을 비교해 새 테이블 누락을 검출. 테스트 준비와 탈퇴 요청의 영속성 컨텍스트를 분리. V18 운영 적용 안 함. |
| P1-7 원문 | 서버 NewsDetailResponse와 앱 모두 최대 3문장/300 code point 발췌. 원문 탭에 출처 링크·발췌 안내. | 소수점 12.5%를 문장 구분자로 오인하지 않는 테스트와 300자 제한. 이전 서버의 전문 응답도 앱에서 제한. 서버 퀴즈/용어 탐지는 저장된 전문을 계속 사용. 요약·재작성 전체를 삭제하는 변경은 아님. |
| P1-8 개인정보 | 프로필→회원 탈퇴, 서버 처리 성공 시 즉시 삭제, 실패 시 재시도 문구로 변경. 선택적 Gemini 사용 시 기사 제목/본문(개인정보 제외) 전송 가능성과 계정 인증 정보 미전송 안내. | Gemini 기본 off 유지. 외부 AI 호출·환경변수 변경 없음. 사용자 제공 정책의 제품 기능 정합성을 수정한 것이며 법률 적합성 전체 심사는 아님. |
| P2-6 인증 | JWT 일반·prod 프로필 기본 7일(실제 prod profile 적용값 검사 포함). Redis connect/read 2초, Caffeine으로 정상 시 요청도 미러링하고 장애 시 동일 IP/계정 제한 유지, Redis 재시도 60초 회로. 이메일 local-part 기본 닉네임, 프로필 PUT /api/profile/nickname과 편집/저장, 비밀번호 찾기 버튼 제거. | Redis 예외 시 로그인 시도 허용·6번째 계정 요청 429·Redis 호출 반복 없음 검사. 닉네임 trim/길이·이메일 기본값 테스트. 기존 JWT_EXPIRATION_SECONDS override와 이미 발급된 토큰 만료는 유지. 다중 프로세스 장애 중 카운터는 공유되지 않음. Refresh token은 AUTH_SESSION_DRAFT.md 설계만 작성. 7일은 졸업작품 로그인 편의와 노출 기간의 절충이며 출시 전 재검토 필요. |
| P2-7 긴 요청 | 기본 및 차트 timeout 60초. 홈·차트·뉴스 상세에 10초 대기 후 안내/재시도/취소. 시작 시 공개 /actuator/health/liveness를 따로 검사하여 liveness 자체가 10초 넘게 대기/응답하면 깨우는 중, 빠른 정상 응답이면 일반 지연 안내. | health URL/AbortSignal 및 문구 선택, 홈 실제 요청 취소/재시도 테스트. Axios에 query signal 전달. 웹 CORS도 기존 허용 출처에 한해 liveness GET/OPTIONS를 허용하며 회귀 테스트 추가. 상태 변경 POST의 자동 재전송은 하지 않음. 실제 Render 절전과 실기기 네트워크는 재현하지 않았음. |
| P3 가격 조정 | 종가 쌍의 등락률 절댓값이 30%를 초과하면 신규 채점 및 UNKNOWN 재채점 preview/apply를 보류. ±30% 경계는 허용. history gradingIssue=PRICE_ADJUSTMENT와 설명, 기존 >30% 결과의 앱 적중 표시도 숨김. | 100→50에서 finalize/AI 호출 0회, 미채점 상태 유지 테스트. 경계/초과와 UI correct=null 테스트. 분할·권리락의 확정 판별은 아니며 비정상 비교 방어. 사유 메모리는 최대 10,000개/2일로 제한, 재시작 후 다음 due 검사에서 다시 계산. 과거 운영 데이터/통계 재계산 없음. |
| P3 관련 자료 | newsId와 요청 내용으로 공개 기사 검색 결과/진행 중 Promise 5분 캐시, 최대 100개. 오류는 캐시 제거, 수동 다시 검색은 갱신. | 중복 요청 1회, 다른 기사 분리, 만료·오류 재시도 테스트. 개인 case/term 검색은 캐시하지 않음. 프로세스 메모리 캐시이며 재시작 시 초기화. |

## 이슈와 PR

GitHub의 열린 backend #59/#61/#62, frontend #5/#9/#82/#84, AI #24/#25의 본문까지 다시 조회했다. OAuth/검색/기사 근거/수준별 투자 포인트는 선행 QA 스택에 이어 이번 변경을 쌓았다. 실기기 딥링크(#9)와 실기기 화면·네트워크·접근성(#5)은 시뮬레이션/단위 테스트만으로 완료 처리하지 않는다. 이슈를 닫거나 main에 머지하지 않았다. npm audit 재확인 결과 전체 high/critical 0개·moderate 13개, --omit=dev 기준 high/critical 0개·moderate 11개가 남는다. glob/minimatch(Expo CLI·config·metro 경유)와 uuid(@expo/ngrok 경유) 권고가 포함된다. audit 자동 제안에는 Expo 58→51의 호환성을 깨는 변경도 포함되어 적용하지 않았다. #82는 해결 완료로 닫지 않으며, 지원되는 Expo 도구 체인 갱신과 Expo Go 검증이 남는다.

| 저장소 | 브랜치 | PR |
|---|---|---|
| backend | `fix/p0-7-account-deletion` | https://github.com/Fiinsight/finsight-backend/pull/160 |
| backend | `fix/p1-7-news-excerpts` | https://github.com/Fiinsight/finsight-backend/pull/161 |
| backend | `fix/p2-6-auth-resilience` | https://github.com/Fiinsight/finsight-backend/pull/162 |
| backend | `fix/p3-price-adjustment-guard` | https://github.com/Fiinsight/finsight-backend/pull/163 |
| frontend | `fix/p1-7-news-excerpts` | https://github.com/Fiinsight/finsight-frontend/pull/187 |
| frontend | `fix/p1-8-privacy-copy` | https://github.com/Fiinsight/finsight-frontend/pull/188 |
| frontend | `fix/p2-6-auth-profile` | https://github.com/Fiinsight/finsight-frontend/pull/189 |
| frontend | `fix/p2-7-request-wait-ux` | https://github.com/Fiinsight/finsight-frontend/pull/190 |
| frontend | `fix/p3-related-news-cache` | https://github.com/Fiinsight/finsight-frontend/pull/191 |
| frontend | `fix/p3-price-adjustment-ui` | https://github.com/Fiinsight/finsight-frontend/pull/192 |
| backend | `fix/qa-round3-report` | https://github.com/Fiinsight/finsight-backend/pull/164 |
| backend | `fix/p2-7-liveness-cors` | https://github.com/Fiinsight/finsight-backend/pull/165 |
| backend | `fix/p2-6-production-session-default` | https://github.com/Fiinsight/finsight-backend/pull/166 |

## 배포 전 사용자 결정

1. 선행 PR과 이번 순차 PR의 머지/배포 여부. 각 PR base는 앞선 fix 브랜치다. 변경 검증과 운영 반영을 혼동하지 않는다.
2. V18 운영 적용. 기존 term_mastery FK에서도 JDBC 삭제는 동작하지만 CASCADE 적용은 별도 배포 시 수행한다. 운영 DB에는 실행하지 않았다.
3. 이전 일일 BKT 원장 SQL 설치와 daily-credit-enabled 활성화. 현재 기본 false이므로 원장 승인 전 API 퀴즈는 연습으로만 기록된다. 기존 숙련도 백필 없음.
4. 출석 서버 저장 API/마이그레이션은 이전 초안 유지, 현재 UI는 이 기기 기준이다.
5. AI 버전 갱신 시 두 프로세스 AI_INTERNAL_TOKEN 일치 설정. 이전 토큰 매트릭스 테스트/배포 체크리스트 유지. 운영 AI pin 6aa00e9를 임의로 변경하지 않았다.
6. 실제 Render cold start, Android/iOS 이메일/카카오 로그인, 닉네임 수정 후 재로그인, 실기기 취소/재시도와 접근성 확인.

기존 커밋별 운영 대조는 MERGE_DEPLOY_STATUS_ROUND2.md를 참조한다. 그 보고서의 관찰 시점 이후 운영 대시보드를 새로 확인한 것으로 주장하지 않는다. 이번 작업은 운영 반영이 아니라 코드와 PR 준비까지다.

## 검증 환경과 증거

Java 21, /tmp/finsight-round3-build로 빌드 출력 분리. loopback PostgreSQL 15432/Redis 16379만 사용했고 검사 후 종료했다. KIS/환율 연결은 loopback 실패 주소로 제한, prod-migrations의 AI client는 mock. 실제 외부 LLM 호출 없음.

- /tmp/finsight-round3-back.log 및 /tmp/finsight-round3-build/test-results/test: Java 316/0/0, PostgreSQL 13/0/0
- /tmp/finsight-round3-front-final.log: 149 pass; npm run typecheck 성공
- /tmp/finsight-round3-ai.log: pytest 242 pass
- GitHub CI는 각 PR head SHA 기준으로 별도 기록한다. 로컬 최종 스택 성공을 중간 PR CI 성공으로 대체하지 않는다.

## GitHub CI 최종 확인

기능 PR backend #160–163 네 개: `test`, `prod-migrations` 모두 SUCCESS.
frontend #187–192 여섯 개: `Typecheck, tests, and web export` 모두 SUCCESS.
각 PR의 현재 headRefOid와 check 결과는 별도 `CI_STATUS_ROUND3.json`에 저장했다. PR 10개를 자동으로 머지하거나 이슈를 닫지 않았다.

추가 검증: 웹 liveness CORS와 prod 프로필의 7일 세션 기본값까지 반영해 최종 로컬 전체 316개가 통과했다. 보완 PR #165/#166은 기존 기능 PR과 별도로 현재 head의 GitHub 검사 탭에서 확인한다. 최신 head별 전체 스냅샷은 작업 산출물의 finsight-round3-checks.json에 보관한다.
