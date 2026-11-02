# End-to-end: real Worker under `wrangler dev` (local D1) + real pc/phone.py + this script playing the phone.
#   cd worker && echo PAIR_MASTER=any-local-secret > .dev.vars
#   npx wrangler@4 d1 migrations apply angaros --local && npx wrangler@4 dev --port 8799
#   python worker/test/e2e.py http://127.0.0.1:8799
import base64, json, os, subprocess, sys, tempfile, threading, time, urllib.error, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "pc"))
from phonebridge import phone  # noqa: E402  (crypto helpers only; the CLI itself runs as a subprocess)

URL = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8799"
MASTER = os.environ.get("PAIR_MASTER") or open(os.path.join(HERE, "..", ".dev.vars")).read().split("PAIR_MASTER=")[1].split()[0]
TMP = tempfile.mkdtemp()
ENV = {**os.environ, "PHONE_BRIDGE_DIR": TMP, "PYTHONIOENCODING": "utf-8"}
PHONE_PY = os.path.join(HERE, "..", "..", "pc", "phone.py")
results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name + ("" if cond else f"  -> {detail}"), flush=True)


def cli(*a, timeout=60):
    p = subprocess.run([sys.executable, PHONE_PY, *a], env=ENV, capture_output=True, text=True, encoding="utf-8", timeout=timeout)
    try:
        return json.loads(p.stdout)
    except ValueError:
        return {"_raw": p.stdout, "_err": p.stderr}


def req(method, path, token, body=None, timeout=40):
    r = urllib.request.Request(URL + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                               headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(r, timeout=timeout) as x:
            return x.status, json.loads(x.read())
    except urllib.error.HTTPError as e:
        return e.code, None


class Phone:
    """Plays the Android app: decodes the pairing string exactly like Pairing.parse, seals with the same AAD."""
    def __init__(self, ps):
        j = json.loads(base64.urlsafe_b64decode(ps[4:] + "=" * (-len(ps[4:]) % 4)))
        self.token, self.key, self.dev, self.down = j["t"], base64.b64decode(j["k"]), j["t"].split(".")[0], 0

    def up(self, msg, aad_box="up"):
        return req("POST", "/v1/up", self.token, {"blob": phone.seal(self.key, f"{self.dev}|{aad_box}", json.dumps(msg).encode())})

    def poll(self, wait=25):
        s, r = req("GET", f"/v1/down?after={self.down}&wait={wait}", self.token, timeout=wait + 20)
        self.down = r["cursor"]
        return [json.loads(phone.unseal(self.key, f"{self.dev}|down", it["blob"])) for it in r["items"]]


now = int(time.time() * 1000)
json.dump({"master": MASTER}, open(os.path.join(TMP, "config.json"), "w"))
pr = cli("pair", "--worker", URL)
check("pair prints pairing string + writes QR", pr.get("pairing", "").startswith("pb1:") and pr.get("qr") and os.path.exists(pr["qr"]), pr)
ph = Phone(pr["pairing"])

# --- auth
check("health endpoint", req("GET", "/v1/health", "x")[0] == 200)
check("bad token -> 401", req("GET", "/v1/up", ph.dev + "." + "0" * 64)[0] == 401)
check("no token -> 401", req("GET", "/v1/down", "")[0] == 401)
check("oversized blob -> 413", req("POST", "/v1/up", ph.token, {"blob": "A" * 300_000})[0] == 413)

# --- phone backfill
otp = "کد تایید شما ۴۵۶۷۸۹ است"
ph.up({"t": "contacts", "full": True, "items": [{"cid": 1, "name": "Ali Rezaei", "numbers": ["+98 900 000 0101"]},
                                                {"cid": 2, "name": "Bank", "numbers": ["9999"]}], "deleted": []})
ph.up({"t": "sms", "items": [
    {"pid": 1, "box": "inbox", "number": "09000000101", "body": "سلام، فردا جلسه داریم", "date": now - 3 * 3600_000, "date_sent": now - 3 * 3600_000, "read": 1},
    {"pid": 2, "box": "inbox", "number": "9999", "body": otp, "date": now - 600_000, "date_sent": now - 600_000, "read": 0},
    {"pid": 3, "box": "sent", "number": "09000000101", "body": "باشه", "date": now - 3000_000, "date_sent": 0, "read": 1}]})
ph.up({"t": "calls", "items": [{"pid": 10, "number": "09000000101", "name": None, "type": "MISSED", "date": now - 100_000, "duration": 0},
                               {"pid": 11, "number": "0935", "name": "X", "type": "INCOMING", "date": now - 200_000, "duration": 30}]})
ph.up({"t": "sms", "items": [{"pid": 9, "box": "inbox", "number": "1", "body": "evil"}]}, aad_box="down")  # wrong AAD: must be skipped

s = cli("sync")
check("sync pulls all 4 items", s.get("items") == 4, s)
check("blob sealed for the wrong direction is dropped", cli("search", "evil", "--offline")["messages"] == [])
m = cli("sms", "--offline")
check("sms lists inbox newest first", [x["number"] for x in m.get("messages", [])] == ["9999", "09000000101"], m)
check("untrusted_content: true", m.get("untrusted_content") is True, m)
check("OTP shown in full by default (no masking)", m["messages"][0]["body"] == otp and m["messages"][0]["otp"] == "456789", m["messages"][0])
check("--mask hides the OTP", cli("sms", "--mask", "--offline")["messages"][0]["body"] == "کد تایید شما ****** است")
check("classified otp", m["messages"][0]["class"] == "otp", m["messages"][0])
check("contact name joined", m["messages"][1]["name"] == "Ali Rezaei", m["messages"][1])
check("--unread", [x["number"] for x in cli("sms", "--unread")["messages"]] == ["9999"])
check("--since 1h", [x["number"] for x in cli("sms", "--since", "1h")["messages"]] == ["9999"])
check("search (FTS, Arabic yeh in query)", [x["body"] for x in cli("search", "جلسه داريم")["messages"]] == ["سلام، فردا جلسه داریم"])
check("--from NAME --box all", len(cli("sms", "--from", "Ali", "--box", "all")["messages"]) == 2)
th = cli("thread", "Ali")
check("thread view oldest first, both boxes", [x["box"] for x in th.get("messages", [])] == ["inbox", "sent"], th)
check("who", cli("who", "+989000000101").get("name") == "Ali Rezaei")
check("contacts --q", [c["name"] for c in cli("contacts", "--q", "ali")["contacts"]] == ["Ali Rezaei"])
calls = cli("calls", "--missed")["calls"]
check("calls --missed with name", len(calls) == 1 and calls[0]["name"] == "Ali Rezaei", calls)

# --- status with backfill progress pushed by the phone
ph.up({"t": "status", "ts": int(time.time() * 1000), "paused": False, "version": "2.0.0", "queue": 3, "battery_unrestricted": True,
       "sims": [{"slot": 1, "name": "MCI"}, {"slot": 2, "name": "Irancell"}],
       "backfill": {"sms": [1200, 5400], "calls": [800, 800], "contacts": [310, 310], "complete": False}})
st = cli("status")
check("status shows backfill progress + phone health", st.get("backfill", {}).get("sms") == [1200, 5400] and st["phone"]["queue"] == 3
      and st["db"]["sms"] == 3, st)


# --- send: PC -> Worker -> phone -> result (+ later delivery) -> PC
def phone_loop(n):
    for _ in range(n):
        for c in ph.poll(wait=20):
            seen.append(c)
            ok = c["op"] in ("send_sms", "ping")
            ph.up({"t": "result", "id": c["id"], "op": c["op"], "ok": ok, "status": "sent" if ok else "failed",
                   "error": None if ok else "unknown op", "audit": {"agent": c.get("agent"), "number": c.get("number")}})
            if c["op"] == "send_sms":
                ph.up({"t": "delivery", "id": c["id"], "ok": True, "status": "delivered", "ts": int(time.time() * 1000)})


seen = []
t = threading.Thread(target=phone_loop, args=(2,), daemon=True); t.start()
r = cli("send", "Ali", "سلام از PC، این یک پیام طولانی فارسی است " * 5, "--agent", "e2e-test")
check("send by contact name -> sent", r.get("ok") is True and r.get("status") == "sent", r)
cmd = next((c for c in seen if c["op"] == "send_sms"), {})
check("phone decrypted the long Persian command", cmd.get("number") == "+98 900 000 0101" and cmd.get("text", "").count("طولانی") == 5
      and cmd.get("agent") == "e2e-test", cmd)
check("command is timestamped (phone enforces 10-min expiry)", abs(cmd.get("ts", 0) - time.time() * 1000) < 60_000)
p = cli("ping")
check("ping round trip", p.get("ok") is True, p)
t.join(5)
a = cli("audit", "--verify")
s0 = (a.get("sends") or [{}])[0]
check("audit: agent, full text, delivered, chain verifies", s0.get("agent") == "e2e-test" and s0.get("status") == "delivered"
      and "طولانی" in s0.get("text", "") and a["verify"]["ok"] and a["verify"]["entries"] == 3, a)

# --- v3: idempotency key through the real Worker: the phone sees ONE command, the 2nd call returns the 1st result
seen = []
t = threading.Thread(target=phone_loop, args=(1,), daemon=True); t.start()
r1 = cli("send", "Ali", "idempotent hello", "--agent", "e2e-test", "--idempotency-key", "E2E-K1")
t.join(25)
r2 = cli("send", "Ali", "idempotent hello", "--agent", "e2e-test", "--idempotency-key", "E2E-K1")
time.sleep(1)
seen += ph.poll(wait=1)
check("v3 idempotency: one command at the phone, 2nd call already_sent with the first result",
      r1.get("status") == "sent" and r1.get("already_sent") is False and r2.get("already_sent") is True and r2.get("id") == r1.get("id")
      and r2.get("status") == "sent" and [c["text"] for c in seen if c.get("op") == "send_sms"] == ["idempotent hello"], (r1, r2, seen))
dr = cli("send", "Ali", "dry", "--dry-run")
check("v3 dry-run posts nothing", dr.get("dry_run") is True and ph.poll(wait=1) == [], dr)
# --- v3: status default_sim + privacy
ph.up({"t": "status", "ts": int(time.time() * 1000), "paused": False, "version": "3.0.0", "queue": 0, "battery_unrestricted": True,
       "sims": [{"slot": 1, "name": "MCI"}, {"slot": 2, "name": "Irancell"}], "default_sim": 2,
       "privacy": {"sms": True, "contacts": True, "calls": False},
       "backfill": {"sms": [5400, 5400], "calls": [0, 0], "contacts": [310, 310], "complete": True}})
st = cli("status")
check("v3 status: default_sim stored in config.json, privacy surfaced", st.get("default_sim") == 2 and st.get("privacy", {}).get("calls") is False
      and json.load(open(os.path.join(TMP, "config.json"))).get("default_sim") == 2, st)

# --- no phone answering: send reports queued (exit 5), not success
t0 = time.time()
p_ = subprocess.run([sys.executable, PHONE_PY, "send", "09000000103", "x"], env=ENV, capture_output=True, text=True, encoding="utf-8", timeout=90)
r = json.loads(p_.stdout)
check("send without phone -> queued after ~30s, exit 5", r.get("status") == "queued" and r.get("ok") is False and p_.returncode == 5
      and 25 < time.time() - t0 < 60, (p_.returncode, r))

# --- otp --wait blocks until a NEW code arrives
o = subprocess.Popen([sys.executable, PHONE_PY, "otp", "--wait", "40"], env=ENV, stdout=subprocess.PIPE, text=True, encoding="utf-8")
time.sleep(4)
ph.up({"t": "sms", "items": [{"pid": 0, "box": "inbox", "number": "+98200050", "body": "<#> کد ورود شما به اسنپ: ۸۸۲۱۴۳", "date": int(time.time() * 1000),
                              "date_sent": int(time.time() * 1000), "read": 0}]})
try:
    got = json.loads(o.communicate(timeout=60)[0] or "{}")
except subprocess.TimeoutExpired:
    o.kill(); got = {}
check("otp --wait returns only the new code", got.get("code") == "882143" and got.get("sender") == "+98200050" and set(got) >= {"code", "sender", "text", "ts"}, got)

# --- daemon: near-real-time sync + rules (bank deposit -> append to a note)
note = os.path.join(TMP, "ledger.md")
with open(os.path.join(TMP, "rules.yaml"), "w", encoding="utf-8") as f:
    f.write("rules:\n  - name: dep\n    on: sms\n    if: {class: bank, bank_type: deposit, amount_gt: 1000000}\n"
            f"    do:\n      - append: '{note}'\n        line: '{{amount_toman}} {{bank}}'\n")
d = subprocess.Popen([sys.executable, PHONE_PY, "daemon"], env=ENV, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8")
time.sleep(6)
ph.up({"t": "sms", "items": [{"pid": 0, "box": "inbox", "number": "+9810001", "body": "بانک ملت\nواریز 25,000,000 ریال\nمانده 90,000,000 ریال",
                              "date": int(time.time() * 1000), "date_sent": int(time.time() * 1000), "read": 0}]})
for _ in range(40):
    if os.path.exists(note): break
    time.sleep(1)
d.kill()
check("daemon fires rule within seconds of arrival", os.path.exists(note) and open(note, encoding="utf-8").read() == "2500000 mellat\n",
      d.stdout.read()[-500:] if not os.path.exists(note) else "")
mir = [c for c in ph.poll(wait=2) if c.get("op") == "rules_mirror"]
check("v3 daemon sends rules_mirror at start (PROTOCOL shape)", mir and mir[0]["rules"][0]["name"] == "dep"
      and set(mir[0]["rules"][0]) == {"name", "trigger", "action", "enabled", "last_run", "last_ok", "runs"}, mir)

# --- watch prints a newly pushed SMS as a JSON line (unmasked)
w = subprocess.Popen([sys.executable, PHONE_PY, "watch"], env=ENV, stdout=subprocess.PIPE, text=True, encoding="utf-8")
time.sleep(3)
ph.up({"t": "sms", "items": [{"pid": 0, "box": "inbox", "number": "9999", "body": "Your code is 5512", "date": int(time.time() * 1000), "date_sent": int(time.time() * 1000), "read": 0}]})
line = {}
threading.Timer(40, w.kill).start()
try:
    line = json.loads(w.stdout.readline() or "{}")
finally:
    w.kill()
check("watch emits JSON line, full text, untrusted flag", line.get("body") == "Your code is 5512" and line.get("untrusted_content") is True, line)

# --- the Worker only ever held ciphertext
d1 = subprocess.run("npx wrangler@4 d1 execute angaros --local --json --command \"SELECT blob FROM msg\"", shell=True, cwd=os.path.join(HERE, ".."),
                    capture_output=True, text=True, encoding="utf-8")
blobs = " ".join(r["blob"] for r in json.loads(d1.stdout)[0]["results"]) if d1.returncode == 0 else ""
check("D1 holds blobs, no plaintext", blobs and not any(w in blobs for w in ("Ali", "سلام", "send_sms", "09000000101")), d1.stderr[-300:])

# --- rate limit on a separate device so the rest of the suite is unaffected
# (fresh device id; up to 2 windows of requests so a minute boundary mid-burst cannot hide the limit)
tok2 = phone.make_token(MASTER, "ratelimit" + os.urandom(6).hex())
codes = []
while len(codes) < 245 and 429 not in codes:
    codes.append(req("GET", "/v1/up", tok2)[0])
check("rate limit -> 429 only after >=120 requests in a minute", 429 in codes and codes.index(429) >= 120
      and set(codes[:codes.index(429)]) == {200}, (len(codes), codes[-5:]))

print(f"\n{sum(ok for _, ok in results)}/{len(results)} passed")
sys.exit(0 if all(ok for _, ok in results) else 1)
