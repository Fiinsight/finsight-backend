# 11월 데모 배포 준비 — 실행 전 단계별 승인 필요

세 저장소의 `release/demo-2026-11`은 검토할 소스를 모은 브랜치다. 이 문서의 명령은 배포 절차이며 아직 실행하지 않았다. main PR 머지·운영 DB schema/data 변경·Render/Expo 배포는 각각 사용자 승인 후에만 실행한다. LLM을 켜거나 운영 AI 재작성을 활성화하지 않는다.

## 1. 승인 경계와 PR 순서

1. 세 release PR의 diff·CI·통합 커밋을 확인한다. 기존 겹치는 PR은 자동 종료/머지하지 않는다.
2. **머지 승인 전** Render 연결 branch/Auto Deploy와 Expo 외부 배포 trigger를 확인한다. 연결 branch에 머지하면 자동 배포될 수 있으므로 이 상태를 모른 채 backend main을 머지하지 않는다. 설정 변경도 별도 승인 없이 하지 않는다.
3. 사용자 머지 승인 후 AI → backend → frontend 순으로 main PR을 머지한다. `gh pr merge <확인한 PR 번호> --merge --repo Fiinsight/<저장소>`를 사용하며 여기서 실행하지 않았다.
4. AI main에 검토한 `e1c546b539fc421bb88f54fc972c75f68e068062`가 포함되는지 `git fetch origin` 후 `git merge-base --is-ancestor e1c546b539fc421bb88f54fc972c75f68e068062 origin/main`으로 확인한다. backend Dockerfile의 핀도 이 SHA와 일치해야 한다. 움직이는 branch URL로 바꾸지 않는다.

## 2. 배포 전 실제 상태·되돌릴 버전 기록

- Render 서비스: srv-dbfi6favcj2c7388o6a0. 현재 연결 branch·실제 Live SHA·Auto Deploy·DB 연결 대상을 로그인된 설정에서 다시 확인한다. 이 작업에서는 로그인 화면으로 이동해 현재 값을 확인하지 못했다.
- 과거 확인값은 backend 7997f1daa6d2bcc6feff808a236c345b3e62a0a9 / AI 핀 514a47e. **현재 rollback 기준으로 맹신하지 말고 배포 직전 Live SHA를 새로 기록한다.**
- Expo projectId 8fda91d0-1fe1-4fd2-ac5f-26e42d765466. 이번 조회의 production URL은 https://finsight-demo.expo.app, deployment v5keac5ci0. 배포 직전 production ID를 다시 기록한다. 별도 `--demo` alias나 다른 프로젝트로 배포하지 않는다.
- `ROLLBACK_BACKEND_SHA`, `ROLLBACK_EXPO_ID`, 배포 DB의 확인된 식별자(비밀번호 제외), migration 이력과 기존 재작성 필드 백업 위치를 기록한다. 토큰·비밀번호·환경변수 값을 로그나 문서에 복사하지 않는다.

## 3. Render·환경변수·migration

V19/V20은 각각 숙련도 원장과 읽기/출석/import 테이블이다. V4_1을 포함한 기존 version과 번호 충돌이 없었다. V18이 적용된 DB에서 새 두 migration은 DDL만 수행하며 기존 learning_progress를 소급 반영하지 않는다.

**Render 배포 시 Flyway가 V19/V20을 자동 적용하므로, 운영 schema 쓰기 승인도 먼저 받아야 한다.** migration 성공 여부·복구 계획을 설명한 뒤 실행한다. 운영 DB에서 Flyway clean/down migration을 실행하지 않는다.

다음은 확인 항목이며 값 자체를 출력하지 않는다.

| 항목 | 기대 상태 |
|---|---|
| AI_INTERNAL_TOKEN | 지정값을 유지하거나 entrypoint가 한 번 생성하여 Java/Python 두 자식에 동일하게 상속. 값은 비공개 |
| LEARNING_DAILY_CREDIT_ENABLED | prod 미지정 시 true. 사용자가 중지할 때만 false로 설정. 이번에 환경변수 수정하지 않음 |
| USE_REAL_LLM / REWRITE_WITH_LLM / DOCENT_WITH_LLM / FEEDBACK_WITH_LLM | 모두 false 유지 |
| DB 연결 | 실제 서비스 datasource와 운영 DB를 대조해 확인. 예전 로컬 설정 파일만 보고 운영과 같다고 단정하지 않음 |

내부 토큰은 deploy/start-free.sh의 공유 export 및 단위 테스트로 검증되어 있다. 운영 검증 시 `/proc`에서 Java와 Python 프로세스 환경을 읽어 두 값이 비어 있지 않고 같은지 **boolean 결과만** 출력한다. `env`, `printenv`, token hash/원문 출력은 사용하지 않는다.

승인 후, 설치·인증된 Render CLI에서 확인된 main merge SHA를 지정한다.

```sh
# RELEASE_BACKEND_SHA에는 승인된 실제 backend main merge SHA를 넣는다.
render deploys create srv-dbfi6favcj2c7388o6a0 --commit "$RELEASE_BACKEND_SHA" --wait
curl --fail --max-time 60 https://finsight-backend-gh7c.onrender.com/actuator/health/liveness
curl --fail --max-time 60 https://finsight-backend-gh7c.onrender.com/actuator/health/readiness
```

Render deploy 결과의 SHA·Live 상태, Flyway 19/20 success, 두 프로세스 health를 확인한다. 배포 성공이 기존 저장 기사 재작성이나 과거 BKT 재계산을 의미하지 않는다.

## 4. Expo production — 별도 배포 승인 후

깨끗한 frontend main 작업 트리에서 승인한 merge SHA를 확인하고 실행한다.

```sh
git fetch origin
git switch main
git pull --ff-only origin main
npm ci
npx tsc --noEmit
npm test
EXPO_PUBLIC_API_BASE_URL=https://finsight-backend-gh7c.onrender.com/api npx expo export --platform web
# 기존 projectId와 production 도메인을 확인한 뒤에만
npx eas-cli deploy --prod --non-interactive --json
```

배포 ID·실제 git SHA·JS bundle 경로/SHA256을 기록한다. production alias가 새 ID를 가리키는지, HTML이 새 bundle을 제공하는지 재조회한다. branch 이름만으로 배포되었다고 판단하지 않는다.

## 5. 배포 후 스모크 — 테스트 데이터 쓰기 범위 먼저 승인

새 프론트는 로그인 때 기기 기록을 서버로 병합하므로 **로그인만 해도 import/읽기 테이블 쓰기가 발생할 수 있다.** 읽기·퀴즈·판단 테스트도 운영 DB 쓰기다. 승인된 테스트 계정과 허용 작업을 명시한 뒤 실행한다.

- 로그인·로그아웃·세션 복원, 계정 표시 일치.
- 홈 최초 3개 → 더보기 → 잘린 핵심/관심 뉴스 복원, 중복 없음, 마지막 페이지 숨김.
- 기사 초보/일반/분석 3수준, `요인를`·`(을/를)`·`어요고` 잔존 없음. 오래 저장된 문구는 새 코드만으로 바뀌지 않는다는 점도 구분.
- 용어 팝업, 정의 퀴즈 실제 정답/오답 저장. 원장 1행과 p_known 변경 확인. 당일 같은 기사·개념 재시도 미반영, 다른 기사 반영.
- 판단 제출과 비교 예정일, 실제 중립/중립 CORRECT 표시. 결과를 만들려고 실제 가격이나 판단 결과를 조작하지 않음.
- 차트 DIRECT/MARKET 중립/부호 불일치 문구, 가격 fallback 안내.
- 학습 남은 퀴즈·정의 펼침·복습·승급, 3개 읽기 출석·다른 기기 복원·사용자 격리.
- 빈 추천/유사 뉴스의 성공 빈 섹션 숨김. loading/error와 재시도는 유지.

## 6. 기존 EXCERPT backfill — dry-run 뒤 별도 승인

조회한 저장 DB 연결 기준 2026-11-02 23:52 KST 후보는 **121건**이다. 실제 Render datasource 일치는 미확인이므로 실행 전에 확인된 운영 연결로 건수를 다시 확정한다. 최근 7일·EXCERPT·실제 원문만 대상이다.

아래는 실행하지 않은 명령이다. DB 접속 변수는 승인된 안전한 방법으로 주입하고 값을 출력하지 않는다.

```sh
cd /Users/subin/Desktop/Finsight/finsight-ai
.venv/bin/python -m pip install 'psycopg[binary]>=3.2,<4'
export USE_REAL_LLM=false REWRITE_WITH_LLM=false DOCENT_WITH_LLM=false FEEDBACK_WITH_LLM=false
FINSIGHT_REWRITE_SINCE=$(.venv/bin/python -c 'from datetime import datetime,timedelta,timezone; print((datetime.now(timezone.utc)-timedelta(days=7)).isoformat())')
.venv/bin/python -m scripts.rewrite_news_batch --excerpt --since "$FINSIGHT_REWRITE_SINCE" --limit 1000
# selected 건수·백업·대상을 사용자가 확인하고 승인한 뒤에만:
.venv/bin/python -m scripts.rewrite_news_batch --excerpt --since "$FINSIGHT_REWRITE_SINCE" --limit 1000 --apply
```

실제 `--apply`는 dry-run 건수와 기존 값 백업을 제시한 후 별도 승인한다. 동일 결과·동시 수정·UNAVAILABLE 표 기사는 저장하지 않으므로 후보 수와 UPDATE 수는 다르다. 원문·사용자·판단·퀴즈를 바꾸지 않는다.

## 7. 이미 저장된 표 형식 기사

최신 AI의 `_is_tabular_article` 함수를 그대로 AST로 읽어 모든 news 원문에 읽기 전용 판별을 적용했다. 조회한 저장 DB 연결에서 **대상 0건**, 이 대상들에 대한 judgement/learning_progress/article_note 참조도 0건이다. 실제 운영 DB라는 확정은 없으므로 배포 전 대상 ID·제목·참조 수를 다시 확인해야 한다.

현재 전역 숨김 컬럼/API는 없다. `rewrite_mode=UNAVAILABLE`만 설정해 기사 자체가 숨겨진다고 설명하지 않는다.

- 우선안: 대상이 생기면 별도 검토 PR에서 검색/브리핑 노출용 soft-hide 상태와 필터를 추가하고, 기존 판단/메모의 원문 연결은 보존한다. 이 기능·migration은 이번 release에 추가하지 않았다.
- 삭제안: 확인된 target ID에 한해 모든 news FK 참조를 조사하고, 퀴즈·판단·메모·신규 원장·읽기 기록 등 참조가 없는 행만 삭제 후보로 제시한다. 참조가 있는 행은 삭제하지 않는다. 무조건 CASCADE 삭제를 사용하지 않는다.
- 실제 DELETE는 ID 목록·건수·백업/복원안을 보여준 후 별도 승인한다. 현재 후보가 0건이므로 실행할 삭제 작업도 없다.

## 8. 실패 시 되돌리기

- Render: 배포 직전에 기록한 Live SHA로 다시 배포한다. 아래는 사용자 rollback 승인 후에만 실행한다.

```sh
render deploys create srv-dbfi6favcj2c7388o6a0 --commit "$ROLLBACK_BACKEND_SHA" --wait
```

- Expo: 기존 production deployment를 재승격한다. 이번에 확인한 ID는 v5keac5ci0이나 실제 rollback은 직전 기록값을 사용한다.

```sh
npx eas-cli deploy:alias --prod --id "$ROLLBACK_EXPO_ID" --non-interactive
```

- DB: additive V19/V20과 기록은 남겨둔다. rollback 명목으로 테이블·Flyway 이력을 지우지 않는다. p_known/backfill 필드까지 복원해야 하면 사전 백업과 별도 DB 쓰기 승인이 필요하다.
- Auto Deploy 상태가 켜져 있으면 지정 SHA 배포가 나중에 연결 branch로 덮일 수 있다. 상태를 먼저 확인하고 설정 변경은 별도 승인받는다.

## 9. 시연 당일 잠듦 대책 — 제안만

수동: 시작 10~15분 전에 liveness/readiness를 최대 60초로 호출하고, 로그인/홈/대표 차트 1개를 확인한다. API health가 빨라도 시세·뉴스 첫 조회는 추가 시간이 들 수 있다. 실제 데이터 쓰기가 있는 퀴즈/판단은 승인된 테스트 계정 범위에서만 수행한다.

```sh
curl --fail --max-time 60 https://finsight-backend-gh7c.onrender.com/actuator/health/liveness
curl --fail --max-time 60 https://finsight-backend-gh7c.onrender.com/actuator/health/readiness
```

자동화 제안: 확정된 시연 시작~종료 시간에만 5분 간격 health GET을 보내고 종료 후 멈추는 로컬 스크립트/예약 작업 또는 GitHub Actions schedule을 사용한다. Actions cron은 UTC이므로 KST에서 9시간을 빼서 설정한다. 시간대와 실행 주체가 정해진 뒤 별도 승인하고 설치한다. 이 작업에서는 cron·workflow·자동화를 설정하지 않았다. Mac에서 실행하면 시연 시간에 Mac이 깨어 있어야 한다.
