# AI 활성화 선택지 — 사용자 결정용 초안

현재 운영 이미지는 AI 6aa00e9를 고정하고 유료 기능 스위치를 모두 끈다. 실제 GPU 모델은 이미지에 포함하지 않는다. 이 작업에서는 환경변수·키·배포·DB를 변경하지 않았다.

| 선택 | 비용·자원 | 실패 시 동작 | 검증 |
|---|---|---|---|
| A. 수집 시 Gemini 리라이팅 | 일부 모델/계정의 무료 티어 범위 내 사용. 일일 30회는 앱 자체 상한 예시이며 공급자 무료 한도 보장이 아니다. 청구 연결·계정·모델에 따라 비용이 생길 수 있어 사용자가 AI Studio의 실제 한도를 확인해야 한다. | timeout·검증 실패 시 EXCERPT, quota 오류는 기존 retry/circuit과 일일·프로세스 예산으로 제한 | mock 테스트 → 로컬 1건 opt-in → 원문 수치 보존/해요체/분석 세 칸/REWRITTEN 확인 → 승인된 수집만 실행 |
| B. 로컬 Ollama 배치 | 외부 LLM API 과금 없이 개인 PC의 모델 다운로드·디스크·RAM·CPU/GPU·전력 사용. Render Free에 모델을 올리지 않는다. | 생성 또는 원문 검증 실패 시 EXCERPT. dry-run은 모델 호출·DB 쓰기 없음 | 로컬 DB 복사본과 적은 cohort로 dry-run → JSONL 원문 비교 → 조건부 UPDATE 검증 → 별도 승인 후 운영 반영 결정 |

A 설정 초안(이번 작업에서 적용하지 않음): `REWRITE_WITH_LLM=true`, `LLM_PROVIDER=gemini`, `GEMINI_API_KEY`, `MAX_DAILY_REWRITE_CALLS=30`. `USE_REAL_LLM=false`, `USE_REAL_EMBEDDINGS=false`, `DOCENT_WITH_LLM=false`, `FEEDBACK_WITH_LLM=false` 유지. 무료 이미지 requirements에는 google-genai가 없으므로 현재 Dockerfile의 변수만 켜면 실제 호출이 동작하지 않는다. 별도 이미지 의존성과 고정 모델 검증을 PR로 준비해야 한다. 키는 Render 비밀 환경변수에서만 관리한다.

리라이팅은 NewsAssembler 수집 파이프라인에만 허용한다. 화면의 뉴스 상세/학습/차트/피드백 요청으로 생성하지 않는다. `POST /api/news/collect`는 기존 재시도를 포함하므로 운영에서 무심코 실행하지 않는다.

B 절차 초안(실행하지 않음):

1. 모델 라이선스와 PC 자원을 확인하고 Ollama를 loopback에만 실행한다.
2. 로컬 복사본 DB를 대상으로 명시적 신규 수집 cohort의 `created_at` 하한을 정한다. 기존 전체 아카이브를 선택하지 않는다.
3. finsight-ai에서 `LLM_PROVIDER=ollama`, `OLLAMA_MODEL=<검증한 로컬 모델>`, `REWRITE_WITH_LLM=true`를 로컬 프로세스에만 설정한다.
4. `python -m scripts.rewrite_news_batch --since <시간대가 포함된 ISO 시각> --limit 10`으로 무쓰기 dry-run을 검토한다.
5. 로컬 DB에서만 `--apply --output <비교용 JSONL>`을 추가해 결과를 검증한다. 이 옵션은 DB 쓰기를 수행한다. 운영에는 이번 지시상 적용 금지다.
6. 원문 숫자·기업명·위험 문장·EXCERPT/REWRITTEN을 비교하고 충돌 시 조건부 갱신이 저장을 막는지 확인한다. 운영 반영 SQL·백필은 별도 사용자 결정 사항이다.

공식 근거: [Gemini 가격](https://ai.google.dev/gemini-api/docs/pricing), [프로젝트별 제한 확인](https://ai.google.dev/gemini-api/docs/rate-limits). 2026-11-01 조회. 무료 모델/한도는 고정값으로 약속하지 않는다.
