# python pc/genparsevec.py -- writes shared/parse-vectors.json: the test_parse.py corpus with parse.py's answers,
# so the Android port (Parse.kt, ParseTest) is checked for parity against the PC parser.
import json, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
PC = os.path.dirname(HERE)
sys.path.insert(0, PC)
from phonebridge import parse as P

src = open(os.path.join(HERE, "test_parse.py"), encoding="utf-8").read()
# Run the corpus definitions only (everything before the first assertion loop that reports).
ns = {"__file__": os.path.join(HERE, "test_parse.py")}
exec(compile(src.split("if fails:")[0], "test_parse.py", "exec"), ns)

bodies = [(b, "10004321", False) for b, _ in ns["OTP"]] + [(b, "+98700000", False) for b, _ in ns["B"]] + \
         [(b, s, k) for b, s, k, _ in ns["C"]] + [(b, s, False) for _, s, b, _ in ns["HELD_OUT"]]
out = []
for body, sender, known in bodies:
    cls, code, b = P.classify(body, sender, known)
    out.append({"body": body, "sender": sender, "known": known, "cls": cls, "otp": code,
                "amount": b["amount"] if b else None, "direction": b["direction"] if b else None})
path = os.path.join(PC, "..", "shared", "parse-vectors.json")
json.dump(out, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print(f"{len(out)} vectors -> shared/parse-vectors.json")
