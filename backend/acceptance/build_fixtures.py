"""Build four small, isolated acceptance snapshots from the Softline YML export."""
import argparse
from copy import deepcopy
from pathlib import Path
import xml.etree.ElementTree as ET


BACKUP = {"418697", "418698", "536456", "539763", "421468", "421471"}
PANTUM = {"363882", "521380", "521379", "526683", "513625"}
CONTROLS = {"477550", "53955"}
OFFER_IDS = BACKUP | PANTUM | CONTROLS
SCENARIOS = ("baseline", "missing-code", "priced-reserve", "unpriced-reserve")


def extract(source):
    categories = {}
    selected = {}
    for _, node in ET.iterparse(source):
        if node.tag == "category":
            categories[node.attrib["id"]] = node
        elif node.tag == "offer":
            oid = node.attrib["id"]
            if oid in OFFER_IDS:
                if oid in selected:
                    raise ValueError(f"Duplicate offer id: {oid}")
                selected[oid] = deepcopy(node)
            node.clear()
        elif node.tag == "currencies":
            currencies = node
        elif node.tag == "shop":
            metadata = [node.find(tag) for tag in ("name", "company", "url")]

    if missing := OFFER_IDS - selected.keys():
        raise ValueError(f"Missing reference offers: {sorted(missing)}")
    used = set()
    for offer in selected.values():
        key = offer.findtext("categoryId")
        while key and key not in used:
            used.add(key)
            key = categories[key].get("parentId")

    root = ET.Element("yml_catalog")
    shop = ET.SubElement(root, "shop")
    shop.extend([*metadata, currencies])
    ET.SubElement(shop, "categories").extend(c for key, c in categories.items() if key in used)
    ET.SubElement(shop, "offers").extend(selected.values())
    return root


def snapshot(base, scenario):
    root = deepcopy(base)
    # Distinct fixture dates let importers that version snapshots by feed date rebuild each index.
    root.set("date", f"2026-11-25 15:0{SCENARIOS.index(scenario)}")
    offers = {o.attrib["id"]: o for o in root.iterfind("./shop/offers/offer")}

    def reserve(oid, without_price):
        offers[oid].set("available", "false")
        if without_price:
            offers[oid].find("price").text = "0"

    reserve("421468", False)
    reserve("421471", True)
    if scenario == "missing-code":
        offers["526683"].remove(offers["526683"].find("vendorCode"))
        reserve("536456", False)
        reserve("539763", True)
    if scenario in ("priced-reserve", "unpriced-reserve"):
        for oid in BACKUP:
            reserve(oid, scenario == "unpriced-reserve" or oid != "421468")
    return ET.ElementTree(root)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path, help="Directory for the four generated XML files")
    args = parser.parse_args()
    paths = {s: args.output / f"{s}.xml" for s in SCENARIOS}
    if args.source.resolve() in {path.resolve() for path in paths.values()}:
        parser.error("Choose an output directory separate from the source file.")
    base = extract(args.source)
    args.output.mkdir(parents=True, exist_ok=True)
    for scenario, target in paths.items():
        snapshot(base, scenario).write(target, encoding="utf-8", xml_declaration=True)
        print(target)


if __name__ == "__main__":
    main()
