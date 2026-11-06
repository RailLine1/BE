-- 내 AI 출제 (#48, P-AI-07~11): GitHub 작업 공간(#10)을 예약 작업 방식으로 바꿔요.

-- 퀴즈 출처: workspace(claudecode · codex) → myai(claude · chatgpt). 기존 workspace 출처는 Gemini 로 되돌려요
update courses set quiz_source_type = 'default', quiz_source_user_id = null, quiz_source_ai = null
 where quiz_source_type <> 'default';
alter table courses drop constraint courses_quiz_source_type_check;
alter table courses add constraint courses_quiz_source_type_check check (quiz_source_type in ('default', 'myai'));
alter table courses drop constraint courses_quiz_source_ai_check;
alter table courses add constraint courses_quiz_source_ai_check check (quiz_source_ai in ('claude', 'chatgpt'));

-- 문제 출처. 이미 저장된 claudecode · codex 문제는 그대로 둬요
alter table questions drop constraint questions_source_check;
alter table questions add constraint questions_source_check check (source in ('gemini', 'claude', 'chatgpt', 'claudecode', 'codex'));

-- 예약 실행 기록: get_jobs 가 처음 들어오면 "등록됨"(P-AI-08), 48시간 동안 없으면 출처 전환(P-BIT-15)
create table myai_runs (
    user_id      bigint not null references users (id) on delete cascade,
    ai           varchar(10) not null check (ai in ('claude', 'chatgpt')),
    first_run_at timestamptz not null default now(),
    last_run_at  timestamptz not null default now(),
    primary key (user_id, ai)
);

drop table workspace_jobs;
drop table workspace_ais;
drop table workspaces;
