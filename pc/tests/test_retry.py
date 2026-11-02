"""wait_result must survive a dropped long-poll (seen on the real phone: WinError 10054 mid-ping). No network."""
import json, os, sys, tempfile, time

TMP = tempfile.mkdtemp(prefix="pbretry-")
os.environ["PHONE_BRIDGE_DIR"] = TMP
HERE = os.path.dirname(os.path.abspath(__file__))
PC = os.path.dirname(HERE)
sys.path.insert(0, PC)
with open(os.path.join(TMP, "config.json"), "w") as f:
    json.dump({"worker": "http://127.0.0.1:9", "token": "devid.sig", "key": "AAAA", "master": "m"}, f)
from phonebridge import phone  # noqa: E402

fails = 0
def check(name, cond):
    global fails
    print(("PASS " if cond else "FAIL ") + name)
    fails += not cond


b = phone.Bridge()
calls = []
def flaky_pull(wait=0):  # 1st long-poll is reset by the network, the 2nd delivers the phone's answer
    calls.append(wait)
    if len(calls) == 1:
        raise phone.PBError("relay unreachable ([WinError 10054] reset)", "unreachable", maybe_posted=True)
    b.con.execute("INSERT INTO results (id, ts, json) VALUES (?,?,?)", ("c1", 0, json.dumps({"ok": True, "op": "ping"})))
b.pull = flaky_pull
try:
    r = b.wait_result("c1", timeout=10)
except phone.PBError as e:
    r = e
check("one dropped long-poll is retried and the result still arrives", r == {"ok": True, "op": "ping"} and len(calls) == 2)

def dead_pull(wait=0):
    raise phone.PBError("relay unreachable (reset)", "unreachable", maybe_posted=True)
b.pull = dead_pull
t = time.time()
try:
    r = b.wait_result("c2", timeout=3); code = None
except phone.PBError as e:
    code = e.code
check("relay down for the whole wait -> still reported as unreachable (exit 4), not a silent timeout",
      code == "unreachable" and time.time() - t < 6)

def http_error(wait=0):
    raise phone.PBError("relay HTTP 401: bad token", "error")
b.pull = http_error
try:
    b.wait_result("c3", timeout=3); code = None
except phone.PBError as e:
    code = e.code
check("an HTTP error from the relay is not retried", code == "error")

print("ALL PASS" if not fails else f"{fails} FAIL")
sys.exit(1 if fails else 0)
