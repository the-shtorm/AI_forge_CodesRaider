"""Same-corpus retrieval benchmark. Run against two Spring processes using the bundled demo."""
import argparse
import csv
import hashlib
import json
import math
from pathlib import Path
import re
import sqlite3
import statistics
import time
from urllib.request import Request, urlopen
from xml.etree import ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "backend/src/main/resources/search"
NS = {"x": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
FAMILIES = {"121231": "487935", "391338": "473582", "3761": "demo-ks,demo-ks-ren",
            "22268": "demo-csp-5.0", "306539": "demo-any", "46421": "demo-scan",
            "437421": "demo-max", "165034": "demo-365", "webinar": "535950,541589,541591",
            "cyberprotect-1033": "418697,418698,536456,539763"}


def cases():
    # Independent judgments from the project research and visible SKU facts in the fixture.
    targets = dict(zip(["astra linux", "Microsoft Office для дома и бизнеса", "kaspersky small office security",
                        "крипто про csp 5.0", "anydesk", "кибер бэкап", "сканер-вс", "мтс", "max patrol", "365"],
                       ["121231", "391338", "3761", "22268", "306539", "cyberprotect-1033", "46421",
                        "webinar", "437421", "165034"]))
    with ZipFile(ROOT / "supplementary/SearchQueries.xlsx") as book:
        strings = ["".join(s.itertext()) for s in ET.fromstring(book.read("xl/sharedStrings.xml"))]
        sheet = ET.fromstring(book.read("xl/worksheets/sheet1.xml"))
        for row in sheet.findall(".//x:row", NS)[1:]:
            values = [c.findtext("x:v", namespaces=NS) for c in row]
            q, url = [strings[int(v)] if c.get("t") == "s" else v for v, c in zip(values, row)]
            dest = targets[q]
            yield "excel", q, FAMILIES[dest].split(","), url
    with Path(__file__).with_name("judgments.tsv").open() as f:
        for suite, q, ids in csv.reader((s for s in f if not s.startswith("#")), delimiter="\t"):
            yield suite, q, [] if ids == "-" else ids.split(","), ""


class Baseline:
    def __init__(self, path):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("CREATE VIRTUAL TABLE docs USING fts5(id UNINDEXED,title,description)")
        self.groups = {}
        self.urls = {}
        self.available = set()
        for offer in ET.parse(path).iterfind("./shop/offers/offer"):
            id_ = offer.get("id")
            model = offer.findtext("param[@name='Базовая модель']") or offer.findtext("vendorCode") or id_
            self.groups[id_] = offer.get("group_id") or (offer.findtext("vendor"), model)
            self.urls[id_] = offer.findtext("url")
            if offer.get("available") == "true" and float(offer.findtext("price") or 0) > 0:
                self.available.add(id_)
            self.db.execute("INSERT INTO docs VALUES (?,?,?)",
                            (id_, offer.findtext("name"), offer.findtext("description") or ""))

    def search(self, q):
        words = re.findall(r"\w+", q.casefold())
        query = " OR ".join(f'"{w}"' for w in words)
        rows = self.db.execute("SELECT id FROM docs WHERE docs MATCH ? ORDER BY rank, id", (query,))
        seen = set()
        cards = []
        for (id_,) in rows:
            family = self.groups[id_]
            if family not in seen and id_ in self.available:
                seen.add(family)
                cards.append(id_)
                if len(cards) == 4:
                    break
        return cards


def search(url, q):
    data = json.dumps({"q": q}, ensure_ascii=False).encode()
    req = Request(url.rstrip("/") + "/v1/search", data, {"Content-Type": "application/json"})
    with urlopen(req, timeout=60) as response:
        return json.load(response)


def dcg(bits):
    return sum(bit / math.log2(i + 2) for i, bit in enumerate(bits))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--rules", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--repeat", type=int, default=3)
    ap.add_argument("--csv", required=True, type=Path)
    args = ap.parse_args()
    fixture = DATA / "demo.xml"
    version = "demo-" + hashlib.sha256(fixture.read_bytes()).hexdigest()[:16] + "-v1"
    baseline = Baseline(fixture)
    rows = []
    for suite, q, relevant, url in cases():
        for mode, host in [("BM25", None), ("rules", args.rules), ("model", args.model)]:
            times = []
            direct = []
            compiler = ""
            target = ""
            observed = set()
            compilers = []
            first = None
            for _ in range(args.repeat if host else 1):
                start = time.perf_counter_ns()
                if host:
                    response = search(host, q)
                    assert response["index_version"] == version, "Different catalog snapshot"
                    direct = [c["offer_id"] for c in response["top4"] if c["role"] == "direct"]
                    target = (response["primary_target"] or {}).get("url", "")
                    compiler = response["program"]["compiler"]
                else:
                    direct = baseline.search(q)
                    target = baseline.urls[direct[0]] if direct else ""
                if first is None:
                    first = (direct, target)
                observed.add(tuple(direct))
                compilers.append(compiler)
                times.append((time.perf_counter_ns() - start) / 1e6)
            direct, target = first
            ideal = min(4, len({baseline.groups[x] for x in relevant}))
            bits = [int(x in relevant) for x in direct]
            family_hit = ""
            if suite == "excel" and q not in ("мтс", "кибер бэкап"):
                family_hit = int(any(baseline.groups[x] == baseline.groups[relevant[0]] for x in direct))
            rows.append(dict(suite=suite, query=q, mode=mode, expected="|".join(relevant), direct="|".join(direct),
                             family_hit=family_hit, top1=int(bool(bits and bits[0])) if relevant else "", compiler="|".join(compilers), variants=len(observed), hit=int(any(bits)) if relevant else "", correct_empty=int(not direct) if not relevant else "",
                             ndcg=round(dcg(bits)/dcg([1]*ideal), 6) if ideal else "", url_hit=int(target == url) if url else "",
                             latency_ms="|".join(f"{t:.2f}" for t in times)))
    with args.csv.open("w", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=rows[0], lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    for mode in ["BM25", "rules", "model"]:
        subset = [r for r in rows if r["mode"] == mode]
        for k in ["hit", "top1", "ndcg", "correct_empty", "family_hit", "url_hit"]:
            vals = [r[k] for r in subset if r[k] != ""]
            print(mode, k, f"{sum(vals):.3f}/{len(vals)} = {statistics.mean(vals):.3f}")
        task = [r["ndcg"] for r in subset if r["suite"] == "task"]
        print(mode, "task nDCG@4", round(statistics.mean(task), 3))
        times = sorted(float(t) for r in subset for t in r["latency_ms"].split("|"))
        print(mode, "p50/p95 ms", statistics.median(times), times[math.ceil(.95*len(times))-1])


if __name__ == "__main__":
    main()
