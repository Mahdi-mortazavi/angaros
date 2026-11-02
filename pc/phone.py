#!/usr/bin/env python
"""Compatibility shim: `python pc/phone.py ARGS` == `angaros ARGS` without installing the package.

A checkout that already keeps config.json next to this file (pre-package installs) keeps using that directory.
`import phone` from this folder returns the real `phonebridge.phone` module.
"""
import os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
if os.path.exists(os.path.join(HERE, "config.json")):
    os.environ.setdefault("PHONE_BRIDGE_DIR", HERE)

from phonebridge import phone as _phone  # noqa: E402

if __name__ == "__main__":
    sys.exit(_phone.main())
sys.modules[__name__] = _phone
