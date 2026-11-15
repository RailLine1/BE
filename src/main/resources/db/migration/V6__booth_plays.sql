-- 부스 퀴즈 (행사 부스 운영용, 앱 기능과 별개). 로그인 없이 닉네임으로 한 번 풀어요.
-- 문제는 src/main/resources/booth/questions.json 에 두고 DB 에는 풀이 기록만 남겨요.
create table booth_plays (
    id           uuid primary key,
    nickname     text not null unique,
    started_at   timestamptz not null,
    submitted_at timestamptz,
    choices      text,          -- 고른 보기 번호를 쉼표로 (예: 1,0,2)
    correct      int,
    elapsed_ms   int,
    created_at   timestamptz not null default now()
);

create index booth_plays_rank_idx on booth_plays (correct desc, elapsed_ms, submitted_at) where submitted_at is not null;
