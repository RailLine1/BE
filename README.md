# Daybit BE

Daybit의 백엔드입니다. Kotlin + Spring Boot 4, Java 21, PostgreSQL 17로 구성했습니다. 동작 기준은 팀 기획 문서([RailLine1/Docs](https://github.com/RailLine1/Docs): 01_기획서 · 02_정책서 · 03_화면목록 · 06_기술스택 · 07_핵심기술)이고, API와 DB 문서는 코드와 함께 바뀌므로 이 저장소에 둡니다.

| 문서 | 내용 |
|---|---|
| [docs/api.md](docs/api.md) | 공통 규칙, 인증 흐름, 오류 코드, 엔드포인트 목록(구현 상태) |
| [docs/db.md](docs/db.md) | 테이블 관계, 핵심 제약 |
| [docs/decisions.md](docs/decisions.md) | 기술 결정, 기획 문서와 다른 구현 |
| [docs/deploy.md](docs/deploy.md) | Railway 배포와 환경 변수 |

## 실행

JDK 21과 Docker Desktop이 필요합니다.

```bash
./gradlew bootRun
```

`compose.yaml`의 Postgres 17이 함께 시작되고, Flyway가 `src/main/resources/db/migration`의 SQL로 테이블을 만듭니다. 호스트 포트는 5433입니다. 서버를 종료해도 DB 컨테이너는 유지됩니다.

| 주소 | 용도 |
|---|---|
| http://localhost:8080/swagger-ui | API 문서 |
| http://localhost:8080/v3/api-docs | OpenAPI JSON. FE의 Orval 입력입니다 |

## 테스트

```bash
./gradlew test
```

Testcontainers가 테스트마다 Postgres 17을 띄우므로 Docker Desktop이 켜져 있어야 합니다. 테스트는 실제 DB에서 HTTP 요청부터 응답까지 확인하는 통합 테스트 위주입니다.

## 배포

Railway에 서버와 PostgreSQL 17을 올립니다. 환경 변수와 배포 후 확인 방법은 [배포 가이드](docs/deploy.md)에 있습니다.

## 브랜치

기능 브랜치에서 작업하고 `main`으로 PR을 엽니다. `main`은 Railway가 자동 배포하며 FE가 이 서버를 사용합니다. 시연 직전에 `main`에서 `release` 브랜치를 한 번 만들어 시연 버전을 고정하고, 배포를 `release`로 전환합니다. 작업 규칙 전체는 [AGENTS.md](AGENTS.md)에 있습니다.

## 구조

```
src/main/kotlin/app/daybit/
├─ global/        설정, 오류 응답, 보안(JWT), OpenAPI
├─ auth/          가입 · 로그인 · 토큰
├─ user/          사용자 · 내 정보
├─ course/        과목 · 멤버 · 가입 요청
├─ explore/       탐색 · 구독
├─ notification/  알림 기록
└─ material, bit, archive, connector, myai, push
```

`eval/`은 문제 생성 프롬프트를 바꿔 가며 품질을 비교하는 로컬 실험 도구입니다. 서버에는 포함되지 않습니다([eval/README.md](eval/README.md)).

`material` 이하 패키지는 요청·응답 형식만 확정한 상태이며, 호출하면 `501 NOT_IMPLEMENTED`를 반환합니다. FE는 이 형식으로 Orval 클라이언트와 mock을 만들어 먼저 개발할 수 있습니다. `notification`은 알림을 DB에 기록하기만 하며, 푸시 발송은 아직 연결하지 않았습니다.

## 인증

`POST /api/v1/auth/signup`과 `/login`은 `{ user, isFirstLogin, tokens: { accessToken, refreshToken, expiresIn } }`를 반환합니다. 이후 요청에는 `Authorization: Bearer <accessToken>`을 붙입니다. 액세스 토큰은 30분, 리프레시 토큰은 14일 동안 유효합니다. `POST /api/v1/auth/refresh`로 새 토큰 쌍을 받으며, 사용한 리프레시 토큰은 즉시 폐기됩니다.

오류는 항상 `{ "error": { "code": "...", "message": "..." } }` 형식입니다. 화면에는 `message`를 그대로 보여주고, 분기는 `code`로 합니다.
