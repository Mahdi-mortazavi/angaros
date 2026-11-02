# Helper for test_phone.py: a rule `shell` action target. Records stdin, whether SMS text leaked into the
# environment, and whether phone.db is writable while the action runs (i.e. the daemon holds no write lock).
import json, os, sqlite3, sys

out, db = sys.argv[1], sys.argv[2]
data = sys.stdin.read()
try:
    con = sqlite3.connect(db, timeout=2)
    con.execute("INSERT INTO meta (k, v) VALUES ('lockprobe', '1') ON CONFLICT(k) DO UPDATE SET v = v + 1")
    con.commit()
    dbw = "ok"
except sqlite3.Error as e:
    dbw = str(e)
with open(out, "w", encoding="utf-8") as f:
    json.dump({"stdin": data, "env_body": os.environ.get("PB_BODY"), "pb_rule": os.environ.get("PB_RULE"), "db_write": dbw}, f)
