-- 정답 숨긴 검수(#52)를 Gemini 장애 · 한도로 못 한 문제를 다시 검수해요.
-- check_pending: 근거 검증은 통과했고 검수를 기다리는 문제. check_after: 이 시각 이후에 다시 시도해요.
alter table questions add column check_pending boolean not null default false;
alter table questions add column check_after timestamptz;
create index questions_check_pending_idx on questions (check_after) where check_pending;
