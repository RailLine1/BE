# Daybit BE 작업 규칙

AI 에이전트(Claude Code, Codex 등)가 이 저장소에서 작업할 때 따르는 규칙이다. Codex는 이 파일을 직접 읽고, Claude Code는 `CLAUDE.md`의 `@AGENTS.md`로 읽는다. 저장소 소개는 [README.md](README.md)에 있다.

## 기준 문서

팀 기획 문서(01_기획서 · 02_정책서 · 03_화면목록 · 06_기술스택 · 07_핵심기술)가 동작의 기준이다. 원본은 `RailLine1/Docs` 저장소이며, 이 저장소와 같은 폴더에 clone해 `../docs`로 읽는다. 작업 전에 `../docs`에서 `git pull`로 최신본을 받는다. 정책은 `02_정책서`의 번호(P-XXX-NN)로 확인한다.

API와 DB 문서는 이 저장소가 원본이다. API를 추가 · 변경하면 같은 PR에서 `docs/api.md`(엔드포인트 상태 ✅ · ⬜, 오류 코드 표)를, 스키마를 바꾸면 `docs/db.md`를 갱신한다. 요청 · 응답 필드는 Swagger가 기준이므로 `docs/api.md`에 옮겨 적지 않는다. 구현이 기획 문서와 달라지거나 기술 결정을 내리면 `docs/decisions.md`에 이유와 함께 추가한다. 정책서에 없는 동작을 정했다면 PR 본문의 "정책서에 없어서 정한 것"에 기록하고, 사람이 정책서 반영 여부를 결정한다.

## 명령

| 목적 | 명령 |
|---|---|
| 컴파일 확인 | `./gradlew compileKotlin compileTestKotlin` |
| 테스트 | `./gradlew test` (Docker 필요) |
| 로컬 실행 | `./gradlew bootRun` → http://localhost:8080/swagger-ui |
| 운영 jar | `./gradlew bootJar` → `build/libs/daybit-server-0.0.1-SNAPSHOT.jar` 하나만 생성된다 |

## 작업 흐름

1. 이슈를 먼저 만든다. 본문은 `.github/ISSUE_TEMPLATE`의 기능 · 버그 양식을 따르고 "~다"로 쓴다. 결정된 것(정한 규칙)과 결정이 필요한 것(먼저 정할 것)을 나눈다. 라벨은 유형(`feat` `bug` `docs` `refactor` `infra` `chore`) 하나와 영역(`area: …`) 하나 이상을 붙이고 마일스톤을 지정한다.
2. `main`에서 `유형/이슈번호-짧은설명` 브랜치를 만든다. 예: `feat/3-materials`
3. 구현과 테스트를 함께 작성하고 `./gradlew test` 통과를 확인한다.
4. **GitHub에 쓰는 모든 작업은 사람의 확인을 받은 뒤에만 한다.** 커밋, push, PR, 이슈, 라벨, 브랜치가 모두 해당한다. 올릴 파일 목록, 커밋 메시지, 대상 브랜치를 먼저 보여주고 승인을 받는다. 작업 목록에 push가 적혀 있어도 실행 직전에 다시 확인한다.
5. PR은 `main`을 대상으로 하고(시연 버전 수정만 `release`) `.github/pull_request_template.md` 양식을 "~합니다"로 채운다. "기능"은 FE가 알아야 할 동작을 실패 경우까지 쓰고, "Swagger로 확인하기"에는 리뷰어가 순서대로 실행할 시나리오를, "확인 필요"에는 검증하지 못한 것을 적는다.
6. 병합은 사람이 한다.

## 브랜치와 병합

GitHub Flow에 시연용 릴리스 브랜치를 더한 방식이다.

| 브랜치 | 역할 | 배포 |
|---|---|---|
| `main` | 개발 통합. 기본 브랜치 | 개발 기간 동안 Railway 자동 배포. FE가 이 서버를 사용 |
| `유형/이슈번호-설명` | 기능 · 수정 작업 | 없음 |
| `release` | 시연 버전. 시연 직전 `main`에서 한 번 만든다 | 생성 후 Railway 배포를 `release`로 전환 |

- 기능 브랜치를 `main`에 넣을 때는 **Squash merge**를 쓴다.
- `main`과 `release`에는 직접 push하지 않는다.
- `release`를 만든 뒤에도 개발은 `main`에서 계속한다. 시연 버전에 꼭 필요한 수정은 `main`에 먼저 병합하고, 그 커밋을 `release`로 cherry-pick하는 PR을 연다. `release`에만 있는 수정이 생기지 않게 한다.
- 무료 비공개 저장소라 브랜치 보호 규칙을 쓸 수 없다. 위 규칙은 약속으로 지킨다.

## 커밋 메시지

`유형: 요약 (#이슈)` 형식의 한국어로 쓴다. 예: `feat: 과목 탈퇴와 관리자 승계 (#3)`. 본문에 관련 정책 번호를 적고, 마지막 줄에 AI 공동 작성자를 표기한다.

## 코드

- 패키지는 기능별로 나눈다(`course`, `explore` 등). 패키지 안에 Controller(`XxxApi.kt`), Service, DTO를 둔다.
- 단순한 엔티티는 JPA(`User`, `RefreshToken`)로, 권한 판단·조회·집계는 `JdbcClient`와 SQL로 다룬다.
- 스키마는 Flyway 파일 `V{n}__설명.sql`을 새로 추가해서 바꾼다. **기존 V 파일은 수정하지 않는다.** 이미 적용된 배포 DB의 체크섬 검증이 실패한다.
- 오류는 `throw ApiException(ErrorCode.X)`로 던진다. 응답은 `{error:{code,message}}`가 된다. 새 `ErrorCode`는 FE가 분기에 쓰므로 PR에 명시한다.
- 과목이 없거나 요청자가 멤버가 아니면 **404**, 멤버이지만 권한이 부족하면 **403**이다. 멤버십 확인은 `CourseService.membership()`을 쓴다.
- enum 값은 DB·API 값과 같은 소문자로 둔다(`admin`, `public`).
- 시간은 서울 기준이다. DB 컬럼은 `timestamptz`이고, 날짜 경계는 `Asia/Seoul`로 계산한다.
- 컨트롤러 DTO가 곧 OpenAPI이고 FE Orval 타입이다. 필드 삭제, 이름 변경, 상태 코드 변경은 호환성을 깨므로 PR에 표시하고 FE에 공지한다.
- 미구현 API도 DTO는 갖추고 `ApiException(ErrorCode.NOT_IMPLEMENTED)`(501)를 던진다.

## 테스트

통합 테스트를 기본으로 한다. `@SpringBootTest`, MockMvc, `TestcontainersConfiguration`을 쓰고, 준비 코드는 `src/test/kotlin/app/daybit/support/Fixtures.kt`(`newUser`, `createCourse`, `join`, `notificationTypes`)를 재사용한다. 테스트 클래스끼리 DB를 공유하므로 이름에 고유값을 붙이고, 전체 개수가 아니라 해당 테스트가 만든 데이터를 기준으로 검증한다. 성공 경로와 함께 권한·검증·충돌 같은 실패 경로를 반드시 포함한다.

## 알려진 함정

- Postgres는 트랜잭션 안에서 unique 위반이 나면 그 트랜잭션 전체를 중단한다. 위반을 잡아 재시도하지 말고 미리 확인한다.
- `course_members_one_admin_uq`가 과목당 관리자를 1명으로 제한한다. 관리자 승계는 기존 관리자를 제거한 뒤 승격한다.
- 로컬 Windows의 PostgreSQL 서비스가 5432를 점유하고 있어 compose는 5433을 쓴다.
- Git Bash의 `curl`에 한글을 인자로 넘기면 UTF-8로 전달되지 않는다. 본문은 UTF-8 파일로 만들어 `--data-binary @file`로 보낸다.
- Jackson 3(`tools.jackson`)에서는 `JsonNode.map {}`이 Kotlin 확장으로 해석되지 않는다. `.toList().map {}`을 쓴다.
- Kotlin은 주석 안의 `/*`를 중첩 주석 시작으로 본다. 주석에 `https://*.도메인` 같은 패턴을 쓰면 파일 전체가 깨진다.
- Spring Boot 4에서 `AutoConfigureMockMvc`의 패키지는 `org.springframework.boot.webmvc.test.autoconfigure`다.
- Railway Postgres의 `DATABASE_URL`은 `postgresql://` 형식이라 JDBC가 읽지 못한다. 운영 설정은 `PG*` 변수로 JDBC URL을 조립한다(`docs/deploy.md`).
- 새 환경 변수를 추가하면 `docs/deploy.md` 표를 갱신하고 PR에 명시한다. 배포 담당이 Railway에 값을 넣어야 동작한다.
