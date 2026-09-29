"""Сто технологий эталона из машинного отчёта сравнения: номер, направление, название, формулировка.

Отчёт — единственное место, где рядом лежат и русское название из датасета, и английская
формулировка, которой технологию искали, и её судьба в конвейере.
"""
from __future__ import annotations
import json, re, sys
from pathlib import Path

AREA = re.compile(r"^## (.+?) — запрос «(.+?)»")
ROW = re.compile(r"^\| (\d+) \| (.+?) \| (.+?) \| (.+?) \| (.*?) \|$")

def main() -> None:
    text = Path(sys.argv[1]).read_text(encoding="utf-8")
    area = None
    items = []
    for line in text.splitlines():
        m = AREA.match(line)
        if m:
            area, query = m.group(1), m.group(2)
            continue
        m = ROW.match(line)
        if m and area and m.group(1).isdigit():
            number, name, phrase, top15, fate = m.groups()
            phrase_docs = all_docs = None
            got = re.search(r"в корпусе: фраза (\d+), все слова (\d+)", fate)
            if got:
                phrase_docs, all_docs = int(got.group(1)), int(got.group(2))
            items.append({
                "number": int(number), "area": area, "query": query, "name": name.strip(),
                "phrase": phrase.strip(), "top15": top15.strip(),
                "fate": re.sub(r";? ?в корпусе:.*", "", fate).strip(),
                "corpusPhraseDocuments": phrase_docs, "corpusAllWordsDocuments": all_docs,
            })
    json.dump(items, open(sys.argv[2], "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print("технологий:", len(items), "| направлений:", len({i["area"] for i in items}))

if __name__ == "__main__":
    main()
