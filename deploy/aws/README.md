# AWS 배포 가이드

FinSight 백엔드는 Docker 이미지로 패키징되므로 AWS EC2에서 실행할 수 있습니다.
졸업작품에서는 먼저 EC2 한 대에 백엔드 컨테이너를 실행하고, PostgreSQL은 RDS로 분리하는 구성을 권장합니다.

## 권장 구성

```text
모바일 앱 / 프론트엔드
          │ HTTPS
          ▼
EC2 (Docker: Spring Boot)
          │ private network
          ▼
RDS PostgreSQL
```

Redis는 비용을 줄이기 위해 초기에는 EC2 내부 컨테이너로 운영할 수 있습니다.
트래픽이 늘거나 고가용성이 필요해지면 ElastiCache로 분리합니다.

## EC2 실행

EC2에 Docker를 설치한 뒤 이미지를 빌드하고 실행합니다.

```bash
git clone https://github.com/Fiinsight/finsight-backend.git
cd finsight-backend
docker build -t finsight-backend .
docker run -d --name finsight-backend --restart unless-stopped \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DB_URL="$DB_URL" \
  -e DB_USERNAME="$DB_USERNAME" \
  -e DB_PASSWORD="$DB_PASSWORD" \
  -e REDIS_HOST="$REDIS_HOST" \
  -e REDIS_PORT="$REDIS_PORT" \
  -e AI_BASE_URL="$AI_BASE_URL" \
  -e FRONTEND_ORIGIN="$FRONTEND_ORIGIN" \
  finsight-backend
```

실제 값은 명령어에 직접 적지 말고 SSM Parameter Store 또는 Secrets Manager에서 주입합니다.
`.env` 파일은 EC2에 복사하거나 GitHub에 커밋하지 않습니다.

## 보안 체크리스트

- 루트 계정으로 작업하지 않고 IAM 사용자 또는 역할을 사용합니다.
- IAM 권한은 필요한 리소스에만 최소 권한으로 부여합니다.
- 루트 및 IAM 계정에 MFA를 설정합니다.
- EC2 Security Group은 80/443만 인터넷에 공개하고, SSH 22번 포트는 개인 IP만 허용합니다.
- RDS는 퍼블릭 액세스를 끄고 EC2 Security Group에서만 접근하도록 설정합니다.
- DB 비밀번호, KIS 키, ECOS 키, DART 키는 Secrets Manager 또는 SSM에 저장합니다.
- HTTPS 인증서를 적용하고 API 로그에 비밀번호·토큰·API 키가 남지 않게 합니다.
- CloudWatch 로그와 비용 알림(AWS Budgets)을 설정합니다.
- 사용하지 않을 때 EC2·RDS를 중지하거나 삭제하고, 스냅샷과 백업을 확인합니다.

## 운영 환경변수

운영 프로필은 다음 값을 필수로 받습니다.

```text
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:postgresql://<rds-endpoint>:5432/<database>
DB_USERNAME=<secret>
DB_PASSWORD=<secret>
REDIS_HOST=<redis-host>
REDIS_PORT=6379
AI_BASE_URL=<ai-service-url>
FRONTEND_ORIGIN=<frontend-origin>
```

앱이 시작되면 Flyway가 `src/main/resources/db/migration`의 마이그레이션을 적용하고,
Hibernate는 스키마를 변경하지 않고 검증만 합니다.
