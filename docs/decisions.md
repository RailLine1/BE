# 결정 기록

백엔드에서 내린 기술 결정과, 구현이 팀 기획 문서와 달라진 부분을 기록한다. 새 결정은 표 아래에 추가하고, 결정을 뒤집을 때는 기존 행을 지우지 않고 상태를 바꾼다.

| # | 날짜 | 결정 | 상태 |
|---|---|---|---|
| D1 | 2026-11-02 | [DB는 PostgreSQL 17](#d1-db는-postgresql-17) | 확정 |
| D2 | 2026-11-02 | [인증은 세션 대신 JWT 액세스 + 리프레시](#d2-인증은-jwt-액세스--리프레시) | 확정 |
| D3 | 2026-11-02 | [GitHub 로그인은 딥링크 일회용 code 교환](#d3-github-로그인은-딥링크-일회용-code-교환) | 확정 |
| D4 | 2026-11-02 | [푸시는 웹 푸시 대신 FCM](#d4-푸시는-fcm) | 확정 |
| D5 | 2026-11-02 | [`bits.open_date` 컬럼으로 출제일 유일성 보장](#d5-bitsopen_date-컬럼) | 확정 |
| D6 | 2026-11-02 | [비멤버의 과목 조회는 항상 404](#d6-비멤버의-과목-조회는-항상-404) | 확정 |

## D1. DB는 PostgreSQL 17

`06_기술스택`에서 정한 대로 PostgreSQL을 쓴다. 배포 담당이 MySQL을 제안해 근거를 정리했다. Railway는 두 DB를 같은 방식으로 제공하므로 배포 작업량은 같다.

| 필요한 것 | PostgreSQL | MySQL 8 |
|---|---|---|
| 한글 과목명 부분 검색 (P-EXP-03) | `pg_trgm`으로 `ilike '%…%'`에도 인덱스 사용 | 앞이 `%`인 LIKE는 인덱스 불가. FULLTEXT ngram 별도 설정 |
| 과목당 관리자 1명 (P-ROLE) | 조건부 유니크 인덱스 `unique (course_id) where role = 'admin'` | 조건부 인덱스 없음. 생성 컬럼 우회나 애플리케이션 락 필요 |
| 중복 구독 방지 (P-COURSE-03) | `on conflict do nothing`, `returning`으로 쿼리 한 번 | `returning` 없음. 결과를 다시 조회 |
| 알림 내용 (P-NOTI-03) | `jsonb` + 인덱스 | `json`. 인덱스는 생성 컬럼 경유 |
| 17:00 열기 · 16:59 마감 (P-BIT-05) | `timestamptz`로 시점을 명확히 저장 | `datetime`은 시간대 정보 없음 |
| 이후 의미 검색 | `pgvector` 확장 추가 | 별도 서비스 필요 |

`pg_trgm` 인덱스와 `pgvector`는 아직 적용하지 않았다. 현재 과목명 검색은 인덱스 없이 동작한다.

## D2. 인증은 JWT 액세스 + 리프레시

기획 문서는 세션(HttpOnly 쿠키)을 [미정]으로 두었다. 앱(Expo)에서는 쿠키 세션을 다루기 어려워 JWT로 정했다. 액세스 토큰 30분, 리프레시 토큰 14일이며 리프레시 토큰은 1회용이다(사용 즉시 폐기, 새 쌍 발급). 이를 위해 문서에 없는 `POST /auth/refresh`, `POST /auth/logout {refreshToken}`을 추가했다.

## D3. GitHub 로그인은 딥링크 일회용 code 교환

문서의 `/auth/github/callback` 방식은 앱이 OAuth 콜백을 직접 받을 수 없어 쓰지 않는다. `/auth/github/start?redirect=` → GitHub → 서버 콜백 → 앱 딥링크로 일회용 code 전달 → `POST /auth/github/exchange`로 토큰 교환 순서다. 일회용 code는 해시로 `github_login_codes`(V2)에 저장하고 1분 안에 한 번만 교환할 수 있다. 앱 주소는 서버가 서명한 `state`에 담아 GitHub에 넘기므로 콜백에서 위조된 주소로 code를 보낼 수 없고, 허용 목록에 없는 주소는 시작 단계에서 거부한다.

기존 계정 연결은 문서의 `start?link=1` 대신 `POST /me/github/link`로 바꿨다. 외부 브라우저로 여는 `start`에는 앱의 액세스 토큰을 실을 수 없어서, 로그인한 API가 연결할 계정을 서명한 `state`에 넣은 주소를 만들어 준다. 다른 계정에 이미 연결된 GitHub로 연결하면 `GITHUB_ALREADY_LINKED`다. GitHub로 처음 가입한 사용자는 임시 닉네임(`user` + 숫자 8자리)으로 만들고 `needsNickname`으로 닉네임 입력을 거치게 한다.

## D4. 푸시는 FCM

문서의 웹 푸시(`push_subscriptions`)는 앱 안에서 동작하지 않아, `06_기술스택`에서 FCM으로 통일했다. 테이블은 `push_tokens(token, platform: android | ios | web)`이다. iOS 발송에는 APNs 키가 필요하다.

발송은 Firebase Admin SDK 대신 FCM HTTP v1 API를 `google-auth-library`(서비스 계정 토큰)와 `RestClient`로 직접 호출한다. 의존성이 작고 필요한 기능은 단건 발송뿐이기 때문이다. `Notifier`가 알림을 기록한 트랜잭션이 커밋되면 별도 가상 스레드에서 바로 보낸다(모아 보내기 없음). FCM이 404 `UNREGISTERED` 또는 400 `INVALID_ARGUMENT`로 답한 토큰은 지운다.

## D5. `bits.open_date` 컬럼

문서는 `unique(course_id, open_at::date)`였다. `timestamptz`를 날짜로 바꾸는 결과가 DB 세션 시간대에 따라 달라지므로, 서울 기준 출제일을 `open_date date` 컬럼에 명시적으로 저장하고 `unique(course_id, open_date)`로 바꿨다.

## D6. 비멤버의 과목 조회는 항상 404

명세서(0. 공통)는 "비공개 과목을 권한 없이 조회할 때도 404"라고만 적었다. 구현은 과목이 없거나 요청자가 승인된 멤버가 아니면(남, 가입 대기, 추방, 탈퇴) 공개 여부와 관계없이 404를 반환한다. 비공개 과목의 존재 여부를 드러내지 않기 위해서다. 멤버이지만 권한이 부족한 경우는 403이다.

## D7. 학습자료 원본은 로컬 디스크 · R2 중 설정으로 고른다

`daybit.storage.type`이 `local`이면 서버 디스크(`DAYBIT_STORAGE_DIR`)에, `s3`이면 Cloudflare R2에 원본을 저장한다. 로컬 개발과 테스트에 R2 키나 MinIO 컨테이너가 필요 없도록 디스크 구현을 기본값으로 뒀다. 보기용 임시 URL은 R2면 presigned URL, 로컬이면 서버의 `/local-files/…?exp=&sig=`(HMAC 서명, 10분)이다. Railway 컨테이너 디스크는 재배포 때 지워지므로 배포 서버는 R2를 쓴다.

PDF 텍스트 추출은 업로드 트랜잭션이 커밋된 뒤 별도 스레드(`@Async`)에서 한다. 서버가 추출 도중 재시작되면 그 파일은 `processing`에 머문다. 재처리 배치는 아직 없다.

## D8. Gemini는 Spring AI 없이 REST로 호출한다

`06_기술스택`은 Spring AI(Gemini Flash)였다. 문제 생성에 필요한 것은 JSON 스키마를 지정한 `generateContent` 호출 하나뿐이고, Spring Boot 4와 맞는 Spring AI 버전 확인에 시간을 쓰지 않기 위해 `RestClient`로 직접 호출한다(`bit/generation/GeminiQuestionGenerator`). 생성기는 `QuestionGenerator` 인터페이스 뒤에 두어 작업 공간 출처(#10)나 다른 모델로 바꿀 수 있다. 커넥터(#8)의 Spring AI MCP Server 사용 여부는 그 이슈에서 따로 정한다.

근거 검증은 모델이 근거 페이지에서 그대로 옮긴 문장(`evidenceQuote`)이 실제 페이지 텍스트에 있는지로 판단한다. PDF 추출과 모델 출력의 공백 · 줄바꿈이 달라 공백을 모두 지우고 대소문자를 무시해 비교한다. 통과하지 못한 문제도 저장하되 `verified = false`로 두어 출제하지 않는다.

모델은 하나로 고정하지 않고 대체 목록(`DAYBIT_GEMINI_MODEL`, 쉼표 구분)을 앞에서부터 시도한다. 2026-11-03에 `gemini-2.5-flash`가 새 키에서 404(종료)였고, 같은 시간에 flash 모델들이 503(혼잡)을 돌려줬기 때문이다(#28). 404 · 429 · 5xx · 시간 초과면 다음 모델로 넘어가고, 400 · 401 · 403처럼 다른 모델로도 똑같이 실패하는 오류는 바로 실패한다. 맨 끝에는 품질은 낮지만 덜 혼잡한 lite 모델을 둬서 문제 생성이 끊기지 않게 한다.

프롬프트(출제 규칙)와 응답 스키마는 `src/main/resources/prompts/`의 파일이다(#29). Gemini와 AI 작업 공간이 같은 파일을 써서 같은 규칙 · 같은 형식으로 문제를 만든다. 품질 실험은 AI 레포(`eval/`)에서 하고, 나은 프롬프트가 나오면 이 파일만 바꾸는 PR을 연다.

문제 · 보기 · 해설은 한국어로 고정하고, 전공 용어 · 코드 · 수식은 자료 표기 그대로 쓴다(#33). 타깃이 국내 대학생이고 전공 강의자료는 영어가 대부분이기 때문이다. `evidenceQuote`는 근거 검증이 원문 일치로 동작하므로 번역하지 않는다. 실제 OS 강의 PDF(14쪽, 10문제)로 근거 검증 10/10을 확인했다. 모델이 용어를 가끔 음차하거나 "번역(원문)"으로 병기하는 경우가 남아 있어, 프롬프트만으로 완전히 막지는 못한다. 다른 언어는 서비스 확장 때 다시 본다.

## D9. 배치는 서버 1대 기준이고, 놓친 단계는 따라잡는다

해커톤 동안 Railway 서버는 1대이므로 ShedLock 없이 `@Scheduled`(서울 시각)로 돌린다. 서버를 늘리면 같은 배치가 동시에 돌 수 있어 그때 ShedLock을 추가한다. 출제 준비는 과목 행을 잠그고 `unique(course_id, open_date)`가 있어 같은 날 Bit가 두 번 생기지는 않는다.

배치의 모든 단계는 다시 실행해도 결과가 같다. 이미 만든 Bit, 이미 남긴 열림 알림, 이미 마감한 Bit는 건너뛴다. 17:00 단계는 마감 시각이 지난 모든 Bit를 마감하고, 16:30 단계를 놓친 과목의 Bit를 만든 뒤 알림을 남긴다. 서버가 시작할 때도 같은 단계를 한 번 실행한다. 따라서 배치 시각에 서버가 꺼져 있었어도 켜지는 즉시 따라잡는다. 수동 재실행 API는 만들지 않았다.

연속 일수는 날짜가 아니라 그 과목의 Bit 순서로 센다. 자료가 없어 출제를 건너뛴 날 때문에 연속이 끊기지 않게 하기 위해서다. 과목 구분 없는 전체 연속(`/me/streak`)은 날짜 기준이다.

## D10. MCP 서버는 Spring AI 없이 직접 구현한다

`06_기술스택`은 Spring AI MCP Server였다. Spring Boot 4와 맞는 버전 확인이 어렵고, 필요한 것은 JSON-RPC 메서드 4개(`initialize`, `ping`, `tools/list`, `tools/call`)뿐이라 `connector/McpController`에 직접 구현했다(D8 Gemini와 같은 판단).

- Streamable HTTP 중 "POST 요청 하나 → JSON 응답 하나"만 지원한다. 서버가 먼저 보내는 알림이 없어서 SSE 스트림(`GET`)은 405로 둔다.
- 세션은 저장하지 않는다. `initialize` 때 `Mcp-Session-Id`를 주고 메모리에 클라이언트 종류만 기억한다. 서버가 재시작되면 잊고 User-Agent로 추정한다. 세션이 없어도 요청은 처리한다.
- 틀린 토큰은 401이 아니라 404다. 401을 받으면 MCP 클라이언트가 OAuth 인가 서버를 찾으러 가기 때문이다.
- `search`는 키워드 검색(`ilike`)으로 시작한다. 의미 검색(pgvector)은 하지 않는다.
- 원본 열람 off일 때 `read_pages`를 제한할지(P-MAT-06 [미정])는 정책서 기본값대로 제한하지 않는다.
- `get_page_image`는 페이지 이미지를 미리 저장하지 않고 요청 때 원본 PDF에서 PNG로 그린다. `file_pages.image_key`는 쓰지 않는다.

## D11. AI 작업 공간 결과는 서명 키 대신 GitHub OIDC로 검증한다

> **D12로 대체됨 (2026-11-06, #48).** 작업 공간 코드는 지웠다. 기록으로 남긴다.

`07_핵심기술` 2-2는 "결과 JSON을 서명해서 전달 → 서명 확인"이었다(정책서 P-AI-09에는 검증 방식이 없다). 서명 키를 쓰려면 서버가 키를 만들어 관리자 저장소 Secret에 넣어야 하는데, GitHub Secret API는 저장소 공개키로 봉인(libsodium sealed box)해서 보내야 해서 구현과 검증 부담이 크다. 대신 GitHub Actions가 실행마다 발급하는 **OIDC 토큰**(GitHub가 서명한 JWT, `repository` 클레임 포함)을 쓴다. 워크플로는 `audience: daybit`로 토큰을 받아 Bearer로 보내고, 서버는 GitHub 공개키(JWKS)로 서명 · 발급자 · audience를 확인한 뒤 `repository`가 작업 주인의 작업 공간 저장소인지 본다. 저장소마다 비밀값을 나눠 줄 필요가 없고, 토큰은 몇 분 안에 만료된다.

- 관리자 GitHub OAuth 토큰(scope: `repo`, `workflow`)은 서버가 실행 요청을 보내기 위해 저장한다. JWT 비밀값에서 만든 키로 AES-GCM 암호화한다. 구독 토큰(Claude · ChatGPT)은 저장소 Secret에만 있고 서버는 모른다(P-AI-09 그대로).
- 저장소는 템플릿 저장소(`RailLine1/AI`, `DAYBIT_WORKSPACE_TEMPLATE`)를 복사해 만든다. 템플릿은 공개 + "Template repository" 설정이어야 다른 사용자가 복사할 수 있다.
- 워크플로는 작업 내용(출제 규칙 · 스키마 · 자료 본문)을 서버에서 받아 간다. 출제 규칙은 Gemini와 같은 `resources/prompts` 파일이다(#29). 자료 본문을 실행 입력으로 넘기지 않는 이유는 `workflow_dispatch` 입력 크기 제한 때문이다.
- 실패 · 만료 · 30분 시간 초과면 즉시 Gemini로 전환하고 관리자 · 공동 관리자에게 알리며(P-BIT-15), 그 자료를 Gemini로 다시 만든다. AI 상태는 `expired`가 되고, 관리자가 3단계 테스트를 다시 통과하면 `ready`로 돌아온다.
- 서버가 GitHub에서 접근 가능한 공개 주소여야 워크플로가 작업을 받아 갈 수 있다. 로컬 서버로는 끝까지 확인할 수 없다.
- `repo` 권한은 GitHub OAuth 앱 구조상 관리자의 **모든 비공개 저장소**에 미친다(저장소 하나로 좁힐 수 없다). 해커톤 PoC에서는 이를 감수하고 토큰을 암호화해 저장한다. 저장소 하나로 좁히려면 GitHub App으로 바꿔야 한다.
- 이미 다른 GitHub 계정을 연결한 사용자가 다른 계정으로 1단계를 허용하면 `GITHUB_ACCOUNT_MISMATCH`로 돌려보낸다. 로그인 계정과 작업 공간 저장소 주인이 달라지지 않게 한다.
- 워크플로 안의 AI에는 도구를 주지 않는다. 자료 본문(공동 관리자도 올릴 수 있음)에 숨긴 지시로 AI가 구독 토큰을 읽어 문제 해설에 넣는 경로를 막기 위해서다. Claude Code는 내장 도구를 모두 끄고, 두 AI 모두 보내기 전에 결과에 구독 토큰 값(원문 · base64)이 들어 있으면 실패로 처리한다.

## D12. 내 AI 출제는 관리자 AI 앱의 예약 작업이 커넥터로 받아 가고 보낸다

GitHub 작업 공간(D11)을 배포 서버에서 끝까지 돌려 보니 등록이 5단계를 넘고 휴대폰으로는 할 수 없었으며, `repo` 권한이 관리자의 모든 비공개 저장소에 미쳤다(2026-11-05). Claude 휴대폰 앱(Cowork)의 예약 작업이 커스텀 커넥터를 부르는 것을 확인하고 방식을 바꿨다(Docs #2, #48).

- 서버가 AI를 깨우지 않는다. 예약 작업이 정한 시각에 스스로 `get_jobs`를 부르고 `submit_questions`로 보낸다. 서버는 구독 계정 · 토큰에 닿지 않고, 실행 요청도 보내지 않는다.
- 출제 규칙 · 스키마 · 자료 본문은 Gemini와 같은 `resources/prompts`로 만들어 `get_jobs`가 내려준다. 규칙을 고치면 모든 관리자에게 바로 반영된다. 예약 문장은 `prompts/myai-schedule.md`이고 `GET /me/myai`가 내려준다.
- 어떤 AI인지는 도구 인자 `ai`로 받는다. 예약 실행의 MCP 클라이언트 이름은 믿을 수 없다(Claude Cowork 예약 작업이 `claudecode`로 기록됐다). 인자는 우리가 만든 예약 문장에 박혀 있다.
- `get_jobs`는 한 번에 자료 하나만 준다. 자료 본문이 크고(최대 8만 자), AI가 하나씩 만들고 보내는 편이 실패해도 덜 잃는다. 예약 문장은 한 실행에 최대 5번 반복한다.
- 출처가 출제 AI인 과목은 자료가 `ready`가 돼도 기다린다. 24시간 안에 안 오면 Gemini가 만들고(P-BIT-20), 예약 실행이 48시간 없으면 출처를 되돌린다(P-BIT-15). 10분마다 확인한다. 16:30 재보충은 출처와 상관없이 Gemini가 한다(정책서에 없어서 정함: 예약 작업은 하루 한 번이라 Bit 직전 부족분을 채울 수 없다).
- `get_jobs`는 MCP `readOnlyHint: true`로 표시한다. 실행 시각만 기록하고 사용자에게 보이는 데이터를 바꾸지 않아서, AI 앱이 매번 승인을 묻지 않게 한다. `submit_questions`는 쓰기 도구로 표시한다.
- ChatGPT 예약 작업이 커스텀 커넥터의 쓰기 도구를 쓸 수 있는지는 확인하지 못했다 **[검증 필요]**. 안 되면 ChatGPT만 빠지고 Gemini로 대체된다.
