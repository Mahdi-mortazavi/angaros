"""Runs every PC test script (each is a plain script that exits non-zero on failure) plus `rules check`.
python pc/tests/run_all.py   -> prints one line per test and exits 1 if any failed."""
import os, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
PC = os.path.dirname(HERE)
TESTS = ["test_parse.py", "test_phone.py", "test_mcp.py", "test_v3.py", "test_v3_fixes.py", "test_docs.py",
         "test_doctor.py", "test_retry.py"]

env = {**os.environ, "PYTHONIOENCODING": "utf-8", "PHONE_BRIDGE_DIR": tempfile.mkdtemp()}  # each test sets its own
env.pop("ANGAROS_HOME", None)  # would override the per-test PHONE_BRIDGE_DIR
failed = []
runs = [[os.path.join(HERE, t)] for t in TESTS] + [[os.path.join(PC, "phone.py"), "rules", "check", os.path.join(PC, "rules.example.yaml")]]
for args in runs:
    name = " ".join(os.path.basename(a) for a in args)
    p = subprocess.run([sys.executable, *args], env=env, capture_output=True, text=True, encoding="utf-8", timeout=600)
    print(f"{'PASS' if p.returncode == 0 else 'FAIL'} {name} (exit {p.returncode})", flush=True)
    if p.returncode:
        failed.append(name)
        print(*[ln for ln in p.stdout.splitlines() if ln.startswith("FAIL")], p.stdout[-2000:], p.stderr[-3000:], sep="\n")
print(f"{len(runs) - len(failed)}/{len(runs)} passed")
sys.exit(1 if failed else 0)
