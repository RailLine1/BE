-- AI 작업 공간 PoC (#10, 결정 D11)
-- workspaces: 관리자 GitHub 토큰(서버 키로 암호화)과 GitHub 로그인. 구독 토큰은 저장하지 않아요 (P-AI-09)
alter table workspaces add column github_login varchar(39);
alter table workspaces add column github_token_enc text;

-- 작업 공간에 맡긴 생성 작업. 워크플로가 job 을 받아 가고 결과를 돌려줘요
create table workspace_jobs (
    id          uuid primary key,
    user_id     bigint not null references users (id) on delete cascade,
    ai          varchar(20) not null check (ai in ('claudecode', 'codex')),
    kind        varchar(10) not null check (kind in ('test', 'generate')),
    course_id   bigint references courses (id) on delete cascade,
    file_id     bigint references files (id) on delete cascade,
    count       int not null,
    status      varchar(10) not null default 'pending' check (status in ('pending', 'running', 'done', 'failed')),
    error       text,
    created_at  timestamptz not null default now(),
    finished_at timestamptz
);
create index workspace_jobs_status_idx on workspace_jobs (status, created_at);
create index workspace_jobs_user_idx on workspace_jobs (user_id, ai, created_at desc);
