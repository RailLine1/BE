# API 안내

Daybit 백엔드 API의 공통 규칙, 오류 코드, 엔드포인트 목록입니다. **요청·응답 필드의 정확한 형식은 Swagger(`/swagger-ui`, `/v3/api-docs`)가 기준**이며, 이 문서는 Swagger에 드러나지 않는 내용을 다룹니다. 이 문서는 팀 기획 문서의 `04_API명세서`(2026-10-31)를 옮겨 온 것으로, 이후 변경은 이 문서에서만 관리합니다.

## 공통

| 항목 | 내용 |
|---|---|
| Base URL | `/api/v1` |
| 형식 | JSON. 파일 업로드만 `multipart/form-data` |
| 인증 | `Authorization: Bearer <accessToken>` |
| 시간 | ISO 8601, 서울 기준(`+09:00`) |
| 페이지 | `?page=1&size=8` (page는 1부터) → `{ items, total, page, size }` |
| 권한 표기 | 🅰 관리자 · 🅲 공동 관리자 · 🅼 멤버 · 🔓 로그인 불필요 |

## 인증 흐름

1. `POST /auth/signup` 또는 `/auth/login`이 `{ user, isFirstLogin, tokens: { accessToken, refreshToken, expiresIn } }`를 반환합니다.
2. 이후 모든 요청에 액세스 토큰을 붙입니다. 유효 시간은 30분입니다.
3. 401 `UNAUTHORIZED`를 받으면 `POST /auth/refresh {refreshToken}`으로 새 토큰 쌍을 받고 원래 요청을 다시 보냅니다.
4. 리프레시 토큰은 14일 동안 유효하며 **한 번 쓰면 폐기**됩니다. 응답으로 받은 새 리프레시 토큰을 저장합니다. 동시에 여러 요청이 갱신을 시도하면 하나만 성공하므로, 갱신 요청은 앱에서 하나로 모읍니다.
5. refresh가 401 `INVALID_REFRESH_TOKEN`을 반환하면 로그인 화면으로 보냅니다.
6. 로그아웃은 `POST /auth/logout {refreshToken}`입니다. 이미 발급된 액세스 토큰은 만료 시각까지 유효합니다.

## 권한과 404 · 403

과목 API(`/courses/{courseId}/…`)는 요청자의 멤버십을 먼저 확인합니다.

| 상황 | 응답 |
|---|---|
| 과목이 없음 | 404 `NOT_FOUND` |
| 멤버가 아님 (가입 대기, 추방, 탈퇴 포함) | 404 `NOT_FOUND`. 공개 여부와 관계없이 같은 응답이라 비공개 과목의 존재가 드러나지 않습니다 |
| 멤버이지만 권한 부족 (예: 멤버가 환경설정 변경) | 403 `FORBIDDEN` |

## 오류

모든 오류는 같은 형식입니다. 분기는 `code`로 하고, 화면에는 `message`를 보여줍니다. 단 `VALIDATION_FAILED`의 `message`는 `필드: 내용`(예: `nickname: 닉네임은 2~12자 한글·영문·숫자예요`) 형식이므로 앞의 필드 이름을 떼고 해당 입력 칸 아래에 표시합니다. 입력 규칙은 앱에서도 먼저 검사하는 것을 권장합니다.

```json
{ "error": { "code": "COURSE_ENDED", "message": "수강 기간이 끝난 과목이에요." } }
```

| HTTP | code | 상황 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | 입력 형식 오류. 요청 본문 검증 실패면 첫 번째 오류가 `필드: 내용`으로, 쿼리 · 경로 값 오류면 기본 문구가 들어갑니다 |
| 400 | `QUIZ_SOURCE_UNAVAILABLE` | 본인이 작업 공간에 연결해 둔 AI가 아닌 퀴즈 출처를 고름 (P-AI-09) |
| 401 | `UNAUTHORIZED` | 토큰이 없거나 만료됨 → refresh |
| 401 | `INVALID_CREDENTIALS` | 아이디·비밀번호 불일치 |
| 401 | `INVALID_REFRESH_TOKEN` | 리프레시 토큰이 없거나 만료·사용됨 → 다시 로그인 |
| 401 | `GITHUB_CODE_INVALID` | GitHub 일회용 code가 없거나 만료(1분) · 사용됨 → 다시 로그인 |
| 403 | `FORBIDDEN` | 멤버이지만 권한 부족 |
| 404 | `NOT_FOUND` | 없는 리소스 또는 멤버가 아닌 과목 |
| 409 | `NICKNAME_TAKEN`, `LOGIN_ID_TAKEN` | 가입·닉네임 변경 중복 |
| 409 | `GITHUB_ALREADY_LINKED` | 다른 계정에 연결된 GitHub (연결 흐름에서는 `?error=`로 전달) |
| 409 | `ALREADY_ATTEMPTED` | 같은 Bit 두 번째 제출 (P-BIT-09) |
| 409 | `BIT_CLOSED` | 마감된 Bit 시작 · 제출 (P-BIT-05) |
| 409 | `COURSE_ENDED` | 종료된 과목 구독 (P-EXP-04) |
| 413 | `FILE_TOO_LARGE` | 업로드 파일이 50MB 초과 (P-MAT-02) |
| 500 | `INTERNAL_ERROR` | 서버 오류 |
| 501 | `NOT_IMPLEMENTED` | 아직 구현하지 않은 API |

새 오류 코드는 이 표에 먼저 추가하고 PR에 명시합니다.

## 엔드포인트

상태: ✅ 구현 · ⬜ 형식만 있음(501). 경로는 Base URL 기준입니다.

### 인증 · 내 정보

| 상태 | 메서드 | 경로 | 권한 | 요약 | 정책 |
|---|---|---|---|---|---|
| ✅ | POST | `/auth/signup` | 🔓 | 아이디 가입 | P-ACC-02~04 |
| ✅ | POST | `/auth/login` | 🔓 | 로그인 | |
| ✅ | POST | `/auth/refresh` | 🔓 | 토큰 갱신 (리프레시 토큰 1회용) | |
| ✅ | POST | `/auth/logout` | 🔓 | 리프레시 토큰 폐기 | |
| ✅ | GET | `/auth/github/start?redirect=` | 🔓 | GitHub 로그인 시작. 끝나면 `redirect`로 `?code=` 또는 `?error=` | P-ACC-06 |
| ✅ | POST | `/auth/github/exchange` | 🔓 | `{code}` 일회용 code(1분, 1회) → 토큰. 신규면 `needsNickname` | P-ACC-04 |
| ✅ | POST | `/me/github/link` | 로그인 | `{redirect}` → 기존 계정에 GitHub를 연결할 인가 주소 `{url}` | P-ACC-06 |
| ✅ | GET | `/me` | 로그인 | 내 정보 (구독 · 관리 과목 수 포함) | |
| ✅ | PATCH | `/me` | 로그인 | 닉네임 변경 | P-ACC-03 |
| ✅ | POST | `/me/onboarding/complete` | 로그인 | 서비스 소개 확인 표시 | P-ACC-05 |
| ✅ | GET | `/me/welcome` | 로그인 | 환영 화면 (최근 24시간 새 자료, 오늘 남은 Bit, 연속 일수) | P-ACC-05 |
| ✅ | GET | `/me/streak?weeks=15` | 로그인 | 전체 학습 기록 격자, 연속 · 최장 · 이번 달 일수 | P-BIT-13 |

GitHub 로그인의 서버 콜백(`/auth/github/callback`)은 GitHub가 호출하는 주소라 앱이 직접 쓰지 않습니다. 흐름은 [결정 기록 D3](decisions.md#d3-github-로그인은-딥링크-일회용-code-교환)에 있습니다.

- 로그인: 외부 브라우저로 `/auth/github/start?redirect=daybit://github`를 엽니다. 돌아온 `?code=`를 `POST /auth/github/exchange`로 바꿉니다. `needsNickname`이 true면 닉네임 입력 화면을 거쳐 `PATCH /me`로 바꿉니다(가입 시 임시 닉네임 `user12345678`).
- 연결: 로그인한 상태에서 `POST /me/github/link {redirect}`로 받은 `url`을 외부 브라우저로 엽니다. 돌아온 `code`도 같은 exchange로 바꿉니다(같은 계정의 새 토큰).
- `redirect`는 서버의 허용 목록(`DAYBIT_GITHUB_REDIRECTS`, 기본 `daybit://*`, `exp://*`, `http://localhost:*`)에 맞아야 하며 아니면 400입니다.
- 실패하면 `redirect`에 `?error=`가 붙습니다: `GITHUB_CANCELLED`(사용자 취소), `GITHUB_FAILED`(GitHub 응답 실패), `GITHUB_ALREADY_LINKED`(다른 계정에 연결된 GitHub).

### 과목

| 상태 | 메서드 | 경로 | 권한 | 요약 | 정책 |
|---|---|---|---|---|---|
| ✅ | POST | `/courses` | 로그인 | 과목 만들기 → `{id, code}`. 기본 폴더 `mid` · `final` 생성 | P-COURSE-01, 02, P-MAT-01 |
| ✅ | GET | `/courses/mine?state=active\|ended` | 로그인 | 강의실 타일 / 지난 강의. 가입 대기 제외 | P-COURSE-05 |
| ✅ | GET | `/courses/{courseId}` | 🅰🅲🅼 | 과목 상세 (`state` 포함) | |
| ✅ | GET | `/courses/{courseId}/settings` | 🅰🅲🅼 | 환경설정. 멤버는 `code` · `role` · `notify`만 | P-ROLE-01 |
| ✅ | PATCH | `/courses/{courseId}` | 🅰🅲 | 환경설정 변경. 보낸 항목만 바뀜. 비공개 → 공개면 대기 요청 자동 승인 | P-COURSE-04, P-BIT-03, 14 |
| ✅ | POST | `/courses/{courseId}/leave` | 🅰🅲🅼 | 탈퇴 → `left` · `promoted`(+`newAdmin`) · `deleted` | P-ROLE-03, 04 |
| ✅ | PATCH | `/courses/{courseId}/subscription` | 🅰🅲🅼 | 과목 알림 켜기·끄기 | P-NOTI-02 |

`quizSource`는 `{ "type": "default" }` 또는 `{ "type": "workspace", "ai": "claudecode" | "codex" }`입니다. `PATCH`에서 값을 보내지 않은 항목은 바뀌지 않으며, 한 번 넣은 시험일을 비우는 방법은 아직 없습니다.

### 탐색 · 구독

| 상태 | 메서드 | 경로 | 권한 | 요약 | 정책 |
|---|---|---|---|---|---|
| ✅ | GET | `/explore/courses?sort=subscribers\|newest&page&size` | 로그인 | 진행 중인 공개 과목. 구독자 수는 정렬에만 사용 | P-EXP-01, 02, 05 |
| ✅ | GET | `/explore/search?q=` | 로그인 | 코드 전체 일치(모든 과목)를 맨 앞에, 이어서 과목명 부분 일치(공개 · 진행 중). 최대 50개 | P-EXP-03 |
| ✅ | GET | `/explore/courses/{code}` | 로그인 | 과목 정보 + `myStatus` (`subscribed` · `pending` · `none`) | P-COURSE-06 |
| ✅ | POST | `/explore/courses/{code}/subscribe` | 로그인 | 공개 → `subscribed`, 비공개 → `pending`, 종료 → 409. 이미 구독·요청했으면 현재 상태 반환 | P-COURSE-03, P-EXP-04 |

과목 코드는 대소문자를 구분하지 않습니다.

### 멤버 · 가입 요청

| 상태 | 메서드 | 경로 | 권한 | 요약 | 정책 |
|---|---|---|---|---|---|
| ✅ | GET | `/courses/{courseId}/members` | 🅰🅲 | 관리자 → 공동 관리자(지정 순) → 멤버(가입 순) | P-ROLE-02 |
| ✅ | PATCH | `/courses/{courseId}/members/{userId}` | 🅰 | 멤버 ↔ 공동 관리자 | P-ROLE-02 |
| ✅ | DELETE | `/courses/{courseId}/members/{userId}` | 🅰🅲 | 추방. 공동 관리자는 멤버만, 본인은 불가 | P-ROLE-05, 06 |
| ✅ | GET | `/courses/{courseId}/join-requests` | 🅰🅲 | 대기 중인 요청 (요청 순) | |
| ✅ | POST | `/courses/{courseId}/join-requests/{requestId}/approve` | 🅰🅲 | 승인 → 요청자 알림 | P-NOTI-03 |
| ✅ | POST | `/courses/{courseId}/join-requests/{requestId}/reject` | 🅰🅲 | 거절 | |
| ✅ | POST | `/courses/{courseId}/join-requests/approve-all` | 🅰🅲 | 모두 승인 → `{approved}` | |

`requestId`는 요청한 사람의 `userId`입니다. 이미 처리된 요청은 404입니다.

### 학습자료

| 상태 | 메서드 | 경로 | 권한 | 요약 | 정책 |
|---|---|---|---|---|---|
| ✅ | GET | `/courses/{courseId}/folders` | 🅰🅲🅼 | 폴더 목록. 멤버는 완료 파일만 세고, 원본 열람 off면 403 | P-MAT-04, 05 |
| ✅ | POST | `/courses/{courseId}/folders` | 🅰🅲 | 폴더 만들기 | P-MAT-01 |
| ✅ | PATCH | `/folders/{folderId}` | 🅰🅲 | 이름 변경 (`mid` · `final`은 400) | P-MAT-01 |
| ✅ | DELETE | `/folders/{folderId}` | 🅰🅲 | 삭제 (`mid` · `final`은 400) | |
| ✅ | GET | `/folders/{folderId}/files` | 🅰🅲🅼 | 파일 목록. 멤버는 `ready`만 | P-MAT-04 |
| ✅ | POST | `/folders/{folderId}/files` | 🅰🅲 | 업로드 (multipart `file`, `title`(생략 시 파일 이름), `consent=true`) → `processing`. PDF만, 50MB 이하 | P-MAT-02, 03, P-BIT-18 |
| ✅ | GET | `/files/{fileId}` | 🅰🅲🅼 | 보기용 임시 URL(10분). 원본 열람 off면 멤버 403, 멤버에게 `ready`가 아닌 파일은 404 | P-MAT-05 |
| ✅ | PATCH | `/files/{fileId}` | 🅰🅲 | 이름 변경 `{title}` | |
| ✅ | DELETE | `/files/{fileId}` | 🅰🅲 | 삭제 | |

업로드 응답은 `processing`입니다. 서버가 페이지별 텍스트를 추출하면 `ready`, 읽을 수 없는 PDF면 `failed`가 됩니다. 앱은 파일 목록을 다시 불러 상태를 확인합니다. 폴더를 지우면 안의 파일과 원본도 함께 지워집니다.

### Bit

| 상태 | 메서드 | 경로 | 권한 | 요약 | 정책 |
|---|---|---|---|---|---|
| ✅ | GET | `/bits/today` | 로그인 | 오늘의 과목별 Bit 티켓 | P-BIT-07 |
| ✅ | POST | `/bits/{bitId}/start` | 🅰🅲🅼 | 문제 내려주기 + 시작 시각 기록 (정답 · 해설 없음) | P-BIT-08 |
| ✅ | POST | `/bits/{bitId}/submit` | 🅰🅲🅼 | `{choice}` → 결과 티켓. 시작 전 400, 두 번째 제출 409, 마감 뒤 409 | P-BIT-09, 10 |
| ✅ | GET | `/bits/{bitId}/ticket` | 🅰🅲🅼 | 결과 티켓 다시 보기 | P-BIT-11 |
| ✅ | GET | `/courses/{courseId}/records?day=today\|yesterday` | 🅰🅲🅼 | 함께 듣는 사람 기록 + 해설 + 내 기록 | P-BIT-12 |
| ✅ | GET | `/users/{userId}/profile` | 로그인 | 닉네임 + 전체 학습 기록 격자. 과목 정보 없음 | P-BIT-13, P-SOC-02 |

"오늘"은 17:00 기준입니다. 16:59까지는 어제 17:00에 열린 Bit가 오늘의 Bit입니다. 기록 · 격자의 날짜도 푼 시각이 아니라 Bit가 열린 날짜로 셉니다. `records`는 그날 Bit가 없으면 `bitNo: null`, 빈 `people`, `explanation: null`을 줍니다. 해설은 제출한 뒤나 마감된 Bit(어제)에서만 줍니다.

### 기록 (아카이브)

| 상태 | 메서드 | 경로 | 권한 | 요약 | 정책 |
|---|---|---|---|---|---|
| ✅ | GET | `/courses/{courseId}/archive?page&size` | 🅰🅲🅼 | 마감된 Bit 목록 (통계 · 메모 수) | P-ARC-01, 02 |
| ✅ | GET | `/archive/{bitId}/memos` | 🅰🅲🅼 | 메모 목록. 작성자 정보 없이 `mine`만 | |
| ✅ | POST | `/archive/{bitId}/memos` | 🅰🅲🅼 | 메모 쓰기 | P-ARC-03 |
| ✅ | PATCH | `/memos/{memoId}` | 작성자 | 메모 수정 | P-ARC-04 |
| ✅ | DELETE | `/memos/{memoId}` | 작성자 · 🅰🅲 | 메모 삭제 | P-ARC-04 |

메모는 마감된 Bit(기록에 올라온 문제)에만 남길 수 있습니다. 마감 전 Bit에는 404입니다. 지운 메모는 목록에서 빠지고 다시 수정 · 삭제하면 404입니다.

### 알림

| 상태 | 메서드 | 경로 | 권한 | 요약 | 정책 |
|---|---|---|---|---|---|
| ✅ | POST | `/push/tokens` | 로그인 | FCM 기기 토큰 등록 `{token, platform: android\|ios\|web}`. 같은 토큰은 갱신 | P-NOTI-01 |
| ✅ | DELETE | `/push/tokens` | 로그인 | 기기 토큰 삭제 `{token}` (로그아웃 시) | |

푸시 4종(Bit 열림, 가입 승인, 관리자 승격, 퀴즈 출처 전환)은 알림이 기록되는 즉시 보냅니다. Bit 열림은 그 과목 알림을 켠 사람에게만 갑니다. 푸시의 `data`에는 `type`(알림 종류), `courseId`, Bit 열림이면 `bitId`가 문자열로 들어 있어 앱이 이동할 화면을 정합니다.

### 커넥터

| 상태 | 메서드 | 경로 | 권한 | 요약 | 정책 |
|---|---|---|---|---|---|
| ✅ | GET | `/me/connector` | 로그인 | 커넥터 주소와 클라이언트별 최근 호출. 처음 부르면 발급하고 전체 주소(`fullUrlShown: true`), 이후는 가린 주소 | P-AI-02, 04 |
| ✅ | POST | `/me/connector/reissue` | 로그인 | 새 주소 발급, 이전 주소 즉시 무효 | P-AI-02 |

### AI 작업 공간 **[검증 필요]**

| 상태 | 메서드 | 경로 | 권한 | 요약 | 정책 |
|---|---|---|---|---|---|
| ⬜ | GET | `/me/workspace` | 로그인 | 저장소 연결 상태, AI별 상태(`none` · `ready` · `expired`) | P-AI-07 |
| ⬜ | POST | `/me/workspace/github` | 로그인 | 1단계: GitHub 권한 요청 URL → 비공개 저장소 · 워크플로 생성 | P-AI-08 |
| ⬜ | POST | `/me/workspace/{ai}/setup` | 로그인 | 2단계: Codespace 열기 URL (`daybit-setup {ai}` 실행) | P-AI-08 |
| ⬜ | POST | `/me/workspace/{ai}/test` | 로그인 | 3단계: 샘플 3문항 워크플로 실행 → 완료 시 `ready` | P-AI-08 |

워크플로가 생성한 문제를 받는 `POST /internal/workspace/results`(저장소별 서명으로 검증, P-AI-09)는 아직 컨트롤러가 없습니다.

## MCP 서버

커넥터는 REST가 아니라 원격 MCP(Streamable HTTP)로 제공하므로 Swagger에 나오지 않습니다. 구현 방식은 [결정 기록 D10](decisions.md)에 있습니다.

- 주소: `https://<BE 도메인>/mcp/u/{token}`. 인증은 주소 안의 개인 토큰입니다. 틀리거나 재발급으로 무효가 된 토큰은 404입니다(401이면 AI 앱이 OAuth 로그인을 시도하기 때문). OAuth 방식 추가는 [미정]입니다.
- `POST`로 JSON-RPC 요청 하나를 보내면 JSON 응답 하나를 받습니다. 서버 → 클라이언트 스트림(`GET`, SSE)은 쓰지 않아 405입니다. 메서드는 `initialize`, `ping`, `tools/list`, `tools/call`입니다.
- 범위: 토큰 주인이 구독 중인 과목의 `ready` 자료만 읽기 전용으로 제공합니다 (P-AI-03). 원본 열람을 꺼도 막지 않습니다 (P-MAT-06).
- 호출 기록: `initialize`의 `clientInfo.name`(없으면 User-Agent)으로 Claude, ChatGPT, Claude Code, Codex를 구분해 `GET /me/connector`의 등록 상태에 씁니다 (P-AI-04).
- 범위 밖 자료, 6쪽 이상 읽기 같은 실패는 HTTP 오류가 아니라 도구 결과의 `isError: true`와 안내 문구로 돌려줍니다.

| 도구 | 입력 | 출력 | 용도 |
|---|---|---|---|
| `list_courses` | 없음 | `[{courseId, name, code, term}]` | 내 과목 목록 |
| `list_materials` | `{courseId}` | `[{folder, fileId, title, pages}]` | 폴더와 파일 목록 |
| `search` | `{courseId, query, limit?=5}` | `[{fileId, title, page, snippet}]` | 키워드가 들어 있는 페이지. 여러 단어는 공백으로 나누고 많이 맞은 페이지부터. 최대 20 |
| `read_pages` | `{fileId, pages:[12,13]}` | `[{page, text}]` (한 번에 최대 5쪽) | 필요한 페이지 텍스트만 반환 |
| `get_page_image` | `{fileId, page}` | PNG 이미지 1장 | 도표 · 수식이 필요할 때만. 원본 PDF에서 그때그때 그림 |

## 배치 작업

서울 시각 기준입니다. ✅는 구현한 작업입니다.

| 상태 | 시각 | 작업 | 정책 |
|---|---|---|---|
| ✅ | 자료 업로드 시 | 텍스트 추출 → `ready` · `failed` | P-MAT-03 |
| ✅ | 자료 처리 완료 시 | 개념 · 문제 생성(Gemini Flash). 근거 문장이 페이지에 있는 문제만 `verified`. 작업 공간 출처는 건너뜀 | P-BIT-01, 02, 14 |
| ✅ | 매일 16:30 | 과목별 출제 범위 계산, 문제 1개 선택. 자료가 없으면 건너뜀 | P-BIT-03, 04, 06 |
| ✅ | 매일 17:00 | 오늘 Bit 열기와 알림. 어제 Bit 마감 → 집계 → 아카이브 게시 | P-BIT-05, P-ARC-01 |
| ✅ | 매일 00:10 | 과목 상태 점검: 활동 없음 OFF, 기말 OFF, 중간 시험일 경과 시 범위 전환 | P-BIT-16~18 |
| ⬜ | 작업 공간 실패 시 | 출처를 Gemini Flash로 전환, 관리자 · 공동 관리자 알림 | P-BIT-15 |
