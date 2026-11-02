# 머지·배포 상태 — 2차 점검

머지·배포는 하지 않았습니다. 모든 미머지 변경은 **사용자 결정 필요**입니다.

## 현재 운영 근거

- Render 대시보드: Live `295110f8bd4401ae179173d277a35371c344d539`, deployment `dep-dbgn4n1srm7s73ea6pug`, Free, main 추적, Auto-Deploy disabled를 읽기 전용으로 확인했습니다. 환경변수 값은 열거나 변경하지 않았습니다.
- Expo 공개 HTML: `index-3ac01bd9f79f88ad9fc2c70d0e34d11f.js`. 10/28 배포 보고서의 `da0870e` 번들과 일치합니다. EAS 배포 API에서 커밋 메타데이터를 직접 읽은 것은 아닙니다.
- 현재 Render 커밋의 Dockerfile은 AI `6aa00e9`를 고정합니다. 로컬/PR의 pin `514a47e`와 구분합니다.

## main보다 앞선 커밋과 운영 포함 여부

2차 작업 시작 시점의 기존 QA PR 스택을 기준으로 대조했습니다. 이번 2차 수정 자체도 아직 미배포이며 별도 PR 목록에 기록합니다.

| 저장소 | 커밋 | 내용 | 운영 포함 | 판단 근거 | 결정 |
|---|---|---|---|---|---|
| frontend | `1b145d7` | fix: use configured API URL for hosted demo | 반영 | 현재 운영 커밋/번들의 조상 | 사용자 결정 필요 |
| frontend | `10d7746` | fix: allow free server cold starts to finish | 반영 | 현재 운영 커밋/번들의 조상 | 사용자 결정 필요 |
| frontend | `7ed1387` | Configure Expo Hosting production deployment | 반영 | 현재 운영 커밋/번들의 조상 | 사용자 결정 필요 |
| frontend | `b5f812d` | Handle OAuth return before guest onboarding | 반영 | 현재 운영 커밋/번들의 조상 | 사용자 결정 필요 |
| frontend | `a08198c` | Upgrade Expo Go development app to SDK 58 | 반영 | 현재 운영 커밋/번들의 조상 | 사용자 결정 필요 |
| frontend | `f89051f` | Persist Expo Go auth sessions in native storage | 반영 | 현재 운영 커밋/번들의 조상 | 사용자 결정 필요 |
| frontend | `f379f3e` | Extend onboarding gradient through iOS safe areas | 반영 | 현재 운영 커밋/번들의 조상 | 사용자 결정 필요 |
| frontend | `cabb18e` | Fix deploy QA issues: auth layout, reading retry, accessible controls and preference races | 반영 | 현재 운영 커밋/번들의 조상 | 사용자 결정 필요 |
| frontend | `9b70e70` | Simplify auth screen: login-first entry, two-field signup, Kakao delay notice | 반영 | 현재 운영 커밋/번들의 조상 | 사용자 결정 필요 |
| frontend | `12cd9ec` | Restore market and chart retries and preserve Kakao connection handling | 반영 | 현재 운영 커밋/번들의 조상 | 사용자 결정 필요 |
| frontend | `da0870e` | Limit home briefing to three articles including watchlist news | 반영 | 현재 운영 커밋/번들의 조상 | 사용자 결정 필요 |
| frontend | `9abe441` | fix: patch compatible Expo dependencies and audit findings | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| frontend | `606c1da` | fix: render selected reading level investment point | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| frontend | `8e7e505` | fix: poll judgement history only when results are overdue | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| frontend | `2daf862` | fix: load judgement detail and completion from stable URL ids | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| frontend | `ee06a93` | fix: label rewritten, rule-based and legacy explanations honestly | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| frontend | `8910aef` | fix: show source-grounded level summary and closing-price basis | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| frontend | `99caa5a` | fix: label demo records and exclude them from real hit counts | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| frontend | `c99baac` | fix: shorten onboarding to three explicit next-step questions | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| frontend | `e899bc3` | fix: collapse history attendance into one expandable row | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| frontend | `c471a75` | fix: default to profile reading level with one selector and source footer | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| frontend | `2c1a760` | fix: simplify learning metrics into one checklist and reduce repeated copy | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| frontend | `b350a18` | fix: use one bounded home loading state with delayed wake-up copy | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| frontend | `199a321` | fix: start overdue polling when a visible pending result becomes due | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| frontend | `a3b45fd` | docs: attach local responsive UI before-after and navigation evidence | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| backend | `3203bd5` | Fix deploy QA issues: enable bundled retrieval and recover KIS failures | 반영 | 현재 운영 커밋/번들의 조상 | 사용자 결정 필요 |
| backend | `3f37307` | Stabilize free instance health checks and bundled news retrieval | 반영 | 현재 운영 커밋/번들의 조상 | 사용자 결정 필요 |
| backend | `6c62089` | Bound related-news query cost and retain keyword results on AI delay | 반영 | 현재 운영 커밋/번들의 조상 | 사용자 결정 필요 |
| backend | `aad0674` | Allow Kakao cold TLS connections and speed up free-instance startup | 반영 | 현재 운영 커밋/번들의 조상 | 사용자 결정 필요 |
| backend | `295110f` | Allow slow KIS token issuance and avoid caching unavailable market summaries | 반영 | 현재 운영 커밋/번들의 조상 | 사용자 결정 필요 |
| backend | `d8b3596` | fix: share OAuth state and bound authentication requests | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| backend | `bcb0d54` | fix: authenticate internal AI requests | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| backend | `ab66247` | test: preserve article evidence and source across reading levels | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| backend | `26328df` | fix: continue grading across 2027 and unknown calendar years | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| backend | `eee02c7` | fix: request owner-scoped asynchronous overdue grading on reads | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| backend | `2a7f049` | fix: bound stale-news catch-up after home requests | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| backend | `e41f105` | fix: bound briefing ranking and reject undated candidates | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| backend | `41bf93d` | fix: preserve and expose actual feedback generation mode | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| backend | `850f79e` | fix: pin reviewed AI boundary and excerpt quality revision | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| backend | `80aa591` | docs: add read-only QA queries and AI enablement options | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| backend | `f915aff` | fix: expose trading-day baseline and comparison dates for judgement entry | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| backend | `636f178` | fix: exclude marked demo outcomes from learning judgement statistics | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| backend | `d702d03` | fix: show cached index movement when chart news is missing | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| backend | `b0d4330` | docs: record issue fixes, test evidence, PRs and remaining decisions | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| backend | `879cb78` | docs: record successful code PR checks and report review link | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| ai | `78b71b0` | fix: bound and authenticate internal AI service | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| ai | `863471d` | fix: ground investment points in quoted article facts | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |
| ai | `514a47e` | fix: make excerpt levels distinct and preserve explicit article risks | 미반영 | 현재 운영 커밋의 조상이 아님 | 사용자 결정 필요 |

## 이전 해시와 현재 PR 대응

- backend `305af57`은 이전 커밋 해시이며, 현재 `fix/ai-service-boundary-client` PR #142의 내부 인증 변경에 대응합니다. PR 스택 정리로 커밋 해시는 바뀌었지만 변경 목적은 같습니다.
- ai `78b71b0`은 내부 인증 경계 PR #64입니다. 운영 AI `6aa00e9`에는 포함되지 않습니다.
- main에 없더라도 수동 특정 커밋 배포로 운영에 반영된 변경이 있습니다. 카카오 20초·KIS 토큰 지연 수정은 그 사례이며, 미머지와 미배포를 동일시하지 않았습니다.

## 배포 전 내부 AI 인증 체크리스트

| backend 토큰 | AI 토큰 | AI 응답 | backend 처리 |
|---|---|---|---|
| 없음 | 없음 | 인증을 요구하지 않는 loopback/private 모드 | 무료 규칙/EXCERPT 정상. 누락이 반드시 실패하는 구조는 아님 |
| 없음/다른 값 | 설정됨 | 401 | rewrite Optional.empty → 원문·URL·시간 보존, EXCERPT 상태(재작성 본문은 없음). feedback/docent는 기존 RULE fallback |
| 설정됨 | 없음 | AI가 헤더를 검증하지 않아 수락 | 인증된 연결로 간주하면 안 됨 |
| 동일한 값 | 동일한 값 | 정상 | 기존 비용 스위치/한도 유지 |

- 신규 AI pin을 올릴 때 backend·AI에 같은 AI_INTERNAL_TOKEN을 제공해야 합니다. 단일 Render 컨테이너에서는 하나의 환경변수가 두 자식 프로세스에 상속됩니다. 값은 보고서/브라우저 번들/로그에 넣지 않습니다.
- 다중 토큰 불일치 테스트는 MockWebServer/FastAPI TestClient만 사용하며 외부 LLM을 호출하지 않습니다.
- EXCERPT 상태는 재작성 성공이 아닙니다. AI 전체 인증이 실패하면 읽기 설명은 UNAVAILABLE로 안내될 수 있고 원문 링크는 남습니다.
- 이번 점검은 체크리스트만 작성했으며 Render 환경변수·서비스 배포는 변경하지 않았습니다.

- 구 AI `6aa00e9`는 인증 경계 자체가 없으므로 신규 백엔드가 토큰을 보내도 성공할 수 있습니다. 요청 성공만으로 보호 적용을 판단하지 말고 Docker pin·AI 버전을 함께 확인해야 합니다.

- 토큰은 Render의 독립 `AI_INTERNAL_TOKEN` 환경변수로 제공해야 합니다. Spring 전용 `SPRING_APPLICATION_JSON` 안에만 넣으면 Python 프로세스의 `os.getenv`에는 전달되지 않습니다. 값을 노출하지 않고 양쪽 설정 여부와 인증 401/성공 동작으로 확인합니다.
