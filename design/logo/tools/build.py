"""gen vectors -> SVG masters -> PNGs (Edge headless) -> preview sheet.

Run: python design/logo/tools/build.py
"""
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).parent))
import gen  # noqa: E402
import vd2svg  # noqa: E402

ROOT = gen.ROOT
LOGO = ROOT / "design/logo"
OUT = LOGO / "renders"
DIST = ROOT / "dist"
DRAW = gen.RES / "drawable"
EDGE = r"C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
TMP = Path(tempfile.mkdtemp(prefix="pb_logo_"))


def svg_of(name, prefix):
    s = vd2svg.convert(DRAW / f"{name}.xml")
    s = s.replace('id="', f'id="{prefix}').replace("url(#", f"url(#{prefix}")
    return s


def inner(svg):
    """strip outer <svg ...> so it can be nested."""
    m = re.match(r"<svg [^>]*>(.*)</svg>$", svg, re.S)
    return m.group(1)


def master(crop=(16, 16, 76), with_bg=True, fg="ic_launcher_foreground"):
    x, y, w = crop
    s = 1024 / w
    body = (inner(svg_of("ic_launcher_background", "b")) if with_bg else "") + inner(svg_of(fg, "f"))
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">\n'
            f'<title>Phone Bridge / پل گوشی</title>\n'
            f'<g transform="scale({s:.5f}) translate({-x},{-y})">{body}</g>\n</svg>\n')


def render(svg_text, px, name):
    svg = TMP / f"{name}.svg"
    svg.write_text(svg_text, encoding="utf-8")
    html = TMP / f"{name}.html"
    html.write_text(f'<html><body style="margin:0;background:transparent">'
                    f'<img src="{svg.name}" style="display:block;width:{px}px;height:{px}px"></body></html>',
                    encoding="utf-8")
    png = OUT / f"{name}.png"
    r = subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                        f"--user-data-dir={TMP / 'prof'}", "--default-background-color=00000000",
                        f"--window-size={px},{px}", f"--screenshot={png}", html.as_uri()],
                       capture_output=True, text=True, timeout=120)
    if not png.exists():
        raise RuntimeError(f"edge render failed for {name}: {r.stderr[-500:]}")
    im = Image.open(png).convert("RGBA")
    return im.crop((0, 0, px, px))


def squircle_mask(n, exp=5.0):
    m = Image.new("L", (n * 4, n * 4), 0)
    px = m.load()
    c = n * 2
    for yy in range(n * 4):
        for xx in range(n * 4):
            if abs((xx - c) / c) ** exp + abs((yy - c) / c) ** exp <= 1:
                px[xx, yy] = 255
    return m.resize((n, n), Image.LANCZOS)


def circle_mask(n):
    m = Image.new("L", (n * 4, n * 4), 0)
    ImageDraw.Draw(m).ellipse((0, 0, n * 4 - 1, n * 4 - 1), fill=255)
    return m.resize((n, n), Image.LANCZOS)


def rrect_mask(n, r=0.22):
    m = Image.new("L", (n * 4, n * 4), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, n * 4 - 1, n * 4 - 1), radius=int(n * 4 * r), fill=255)
    return m.resize((n, n), Image.LANCZOS)


def masked(im, mask):
    out = im.copy()
    out.putalpha(ImageChops.multiply(im.getchannel("A"), mask))
    return out


def tint(im, rgb):
    """recolour a white silhouette (themed-icon simulation)."""
    a = im.getchannel("A")
    out = Image.new("RGBA", im.size, rgb + (255,))
    out.putalpha(a)
    return out


def validate():
    """well-formed + only supported VectorDrawable attributes (vd2svg raises otherwise)."""
    names = ["ic_launcher_background", "ic_launcher_foreground", "ic_launcher_monochrome",
             "ic_brand_mark", "ic_splash", "ic_stat_bridge"]
    for n in names:
        ET.parse(DRAW / f"{n}.xml")
        vd2svg.convert(DRAW / f"{n}.xml")
    for n in ("ic_launcher.xml", "ic_launcher_round.xml"):
        r = ET.parse(gen.RES / "mipmap-anydpi-v26" / n).getroot()
        assert r.tag == "adaptive-icon" and len(r) == 3, n
    # monochrome & stat icon must be pure white, no gradients
    for n in ("ic_launcher_monochrome", "ic_stat_bridge"):
        t = (DRAW / f"{n}.xml").read_text()
        assert "gradient" not in t and set(re.findall(r'Color="(#\w+)"', t)) == {"#FFFFFFFF"}, n
    print("validate: OK", len(names) + 2, "files")


def check_safe_zone(im, r_units=33.0):
    """glyph (4px per dp) must stay inside the 66dp safe circle; print its centroid."""
    a = im.getchannel("A")
    w = a.width
    k = w / 108
    px = a.load()
    out = s = sx = sy = 0
    for y in range(w):
        for x in range(w):
            v = px[x, y]
            if v > 8 and ((x + .5) / k - 54) ** 2 + ((y + .5) / k - 54) ** 2 > r_units ** 2:
                out += 1
            s += v; sx += v * x; sy += v * y
    print(f"glyph centroid dp=({sx / s / k:.2f},{sy / s / k:.2f}) bbox_dp={[round(b / k, 1) for b in a.getbbox()]} "
          f"pixels outside safe circle={out}")
    assert out == 0, "glyph leaves the 66dp safe zone"


def main():
    gen.write_all()
    validate()
    OUT.mkdir(parents=True, exist_ok=True)
    DIST.mkdir(exist_ok=True)
    (LOGO / "logo.svg").write_text(master(), encoding="utf-8")
    (LOGO / "logo-foreground.svg").write_text(master(with_bg=False), encoding="utf-8")

    logo = render(master(), 1024, "logo")
    logo.save(DIST / "logo.png")
    masked(logo.resize((512, 512), Image.LANCZOS), circle_mask(512)).save(DIST / "logo-round-512.png")

    # Android adaptive composite: 108dp canvas, launcher shows centre 72dp
    full = render(master(crop=(0, 0, 108)), 432, "adaptive_full")
    launcher = full.crop((72, 72, 360, 360))  # centre 72/108
    mono_full = render(master(crop=(0, 0, 108), with_bg=False, fg="ic_launcher_monochrome"), 432, "mono")
    check_safe_zone(mono_full)
    mono = mono_full.crop((72, 72, 360, 360))
    mark = render(vd2svg.convert(DRAW / "ic_brand_mark.xml"), 192, "brand_mark")
    splash = render(vd2svg.convert(DRAW / "ic_splash.xml"), 288, "splash")
    stat = render(vd2svg.convert(DRAW / "ic_stat_bridge.xml"), 96, "stat")

    # ---------- preview sheet ----------
    W, H = 1800, 1180
    sheet = Image.new("RGBA", (W, H), (246, 241, 230, 255))
    d = ImageDraw.Draw(sheet)
    d.rectangle((W // 2, 0, W, H), fill=(14, 18, 34, 255))
    try:
        f = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 20)
    except OSError:
        f = ImageFont.load_default()

    def put(im, x, y, label, dark=False):
        sheet.alpha_composite(im, (x, y))
        d.text((x, y + im.height + 6), label, font=f, fill=(200, 205, 220) if dark else (60, 60, 70))

    big = logo.resize((440, 440), Image.LANCZOS)
    put(masked(big, squircle_mask(440)), 40, 40, "logo.png / squircle")
    put(masked(big, squircle_mask(440)), W // 2 + 40, 40, "on dark", True)
    for i, (m, lab) in enumerate([(circle_mask, "circle"), (squircle_mask, "squircle"), (rrect_mask, "rounded")]):
        im = masked(launcher.resize((180, 180), Image.LANCZOS), m(180))
        put(im, 520 + (i % 2) * 200, 40 + (i // 2) * 230, f"adaptive {lab}")
        put(im, W // 2 + 520 + (i % 2) * 200, 40 + (i // 2) * 230, f"adaptive {lab}", True)
    y = 540
    for side, dark in ((0, False), (W // 2, True)):
        x = side + 40
        for s in (96, 72, 48, 36):
            put(masked(launcher.resize((s, s), Image.LANCZOS), circle_mask(s)), x, y + (96 - s), f"{s}px", dark)
            x += s + 34
        # themed (monochrome) icons: Android tints silhouette over a tonal disc
        bgc, fgc = ((215, 226, 255), (16, 32, 96)) if not dark else ((40, 48, 80), (190, 205, 255))
        disc = Image.new("RGBA", (96, 96), bgc + (255,))
        disc.alpha_composite(tint(mono.resize((96, 96), Image.LANCZOS), fgc))
        put(masked(disc, circle_mask(96)), x + 20, y, "themed 96", dark)
        disc48 = disc.resize((48, 48), Image.LANCZOS)
        put(masked(disc48, circle_mask(48)), x + 150, y + 48, "themed 48", dark)
    y = 770
    for side, dark in ((0, False), (W // 2, True)):
        put(mark.resize((120, 120), Image.LANCZOS), side + 40, y, "ic_brand_mark", dark)
        put(mark.resize((40, 40), Image.LANCZOS), side + 190, y + 80, "40px", dark)
        put(splash.resize((220, 220), Image.LANCZOS), side + 290, y, "ic_splash (108dp)", dark)
        put(stat.resize((24, 24), Image.LANCZOS), side + 560, y + 30, "stat 24", dark)
        put(stat.resize((72, 72), Image.LANCZOS), side + 640, y, "stat 72", dark)
    sheet.convert("RGB").save(LOGO / "preview.png")
    print("ok:", DIST / "logo.png", LOGO / "preview.png")


if __name__ == "__main__":
    main()
