"""
문제 생성 품질 실험 스크립트.

BE 서버와 같은 방식(같은 프롬프트 · 스키마 · 근거 검증)으로 Gemini 를 불러 문제를 만들고,
결과와 지표를 eval/results/ 에 남긴다. 프롬프트만 바꿔 가며 비교할 때 쓴다.

사용:
  set DAYBIT_GEMINI_API_KEY=...            (또는 저장소의 .env 를 자동으로 읽음)
  python eval/generate.py --pages eval/samples/lec08.json --prompt eval/prompts/baseline.md --count 10

pages 파일 형식: [{"page": 1, "text": "..."}, ...]  (BE file_pages 를 내보낸 것)
강의자료 원문은 저작권이 있으므로 eval/samples/ 는 git 에 올리지 않는다(.gitignore).
"""
import argparse
import datetime
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BE_PROMPTS = os.path.join(ROOT, "src", "main", "resources", "prompts")
MODELS = ["gemini-3.8-flash", "gemini-flash-latest", "gemini-3.5-flash", "gemini-flash-lite-latest"]


def api_key():
    key = os.environ.get("DAYBIT_GEMINI_API_KEY")
    if key:
        return key
    env = os.path.join(ROOT, ".env")
    if os.path.exists(env):
        for line in open(env, encoding="utf-8"):
            if line.startswith("DAYBIT_GEMINI_API_KEY="):
                return line.split("=", 1)[1].strip()
    sys.exit("DAYBIT_GEMINI_API_KEY 가 없어요")


def upper_types(node):
    if isinstance(node, dict):
        return {k: (v.upper() if k == "type" and isinstance(v, str) else upper_types(v)) for k, v in node.items()}
    if isinstance(node, list):
        return [upper_types(x) for x in node]
    return node


def user_prompt(course, title, count, pages):
    lines = [f"과목: {course}", f"자료: {title}", f"만들 문제 수: {count}", ""]
    for p in pages:
        lines += [f'<page number="{p["page"]}">', p["text"], "</page>"]
    return "\n".join(lines) + "\n"


def call(key, body, models):
    last = None
    for model in models:
        req = urllib.request.Request(
            f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
            data=json.dumps(body).encode("utf-8"),
            headers={"x-goog-api-key": key, "Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(req, timeout=180) as res:
                return model, json.loads(res.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            last = f"{model}: {e.code}"
            if e.code not in (404, 429) and e.code < 500:
                raise
        except Exception as e:  # 시간 초과 등
            last = f"{model}: {e}"
        print("  넘어감:", last, file=sys.stderr)
    sys.exit(f"모든 모델 실패: {last}")


def normalize(s):
    return re.sub(r"\s+", "", s.lower())


def evaluate(questions, pages):
    """BE QuestionBankService 와 같은 형식 · 근거 검증"""
    texts = {p["page"]: p["text"] for p in pages}
    rows = []
    for q in questions:
        choices = [c.strip() for c in q.get("choices", [])]
        well_formed = (
            all(q.get(k, "").strip() for k in ("concept", "stem", "explanation"))
            and len(choices) == 4 and all(choices) and len(set(choices)) == 4
            and q.get("answerIndex") in range(4) and q.get("evidencePage") in texts
        )
        quote = normalize(q.get("evidenceQuote", ""))
        grounded = well_formed and len(quote) >= 10 and quote in normalize(texts[q["evidencePage"]])
        rows.append({**q, "wellFormed": well_formed, "verified": grounded})
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pages", required=True)
    ap.add_argument("--prompt", default=os.path.join(BE_PROMPTS, "question-generation.md"))
    ap.add_argument("--schema", default=os.path.join(BE_PROMPTS, "question-schema.json"))
    ap.add_argument("--course", default="운영체제")
    ap.add_argument("--title", default="강의 자료")
    ap.add_argument("--count", type=int, default=10)
    ap.add_argument("--models", default=",".join(MODELS))
    args = ap.parse_args()

    pages = [p for p in json.load(open(args.pages, encoding="utf-8")) if p["text"].strip()]
    system = open(args.prompt, encoding="utf-8").read().strip()
    schema = upper_types(json.load(open(args.schema, encoding="utf-8")))
    body = {
        "systemInstruction": {"parts": [{"text": system}]},
        "contents": [{"role": "user", "parts": [{"text": user_prompt(args.course, args.title, args.count, pages)}]}],
        "generationConfig": {"temperature": 0.4, "responseMimeType": "application/json", "responseSchema": schema},
    }
    started = time.time()
    model, res = call(api_key(), body, [m.strip() for m in args.models.split(",") if m.strip()])
    text = res["candidates"][0]["content"]["parts"][0]["text"]
    rows = evaluate(json.loads(text).get("questions", []), pages)

    summary = {
        "prompt": os.path.basename(args.prompt),
        "model": model,
        "seconds": round(time.time() - started, 1),
        "requested": args.count,
        "returned": len(rows),
        "wellFormed": sum(r["wellFormed"] for r in rows),
        "verified": sum(r["verified"] for r in rows),
        "concepts": len({r.get("concept", "").strip() for r in rows}),
        "pagesCovered": sorted({r.get("evidencePage") for r in rows if r["wellFormed"]}),
        "usage": res.get("usageMetadata"),
    }
    os.makedirs(os.path.join(ROOT, "eval", "results"), exist_ok=True)
    stamp = datetime.datetime.now().strftime("%m%d-%H%M%S")
    out = os.path.join(ROOT, "eval", "results", f"{stamp}-{os.path.splitext(summary['prompt'])[0]}.json")
    json.dump({"summary": summary, "questions": rows}, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print("저장:", out)


if __name__ == "__main__":
    main()
