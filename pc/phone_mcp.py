#!/usr/bin/env python
"""Compatibility shim: `python pc/phone_mcp.py [--http]` == `angaros-mcp [--http]` without installing the package."""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
if os.path.exists(os.path.join(HERE, "config.json")):
    os.environ.setdefault("PHONE_BRIDGE_DIR", HERE)

from phonebridge import mcp_server  # noqa: E402

if __name__ == "__main__":
    sys.exit(mcp_server.main())
