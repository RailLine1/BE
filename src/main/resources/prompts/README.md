# 문제 생성 프롬프트

기본 출처(Gemini)와 AI 작업 공간(Claude Code · Codex)이 같은 파일을 씁니다. 품질 실험 결과를 반영할 때는 이 폴더의 파일만 고칩니다.

| 파일 | 내용 |
|---|---|
| `question-generation.md` | 출제 규칙(시스템 프롬프트). 파일 내용이 그대로 모델에 갑니다 |
| `question-schema.json` | 응답 JSON 스키마. 필드 이름을 바꾸면 `QuestionPrompt` · 서버 검증도 같이 바꿔야 합니다 |

사용자 프롬프트(과목명, 자료명, 문제 수, `<page number="N">` 본문)는 `bit/generation/QuestionPrompt.kt`가 만듭니다.
