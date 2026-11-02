"""Single source of truth for the Phone Bridge icon.

Emits Android VectorDrawables (res/drawable) + adaptive-icon XMLs.
SVG masters are produced from those XMLs by vd2svg.py, so the SVG and the
on-device vectors cannot drift apart.

Coordinates: 108x108 adaptive-icon canvas, centre (54,54), safe circle r=33.
"""
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RES = ROOT / "android/app/src/main/res"

# brand palette (#AARRGGBB)
LAPIS, DEEP, TURQ, GOLD = "#FF24389C", "#FF0F1B4D", "#FF2BB3C0", "#FFE3A82B"


# ---------- geometry helpers ----------
def circle(cx, cy, r):
    return f"M{cx - r:g},{cy:g}a{r:g},{r:g} 0 1 0 {2 * r:g},0a{r:g},{r:g} 0 1 0 {-2 * r:g},0Z"


def star8(cx, cy, R, inner=0.7654):
    """Khatam / 8-point star (two overlapping squares) - the shamseh nod."""
    pts = []
    for k in range(16):
        r = R if k % 2 == 0 else R * inner
        a = math.radians(-90 + k * 22.5)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return "M" + "L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + "Z"


def lin(x1, y1, x2, y2, *stops):
    return dict(type="linear", x1=x1, y1=y1, x2=x2, y2=y2, stops=stops)


def rad(cx, cy, r, *stops):
    return dict(type="radial", cx=cx, cy=cy, r=r, stops=stops)


# ---------- glyph ----------
# Message bubble (glass) on the left, AI node (turquoise orb with khatam star)
# on the right, joined by a low gold arch: the bridge (pol).
BX0, BX1, BY0, BY1, BR = 18.5, 51.5, 47.0, 72.0, 12.0   # bubble body
BCX = (BX0 + BX1) / 2
OX, OY, ORAD = 79.5, 57.0, 10.0                           # orb (AI node)

BUBBLE = (
    f"M{BX0 + BR},{BY0}H{BX1 - BR}A{BR},{BR} 0 0 1 {BX1},{BY0 + BR}"
    f"V{BY1 - BR}A{BR},{BR} 0 0 1 {BX1 - BR},{BY1}"
    f"H{BX0 + 13.5}C{BX0 + 11},{BY1} {BX0 + 8.6},{BY1 + 3.2} {BX0 + 5.4},{BY1 + 5.6}"
    f"C{BX0 + 4.3},{BY1 + 6.4} {BX0 + 3.1},{BY1 + 5.6} {BX0 + 3.5},{BY1 + 4.3}"
    f"C{BX0 + 4.1},{BY1 + 2.4} {BX0 + 4.5},{BY1 + 0.6} {BX0 + 4.4},{BY1 - 1.4}"
    f"A{BR},{BR} 0 0 1 {BX0},{BY1 - BR}V{BY0 + BR}A{BR},{BR} 0 0 1 {BX0 + BR},{BY0}Z"
)
# top specular rim only (left corner -> right corner)
BUBBLE_RIM = (f"M{BX0 + 0.9},{BY0 + BR}A{BR - 0.9},{BR - 0.9} 0 0 1 {BX0 + BR},{BY0 + 0.9}"
              f"H{BX1 - BR}A{BR - 0.9},{BR - 0.9} 0 0 1 {BX1 - 0.9},{BY0 + BR}")
DY = (BY0 + BY1) / 2
DOTS = "".join(circle(BCX + dx, DY, 2.5) for dx in (-8.0, 0.0, 8.0))

# bridge span: bubble's upper-right shoulder -> orb's upper-left shoulder
# feet tucked behind both bodies: the arch spans the gap like a bridge between two banks
A0, A1 = (BX1 - 1.2, 58.0), (OX - 6.0, 59.0)
AP = ((A0[0] + A1[0]) / 2, 44.5)  # crown
ARCH = (f"M{A0[0]},{A0[1]}C{A0[0] + 0.6},{AP[1] + 6} {AP[0] - 7.5},{AP[1]} {AP[0]},{AP[1]}"
        f"C{AP[0] + 7.5},{AP[1]} {A1[0]},{AP[1] + 7} {A1[0]},{A1[1]}")


def glyph(mode="color"):
    """mode: color | mono | mark (glyph for light/any backgrounds)."""
    if mode == "mono":
        W = "#FFFFFFFF"
        return [
            dict(d=BUBBLE + DOTS, fill=W, evenodd=True),
            dict(d=circle(OX, OY, ORAD) + star8(OX, OY, 5.4), fill=W, evenodd=True),
            dict(d=ARCH, stroke=W, sw=3.0, cap="round"),
        ]
    sx, sy = OX - 4.2, OY - 4.6  # orb specular centre
    els = [
        # soft contact shadows (fake blur: radial gradient to transparent)
        dict(group=dict(pivotX=BCX, pivotY=BY1 + 5, scaleY=0.22),
             children=[dict(d=circle(BCX, BY1 + 5, 21), fill=rad(BCX, BY1 + 5, 21, (0, "#59050A26"), (1, "#00050A26")))]),
        dict(group=dict(pivotX=OX, pivotY=OY + ORAD + 2.5, scaleY=0.22),
             children=[dict(d=circle(OX, OY + ORAD + 2.5, 12), fill=rad(OX, OY + ORAD + 2.5, 12, (0, "#4D050A26"), (1, "#00050A26")))]),
        # --- bridge: gold span (the only gold in the mark) + a fine specular line on its crown
        dict(d=ARCH, stroke=lin(A0[0], 50, A1[0], 50, (0, "#FFC98A1C"), (0.5, "#FFF6D27A"), (1, "#FFE3A82B")), sw=2.4, cap="round"),
        dict(d=ARCH, stroke="#8CFFF4D6", sw=0.5, cap="round", tx=-0.2, ty=-0.45),
        # --- AI node: turquoise glass orb
        dict(d=circle(OX, OY, ORAD + 2.4), fill=rad(OX, OY, ORAD + 2.4, (0.76, "#4D2BB3C0"), (1, "#002BB3C0"))),  # glow
        dict(d=circle(OX, OY, ORAD), fill=rad(OX - 3.5, OY - 4.5, 15, (0, "#FFA8F1F2"), (0.42, "#FF3CC3CE"), (1, "#FF135E85"))),
        dict(d=star8(OX, OY, 5.4), fill=lin(OX, OY - 6, OX, OY + 6, (0, "#FFFFFFFF"), (1, "#E6DDF7F8"))),
        dict(d=circle(OX, OY, 1.45), fill=GOLD),
        dict(d=circle(OX, OY, ORAD - 0.35), stroke=lin(OX - 7, OY - 9, OX + 7, OY + 9, (0, "#CCFFFFFF"), (0.5, "#1AFFFFFF"), (1, "#66BFF6F8")), sw=0.7),
        dict(group=dict(pivotX=sx, pivotY=sy, rotation=-35),
             children=[dict(d=f"M{sx - 4},{sy}a4,1.6 0 1 0 8,0a4,1.6 0 1 0 -8,0Z",
                            fill=lin(sx, sy - 1.6, sx, sy + 1.6, (0, "#CCFFFFFF"), (1, "#00FFFFFF")))]),
        # --- message bubble: frosted glass
        dict(d=BUBBLE, fill=lin(BCX, BY0, BCX, BY1 + 6, (0, "#F7FFFFFF"), (0.6, "#E6EAF6F9"), (1, "#C7C2E9F0"))),
        # refracted turquoise tint pooling at the bottom edge
        dict(d=BUBBLE, fill=lin(BCX, DY, BCX, BY1 + 6, (0, "#002BB3C0"), (1, "#402BB3C0"))),
        dict(d=BUBBLE, stroke=lin(BX0 + 2, BY0 + 1, BX1 - 2, BY1 + 3, (0, "#FFFFFFFF"), (0.55, "#40FFFFFF"), (1, "#8CA9E8EE")), sw=0.6),
        dict(d=BUBBLE_RIM, stroke="#FFFFFFFF", sw=0.9, cap="round"),
        dict(d=DOTS, fill=lin(BCX, DY - 2.5, BCX, DY + 2.5, (0, "#FF2E46B8"), (1, "#FF1A2A78"))),
    ]
    if mode == "mark":  # define the bubble edge on light (parchment) backgrounds
        els.insert(-1, dict(d=BUBBLE, stroke="#4024389C", sw=0.5))
    return els


def background():
    return [
        dict(d="M0,0h108v108h-108z", fill=lin(18, 0, 90, 108, (0, "#FF2D49B8"), (0.5, "#FF1C2C7E"), (1, "#FF0B1440"))),
        # turquoise bloom behind the node + soft top light
        dict(d="M0,0h108v108h-108z", fill=rad(OX, OY - 4, 44, (0, "#402BB3C0"), (1, "#002BB3C0"))),
        dict(d="M0,0h108v108h-108z", fill=rad(30, 14, 60, (0, "#26FFFFFF"), (1, "#00FFFFFF"))),
        # shamseh: a faint medallion (ring + khatam) behind the glyph, decorative only
        dict(d=circle(54, 54, 36), stroke="#12FFFFFF", sw=0.4),
        dict(d=star8(54, 54, 34), stroke="#0AFFFFFF", sw=0.35),
    ]


# ---------- VectorDrawable writer ----------
def _color(c):
    return c


def _grad_xml(attr, g, ind):
    p = ind + "    "
    if g["type"] == "linear":
        a = f'android:type="linear" android:startX="{g["x1"]:g}" android:startY="{g["y1"]:g}" android:endX="{g["x2"]:g}" android:endY="{g["y2"]:g}"'
    else:
        a = f'android:type="radial" android:centerX="{g["cx"]:g}" android:centerY="{g["cy"]:g}" android:gradientRadius="{g["r"]:g}"'
    items = "".join(f'{p}        <item android:offset="{o:g}" android:color="{c}"/>\n' for o, c in g["stops"])
    return (f'{ind}    <aapt:attr name="{attr}">\n{p}    <gradient {a}>\n{items}{p}    </gradient>\n'
            f'{ind}    </aapt:attr>\n')


def _el_xml(e, ind):
    if "group" in e:
        g = e["group"]
        attrs = " ".join(f'android:{k}="{v:g}"' for k, v in g.items())
        inner = "".join(_el_xml(c, ind + "    ") for c in e["children"])
        return f"{ind}<group {attrs}>\n{inner}{ind}</group>\n"
    if "tx" in e:  # offset copy -> wrap in a translate group
        e2 = {k: v for k, v in e.items() if k not in ("tx", "ty")}
        return _el_xml(dict(group=dict(translateX=e["tx"], translateY=e["ty"]), children=[e2]), ind)
    attrs = [f'android:pathData="{e["d"]}"']
    kids = ""
    f, s = e.get("fill"), e.get("stroke")
    if isinstance(f, str):
        attrs.append(f'android:fillColor="{f}"')
    elif f:
        kids += _grad_xml("android:fillColor", f, ind)
    if s:
        attrs.append(f'android:strokeWidth="{e["sw"]:g}"')
        if isinstance(s, str):
            attrs.append(f'android:strokeColor="{s}"')
        else:
            kids += _grad_xml("android:strokeColor", s, ind)
        if e.get("cap"):
            attrs.append(f'android:strokeLineCap="{e["cap"]}"')
    if e.get("evenodd"):
        attrs.append('android:fillType="evenOdd"')
    a = ("\n" + ind + "    ").join(attrs)
    if kids:
        return f"{ind}<path\n{ind}    {a}>\n{kids}{ind}</path>\n"
    return f"{ind}<path\n{ind}    {a}/>\n"


def vector(els, size_dp, viewport=108, wrap=None):
    body = "".join(_el_xml(e, "    ") for e in els)
    if wrap:
        body = _el_xml(dict(group=wrap, children=els), "    ")
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Generated by design/logo/tools/gen.py - edit there, not here. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            f'    android:width="{size_dp}dp" android:height="{size_dp}dp"\n'
            f'    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">\n'
            f"{body}</vector>\n")


ADAPTIVE = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/ic_launcher_background" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n'
            '</adaptive-icon>\n')

# glyph spans ~x 18.5..89.5, y 43.5..77.6. Centre it OPTICALLY, not by bbox: the area-weighted
# centre of bubble (~59.96) + orb (57.0) is ~59.2; counting the thin arch lightly -> 59.0.
OPTICAL_Y = 59.0
CENTER = dict(pivotX=54, pivotY=54, scaleX=0.9, scaleY=0.9, translateX=0.6, translateY=-0.9 * (OPTICAL_Y - 54))


def stat_icon():
    """24dp status-bar silhouette: same bubble + node + arch, solid white."""
    W = "#FFFFFFFF"
    els = [
        # 24px is too small for the dots / khatam cut-outs: solid silhouettes, heavier span
        dict(d=BUBBLE, fill=W),
        dict(d=circle(OX, OY, ORAD), fill=W),
        dict(d=ARCH, stroke=W, sw=4.2, cap="round"),
    ]
    # glyph is ~71 wide; fill ~95% of the 108 viewport
    return vector(els, 24, wrap=dict(pivotX=54, pivotY=54, scaleX=1.45, scaleY=1.45, translateY=-9.4))


def write_all():
    d = RES / "drawable"
    d.mkdir(parents=True, exist_ok=True)
    (RES / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
    (d / "ic_launcher_background.xml").write_text(vector(background(), 108), encoding="utf-8")
    (d / "ic_launcher_foreground.xml").write_text(vector(glyph(), 108, wrap=CENTER), encoding="utf-8")
    (d / "ic_launcher_monochrome.xml").write_text(vector(glyph("mono"), 108, wrap=CENTER), encoding="utf-8")
    # brand mark: glyph alone, cropped tight, 48dp
    (d / "ic_brand_mark.xml").write_text(
        vector(glyph("mark"), 48, wrap=dict(pivotX=54, pivotY=54, scaleX=1.5, scaleY=1.5, translateY=-9.75)),
        encoding="utf-8")
    # splash: lapis disc (r=36, the visible 2/3 circle) + glyph scaled to sit well inside it
    splash = [dict(d=circle(54, 54, 36), fill=lin(30, 22, 78, 90, (0, "#FF2D49B8"), (0.55, "#FF1C2C7E"), (1, "#FF0B1440"))),
              dict(d=circle(54, 54, 36), fill=rad(74, 50, 30, (0, "#402BB3C0"), (1, "#002BB3C0"))),
              dict(group=dict(pivotX=54, pivotY=54, scaleX=0.84, scaleY=0.84, translateY=-0.84 * (OPTICAL_Y - 54)), children=glyph())]
    (d / "ic_splash.xml").write_text(vector(splash, 108), encoding="utf-8")
    (d / "ic_stat_bridge.xml").write_text(stat_icon(), encoding="utf-8")
    for n in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (RES / "mipmap-anydpi-v26" / n).write_text(ADAPTIVE, encoding="utf-8")


if __name__ == "__main__":
    write_all()
    print("written to", RES)
