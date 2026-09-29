"""Как технологию называют статьи: до пяти имён от модели, чтобы искать по ним, а не по формулировке аналитика."""
from __future__ import annotations
import json, os, sys, urllib.request

PROMPT = (
    "For each numbered technology below, list up to 5 short phrases (2-5 words) that scientific "
    "papers and technical documentation actually use as its name — the wording that appears in "
    "titles and abstracts, including the umbrella term the field uses. Answer with a JSON object "
    "mapping the number to the array of phrases, nothing else."
)

def ask(items):
    body = json.dumps({
        "model": "gpt-5.6-luna",
        "messages": [{"role": "user", "content": PROMPT + "\n" + "\n".join(
            f"{i['number']}. {i['phrase']} — {i['name']}" for i in items)}],
        "reasoning_effort": "low", "max_completion_tokens": 8000,
    }).encode()
    request = urllib.request.Request(
        os.environ["LUNA_BASE"].rstrip("/") + "/chat/completions", data=body,
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + os.environ["LUNA_KEY"]})
    with urllib.request.urlopen(request, timeout=300) as response:
        text = json.loads(response.read())["choices"][0]["message"]["content"]
    return json.loads(text[text.find("{"): text.rfind("}") + 1])

def main() -> None:
    scratch = sys.argv[1]
    items = json.load(open(f"{scratch}/dataset_items.json", encoding="utf-8"))
    out = {}
    for start in range(0, len(items), 10):
        chunk = items[start:start + 10]
        try:
            out.update(ask(chunk))
        except Exception as error:  # noqa: BLE001
            print("пачка не удалась:", error, flush=True)
        print("готово", min(start + 10, len(items)), flush=True)
    json.dump(out, open(f"{scratch}/variants_100.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("с вариантами:", sum(1 for v in out.values() if v), "из", len(items))

if __name__ == "__main__":
    main()
