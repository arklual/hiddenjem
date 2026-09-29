"""Второй, независимый ряд по годам: Semantic Scholar по точной фразе.

Зачем второй источник. OpenAlex — основной, но у него суточный бюджет, и в день замера он
кончается. Semantic Scholar отвечает на ту же точную фразу и ограничен только темпом, поэтому
годится и как проверка первого источника, и как замена, когда тот молчит.
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


def count(term: str, year: int) -> int | None:
    quoted = urllib.parse.quote(f'"{term}"')
    payload = fetch(
        "https://api.semanticscholar.org/graph/v1/paper/search/bulk"
        f"?query={quoted}&year={year}-{year}&fields=year"
    )
    total = payload.get("total")
    return int(total) if isinstance(total, int) else None


def main() -> None:
    scratch = Path(sys.argv[1])
    terms = json.loads((scratch / sys.argv[2]).read_text(encoding="utf-8"))
    out_path = scratch / sys.argv[3]
    done = {}
    if out_path.is_file():
        for line in out_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                done[row["term"]] = row
    with out_path.open("a", encoding="utf-8") as out:
        for index, item in enumerate(terms, 1):
            term = item["term"] if isinstance(item, dict) else str(item)
            if term in done:
                continue
            series = {}
            for year in YEARS:
                value = count(term, year)
                # Отказ источника не превращается в ноль: пропуск остаётся пропуском.
                if value is not None:
                    series[str(year)] = value
                time.sleep(1.1)
            out.write(json.dumps({"term": term, "by_year": series}, ensure_ascii=False) + "\n")
            out.flush()
            print(f"[{index}/{len(terms)}] {term[:40]}: {sum(series.values())} работ", flush=True)


if __name__ == "__main__":
    main()
