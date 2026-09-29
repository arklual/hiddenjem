"""Статистика по имени, которым технологию называют статьи.

Два шага, чтобы не тратить запросы впустую: сначала у каждого варианта имени спрашивается общее
число работ за окно (один запрос), затем ряд по годам собирается только для лучшего варианта.
"""

from __future__ import annotations

import json
import subprocess
import sys
import time
import urllib.parse
from pathlib import Path

YEARS = range(2016, 2027)


def fetch(url: str) -> dict:
    out = subprocess.run(
        ["curl", "-sL", "--max-time", "40", "-A", "HorizonBot/1.0 (research validation)", url],
        capture_output=True, text=True,
    ).stdout
    try:
        return json.loads(out)
    except ValueError:
        return {}


def total(term: str, years: str) -> int | None:
    """Число работ за окно. Semantic Scholar режет по темпу, поэтому три попытки с отступлением."""
    quoted = urllib.parse.quote(f'"{term}"')
    url = (
        "https://api.semanticscholar.org/graph/v1/paper/search/bulk"
        f"?query={quoted}&year={years}&fields=year"
    )
    for attempt in range(3):
        payload = fetch(url)
        value = payload.get("total")
        if isinstance(value, int):
            return value
        time.sleep(4 * (attempt + 1))
    return None


def main() -> None:
    scratch = Path(sys.argv[1])
    items = json.loads((scratch / "dataset_items.json").read_text(encoding="utf-8"))
    variants = json.loads((scratch / "variants_100.json").read_text(encoding="utf-8"))
    out_path = scratch / "variants_s2.jsonl"
    # Повторный запуск добирает только то, что не удалось: строка без имени считается несобранной.
    done = set()
    kept = []
    if out_path.is_file():
        for line in out_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                if row.get("best"):
                    done.add(row["number"])
                    kept.append(line)
        out_path.write_text("\n".join(kept) + ("\n" if kept else ""), encoding="utf-8")
    with out_path.open("a", encoding="utf-8") as out:
        for index, item in enumerate(items, 1):
            number = item["number"]
            if number in done:
                continue
            names = [str(v) for v in (variants.get(str(number)) or [])][:5]
            scored = []
            for name in names:
                value = total(name, "2016-2026")
                time.sleep(1.6)
                if value is not None:
                    scored.append((value, name))
            best = max(scored, default=(None, None))
            series = {}
            if best[1] and best[0]:
                for year in YEARS:
                    value = total(best[1], f"{year}-{year}")
                    if value is not None:
                        series[str(year)] = value
                    time.sleep(1.6)
            out.write(json.dumps({
                "number": number, "phrase": item["phrase"], "best": best[1], "total": best[0],
                "by_year": series, "checked": [{"name": n, "total": v} for v, n in scored],
            }, ensure_ascii=False) + "\n")
            out.flush()
            print(f"[{index}/{len(items)}] {item['phrase'][:34]} -> {best[1]} ({best[0]})", flush=True)


if __name__ == "__main__":
    main()
