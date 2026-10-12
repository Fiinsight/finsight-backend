# 로컬 시연용 데이터 (승인 전 실행 금지)

`seed_demo.py`는 **별도 로컬 `finsight_demo` PostgreSQL DB**에만 기록을 넣습니다. 운영 DB나 현재 개발 DB `finsight`에는 삽입할 수 없습니다. 기존 앱의 데이터·테이블·환경 파일을 수정하지 않습니다.

준비: 사용자 승인 후 별도 로컬 DB를 만들고 기존 백엔드의 엔티티 구조로 초기화해야 합니다. 자동 뉴스 수집은 실행 인자 `--finsight.news.collection.enabled=false`로 비활성화합니다. 기존 비용·캐시·호출 제한은 그대로 둡니다. 이 문서 작성 단계에서는 DB 생성·초기화·시드 실행을 하지 않았습니다.

로그인 비밀번호는 기존 **로컬 QA 계정**의 BCrypt 해시를 복사합니다. `--source-email`은 qa- 또는 finsight-demo로 시작하는 `@example.invalid` 계정만 허용합니다. 실제 사용자 계정은 사용하지 않습니다. 비밀번호를 콘솔·파일에 출력하지 않으며 DB 비밀번호는 기존 PostgreSQL 인증 환경을 사용합니다. 새 패키지나 pgcrypto 확장을 설치하지 않습니다.

승인 후, 기본 개발 DB 이름이 `finsight`이고 별도 시연 DB가 준비된 경우:

```sh
python3 scripts/seed_demo.py \
  --source-database finsight \
  --source-email qa-existing@example.invalid \
  --confirm-local-demo
```

예시 source-email을 실제 로컬 QA 계정으로 바꿔야 합니다. `finsight-demo@example.invalid` 계정이 생성되며 원본 QA 계정과 같은 비밀번호를 사용합니다. 목적지/원본 DB 연결은 loopback 주소와 서버 자체 주소를 확인합니다. DB명은 finsight_demo, 포트는5432만 허용하며 원본 DB는 finsight 또는 finsight_demo 읽기 조회만 합니다.

생성 내용: 시연 계정1개, 가상 기사3개, 결과·등락률·피드백이 채워진 판단3건, 메모3건, 퀴즈 기록3건(정답1/오답2), 온보딩 설정. 모든 기사/문구는 시연용 가상 데이터로 표시하며 관련 종목은 비워 외부 시세 조회를 만들지 않습니다. `SYNTHETIC_QA` 뉴스는 실제 뉴스 피드와 섞지 않습니다. 이는 실제 투자 성과나 기사 원문이 아닙니다.

한 트랜잭션으로 삽입합니다. 같은 계정/URL이 이미 있으면 전체 실패하여 기존 데이터를 덮어쓰지 않습니다. 삭제·재실행·복구는 자동으로 하지 않습니다.

DB 없이 안전장치만 검증:

```sh
python3 scripts/seed_demo.py --self-test
```
