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

문서의 `/auth/github/callback` 방식은 앱이 OAuth 콜백을 직접 받을 수 없어 쓰지 않는다. `/auth/github/start?redirect=` → GitHub → 서버 콜백 → 앱 딥링크로 일회용 code 전달 → `POST /auth/github/exchange`로 토큰 교환 순서다. 현재 API 형식만 정했고 구현은 이슈 #9에서 한다.

## D4. 푸시는 FCM

문서의 웹 푸시(`push_subscriptions`)는 앱 안에서 동작하지 않아, `06_기술스택`에서 FCM으로 통일했다. 테이블은 `push_tokens(token, platform: android | ios | web)`이다. iOS 발송에는 APNs 키가 필요하다. 구현은 이슈 #7에서 한다.

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
