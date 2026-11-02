// Unit tests for the relay Worker: the real src/index.js against an in-memory D1 stand-in (node:sqlite).
// Run: cd worker && npm test   (Node >= 22.13)
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import { createHmac } from "node:crypto";
import worker from "../src/index.js";

const MASTER = "test-master-secret";

function fakeD1() {
  const db = new DatabaseSync(":memory:");
  db.exec(readFileSync(new URL("../migrations/0001_init.sql", import.meta.url), "utf8"));
  const stmt = (sql, args = []) => ({
    bind: (...a) => stmt(sql, a),
    first: async () => db.prepare(sql).get(...args) ?? null,
    all: async () => ({ results: db.prepare(sql).all(...args) }),
    run: async () => db.prepare(sql).run(...args),
  });
  return { raw: db, prepare: (sql) => stmt(sql), batch: async (list) => Promise.all(list.map((s) => s.run())) };
}

const env = () => ({ DB: fakeD1(), PAIR_MASTER: MASTER });
const ctx = { waitUntil: () => {} };
const token = (dev) => dev + "." + createHmac("sha256", MASTER).update(dev).digest("hex");
const call = (e, method, path, { auth, body, ip } = {}) =>
  worker.fetch(new Request("https://relay.test" + path, {
    method,
    body: body === undefined ? undefined : typeof body === "string" ? body : JSON.stringify(body),
    headers: { ...(auth ? { authorization: "Bearer " + auth } : {}), ...(ip ? { "cf-connecting-ip": ip } : {}) },
  }), e, ctx);

const DEV_A = "deviceAAAAAAAAAAAA", DEV_B = "deviceBBBBBBBBBBBB";

test("health needs no auth", async () => {
  const r = await call(env(), "GET", "/v1/health");
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { ok: true });
});

test("unknown path is 404, bad or missing token is 401", async () => {
  const e = env();
  assert.equal((await call(e, "GET", "/v1/nope")).status, 404);
  assert.equal((await call(e, "GET", "/v1/up")).status, 401);
  const forged = DEV_A + "." + "0".repeat(64);
  assert.equal((await call(e, "GET", "/v1/up", { auth: forged })).status, 401);
});

test("post then read back in order, cursor advances, other box and other device see nothing", async () => {
  const e = env(), a = token(DEV_A);
  const ids = [];
  for (const blob of ["AAAA", "BBBB", "CCCC"]) {
    const r = await call(e, "POST", "/v1/up", { auth: a, body: { blob } });
    assert.equal(r.status, 200);
    ids.push((await r.json()).id);
  }
  const got = await (await call(e, "GET", "/v1/up?after=0", { auth: a })).json();
  assert.deepEqual(got.items.map((i) => i.blob), ["AAAA", "BBBB", "CCCC"]);
  assert.equal(got.cursor, ids[2]);
  const after = await (await call(e, "GET", `/v1/up?after=${ids[0]}&limit=1`, { auth: a })).json();
  assert.deepEqual(after.items.map((i) => i.blob), ["BBBB"]);
  assert.equal((await (await call(e, "GET", "/v1/down?after=0", { auth: a })).json()).items.length, 0);
  assert.equal((await (await call(e, "GET", "/v1/up?after=0", { auth: token(DEV_B) })).json()).items.length, 0);
});

test("empty read with wait=0 returns the same cursor", async () => {
  const r = await (await call(env(), "GET", "/v1/down?after=42&wait=0", { auth: token(DEV_A) })).json();
  assert.deepEqual(r, { items: [], cursor: 42 });
});

test("rejects non-base64 blobs, invalid JSON and bodies over 256 KB", async () => {
  const e = env(), a = token(DEV_A);
  assert.equal((await call(e, "POST", "/v1/up", { auth: a, body: { blob: "<script>" } })).status, 400);
  assert.equal((await call(e, "POST", "/v1/up", { auth: a, body: "not json" })).status, 400);
  assert.equal((await call(e, "POST", "/v1/up", { auth: a, body: { blob: "A".repeat(256 * 1024) } })).status, 413);
});

test("items older than 7 days are not returned", async () => {
  const e = env(), a = token(DEV_A);
  await call(e, "POST", "/v1/down", { auth: a, body: { blob: "OLD0" } });
  e.DB.raw.prepare("UPDATE msg SET ts = ts - ?").run(8 * 24 * 3600 * 1000);
  assert.equal((await (await call(e, "GET", "/v1/down?after=0", { auth: a })).json()).items.length, 0);
});

test("per-device rate limit returns 429 after 120 requests a minute", async () => {
  const e = env(), a = token(DEV_A);
  let last;
  for (let i = 0; i < 121; i++) last = await call(e, "GET", "/v1/up?after=0", { auth: a });
  assert.equal(last.status, 429);
});

test("failed-auth limit per IP returns 429 after 30 bad attempts", async () => {
  const e = env();
  let last;
  for (let i = 0; i < 31; i++) last = await call(e, "GET", "/v1/up", { auth: "x".repeat(16) + "." + "0".repeat(64), ip: "203.0.113.7" });
  assert.equal(last.status, 429);
});

test("unsupported method is 405", async () => {
  assert.equal((await call(env(), "PUT", "/v1/up", { auth: token(DEV_A), body: "{}" })).status, 405);
});
