"""Draws Ember Client's title-screen logo (replaces Minecraft's title textures)."""
import random, sys
from PIL import Image, ImageDraw, ImageFilter

FONT = {
    'E': ["11111","10000","10000","11110","10000","10000","11111"],
    'M': ["10001","11011","10101","10101","10001","10001","10001"],
    'B': ["11110","10001","10001","11110","10001","10001","11110"],
    'R': ["11110","10001","10001","11110","10100","10010","10001"],
    'C': ["01111","10000","10000","10000","10000","10000","01111"],
    'L': ["10000","10000","10000","10000","10000","10000","11111"],
    'I': ["111","010","010","010","010","010","111"],
    'N': ["10001","11001","10101","10011","10001","10001","10001"],
    'T': ["11111","00100","00100","00100","00100","00100","00100"],
}

def cells(text):
    x, out = 0, []
    for ch in text:
        g = FONT[ch]
        for r, row in enumerate(g):
            for c, v in enumerate(row):
                if v == '1': out.append((x + c, r))
        x += len(g[0]) + 1
    return out, x - 1

def lerp(a, b, t): return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))

def logo(path, W=1024, H=256, unit=25, top=6):
    rnd = random.Random(26)
    pts, cols = cells("EMBER")
    left = (W - cols * unit) // 2
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    depth = unit // 2
    # extruded side (dark ember) and outline
    for k in range(depth, 0, -1):
        for (cx, cy) in pts:
            x, y = left + cx * unit + k, top + cy * unit + k
            shade = lerp((58, 16, 10), (110, 30, 12), 1 - k / depth)
            d.rectangle([x, y, x + unit - 1, y + unit - 1], fill=shade + (255,))
    # face: lava gradient, block noise, bevel
    rows = 7 * unit
    for (cx, cy) in pts:
        x0, y0 = left + cx * unit, top + cy * unit
        for yy in range(unit):
            t = (cy * unit + yy) / rows
            base = lerp((255, 214, 92), (255, 122, 38), min(1, t * 1.4)) if t < 0.7 else lerp((255, 122, 38), (196, 44, 18), (t - 0.7) / 0.3)
            for xx in range(0, unit, 4):
                n = rnd.randint(-14, 14)
                col = tuple(max(0, min(255, c + n)) for c in base)
                d.rectangle([x0 + xx, y0 + yy, x0 + min(unit, xx + 4) - 1, y0 + yy], fill=col + (255,))
        # bevel: light top/left, dark bottom/right inside each block
        b = max(2, unit // 8)
        d.rectangle([x0, y0, x0 + unit - 1, y0 + b - 1], fill=(255, 241, 201, 140))
        d.rectangle([x0, y0, x0 + b - 1, y0 + unit - 1], fill=(255, 241, 201, 90))
        d.rectangle([x0, y0 + unit - b, x0 + unit - 1, y0 + unit - 1], fill=(120, 24, 8, 120))
    # dark outline around the whole word
    a = img.split()[3]
    outline = a.filter(ImageFilter.MaxFilter(9))
    bg = Image.new("RGBA", (W, H), (24, 8, 6, 0))
    bg.putalpha(outline.point(lambda v: 255 if v > 0 else 0))
    bg = Image.composite(Image.new("RGBA", (W, H), (26, 10, 8, 255)), Image.new("RGBA", (W, H), (0, 0, 0, 0)), bg.split()[3])
    bg.alpha_composite(img)
    bg.save(path)

def edition(path, W=512, H=64, unit=6):
    pts, cols = cells("CLIENT")
    left = (W - cols * unit) // 2
    top = (H - 7 * unit) // 2
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for (cx, cy) in pts:
        x, y = left + cx * unit, top + cy * unit
        d.rectangle([x + 3, y + 3, x + unit + 2, y + unit + 2], fill=(40, 14, 8, 255))
    for (cx, cy) in pts:
        x, y = left + cx * unit, top + cy * unit
        d.rectangle([x, y, x + unit - 1, y + unit - 1], fill=(243, 236, 228, 255))
    a = img.split()[3].filter(ImageFilter.MaxFilter(5))
    out = Image.composite(Image.new("RGBA", (W, H), (26, 10, 8, 255)), Image.new("RGBA", (W, H), (0, 0, 0, 0)), a)
    out.alpha_composite(img)
    out.save(path)

if __name__ == "__main__":
    out = sys.argv[1]
    logo(out + "/minecraft.png")
    edition(out + "/edition.png")
