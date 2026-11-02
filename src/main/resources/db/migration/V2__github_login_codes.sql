-- GitHub 로그인: 서버 콜백 → 앱 딥링크로 넘기는 일회용 code (결정 D3)
-- 원문은 앱에만 가고 서버에는 해시만 저장해요. 1분 안에 한 번만 교환할 수 있어요.
create table github_login_codes (
    code_hash      text primary key,
    user_id        bigint not null references users (id) on delete cascade,
    needs_nickname boolean not null default false,
    expires_at     timestamptz not null,
    used_at        timestamptz,
    created_at     timestamptz not null default now()
);
create index github_login_codes_expires_idx on github_login_codes (expires_at);
