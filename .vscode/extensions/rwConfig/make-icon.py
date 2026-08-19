"""Regenerate `icon.png`. No dependencies: it rasterises and writes the PNG itself."""
import zlib, struct, math

def png(path, pix, w, h):
    raw = b''.join(b'\x00' + bytes(pix[y*w*4:(y+1)*w*4]) for y in range(h))
    def chunk(t, d):
        c = struct.pack('>I', len(d)) + t + d
        return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    open(path,'wb').write(
        b'\x89PNG\r\n\x1a\n'
        + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
        + chunk(b'IDAT', zlib.compress(raw, 9))
        + chunk(b'IEND', b''))

def rrect(px, py, cx, cy, hw, hh, r):
    dx = abs(px-cx) - hw + r
    dy = abs(py-cy) - hh + r
    return math.hypot(max(dx,0), max(dy,0)) + min(max(dx,dy),0) - r

def seg(px, py, ax, ay, bx, by, t):
    vx, vy = bx-ax, by-ay
    wx, wy = px-ax, py-ay
    L = vx*vx + vy*vy
    u = 0 if L == 0 else max(0, min(1, (wx*vx + wy*vy)/L))
    return math.hypot(wx-u*vx, wy-u*vy) - t/2

def over(dst, src, a):
    return tuple(src[i]*a + dst[i]*(1-a) for i in range(3))

def cov(d, px=1.0):
    return max(0.0, min(1.0, 0.5 - d/px))

S = 4                      # supersample factor
def render(size, spec):
    W = size*S
    buf = bytearray(W*W*4)
    for y in range(W):
        for x in range(W):
            p, q = (x+0.5)/S, (y+0.5)/S
            rgb, a = spec(p, q, size)
            i = (y*W + x)*4
            buf[i]   = max(0,min(255,int(rgb[0]*255+0.5)))
            buf[i+1] = max(0,min(255,int(rgb[1]*255+0.5)))
            buf[i+2] = max(0,min(255,int(rgb[2]*255+0.5)))
            buf[i+3] = max(0,min(255,int(a*255+0.5)))
    # box downsample
    out = bytearray(size*size*4)
    for y in range(size):
        for x in range(size):
            acc = [0.0]*4
            for dy in range(S):
                for dx in range(S):
                    i = ((y*S+dy)*W + (x*S+dx))*4
                    acc[0]+=buf[i]; acc[1]+=buf[i+1]; acc[2]+=buf[i+2]; acc[3]+=buf[i+3]
            j = (y*size+x)*4
            for k in range(4):
                out[j+k] = int(acc[k]/(S*S) + 0.5)
    return out

def hexc(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i+2],16)/255 for i in (0,2,4))

# ---------------------------------------------------------------- the icon

SLATE, AMBER = hexc('#4E6076'), hexc('#F5A524')

def gear(px, py, cx, cy, r_body, r_tip, tooth_w, teeth, round_r, tooth_in):
    """A cog, by folding the plane into one tooth's sector and drawing that tooth."""
    dx, dy = px - cx, py - cy
    r = math.hypot(dx, dy)
    if r < 1e-6:
        return -r_body
    a = math.atan2(dy, dx)
    sector = 2 * math.pi / teeth
    a2 = a - round(a / sector) * sector
    qx, qy = r * math.cos(a2), r * math.sin(a2)
    # Teeth are rooted inside the disc, not butted against it: rounding pulls a
    # tooth's inner corners back, and a tooth that merely touches the rim reads
    # as a separate blob floating beside the gear.
    mid = (tooth_in + r_tip) / 2
    bx = abs(qx - mid) - (r_tip - tooth_in) / 2 + round_r
    by = abs(qy) - tooth_w / 2 + round_r
    tooth = math.hypot(max(bx, 0), max(by, 0)) + min(max(bx, by), 0) - round_r
    return min(r - r_body, tooth)

def spec(x, y, N):
    """A cog with a check on it. No tile: the glyph stands on the theme behind it,
    so it is drawn in two mid tones that hold up on a light or a dark ground."""
    u = lambda v: v * N / 128.0
    # Teeth reach well inside the body, or the rounding parts them from it.
    # Eight is what survives being shrunk to the sidebar: ten blurs into a fringe.
    g = gear(x, y, u(64), u(64), u(46), u(58), u(20), 8, u(3), u(36))
    # Hub. A hole of half the body's diameter is all but covered by the check, so
    # it reads as solid; this is a little wider, and wider still starts to thin
    # the rim past what survives being drawn at 16 pixels.
    g = max(g, u(27) - math.hypot(x - u(64), y - u(64)))
    c = min(seg(x, y, u(38), u(64), u(58), u(84), u(14)),
            seg(x, y, u(58), u(84), u(94), u(42), u(14)))
    ga, ca = cov(g, u(1)), cov(c, u(1))
    a = max(ga, ca)
    if a <= 0.0:
        return SLATE, 0.0
    return tuple((AMBER[i]*ca + SLATE[i]*ga*(1-ca)) / a for i in range(3)), a

if __name__ == '__main__':
    png('icon.png', render(256, spec), 256, 256)
    print('icon.png written')
