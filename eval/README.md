# 문제 품질 실험

BE 서버와 같은 방식(같은 프롬프트 · 스키마 · 근거 검증)으로 Gemini를 불러 문제를 만들고 결과를 비교합니다. 나은 프롬프트가 나오면 BE의 `src/main/resources/prompts/question-generation.md`에 PR로 반영합니다.

```
python eval/generate.py --pages eval/samples/lec08.json --prompt eval/prompts/understanding.md --title "강의 제목" --count 10
```

- `eval/samples/`: 자료 페이지 JSON `[{"page": 1, "text": "..."}]`. 강의자료 원문이라 **git에 올리지 않습니다**(`.gitignore`). 로컬 DB의 `file_pages`에서 내보냅니다.
- `eval/prompts/`: 비교할 프롬프트. `baseline.md`는 BE에 반영된 현재 프롬프트입니다.
- `eval/results/`: 실행마다 JSON(지표 + 문제 전체)이 남습니다. git에 올리지 않습니다.
- 키는 환경 변수 `DAYBIT_GEMINI_API_KEY` 또는 저장소의 `.env`에서 읽습니다.

지표: `verified`(근거 문장이 페이지에 있는 문제 수), `wellFormed`(형식이 맞는 문제 수), `concepts`(서로 다른 개념 수), `pagesCovered`(근거로 쓴 페이지).
