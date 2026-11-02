"""phone.py doctor: temp PHONE_BRIDGE_DIR, a local fake /v1/health, no real relay."""
import json, os, subprocess, sys, tempfile, threading, time
from http.server import BaseHTTPRequestHandler, HTTPServer

TMP = tempfile.mkdtemp(prefix="pbdoc-")
os.environ["PHONE_BRIDGE_DIR"] = TMP
HERE = os.path.dirname(os.path.abspath(__file__))
PC = os.path.dirname(HERE)
ENV = {**os.environ, "PHONE_BRIDGE_DIR": TMP, "PYTHONIOENCODING": "utf-8"}


class Health(BaseHTTPRequestHandler):
    def do_GET(self):
        body = json.dumps({"ok": self.path == "/v1/health"}).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.end_headers(); self.wfile.write(body)

    def log_message(self, *a): pass


def doctor():
    p = subprocess.run([sys.executable, os.path.join(PC, "phone.py"), "doctor"], env=ENV, capture_output=True, text=True,
                       encoding="utf-8", timeout=60)
    return p.returncode, json.loads(p.stdout)


def by(r):
    return {c["check"]: c for c in r["checks"]}


def write_cfg(worker):
    with open(os.path.join(TMP, "config.json"), "w") as f:
        json.dump({"worker": worker, "token": "devid.sig", "key": "AAAA", "master": "m"}, f)


fails = 0
def check(name, cond):
    global fails
    print(("PASS " if cond else "FAIL ") + name)
    fails += not cond


rc, r = doctor()
check("unpaired: exit 1, pairing fails with the pair command as fix",
      rc == 1 and not r["ok"] and by(r)["pairing"]["level"] == "fail" and "angaros pair" in by(r)["pairing"]["fix"])

write_cfg("http://127.0.0.1:9")  # nothing listens on the discard port: connection refused
rc, r = doctor()
check("relay refused: relay fails with a fix", rc == 1 and by(r)["relay"]["level"] == "fail" and by(r)["relay"]["fix"])
check("never-seen phone fails", by(r)["phone"]["level"] == "fail")
check("no secret in output", "devid.sig" not in json.dumps(r) and "AAAA" not in json.dumps(r))

srv = HTTPServer(("127.0.0.1", 0), Health)
threading.Thread(target=srv.serve_forever, daemon=True).start()
write_cfg(f"http://127.0.0.1:{srv.server_port}")
sys.path.insert(0, PC)
from phonebridge import phone  # noqa: E402  (after PHONE_BRIDGE_DIR is set)
con = phone.db()
now = int(time.time() * 1000)
phone.meta(con, "last_seen", now); phone.meta(con, "last_sync", now); phone.meta(con, "daemon_beat", now)
phone.meta(con, "phone_status", json.dumps({"ts": now, "version": "3.0.2", "paused": False, "battery_unrestricted": True,
                                            "default_sim": 2, "sims": [{"slot": 1, "name": "A"}]}))
con.commit()
rc, r = doctor()
check("healthy relay + fresh phone + heartbeat: relay/phone/daemon ok",
      by(r)["relay"]["level"] == "ok" and by(r)["phone"]["level"] == "ok" and by(r)["daemon"]["level"] == "ok")
check("default SIM 2 not active: warn (not fail), exit 0", by(r)["default_sim"]["level"] == "warn" and rc == 0 and r["ok"])

phone.meta(con, "phone_status", json.dumps({"ts": now, "paused": True, "sims": []})); con.commit()
rc, r = doctor()
check("paused phone fails with the resume fix", rc == 1 and "resume" in by(r)["phone_paused"]["fix"])
srv.shutdown()

print(f"test_doctor: {'all checks passed' if not fails else f'{fails} failed'}")
sys.exit(1 if fails else 0)
