# Regenerates shared/test-vector.json (fixed key + nonce) from the Python implementation.
import json, os, sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from phonebridge import phone

key, nonce = bytes(range(32)), bytes(range(100, 112))
aad = "dev123dev123dev123|up"
plain = '{"t":"sms","items":[{"number":"+989000000101","body":"سلام کد ۱۲۳۴"}]}'
token = "dev123dev123dev123." + "ab" * 32
v = {"key_hex": key.hex(), "nonce_hex": nonce.hex(), "aad": aad, "plaintext": plain,
     "blob": phone.seal(key, aad, plain.encode(), nonce),
     "pairing": {"worker": "https://pb.example.workers.dev", "token": token,
                 "string": phone.pairing_string("https://pb.example.workers.dev", token, key)}}
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "shared", "test-vector.json")
with open(out, "w", encoding="utf-8") as f:
    json.dump(v, f, ensure_ascii=False, indent=1)
print(v["blob"])
