"""Check the local storefront and exercise its API with concurrent requests."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import math
from statistics import median
from time import perf_counter
from urllib.error import HTTPError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener


CASES = {
    "astra linux": ("offer", "487935"),
    "Microsoft Office для дома и бизнеса": ("offer", "473582"),
    "kaspersky small office security": ("offer", "demo-ks"),
    "крипто про csp 5.0": ("offer", "demo-csp-5.0"),
    "anydesk": ("offer", "demo-any"),
    "кибер бэкап": ("collection", "cyberprotect-1033"),
    "сканер-вс": ("offer", "demo-scan"),
    "мтс": ("vendor", "webinar"),
    "max patrol": ("offer", "demo-max"),
    "365": ("offer", "demo-365"),
}


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, target):
        return None


def request(base, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"} if data is not None else {}
    with build_opener(ProxyHandler({}), NoRedirects()).open(Request(base + path, data, headers), timeout=10) as response:
        content = response.read()
    return json.loads(content) if path.startswith(("/v1/", "/actuator/")) else content


def verify_query(base, query, session=None):
    response = request(base, "/v1/search", {"q": query, "limit": 50, "session_id": session})
    kind, identifier = CASES[query]
    direct = [card for card in response["top4"] if card["role"] == "direct"]
    assert direct, query
    if kind == "offer":
        assert direct[0]["offer_id"] == identifier, (query, direct[0]["offer_id"])
    else:
        target = response["primary_target"]
        assert (target["kind"], target["id"]) == (kind, identifier), query
    assert len({card["family_key"] for card in response["top4"]}) == len(response["top4"]), query


def catalog(base, page=1, sort="relevance", **filters):
    return request(base, "/v1/catalog?" + urlencode({"page": page, "sort": sort, **filters}))


def verify_catalog(base):
    baseline = catalog(base)
    total = baseline["total"]
    last = math.ceil(total / baseline["page_size"])
    assert total > baseline["page_size"], "Use the demo catalog for the pagination scenarios"
    ordered = {}
    for sort in ("price_asc", "price_desc"):
        offers = []
        for page in range(1, last + 1):
            result = catalog(base, page, sort)
            assert result["page"] == page and result["total"] == total, result
            offers += result["offers"]
        ids = {offer["offer_id"] for offer in offers}
        assert len(offers) == len(ids) == total, "Duplicate or missing offers across pages"
        prices = [offer["price_minor"] for offer in offers if offer["price_minor"] is not None]
        assert prices == sorted(prices, reverse=sort == "price_desc"), sort
        assert all(offer["price_minor"] is None for offer in offers[len(prices):]), sort
        ordered[sort] = ids
    assert ordered["price_asc"] == ordered["price_desc"]
    for offer_id in ordered["price_asc"]:
        entity = request(base, "/v1/entities/offer/" + quote(offer_id, safe=""))
        assert entity["offers"][0]["offer_id"] == offer_id, offer_id
    assert catalog(base, 9999)["page"] == last
    filtered = catalog(base, 2, category_id="p")
    assert filtered["page"] == 1 and filtered["offers"], filtered
    assert all("Pantum" in item["name"] for item in filtered["offers"]), filtered
    assert catalog(base, max_price_minor=0)["total"] == 0
    for query in ("page=0", "page_size=49", "sort=invalid"):
        try:
            request(base, "/v1/catalog?" + query)
        except HTTPError as error:
            assert error.code == 400, error
        else:
            raise AssertionError(query)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("base", help="An HTTP server on localhost with the seeded demo catalog")
    parser.add_argument("--requests", type=int, default=240)
    parser.add_argument("--workers", type=int, default=12)
    args = parser.parse_args()
    base = args.base.rstrip("/")
    origin = urlsplit(base)
    if origin.scheme != "http" or origin.hostname not in {"127.0.0.1", "localhost", "::1"} or origin.path or origin.query or origin.fragment or origin.username:
        parser.error("Use a loopback HTTP origin")
    if not 1 <= args.workers <= 32 or not 1 <= args.requests <= 5000:
        parser.error("Use 1–32 workers and 1–5000 requests")

    assert request(base, "/actuator/health/readiness")["status"] == "UP"
    for path in ("/", "/catalog/", "/search/?query=EP2-06605", "/basket/",
                 "/microsoft/ep2-06605-473582/", "/cyberprotect/"):
        assert b'<div id="root">' in request(base, path), path
    assert request(base, "/product-placeholder.png").startswith(b"\x89PNG\r\n\x1a\n")
    for query in CASES:
        verify_query(base, query)
    verify_catalog(base)
    backup = request(base, "/v1/search", {"q": "лицензия", "limit": 50})
    assert len(backup["top4"]) == 4 and backup["more_results"]
    assert request(base, "/v1/entities/offer/363882")["related_offers"][0]["offer_id"] == "526683"
    assert request(base, "/v1/search", {"q": "EP2-06605", "strategy": "llm"})["assistant_message"]
    assert request(base, "/v1/search", {"q": "неизвестная модель XYZ9999"})["direct_count"] == 0

    queries = list(CASES)

    def check(index):
        start = perf_counter()
        scenario = index % 4
        if scenario == 0:
            result = catalog(base, 2, "price_asc")
            assert result["page"] == 2 and result["offers"]
        elif scenario == 1:
            verify_query(base, queries[(index // 4) % len(queries)], f"storefront-load-{index}")
        elif scenario == 2:
            hints = request(base, "/v1/suggest?q=EP2-0")
            assert any(hint["target_id"] == "473582" for hint in hints)
        else:
            product = request(base, "/v1/entities/offer/473582")
            assert product["offers"][0]["sku"] == "EP2-06605"
        return (perf_counter() - start) * 1000

    start = perf_counter()
    durations = []
    failures = []
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(check, index) for index in range(args.requests)]
        for index, future in enumerate(futures):
            try:
                durations.append(future.result())
            except Exception as error:
                failures.append(f"{index}: {error!r}")
    if failures:
        raise SystemExit(f"{len(failures)} failed requests: " + "; ".join(failures[:5]))
    p95 = sorted(durations)[math.ceil(len(durations) * 0.95) - 1]
    print(f"10 task queries and catalog regressions passed; {len(durations)} requests, "
          f"{args.workers} workers, 0 errors, p50 {median(durations):.1f} ms, "
          f"p95 {p95:.1f} ms, max {max(durations):.1f} ms, total {perf_counter() - start:.2f} s")


if __name__ == "__main__":
    main()
