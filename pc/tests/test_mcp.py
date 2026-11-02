# python pc/test_mcp.py  -- starts phone_mcp.py over stdio with the official MCP client and calls its tools.
import asyncio, base64, json, os, sys, tempfile, time

HERE = os.path.dirname(os.path.abspath(__file__))
PC = os.path.dirname(HERE)
TMP = tempfile.mkdtemp()
os.environ["PHONE_BRIDGE_DIR"] = TMP
sys.path.insert(0, PC)
from phonebridge import phone
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

# paired to an unreachable relay: reads must still answer from phone.db (offline-first), sends must fail loudly
phone.save_cfg({"worker": "http://127.0.0.1:9", "token": "dev0123456789abcdef." + "0" * 64, "key": base64.b64encode(os.urandom(32)).decode()})
b = phone.Bridge()
now = int(time.time() * 1000)
b.ingest({"t": "contacts", "full": True, "items": [{"cid": 1, "name": "علی", "numbers": ["09000000101"]}]})
b.ingest({"t": "sms", "items": [
    {"pid": 1, "box": "inbox", "number": "+989000000101", "body": "سلام، كتاب رو آوردی؟", "date": now - 60_000},
    {"pid": 2, "box": "inbox", "number": "10004321", "body": "کد ورود: ۵۵۴۴۳۳", "date": now - 30_000},
    {"pid": 3, "box": "inbox", "number": "+9899999", "body": "بانک ملی\nواریز:2,000,000\nمانده:9,000,000", "date": now - 10_000}]})
b.con.commit()

EXPECTED = {"sms_search", "sms_recent", "conversation", "send_sms", "wait_for_otp", "otp_latest", "contacts_search",
            "call_log", "bank_summary", "status", "watch_rules"}


async def main():
    params = StdioServerParameters(command=sys.executable, args=[os.path.join(PC, "phone_mcp.py")],
                                   env={**os.environ, "PHONE_BRIDGE_DIR": TMP, "PYTHONIOENCODING": "utf-8"})
    async with stdio_client(params) as (r, w), ClientSession(r, w) as s:
        await s.initialize()
        names = {t.name for t in (await s.list_tools()).tools}
        assert EXPECTED <= names, EXPECTED - names  # v3 added tools; the 11 v2 tools must stay

        async def call(name, **kw):
            res = await s.call_tool(name, kw)
            return json.loads(res.content[0].text)

        o = await call("sms_search", query="کتاب")  # Persian kaf in query, Arabic kaf in the SMS
        assert [m["name"] for m in o["messages"]] == ["علی"] and o["untrusted_content"] is True and "sync_error" in o, o
        o = await call("sms_recent", mask=True)
        assert "554433" not in json.dumps(o, ensure_ascii=False) and o["masked"] is True, o
        o = await call("otp_latest")
        assert o["code"] == "554433", o
        o = await call("bank_summary", since="1d")
        assert o["in"] == 2_000_000 and o["by_bank"]["melli"]["count"] == 1, o
        o = await call("conversation", contact_or_number="علی")
        assert len(o["messages"]) == 1, o
        o = await call("status")
        assert o["db"]["sms"] == 3 and o["paired"] is True, o
        o = await call("send_sms", to="علی", text="test", agent="mcp-test")
        assert o.get("code") == "unreachable", o
        o = await call("watch_rules")
        assert o["rules"] == [] and o["recent_firings"] == [], o
    # `angaros mcp` (what the MCP registry entry runs) must serve the same tools over stdio
    params = StdioServerParameters(command=sys.executable, args=["-c", "import sys; from phonebridge.phone import main; sys.exit(main(['mcp']))"],
                                   env={**os.environ, "PHONE_BRIDGE_DIR": TMP, "PYTHONIOENCODING": "utf-8", "PYTHONPATH": PC},
                                   cwd=PC)
    async with stdio_client(params) as (r, w), ClientSession(r, w) as s:
        await s.initialize()
        assert EXPECTED <= {t.name for t in (await s.list_tools()).tools}
    print("test_mcp: all checks passed (11 tools)")


asyncio.run(main())
