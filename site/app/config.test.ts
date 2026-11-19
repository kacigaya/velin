import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { site } from "./config.ts";

test("the site config has no TODO placeholders", () => {
  const left = JSON.stringify(site).match(/TODO[^"]*/g);
  assert.equal(left, null, `Fill in app/config.ts: ${left?.join("; ")}`);
});

test("the repo has the owner/name form", () => {
  assert.match(site.repo, /^[\w.-]+\/[\w.-]+$/);
});

test("globals.css has the app color scheme", () => {
  const css = readFileSync(new URL("./globals.css", import.meta.url), "utf8");
  assert.doesNotMatch(css, /TODO/, "Paste the scripts/scheme.mjs output into app/globals.css");
});
