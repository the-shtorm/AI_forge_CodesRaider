"""Refresh the authorized copy from public, anonymous Softline responses."""

import argparse
import concurrent.futures
import hashlib
import html as entities
import json
import re
import subprocess
from pathlib import Path
from urllib.parse import unquote, urljoin, urlsplit

from lxml import html


ORIGIN = "https://store.softline.ru"
IMAGE_HOSTS = {"store.softline.ru", "img.softline.com", "storesoftlineru.webim.ru"}
ROOT = Path(__file__).resolve().parent
ASSETS = ROOT / "public" / "softline"
CSS_URL = re.compile(r"url\(\s*(?:(['\"])(.*?)\1|([^)'\"]+))\s*\)", re.IGNORECASE)
TRACKER = re.compile(r"metri[ck]|counter|rambler|rutarget|captcha|mindbox|top100", re.IGNORECASE)
SENSITIVE = re.compile(r"csrf|sessid|session|token|password|passwd|phone|email", re.IGNORECASE)

# Only the public pages selected for the contest are requested.
PAGES = {
    "home": "/",
    "catalog": "/catalog/",
    "category": "/ofisnie-programmi/",
    "vendors": "/vendors/",
    "specials": "/specials/",
    "antivirus": "/antivirusi-bezopasnost/antivirusi-organizatsii/",
    "security": "/antivirusi-bezopasnost/bezopasnost-zaschita-informatsii/",
    "computers": "/computers/",
    "inferit": "/inferit-tech/",
    "blog": "/blog/",
    "ai": "/ii/",
    "quote": "/writeorder/",
    "tenders": "/tenders/",
    "business": "/b2b/",
    "delivery": "/delivery_info/",
    "contacts": "/contacts/",
    "product": "/microsoft/ep2-06605-473582/",
    "printer": "/pantum/p2500w-363882/",
    "collection": "/cyberprotect/?filter%5Bcategory%5D%5B%5D=1033",
    "compare": "/compare/",
    "favorites": "/favorites/",
    "basket": "/basket/",
    "login": "/user/login/",
    "search": "/search/?query=Pantum",
}


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()[:20]


def normalize_markup(markup):
    return b"\n".join(
        re.sub(rb"^[ \t]* \t[ \t]*", lambda leading: leading.group().expandtabs(8), line.rstrip(b" \t"))
        for line in markup.splitlines()
    ) + b"\n"


def fill_popular_products(doc):
    skeleton = doc.find('.//*[@data-type="recommendations"][@data-id="22"]')
    if skeleton is None:
        return
    items = html.Element("ul", {"class": "b-suggestions__list owl-carousel js-carousel-cart-container js-carousel"})
    skeleton.getparent().replace(skeleton, items)
    products = json.loads((ROOT / "popular-products.json").read_text(encoding="utf-8"))
    for fields in products:
        href, title, vendor, image, price, previous, rebate, rating, reviews = map(entities.escape, fields)
        offer = f'<p class="suggestion__old-price">{previous}<span class="suggestion__rebate">{rebate}</span></p>' if previous else ""
        feedback = f"""
            <div class="suggestion__rating">
                <span class="suggestion__rate">{rating}</span>
                <svg class="icon icon-size-16 sl-color-icon-yellow"><use xlink:href="#star"></use></svg>
                <a class="suggestion__reviews" href="{href}#reviews">{reviews}</a>
            </div>""" if rating else ""
        items.append(html.fragment_fromstring(f"""
            <li class="b-suggestions__item js-carusel-item">
                <article class="suggestion">
                    <label class="suggestion__like">
                        <input type="checkbox" name="like" class="suggestion__input" aria-label="В избранное">
                        <svg class="icon icon-size-20 suggestion__like-icon"><use xlink:href="#heart-like"></use></svg>
                    </label>
                    <a class="suggestion__image-box" href="{href}">
                        <img src="https://img.softline.com/mcf/{image}/scale-dec-q_50-220x220.avif" class="suggestion__image" alt="{title}">
                    </a>
                    <span class="suggestion__vendor" title="{vendor}">{vendor}</span>
                    <p class="suggestion__title"><a class="suggestion__description" href="{href}" title="{title}">{title}</a></p>
                    <div class="suggestion__aligner">{feedback}</div>
                    <div class="suggestion__purchase">
                        <div class="suggestion__purchase-row">
                            <div class="suggestion__purchase-info">{offer}<p class="suggestion__current-price">{price}</p></div>
                            <a class="button button_primary button_square b-to-cart-button" href="{href}" aria-label="В корзину">
                                <div class="button__prefix"><svg class="icon icon-size-20 icon-color-current to-cart"><use xlink:href="#to-cart"></use></svg></div>
                            </a>
                        </div>
                    </div>
                </article>
            </li>"""))


class Capture:
    def __init__(self, cache):
        self.cache = cache
        self.assets = set()
        self.styles = set()
        self.routes = set()

    def get(self, url, name=None, timeout=22):
        path = self.cache / (name or (digest(url) + ".resource"))
        if path.is_file() and path.stat().st_size:
            return path.read_bytes()
        partial = path.with_suffix(".partial")
        result = subprocess.run(
            [
                "curl", "--silent", "--show-error", "--fail", "--location",
                "--max-redirs", "4", "--proto", "=https", "--proto-redir", "=https",
                "--connect-timeout", "8", "--max-time", str(timeout),
                "--output", str(partial), "--write-out", "%{url_effective}", url,
            ],
            capture_output=True, text=True, check=False,
        )
        if (
            result.returncode or urlsplit(result.stdout).hostname not in IMAGE_HOSTS
            or not partial.is_file() or not partial.stat().st_size
        ):
            partial.unlink(missing_ok=True)
            return None
        partial.replace(path)
        return path.read_bytes()

    def page(self, name, route):
        cache_name = name + ".source"
        path = self.cache / cache_name
        if path.exists() and not re.search(rb"</html\s*>", path.read_bytes()[-6000:], re.IGNORECASE):
            path.unlink()
        content = self.get(ORIGIN + route, cache_name, timeout=55)
        if not content or not re.search(rb"</html\s*>", content[-6000:], re.IGNORECASE):
            print(f"Unavailable page: {route}", flush=True)
            return None
        doc = html.document_fromstring(content.decode("utf-8-sig"))
        if doc.find(".//main") is None and doc.find(".//header") is None:
            print(f"Unexpected response: {route}", flush=True)
            return None
        if route == "/":
            fill_popular_products(doc)
        return doc

    def asset(self, value, base):
        if value.startswith(("data:", "#")) or not value:
            return value
        url = urljoin(base, entities.unescape(value))
        if urlsplit(url).hostname not in IMAGE_HOSTS or TRACKER.search(url):
            return ""
        self.assets.add(url)
        file = self.asset_name(url)
        return f"/softline/{file}" if (ASSETS / file).exists() else url

    def css(self, css, base):
        def replace(match):
            value = match.group(2) if match.group(1) else match.group(3).strip()
            if value.startswith(("data:", "#")):
                return match.group()
            return f'url("{self.asset(value, base)}")'

        return CSS_URL.sub(replace, css)

    @staticmethod
    def asset_name(url):
        path = urlsplit(url).path
        name = re.sub(r"[^a-zA-Z0-9_.-]", "_", unquote(path.split("/")[-1]))[-95:]
        return f"{digest(url)}-{name or 'asset'}"

    def write_asset(self, url):
        target = ASSETS / self.asset_name(url)
        if target.is_file():
            return
        content = self.get(url, timeout=13)
        if content and not content.lstrip()[:30].lower().startswith((b"<!doctype html", b"<html")):
            target.write_bytes(content)

    def document(self, doc, route, symbols):
        base = ORIGIN + route
        doc.find("body").set("data-storefront-routes", " ".join(sorted(self.routes)))
        for element in list(doc.iter()):
            tag = element.tag
            if not isinstance(tag, str):
                element.getparent().remove(element)
                continue
            if tag in {"script", "noscript", "iframe", "object", "embed", "base"} or (
                tag == "link" and (
                    element.get("rel") in {"preconnect", "manifest", "dns-prefetch", "profile"}
                    or urlsplit(urljoin(base, element.get("href", ""))).hostname not in IMAGE_HOSTS
                )
            ):
                element.drop_tree()
                continue
            if tag == "meta" and (SENSITIVE.search(element.get("name", "")) or element.get("http-equiv")):
                element.drop_tree()
                continue
            for key in list(element.attrib):
                if key.startswith("on") or key in {"integrity", "nonce", "srcdoc"} or SENSITIVE.search(key) and key.startswith("data-"):
                    element.attrib.pop(key)
            if tag == "form":
                action = urlsplit(urljoin(base, element.get("action") or route))
                if action.hostname == "store.softline.ru" and action.path == "/search/" and "/search/" in self.routes:
                    element.set("method", "get")
                    element.set("action", "/search/")
                else:
                    element.attrib.pop("action", None)
                    element.set("data-storefront-local", "")
                    for field in element.xpath(".//input | .//textarea"):
                        if SENSITIVE.search(field.get("name", "")) or field.get("type") in {"email", "password", "tel"}:
                            field.attrib.pop("value", None)
                            field.set("readonly", "")
            if tag == "input" and element.get("type") == "hidden" and SENSITIVE.search(element.get("name", "")):
                element.drop_tree()
                continue
            if tag == "link" and element.get("rel") == "stylesheet":
                url = urljoin(base, element.get("href"))
                self.styles.add(url)
                element.set("href", "/softline/" + self.asset_name(url))
            for attr in ("src", "data-src", "poster", "data-background"):
                if element.get(attr):
                    value = self.asset(element.get(attr), base)
                    if value:
                        element.set(attr, value)
                    else:
                        element.attrib.pop(attr)
            for attr in ("srcset", "data-srcset"):
                if element.get(attr):
                    candidates = [candidate.strip().split(maxsplit=1) for candidate in element.get(attr).split(",")]
                    rewritten = []
                    for candidate in filter(None, candidates):
                        value = self.asset(candidate[0], base)
                        if value:
                            rewritten.append(" ".join([value, *candidate[1:]]))
                    element.set(attr, ", ".join(rewritten))
            for attr in ("href", "xlink:href"):
                value = element.get(attr)
                if not value or value.startswith(("#", "mailto:", "tel:")) or tag == "link" and element.get("rel") == "stylesheet":
                    continue
                url = urljoin(base, value)
                parsed = urlsplit(url)
                if parsed.scheme not in {"http", "https"}:
                    element.set(attr, "#")
                elif tag == "link":
                    element.set(attr, self.asset(value, base) if element.get("rel") != "canonical" else url)
                elif parsed.hostname == "store.softline.ru" and parsed.path in self.routes:
                    element.set(attr, parsed._replace(scheme="", netloc="").geturl())
                else:
                    element.set(attr, url)
            if element.get("style"):
                element.set("style", self.css(element.get("style"), base))
            if tag == "style" and element.text:
                element.text = self.css(element.text, base)

        if symbols:
            placeholder = doc.find(".//*[@id='svgDefinitions']")
            if placeholder is not None:
                placeholder.append(html.fragment_fromstring(symbols, create_parent="div"))
        head = doc.find("head")
        for charset in head.findall("meta[@charset]"):
            charset.drop_tree()
        head.insert(0, html.Element("meta", {"charset": "utf-8"}))
        head.insert(1, html.Element("meta", {
            "http-equiv": "Content-Security-Policy",
            "content": (
                "default-src 'none'; base-uri 'none'; form-action 'self'; "
                "script-src 'self'; connect-src 'self' ws://localhost:* ws://127.0.0.1:*; "
                "style-src 'self' 'unsafe-inline'; font-src 'self'; "
                "img-src 'self' data: https://img.softline.com https://store.softline.ru https://storesoftlineru.webim.ru"
            ),
        }))
        head.insert(2, html.Element("meta", {"name": "referrer", "content": "no-referrer"}))
        head.append(html.Element("link", {"rel": "stylesheet", "href": "/softline-ui.css"}))
        head.append(html.Element("script", {"src": "/softline-ui.js", "type": "module"}))
        return html.tostring(doc, encoding="utf-8", doctype="<!doctype html>", method="html")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("cache", type=Path)
    parser.add_argument("--page", action="append", choices=PAGES)
    parser.add_argument("--assets", action="store_true", help="download optional images for offline preview")
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    ASSETS.mkdir(parents=True, exist_ok=True)
    capture = Capture(args.cache)
    selected = args.page or PAGES
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        pending = {name: executor.submit(capture.page, name, PAGES[name]) for name in selected}
        sprite = executor.submit(capture.get, ORIGIN + "/svg-definitions/", "svg-sprite.source")
        pages = {name: future.result() for name, future in pending.items()}
        symbols = sprite.result()
        if symbols and symbols.lstrip().startswith(b"<svg"):
            symbols = symbols.decode("utf-8")
            if not re.search(r"<script\b|\son\w+\s*=|<foreignObject\b", symbols, re.IGNORECASE):
                (ASSETS / "symbols.html").write_bytes(normalize_markup(symbols.encode("utf-8")))
        sprite_path = ASSETS / "symbols.html"
        symbols = sprite_path.read_text(encoding="utf-8") if sprite_path.exists() else None
        capture.routes = {
            urlsplit(PAGES[name]).path for name, doc in pages.items() if doc is not None
        }
        for name, doc in pages.items():
            if doc is not None:
                capture.document(html.fromstring(html.tostring(doc)), PAGES[name], symbols)

        def fetch_style(url):
            # The landing page and stylesheet may already have been checked during capture.
            name = "css.source" if urlsplit(url).path == "/styles/2025/styles.css" else None
            css = capture.get(url, name)
            return (url, css.decode("utf-8-sig") if css else None)

        styles = list(executor.map(fetch_style, sorted(capture.styles)))
        for url, stylesheet in styles:
            if stylesheet:
                capture.css(stylesheet, url)
        mandatory = {
            url for url in capture.assets
            if urlsplit(url).path.endswith((".woff2", "-q_50-220x220.avif"))
            or "/images/svg/softline-" in url
        }
        list(executor.map(capture.write_asset, sorted(capture.assets if args.assets else mandatory)))
        for url, stylesheet in styles:
            if stylesheet:
                (ASSETS / capture.asset_name(url)).write_text(capture.css(stylesheet, url), encoding="utf-8")
            else:
                print(f"Unavailable stylesheet: {url}", flush=True)
        for name, doc in pages.items():
            if doc is None:
                continue
            route = PAGES[name]
            path = urlsplit(route).path
            target = ROOT / "index.html" if path == "/" else ROOT / "public" / path.strip("/") / "index.html"
            target.parent.mkdir(parents=True, exist_ok=True)
            snapshot = capture.document(doc, route, symbols)
            target.write_bytes(normalize_markup(snapshot))
            print(f"Captured: {route}", flush=True)


if __name__ == "__main__":
    main()
