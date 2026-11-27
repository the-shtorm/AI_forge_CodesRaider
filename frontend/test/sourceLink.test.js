import test from "node:test";
import assert from "node:assert/strict";
import { safeLink, sourceLink } from "../src/api.js";

test("manufacturer and catalog evidence can open independently of internal routing", () => {
  for (const url of ["https://www.pantum.ru/products/laser-devices/monohromnyj-printer-p2500w/",
    "https://store.softline.ru/pantum/pc-211p-526683/"]) {
    assert.equal(sourceLink(url), url);
    assert.equal(safeLink(url), undefined);
  }
  assert.equal(sourceLink("/?kind=offer&id=526683"), "/?kind=offer&id=526683");
});

test("evidence links require an internal path or an explicit credential-free HTTPS URL", () => {
  for (const value of [null, "", "source", "//example.org/path", "/\\example.org", "https://", "https:///no-host",
    "http://example.org", "https://user:pass@example.org", "javascript:void(0)", "data:text/plain,info", "https://bad url"]) {
    assert.equal(sourceLink(value), undefined);
  }
});
