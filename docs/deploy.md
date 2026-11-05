# Railway 배포 가이드

2026-11-02 · 백엔드 → 배포 담당

Railway에 Daybit 백엔드와 PostgreSQL 17을 올리는 방법입니다. 개발 기간에는 `main` 브랜치를, 시연 직전부터는 `release` 브랜치를 배포합니다. FE를 포함한 전체 배포 구성은 Docs 저장소의 `08_배포.md`에 있습니다.

## 구성

| 서비스 | 내용 |
|---|---|
| `BE` | 이 저장소(`RailLine1/BE`)입니다. Gradle 프로젝트이므로 Railway가 Java 21로 자동 빌드합니다 |
| `Postgres` | Railway PostgreSQL 17입니다. 선택 이유는 [결정 기록 D1](decisions.md#d1-db는-postgresql-17)에 있습니다 |

빌드 결과물은 `build/libs/daybit-server-0.0.1-SNAPSHOT.jar` 하나입니다. plain jar는 만들지 않도록 설정했습니다. 테이블은 서버가 처음 시작할 때 Flyway가 만들기 때문에 DB에 직접 SQL을 실행할 필요가 없습니다.

## 배포 브랜치

| 기간 | 배포 브랜치 | 이유 |
|---|---|---|
| 개발 기간 | `main` | PR이 병합될 때마다 자동 배포되어 FE가 최신 API를 사용합니다 |
| 시연 직전부터 | `release` | `main`에서 `release`를 만든 뒤 Railway 서비스의 배포 브랜치를 바꿉니다. 이후 `main`의 변경은 시연 서버에 영향을 주지 않습니다 |

## 환경 변수 (`BE` 서비스)

### 필수

| 이름 | 값 | 설명 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` | 운영 설정을 적용합니다 |
| `PGHOST` | `${{Postgres.PGHOST}}` | Railway 변수 참조입니다. Postgres 서비스 이름이 다르면 그 이름으로 바꿉니다 |
| `PGPORT` | `${{Postgres.PGPORT}}` | |
| `PGDATABASE` | `${{Postgres.PGDATABASE}}` | |
| `PGUSER` | `${{Postgres.PGUSER}}` | |
| `PGPASSWORD` | `${{Postgres.PGPASSWORD}}` | |
| `DAYBIT_JWT_SECRET` | 32자 이상의 임의 문자열 | 로그인 토큰 서명 키입니다. **값이 없으면 서버가 시작하지 않습니다.** 예: `openssl rand -base64 48` |
| `DAYBIT_PUBLIC_BASE_URL` | `https://<BE 도메인>` | 백엔드 공개 주소입니다. 커넥터 주소와 GitHub 로그인 콜백에 사용합니다 |
| `DAYBIT_CORS_ORIGINS` | `https://*.expo.app,http://localhost:8081` | 웹 프론트 주소입니다. 쉼표로 여러 개를 넣을 수 있고 `*` 패턴을 쓸 수 있습니다. EAS Hosting은 미리보기 배포마다 주소가 바뀌므로 패턴으로 허용합니다. 별도 도메인을 연결하면 그 주소를 추가합니다. 앱(APK · iOS)은 CORS 대상이 아닙니다 |

Postgres 서비스가 제공하는 **`DATABASE_URL`은 넣지 않습니다.** `postgresql://...` 형식이라 Spring이 읽지 못합니다. 서버는 위의 `PG*` 다섯 개로 JDBC 주소를 직접 만듭니다. `DATABASE_URL`을 꼭 써야 한다면 `jdbc:postgresql://호스트:포트/DB` 형식으로 넣고 `DATABASE_USERNAME`과 `DATABASE_PASSWORD`도 함께 넣습니다.

`PORT`는 Railway가 자동으로 넣으며 서버가 그 값을 사용합니다.

### 선택

| 이름 | 기본값 | 설명 |
|---|---|---|
| `DAYBIT_SWAGGER_ENABLED` | `true` | 해커톤 기간에는 켜 둡니다. FE가 배포 서버의 `/v3/api-docs`로 API 클라이언트를 생성합니다 |
| `DAYBIT_DEV_TOOLS` | `false` | `true`면 개발자 전용 API(`/api/v1/dev/**`: Bit 바로 열기, 내 풀이 지우기, Bit 지우기)를 켭니다. 점검 · 시연 기간에만 켜고 끝나면 지웁니다 (`docs/api.md` 개발자 전용) |

### 문제 생성 (Gemini)

값이 없으면 서버는 정상적으로 뜨지만 문제를 만들지 않습니다. 문제가 없으면 Bit도 열리지 않습니다.

| 이름 | 값 | 설명 |
|---|---|---|
| `DAYBIT_GEMINI_API_KEY` | Google AI Studio API 키 | https://aistudio.google.com/apikey 에서 발급합니다 |
| `DAYBIT_GEMINI_MODEL` | 기본값 `gemini-3.8-flash,gemini-flash-latest,gemini-3.5-flash,gemini-flash-lite-latest` | 쉼표로 여러 개를 넣으면 앞에서부터 시도합니다. 모델이 없어졌거나 혼잡하면 다음 모델을 씁니다. 바꿀 일이 있을 때만 넣습니다 |

### 학습자료 저장 (Cloudflare R2)

값을 넣지 않으면 서버 디스크에 저장합니다. Railway는 재배포할 때 디스크를 비우므로, 올린 자료가 사라지지 않게 하려면 R2를 설정합니다.

| 이름 | 값 | 설명 |
|---|---|---|
| `DAYBIT_STORAGE_TYPE` | `s3` | R2를 사용합니다. 기본값은 `local`(디스크)입니다 |
| `DAYBIT_R2_ENDPOINT` | `https://<계정 ID>.r2.cloudflarestorage.com` | R2 버킷 설정 화면의 S3 API 주소입니다. 끝의 버킷 이름은 뺍니다 |
| `DAYBIT_R2_BUCKET` | 버킷 이름 | 비공개 버킷을 만듭니다. 공개 접근 설정은 필요 없습니다 |
| `DAYBIT_R2_ACCESS_KEY` | R2 API 토큰의 Access Key ID | 해당 버킷의 객체 읽기 · 쓰기 권한이 필요합니다 |
| `DAYBIT_R2_SECRET_KEY` | R2 API 토큰의 Secret Access Key | |

### 푸시 알림 (Firebase)

값이 없으면 알림은 DB에만 기록되고 푸시는 보내지 않습니다.

| 이름 | 값 | 설명 |
|---|---|---|
| `DAYBIT_FIREBASE_CREDENTIALS` | 서비스 계정 JSON | Firebase 콘솔 › 프로젝트 설정 › 서비스 계정 › 새 비공개 키 생성으로 받은 JSON입니다. 원문 그대로 넣거나, 줄바꿈 문제가 있으면 `base64 -w0 key.json` 결과를 넣습니다. iOS로 보내려면 Firebase에 APNs 키도 등록해야 합니다 |

### GitHub 로그인

값이 없으면 GitHub 로그인 버튼을 누를 때 GitHub가 오류 화면을 보여 줍니다. 아이디 로그인은 영향이 없습니다.

| 이름 | 값 | 설명 |
|---|---|---|
| `DAYBIT_GITHUB_CLIENT_ID` | OAuth 앱 Client ID | GitHub › Settings › Developer settings › OAuth Apps에서 만듭니다. **Authorization callback URL**은 `https://<BE 도메인>/api/v1/auth/github/callback`입니다 |
| `DAYBIT_GITHUB_CLIENT_SECRET` | OAuth 앱 Client Secret | |
| `DAYBIT_WORKSPACE_TEMPLATE` | 기본값 `RailLine1/AI` | AI 작업 공간 저장소를 만들 때 복사할 템플릿입니다. 이 저장소는 공개이고 Settings › General › **Template repository**가 켜져 있어야 합니다 |
| `DAYBIT_GITHUB_REDIRECTS` | 기본값 `daybit://*,exp://*,http://localhost:*` | 로그인 뒤 돌아갈 앱 주소 패턴입니다. 웹 프론트 주소(예: `https://*.expo.app/*`)를 쓰면 추가합니다 |

## 배포 후 확인

| 주소 | 기대 결과 |
|---|---|
| `https://<BE 도메인>/actuator/health` | `{"status":"UP", ...}`. Railway Healthcheck Path로 사용할 수 있습니다 |
| `https://<BE 도메인>/swagger-ui` | API 문서 화면 |
| `https://<BE 도메인>/v3/api-docs` | OpenAPI JSON |

로컬에서 jar를 `prod` 프로필과 같은 형식의 `PG*`, `PORT` 변수로 실행해 위 세 주소의 응답을 확인했습니다. Railway 환경에서의 실행은 아직 확인하지 않았습니다.

## 오류별 원인

| 증상 | 원인 |
|---|---|
| `Could not resolve placeholder 'DAYBIT_JWT_SECRET'` | `DAYBIT_JWT_SECRET`이 없습니다 |
| `Driver ... claims to not accept jdbcUrl, postgresql://...` | `DATABASE_URL`에 Railway 기본 형식이 들어갔습니다. 지우거나 JDBC 형식으로 바꿉니다 |
| `Connection refused`, `password authentication failed` | `PG*` 참조의 서비스 이름이나 값이 맞지 않습니다 |
| Flyway 오류 | DB에 다른 테이블이 이미 있으면 발생합니다. 새 Postgres로 시작합니다 |
