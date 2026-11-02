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
