"""Evaluate the live search API on task descriptions against product types in the original XML."""
import argparse
import csv
import gzip
import hashlib
import json
import math
import random
import re
import statistics
import time
from collections import defaultdict
from pathlib import Path
from urllib.request import Request, urlopen
from xml.etree import ElementTree

CATEGORIES = {
    "backup": {"1033"}, "recovery": {"5922"}, "remote": {"327029", "327030"},
    "antivirus": {"629", "3775", "650"}, "printer": {"91774", "93995"}, "ocr": {"6"},
    "video": {"495"}, "photo": {"186", "241"}, "virtual": {"2500", "222321"},
    "archive": {"717"}, "ups": {"82080"}, "switch": {"85741"}, "monitor": {"80843"},
}


def relevant(intent, category, name):
    if intent == "server":
        return category in {"86563", "87093", "87268", "87276"} and bool(
            re.search(r"сервер|\bserver|superserver|depo storm", name))
    if intent == "meet":
        return bool(re.search(r"мтс линк|trueconf|вебинар|webinar|видеоконферен|video conferenc|zoom meetings|webex meetings", name)
                    and not re.search("камера|микрофон", name))
    title_matches = {"ocr": r"finereader|\bocr\b", "recovery": "recoverit|data recovery|восстановлени[ея] данных",
                     "video": "premiere pro|vegas pro|video editor", "remote": "anydesk|teamviewer.*remote"}
    return category in CATEGORIES[intent] or (intent in title_matches and bool(re.search(title_matches[intent], name)))


def read_catalog(path):
    products = {}
    eligible = {intent: set() for intent in (*CATEGORIES, "server", "meet")}
    with path.open("rb") as raw:
        compressed = raw.read(2) == b"\x1f\x8b"
        raw.seek(0)
        with gzip.GzipFile(fileobj=raw) if compressed else raw as xml:
            for _, node in ElementTree.iterparse(xml):
                if node.tag == "offer":
                    products[node.attrib["id"]] = (node.findtext("categoryId"), node.findtext("name", "").casefold())
                    if node.get("available") == "true" and float(node.findtext("price") or 0) > 0:
                        family = node.get("group_id") or node.findtext("group_id") or node.attrib["id"]
                        for intent, groups in eligible.items():
                            if len(groups) < 4 and relevant(intent, *products[node.attrib["id"]]):
                                groups.add(family)
                    node.clear()
    return products, {intent: dcg([1] * len(groups)) for intent, groups in eligible.items()}


def dcg(bits):
    return sum(bit / math.log2(position + 2) for position, bit in enumerate(bits))


def measure(cases, endpoint, fingerprint, products, ideals, repeat):
    for repetition in range(repeat):
        ordered = cases.copy()
        random.Random(211 + repetition).shuffle(ordered)
        for case in ordered:
            payload = json.dumps({"q": case["query"]}, ensure_ascii=False).encode()
            request = Request(endpoint.rstrip("/") + "/v1/search", data=payload,
                              headers={"Content-Type": "application/json"})
            start = time.monotonic()
            with urlopen(request, timeout=60) as response:
                result = json.load(response)
            latency = round(1000 * (time.monotonic() - start), 2)
            if not result["index_version"].startswith(fingerprint):
                raise ValueError("The feed and the running catalog snapshot differ")
            ids = [card["offer_id"] for card in result["top4"] if card["role"] == "direct"]
            bits = [int(relevant(case["intent"], *products[id_])) for id_ in ids]
            yield {**case, "repeat": repetition, "top1": int(bool(bits and bits[0])),
                   "hit4": int(any(bits)), "ndcg4": dcg(bits) / ideals[case["intent"]] if ideals[case["intent"]] else 0,
                   "empty": int(not ids), "ids": "|".join(ids), "ms": latency}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("feed", type=Path)
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--csv", type=Path, required=True)
    args = parser.parse_args()
    if args.repeat < 1:
        parser.error("--repeat must be positive")
    with Path(__file__).with_name("catalog_tasks.tsv").open() as f:
        cases = list(csv.DictReader(f, delimiter="\t"))
    with args.feed.open("rb") as f:
        fingerprint = hashlib.file_digest(f, "sha256").hexdigest()[:16]
    products, ideals = read_catalog(args.feed)
    rows = list(measure(cases, args.url, fingerprint, products, ideals, args.repeat))
    with args.csv.open("w", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=rows[0])
        writer.writeheader()
        writer.writerows(rows)
    splits = defaultdict(list)
    for row in rows:
        splits[row["split"]].append(row)
    for split, samples in splits.items():
        first = [r for r in samples if r["repeat"] == 0]
        latency = sorted(r["ms"] for r in samples)
        print(split, "Hit@1", sum(r["top1"] for r in first), "Hit@4", sum(r["hit4"] for r in first),
              "nDCG@4", round(statistics.mean(r["ndcg4"] for r in first), 3),
              "empty", sum(r["empty"] for r in first), "out of", len(first),
              "p50/p95 ms", statistics.median(latency), latency[math.ceil(.95 * len(latency)) - 1])


if __name__ == "__main__":
    main()
