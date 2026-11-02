"""Convert an Android VectorDrawable XML to SVG (subset used by our icons).

Raises on any element/attribute it does not understand, so an unsupported
attribute in the vectors fails loudly instead of silently rendering wrong.
"""
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"
AAPT = "{http://schemas.android.com/aapt}"
PATH_ATTRS = {"pathData", "fillColor", "fillAlpha", "strokeColor", "strokeWidth", "strokeAlpha",
              "strokeLineCap", "strokeLineJoin", "fillType", "name"}
GROUP_ATTRS = {"translateX", "translateY", "scaleX", "scaleY", "rotation", "pivotX", "pivotY", "name"}


def col(c):
    c = c.lstrip("#")
    if len(c) == 6:
        c = "FF" + c
    a, rgb = int(c[:2], 16) / 255, c[2:]
    return f"#{rgb}", a


def _attrs(el, allowed):
    out = {}
    for k, v in el.attrib.items():
        if not k.startswith(A):
            raise ValueError(f"unexpected attribute {k} on {el.tag}")
        n = k[len(A):]
        if n not in allowed:
            raise ValueError(f"unsupported attribute android:{n} on {el.tag}")
        out[n] = v
    return out


class Conv:
    def __init__(self):
        self.defs, self.n = [], 0

    def grad(self, g):
        self.n += 1
        gid = f"g{self.n}"
        ga = {k[len(A):]: v for k, v in g.attrib.items()}
        stops = "".join(
            '<stop offset="{}" stop-color="{}" stop-opacity="{:.3f}"/>'.format(
                i.get(A + "offset"), *col(i.get(A + "color"))) for i in g.findall("item"))
        if ga["type"] == "linear":
            self.defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{ga["startX"]}" '
                             f'y1="{ga["startY"]}" x2="{ga["endX"]}" y2="{ga["endY"]}">{stops}</linearGradient>')
        elif ga["type"] == "radial":
            self.defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{ga["centerX"]}" '
                             f'cy="{ga["centerY"]}" r="{ga["gradientRadius"]}">{stops}</radialGradient>')
        else:
            raise ValueError("gradient type " + ga["type"])
        return f"url(#{gid})"

    def path(self, el):
        a = _attrs(el, PATH_ATTRS)
        for aa in el.findall(AAPT + "attr"):
            name = aa.get("name").split(":")[1]
            a[name] = self.grad(aa.find("gradient"))
        s = [f'd="{a["pathData"]}"']
        f = a.get("fillColor")
        if f and f.startswith("#"):
            c, op = col(f)
            s.append(f'fill="{c}" fill-opacity="{op * float(a.get("fillAlpha", 1)):.3f}"')
        elif f:
            s.append(f'fill="{f}" fill-opacity="{a.get("fillAlpha", 1)}"')
        else:
            s.append('fill="none"')
        st = a.get("strokeColor")
        if st:
            if st.startswith("#"):
                c, op = col(st)
                s.append(f'stroke="{c}" stroke-opacity="{op * float(a.get("strokeAlpha", 1)):.3f}"')
            else:
                s.append(f'stroke="{st}" stroke-opacity="{a.get("strokeAlpha", 1)}"')
            s.append(f'stroke-width="{a.get("strokeWidth", 0)}" stroke-linecap="{a.get("strokeLineCap", "butt")}" '
                     f'stroke-linejoin="{a.get("strokeLineJoin", "miter")}"')
        s.append(f'fill-rule="{"evenodd" if a.get("fillType") == "evenOdd" else "nonzero"}"')
        return "<path " + " ".join(s) + "/>"

    def group(self, el):
        a = {k: float(v) for k, v in _attrs(el, GROUP_ATTRS).items() if k != "name"}
        px, py = a.get("pivotX", 0), a.get("pivotY", 0)
        t = (f'translate({a.get("translateX", 0) + px},{a.get("translateY", 0) + py}) rotate({a.get("rotation", 0)}) '
             f'scale({a.get("scaleX", 1)},{a.get("scaleY", 1)}) translate({-px},{-py})')
        return f'<g transform="{t}">' + self.children(el) + "</g>"

    def children(self, el):
        out, clip = [], None
        for c in el:
            if c.tag == "path":
                out.append(self.path(c))
            elif c.tag == "group":
                out.append(self.group(c))
            elif c.tag == "clip-path":
                self.n += 1
                clip = f"c{self.n}"
                self.defs.append(f'<clipPath id="{clip}"><path d="{c.get(A + "pathData")}"/></clipPath>')
                out.append(f'<g clip-path="url(#{clip})">')
            else:
                raise ValueError("unsupported element " + c.tag)
        if clip:
            out.append("</g>")
        return "".join(out)


def convert(xml_path):
    root = ET.parse(xml_path).getroot()
    assert root.tag == "vector", root.tag
    vw, vh = root.get(A + "viewportWidth"), root.get(A + "viewportHeight")
    c = Conv()
    body = c.children(root)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vw} {vh}">'
            f'<defs>{"".join(c.defs)}</defs>{body}</svg>')


if __name__ == "__main__":
    print(convert(sys.argv[1]))
