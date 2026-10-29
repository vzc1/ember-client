"""Draws Ember Client's title-screen logo (replaces Minecraft's title textures).

Pixel-art letters: dark basalt faces with glowing lava cracks, a deep extruded
side, black outline and a soft ember glow, drawn at Minecraft-logo pixel size."""
import random, sys
from PIL import Image, ImageDraw, ImageFilter

BIG = {
    'E': ["111111", "11....", "11....", "11111.", "11....", "11....", "111111"],
    'M': ["11...11", "111.111", "1111111", "11.1.11", "11...11", "11...11", "11...11"],
    'B': ["11111.", "11..11", "11..11", "11111.", "11..11", "11..11", "11111."],
    'R': ["11111.", "11..11", "11..11", "11111.", "11.11.", "11..11", "11..11"],
}
SMALL = {
    'C': ["0111", "1000", "1000", "1000", "0111"],
    'L': ["1000", "1000", "1000", "1000", "1111"],
    'I': ["111", "010", "010", "010", "111"],
    'E': ["1111", "1000", "1110", "1000", "1111"],
    'N': ["1001", "1101", "1011", "1001", "1001"],
    'T': ["11111", "00100", "00100", "00100", "00100"],
}

def mask(text, font, gap):
    x, out = 0, set()
    for ch in text:
        g = font[ch]
        for r, row in enumerate(g):
            for c, v in enumerate(row):
                if v == '1': out.add((x + c, r))
        x += len(g[0]) + gap
    return out, x - gap, len(font[text[0]])

def expand(cells, sub):
    """Each glyph cell becomes sub x sub texture pixels."""
    return {(cx * sub + i, cy * sub + j) for (cx, cy) in cells for i in range(sub) for j in range(sub)}

def put(img, x, y, p, col):
    ImageDraw.Draw(img).rectangle([x * p, y * p, x * p + p - 1, y * p + p - 1], fill=col)

def logo(path, W=1024, H=256):
    rnd = random.Random(7)
    cells, cols, rows = mask("EMBER", BIG, 1)
    sub, p = 4, 6                       # 4x4 texture pixels per glyph cell, 6 image px per texture pixel
    px = expand(cells, sub)
    gw, gh = cols * sub, rows * sub
    ox = (W // p - gw) // 2 - 1
    oy = 2
    face = {(x + ox, y + oy) for (x, y) in px}
    depth = 3
    side = {(x + d, y + d) for (x, y) in face for d in range(1, depth + 1)} - face
    body = face | side
    outline = {(x + dx, y + dy) for (x, y) in body for dx in (-1, 0, 1) for dy in (-1, 0, 1)} - body

    # lava cracks: short random walks inside the face
    cracks = set()
    for _ in range(26):
        x, y = rnd.choice(sorted(face))
        for _ in range(rnd.randint(3, 9)):
            if (x, y) in face: cracks.add((x, y))
            x += rnd.choice((-1, 0, 1)); y += rnd.choice((0, 1))

    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    for (x, y) in outline: put(img, x, y, p, (12, 6, 8, 255))
    for (x, y) in side:
        t = min(1, (y - oy) / (gh + depth))
        put(img, x, y, p, (int(70 - 30 * t), int(22 - 10 * t), int(16 - 6 * t), 255))
    for (x, y) in face:
        t = (y - oy) / gh
        n = rnd.randint(-10, 10)
        base = (52 + n - int(14 * t), 38 + n - int(12 * t), 44 + n - int(10 * t))
        if (x, y - 1) not in face: base = (118 + n, 84 + n, 78 + n)          # lit top edge
        elif (x - 1, y) not in face: base = (86 + n, 62 + n, 62 + n)         # lit left edge
        put(img, x, y, p, base + (255,))
    for (x, y) in cracks:
        hot = rnd.random() < 0.45
        put(img, x, y, p, (255, 214, 96, 255) if hot else (255, 106, 32, 255))

    # ember glow behind everything
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    gl = ImageDraw.Draw(glow)
    for (x, y) in body:
        gl.rectangle([x * p, y * p, x * p + p - 1, y * p + p - 1], fill=(255, 90, 31, 120))
    glow = glow.filter(ImageFilter.GaussianBlur(10))
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    out.alpha_composite(glow)
    out.alpha_composite(img)
    out.save(path)

def edition(path, W=512, H=64):
    cells, cols, rows = mask("CLIENT", SMALL, 1)
    p = 7
    ox = (W // p - cols) // 2
    oy = (H // p - rows) // 2
    face = {(x + ox, y + oy) for (x, y) in cells}
    side = {(x + 1, y + 1) for (x, y) in face} - face
    body = face | side
    outline = {(x + dx, y + dy) for (x, y) in body for dx in (-1, 0, 1) for dy in (-1, 0, 1)} - body
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    for (x, y) in outline: put(img, x, y, p, (12, 6, 8, 255))
    for (x, y) in side: put(img, x, y, p, (120, 34, 14, 255))
    for (x, y) in face:
        t = (y - oy) / rows
        put(img, x, y, p, (255, int(196 - 90 * t), int(80 - 50 * t), 255))
    img.save(path)

if __name__ == "__main__":
    out = sys.argv[1]
    logo(out + "/minecraft.png")
    edition(out + "/edition.png")
