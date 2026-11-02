# python pc/test_docs.py -- every rules.yaml snippet in docs/ + SKILL.md passes `rules check`, every Python snippet
# compiles, and the read-only SDK recipes (03, 09) actually run via `phone.py run` against a temp phone.db.
import base64, glob, os, re, subprocess, sys, tempfile, time

TMP = tempfile.mkdtemp()
os.environ["PHONE_BRIDGE_DIR"] = TMP
HERE = os.path.dirname(os.path.abspath(__file__))
PC = os.path.dirname(HERE)
ROOT = os.path.dirname(PC)
sys.path.insert(0, PC)
import yaml  # noqa: E402
from phonebridge import phone, rules  # noqa: E402

docs = sorted(glob.glob(os.path.join(ROOT, "docs", "recipes", "*.md"))) + [os.path.join(ROOT, "docs", "AGENTS.md"),
                                                                           os.path.join(ROOT, "skills", "angaros", "SKILL.md"), os.path.join(ROOT, "README.md")]
assert len(glob.glob(os.path.join(ROOT, "docs", "recipes", "*.md"))) >= 10
n_yaml = n_py = 0
for f in docs:
    text = open(f, encoding="utf-8").read()
    for block in re.findall(r"```yaml\n(.*?)```", text, re.S):
        doc = yaml.safe_load(block)
        if isinstance(doc, dict) and "rules" in doc:
            probs = rules.check(doc)
            assert probs == [], (f, probs)
            n_yaml += 1
    for block in re.findall(r"```python\n(.*?)```", text, re.S):
        compile(block, f, "exec")
        n_py += 1
print(f"rules snippets valid: {n_yaml}, python snippets compile: {n_py}")
assert n_yaml >= 10 and n_py >= 4

phone.save_cfg({"worker": "http://127.0.0.1:9", "token": "dev0123456789abcdef." + "0" * 64, "key": base64.b64encode(os.urandom(32)).decode()})
b = phone.Bridge()
now = int(time.time() * 1000)
b.ingest({"t": "sms", "items": [
    {"pid": 1, "box": "inbox", "number": "+9899999", "body": "بانک ملت\nخرید 1,500,000 ریال\nفروشگاه رفاه\nمانده 9,000,000", "date": now - 5000},
    {"pid": 2, "box": "inbox", "number": "09000000102", "body": "سلام، فردا میای؟", "date": now - 4000},
    {"pid": 3, "box": "sent", "number": "09000000102", "body": "آره", "date": now - 3000}]})
b.con.commit()
for name, expect in (("03-expense-tracker-obsidian.md", "out"), ("09-contact-enrichment.md", "09000000102")):
    code = re.findall(r"```python\n(.*?)```", open(os.path.join(ROOT, "docs", "recipes", name), encoding="utf-8").read(), re.S)[0]
    script = os.path.join(TMP, name + ".py")
    open(script, "w", encoding="utf-8").write(code)
    p = subprocess.run([sys.executable, os.path.join(PC, "phone.py"), "run", script, "--offline"], capture_output=True, text=True,
                       encoding="utf-8", env={**os.environ, "PHONE_BRIDGE_DIR": TMP, "PYTHONIOENCODING": "utf-8"}, timeout=60)
    assert p.returncode == 0 and expect in p.stdout, (name, p.returncode, p.stdout, p.stderr[-800:])
    print(f"recipe {name} runs: {p.stdout.strip().splitlines()[0]}")
p = subprocess.run([sys.executable, os.path.join(PC, "phone.py"), "run", os.path.join(ROOT, "examples", "today.py"), "--offline"],
                   capture_output=True, text=True, encoding="utf-8", env={**os.environ, "PHONE_BRIDGE_DIR": TMP, "PYTHONIOENCODING": "utf-8"},
                   timeout=60)
assert p.returncode == 0 and "3 messages today" in p.stdout, (p.returncode, p.stdout, p.stderr[-800:])
print(f"examples/today.py runs: {p.stdout.strip().splitlines()[0]}")
print("test_docs: all checks passed")
