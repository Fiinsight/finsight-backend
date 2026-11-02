# 초보자 EXCERPT 문법 수정 배포 준비 — 미배포

- 기존 운영 AI 고정: `514a47e827fd758429a7d7ec9af94ac46b14a66b`
- 새 AI 고정: `6fdfa147e5de6ab0f66a0b948ccfcdcc48c8679d`
- AI 브랜치: `codex/beginner-excerpt-grammar`
- backend 준비 브랜치: `codex/pin-beginner-excerpt-grammar`
- 이번 backend 변경은 Dockerfile의 위 SHA 교체와 이 문서뿐입니다. 기존 운영 branch/main을 수정하거나 배포하지 않았습니다.

## 수정 및 검증

간접 인용 동사는 문장 끝에서만 존댓말로 바꿉니다. `기록했다고 밝혔다` → `기록했다고 밝혔어요`가 됩니다. 중요도 문구·용어 치환의 조사를 `_with_particle`로 맞추고, 초보자 본문과 중요도 인용에서 6자리 종목코드를 제거합니다. 금액·연도는 유지합니다.

AI 전체 pytest: **309개 통과**. 운영 DB의 실제 수집 기사 20개를 300자 이하 fixture로 만들었으며 검사한 `어요고`, `어요라고`, 잘못된 조사·잔존 종목코드 패턴은 0건입니다. 기준 코드가 작성하는 SQL의 안전성은 mock DB로 테스트했으며 실제 backfill을 실행하지 않았습니다.

## 배포 전·후 순서

1. 새 AI 커밋과 이 Dockerfile 차이를 검토합니다. 이전에 생성한 main 정리 PR들과의 포함 관계도 확인하고 main에 반영할 순서를 정합니다. 이번 작업에서 main 정리 PR은 머지하거나 변경하지 않았습니다.
2. 사용자 승인 후에만 이 AI SHA가 포함된 backend를 배포합니다. 기존 Java/Python 토큰 공유 entrypoint를 유지하고 유료 LLM을 켜지 않습니다.
3. health와 새 기사/학습 EXCERPT 응답으로 문장·조사·숫자·종목코드를 확인합니다.
4. 기존 저장값은 새 코드 배포만으로 갱신되지 않으므로, 아래 계획을 별도로 검토하고 승인한 뒤에만 최근 7일 backfill을 수행합니다.

## 기존 guarded manual backfill과 차이

기존 backend `scripts/backfill_rewrites.py`는 Gemini 활성화와 REWRITTEN 성공 결과만 허용하므로 무료 EXCERPT 수정에 맞지 않습니다. 그 도구를 실행하거나 조건을 해제하지 않았습니다.

새 AI 커밋은 기존 AI `scripts/rewrite_news_batch.py`에 `--excerpt` 모드를 추가합니다. 기존 읽기 전용 DB helper와 snapshot 검사 방식을 재사용합니다. `allow_llm=False`이며 오래된 모델 응답 캐시를 재사용하지 않습니다. NULL/REWRITTEN 기사는 보존하고, 최근 7일 published_at의 EXCERPT만 처리합니다. 요약 3종과 중요도 4필드를 함께 갱신하며 모든 기존값·원문·모드·발행시각·retry 상태가 일치할 때만 저장합니다. 동시 변경과 동일 결과는 스킵합니다.

## 예상 대상 수 — 읽기 전용 조회만 실행

2026-11-02 조회 시점, 설정된 Supabase DB의 전체 뉴스 534건 중 최근 7일 111건, 무료 EXCERPT 재생성 대상 **111건**입니다. 운영 API의 기사 id 10개와 DB id 10개가 모두 일치했습니다. 최신 발행시각은 `2026-11-02T05:44:05Z`였습니다. 별도 로컬 DB는 전체 423건이며 최근 7일 대상 0건으로 구분했습니다.

111건은 실제 UPDATE의 상한입니다. 같은 결과·동시 변경은 스킵되며, 실행 시점에는 기사 수집과 7일 범위 이동 때문에 달라질 수 있습니다. 운영 count와 조회는 read-only 세션에서 수행했고 생성·UPDATE는 하지 않았습니다.

AI 저장소 `docs/beginner-excerpt-grammar-2026-11-02.md`에 대상 COUNT SQL과 실행 전제 조건이 있습니다.

## 실행할 명령 예시 — 실행하지 않았음

새 AI 커밋 체크아웃의 환경에서, DB_URL/DB_USERNAME/DB_PASSWORD는 검토한 대상 DB의 보안 환경으로 전달합니다. 비밀값을 명령에 직접 적거나 로그에 출력하지 않습니다. psycopg는 기존 선택적 DB 도구 의존성이며 현재 개발 venv에는 설치되어 있지 않습니다. 전체 ML 패키지 대신 아래 DB 드라이버만 별도로 준비할 수 있습니다.

```sh
.venv/bin/python -m pip install 'psycopg[binary]>=3.2,<4'
FINSIGHT_REWRITE_SINCE=$(.venv/bin/python -c 'from datetime import datetime,timedelta,timezone; print((datetime.now(timezone.utc)-timedelta(days=7)).isoformat())')

# 1. 대상 미리보기만: DB 쓰기·텍스트 생성 없음
USE_REAL_LLM=false REWRITE_WITH_LLM=false .venv/bin/python -m scripts.rewrite_news_batch \
  --excerpt --since "$FINSIGHT_REWRITE_SINCE" --limit 111

# 2. 대상 수·환경을 확인하고 별도 승인한 뒤에만 저장
USE_REAL_LLM=false REWRITE_WITH_LLM=false .venv/bin/python -m scripts.rewrite_news_batch \
  --excerpt --since "$FINSIGHT_REWRITE_SINCE" --limit 111 --apply
```

기존값 백업·대상 수 확인 후 적용합니다. 원문·사용자 판단·퀴즈 기록은 갱신 대상이 아닙니다. 한 번에 최대 1,000개를 읽으며, 위 limit은 실행 직전 확인한 수로 정해야 합니다.

## 복구

배포 전 문제가 확인되면 새 branch를 머지/배포하지 않습니다. 배포 후 문제가 생기면 이전 backend 운영 SHA `7997f1daa6d2bcc6feff808a236c345b3e62a0a9`(AI 514a47e 고정)로 되돌릴 수 있습니다. 저장된 리라이팅은 코드 롤백만으로 복원되지 않으므로 backfill 승인 시 기존 필드 백업도 함께 검토합니다.
