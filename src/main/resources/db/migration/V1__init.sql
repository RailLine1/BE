-- Daybit 초기 스키마
-- 기준: docs/05_DB스키마.md (규칙 번호는 docs/02_정책서.md)
-- 문서와 다른 점:
--   * refresh_tokens 추가 (JWT 리프레시 토큰, 앱 로그인 유지용)
--   * push_subscriptions(웹 푸시) 대신 push_tokens(FCM 기기 토큰) — 기술 스택 결정에 따름
--   * bits.open_date 추가 — unique(course_id, open_at::date) 를 표현하기 위한 컬럼

create extension if not exists pg_trgm;

-- =========================================================
-- 1. 계정
-- =========================================================
create table users (
    id            bigserial primary key,
    login_id      varchar(20) unique,                 -- P-ACC-02
    password_hash text,
    github_id     bigint unique,                      -- P-ACC-06
    github_login  varchar(39),
    nickname      varchar(12) not null unique,        -- P-ACC-03
    onboarded_at  timestamptz,                        -- P-ACC-05
    created_at    timestamptz not null default now(),
    constraint users_login_method_chk check (login_id is not null or github_id is not null)
);

create table refresh_tokens (
    id          bigserial primary key,
    user_id     bigint not null references users (id) on delete cascade,
    token_hash  text not null unique,
    expires_at  timestamptz not null,
    revoked_at  timestamptz,
    created_at  timestamptz not null default now()
);
create index refresh_tokens_user_idx on refresh_tokens (user_id);

-- =========================================================
-- 2. 과목
-- =========================================================
create table courses (
    id                  bigserial primary key,
    code                char(6) not null unique,                       -- P-COURSE-02
    name                varchar(40) not null,
    icon                varchar(20) not null,
    intro               varchar(60),
    term                varchar(20),
    visibility          varchar(10) not null check (visibility in ('public', 'private')),
    raw_allowed         boolean not null default true,                 -- P-MAT-05
    mid_exam_date       date,                                          -- P-BIT-03
    final_exam_date     date,                                          -- P-BIT-17
    scope               varchar(10) check (scope in ('mid', 'final')),
    quiz_source_type    varchar(10) not null default 'default' check (quiz_source_type in ('default', 'workspace')),
    quiz_source_user_id bigint references users (id) on delete set null, -- P-AI-09
    quiz_source_ai      varchar(20) check (quiz_source_ai in ('claudecode', 'codex')),
    state               varchar(10) not null default 'active' check (state in ('active', 'ended')),
    ended_reason        varchar(20) check (ended_reason in ('inactive', 'final_exam')),
    ended_at            timestamptz,
    last_upload_at      timestamptz,
    created_by          bigint not null references users (id),
    created_at          timestamptz not null default now()
);
create index courses_explore_idx on courses (visibility, state, created_at desc);
create index courses_name_trgm_idx on courses using gin (name gin_trgm_ops);

create table course_members (
    course_id    bigint not null references courses (id) on delete cascade,
    user_id      bigint not null references users (id) on delete cascade,
    role         varchar(10) not null check (role in ('admin', 'co', 'member')),
    status       varchar(10) not null check (status in ('active', 'pending')),
    co_since     timestamptz,                 -- P-ROLE-03 승계 순서
    notify       boolean not null default true, -- P-NOTI-02
    requested_at timestamptz,
    joined_at    timestamptz,
    created_at   timestamptz not null default now(),
    primary key (course_id, user_id)
);
create index course_members_user_idx on course_members (user_id, status);
create index course_members_role_idx on course_members (course_id, role, co_since);
-- 과목마다 관리자는 1명
create unique index course_members_one_admin_uq on course_members (course_id) where role = 'admin';

-- =========================================================
-- 3. 학습자료
-- =========================================================
create table folders (
    id         bigserial primary key,
    course_id  bigint not null references courses (id) on delete cascade,
    name       varchar(30) not null,
    kind       varchar(10) not null check (kind in ('mid', 'final', 'custom')), -- P-MAT-01
    sort       int not null default 0,
    created_at timestamptz not null default now()
);
create index folders_course_idx on folders (course_id, sort);

create table files (
    id          bigserial primary key,
    folder_id   bigint not null references folders (id) on delete cascade,
    course_id   bigint not null references courses (id) on delete cascade,
    title       varchar(80) not null,
    storage_key text not null,
    pages       int,
    status      varchar(12) not null check (status in ('processing', 'ready', 'failed')), -- P-MAT-03
    uploaded_by bigint not null references users (id),
    consent_at  timestamptz not null,                                                   -- P-MAT-02
    created_at  timestamptz not null default now()
);
create index files_course_status_idx on files (course_id, status);
create index files_folder_idx on files (folder_id);

create table file_pages (
    file_id   bigint not null references files (id) on delete cascade,
    page_no   int not null,
    text      text,
    image_key text,
    primary key (file_id, page_no)
);
-- 커넥터 search: 한글 부분 일치 검색 (pg_trgm)
create index file_pages_text_trgm_idx on file_pages using gin (text gin_trgm_ops);

create table concepts (
    id           bigserial primary key,
    course_id    bigint not null references courses (id) on delete cascade,
    file_id      bigint not null references files (id) on delete cascade,
    name         varchar(80) not null,
    summary      text,
    page_from    int,
    page_to      int,
    weight       smallint not null default 2 check (weight between 1 and 3),
    capacity     smallint not null default 2,
    times_used   int not null default 0,
    last_used_at timestamptz,          -- P-BIT-04 최근 출제 페널티
    wrong_rate   numeric(4, 3),
    created_at   timestamptz not null default now()
);
create index concepts_course_idx on concepts (course_id);

-- =========================================================
-- 4. Bit
-- =========================================================
create table questions (
    id               bigserial primary key,
    course_id        bigint not null references courses (id) on delete cascade,
    concept_id       bigint references concepts (id) on delete set null,
    scope            varchar(10) check (scope in ('mid', 'final')),
    stem             text not null,
    choices          jsonb not null,
    answer_idx       smallint not null check (answer_idx between 0 and 3),
    explanation      text not null,
    evidence_file_id bigint references files (id) on delete set null,
    evidence_page    int,
    source           varchar(20) check (source in ('gemini', 'claudecode', 'codex')),
    verified         boolean not null default false,
    used_at          timestamptz,
    created_at       timestamptz not null default now()
);
create index questions_bank_idx on questions (course_id, scope, used_at);

create table bits (
    id          bigserial primary key,
    course_id   bigint not null references courses (id) on delete cascade,
    no          int not null,
    question_id bigint not null references questions (id),
    open_date   date not null,          -- 서울 기준 출제일
    open_at     timestamptz not null,   -- 17:00 (P-BIT-05)
    close_at    timestamptz not null,   -- 다음 날 16:59
    archived_at timestamptz,            -- P-ARC-01
    attempts    int,
    accuracy    numeric(4, 1),
    average_sec int,
    created_at  timestamptz not null default now(),
    unique (course_id, no),
    unique (course_id, open_date)
);
create index bits_open_idx on bits (open_date);

create table bit_attempts (
    bit_id       bigint not null references bits (id) on delete cascade,
    user_id      bigint not null references users (id) on delete cascade,
    started_at   timestamptz not null,  -- P-BIT-08 서버 기록
    submitted_at timestamptz,
    choice_idx   smallint,
    correct      boolean,
    elapsed_sec  int,
    score        int,                   -- P-BIT-10
    primary key (bit_id, user_id)       -- P-BIT-09 한 번만 응시
);
create index bit_attempts_user_idx on bit_attempts (user_id, submitted_at);

create table user_course_stats (
    user_id           bigint not null references users (id) on delete cascade,
    course_id         bigint not null references courses (id) on delete cascade,
    current_streak    int not null default 0,
    best_streak       int not null default 0,
    attempts          int not null default 0,
    corrects          int not null default 0,
    last_attempt_date date,
    primary key (user_id, course_id)
);

-- =========================================================
-- 5. 기록 (아카이브)
-- =========================================================
create table memos (
    id         bigserial primary key,
    bit_id     bigint not null references bits (id) on delete cascade, -- P-ARC-03
    user_id    bigint not null references users (id) on delete cascade,
    body       varchar(500) not null,
    updated_at timestamptz,
    deleted_at timestamptz,
    deleted_by bigint references users (id),                           -- P-ARC-04
    created_at timestamptz not null default now()
);
create index memos_bit_idx on memos (bit_id) where deleted_at is null;

-- =========================================================
-- 6. AI 연결
-- =========================================================
create table connector_tokens (
    user_id    bigint primary key references users (id) on delete cascade,
    token_hash text not null unique,     -- P-AI-02 원문 저장 안 함
    issued_at  timestamptz not null default now()
);

create table connector_calls (
    id        bigserial primary key,
    user_id   bigint not null references users (id) on delete cascade,
    client    varchar(20) not null check (client in ('claude', 'chatgpt', 'claudecode', 'codex', 'unknown')),
    tool      varchar(30) not null,
    course_id bigint,
    called_at timestamptz not null default now()
);
create index connector_calls_user_client_idx on connector_calls (user_id, client, called_at desc);

create table workspaces (               -- [검증 필요] P-AI-07~09
    user_id          bigint primary key references users (id) on delete cascade,
    github_repo      varchar(140),
    signing_key_hash text,
    linked_at        timestamptz
);

create table workspace_ais (
    user_id      bigint not null references workspaces (user_id) on delete cascade,
    ai           varchar(20) not null check (ai in ('claudecode', 'codex')),
    status       varchar(10) not null default 'none' check (status in ('none', 'ready', 'expired')),
    last_test_at timestamptz,
    last_error   text,
    primary key (user_id, ai)
);

-- =========================================================
-- 7. 알림
-- =========================================================
create table push_tokens (
    id         bigserial primary key,
    user_id    bigint not null references users (id) on delete cascade,
    token      text not null unique,     -- FCM 등록 토큰
    platform   varchar(10) not null check (platform in ('android', 'ios', 'web')),
    created_at timestamptz not null default now(),
    last_seen_at timestamptz not null default now()
);
create index push_tokens_user_idx on push_tokens (user_id);

create table notifications (
    id         bigserial primary key,
    user_id    bigint not null references users (id) on delete cascade,
    type       varchar(30) not null check (type in ('bit_open', 'join_approved', 'promoted_admin', 'quiz_source_fallback')), -- P-NOTI-03
    payload    jsonb not null default '{}'::jsonb,
    read_at    timestamptz,
    created_at timestamptz not null default now()
);
create index notifications_user_idx on notifications (user_id, created_at desc);
