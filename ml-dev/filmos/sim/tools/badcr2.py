"""Broken CR2 files, for the darkroom to turn down: each is a good one
(mkcr2's) with one thing wrong in its RAW's lossless JPEG header, as a
damaged card or file can have. The darkroom must say so, and not read or
write outside the header's segments and its tables (cardtest.sh).

    python3 badcr2.py good.CR2 outdir

Writes outdir/<name>.CR2 for each below and outdir/LIST.txt: a line for
each, its name and the message the darkroom must give. Two must still
develop ("ok"): the header taken apart and put back as it was, and every
table given the codes it left free, so that its codes fill their lengths.
"""
import struct
import sys
from pathlib import Path

WINDOW = 2048   # the bytes of the header the darkroom reads at once (fos_dev.c, parse_ljpeg)
DHT, SOF3, DRI, SOS, COM = 0xC4, 0xC3, 0xDD, 0xDA, 0xFE


def raw_strip(b):
    """the RAW's offset and length, and where its length is written (the RAW IFD's 0x0111, 0x0117)"""
    ifd = struct.unpack_from("<I", b, 12)[0]
    off = ln = at = None
    for i in range(struct.unpack_from("<H", b, ifd)[0]):
        e = ifd + 2 + 12 * i
        tag = struct.unpack_from("<H", b, e)[0]
        if tag == 0x0111:
            off = struct.unpack_from("<I", b, e + 8)[0]
        elif tag == 0x0117:
            ln, at = struct.unpack_from("<I", b, e + 8)[0], e + 8
    return off, ln, at


def split(raw):
    """the segments, the scan's header the last, as [tag, body]; and the entropy data after them"""
    if raw[:2] != b"\xff\xd8":
        sys.exit("the RAW is not a lossless JPEG")
    segs, p = [], 2
    while True:
        tag, n = raw[p + 1], raw[p + 2] << 8 | raw[p + 3]
        segs.append([tag, bytearray(raw[p + 4:p + 2 + n])])
        p += 2 + n
        if tag == SOS:
            return segs, raw[p:]


def join(segs):
    out = bytearray(b"\xff\xd8")
    for tag, body in segs:
        out += bytes([0xFF, tag]) + struct.pack(">H", len(body) + 2) + body
    return out


def dhts(segs):
    return [body for tag, body in segs if tag == DHT]


def body_of(segs, tag):
    return next(body for t, body in segs if t == tag)


# mkcr2 writes a table to a segment: its number, its 16 counts (codes of
# each length), its values (the difference sizes)

def codes_overflow(segs):
    """more codes of a length than it has room for (fifteen 1-bit codes): the
    lookup the darkroom fills for them would run past its end"""
    t = dhts(segs)[0]
    total = sum(t[1:17])
    t[1:17] = bytes([total] + [0] * 15)
    return segs


def codes_one_over(segs):
    """one code more than there is room for, at 9 bits, the lookup's own
    length: a code of each length from 1 to 8 leaves room for two of 9 bits,
    and it has three (the third's would be written one past the lookup's
    end). With two, its codes would fill their lengths, as complete's do"""
    t = dhts(segs)[0]
    t[1:] = bytes([1] * 8 + [3] + [0] * 7 + list(range(11)))
    return segs


def values_cut(segs):
    """the segment ends before the table's last 5 values"""
    del dhts(segs)[0][-5:]
    return segs


def counts_cut(segs):
    """the segment ends in the middle of the 16 counts"""
    del dhts(segs)[0][9:]
    return segs


def values_over_256(segs):
    """300 values (codes 15 and 16 bits long, room for all): no table holds more than 256"""
    t = dhts(segs)[0]
    t[1:] = bytes([0] * 14 + [45, 255] + [i % 15 for i in range(300)])
    return segs


def symbol_17(segs):
    """a value no difference has: they go up to 16"""
    dhts(segs)[0][-1] = 17
    return segs


def window_end(segs):
    """the second table's segment ends where the bytes read at once do, 5 of
    its values short: they would be read past those bytes"""
    t0, t1 = dhts(segs)
    del t1[-5:]
    fill = WINDOW - 2 - (4 + len(t0)) - (4 + len(t1)) - 4
    rest = [s for s in segs if s[0] != DHT]
    return [[DHT, t0], [COM, bytearray(fill)], [DHT, t1]] + rest


def sos_components(segs):
    """the scan's header names 255 components, and holds 2"""
    body_of(segs, SOS)[0] = 255
    return segs


def sof_short(segs):
    """the frame's header ends after the number of components"""
    del body_of(segs, SOF3)[6:]
    return segs


def dri_short(segs):
    """a restart interval with no interval in it"""
    return [[DRI, bytearray()]] + segs


def same(segs):
    return segs


def complete(segs):
    """each table given the codes it leaves free at its longest length (the
    all-ones code, or more), with value 0: the data never uses them"""
    for t in dhts(segs):
        counts = list(t[1:17])
        longest = max(n for n in range(1, 17) if counts[n - 1])
        room = (1 << longest) - sum(c << (longest - n) for n, c in enumerate(counts[:longest], 1))
        if not 0 < room <= 256 - sum(counts):
            sys.exit(f"a table with {room} codes free: not one to complete")
        counts[longest - 1] += room
        t[1:17] = bytes(counts)
        t += bytes(room)
    return segs


BAD = "bad Huffman table"
BROKEN = "broken RAW header"
WAYS = [
    ("codes-overflow", codes_overflow, BAD),
    ("codes-one-over", codes_one_over, BAD),
    ("values-cut", values_cut, BAD),
    ("counts-cut", counts_cut, BAD),
    ("values-over-256", values_over_256, BAD),
    ("symbol-17", symbol_17, BAD),
    ("window-end", window_end, BAD),
    ("sos-components", sos_components, BROKEN),
    ("sof-short", sof_short, BROKEN),
    ("dri-short", dri_short, BROKEN),
    ("same", same, "ok"),
    ("complete", complete, "ok"),
]


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    b = Path(sys.argv[1]).read_bytes()
    off, ln, at = raw_strip(b)
    if off is None or ln is None or off + ln != len(b):
        sys.exit(f"{sys.argv[1]}: wants the RAW last in the file, as mkcr2 writes it")
    out = Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    listing = []
    for name, way, want in WAYS:
        segs, data = split(b[off:])
        raw = join(way(segs)) + data
        f = bytearray(b[:off]) + raw
        struct.pack_into("<I", f, at, len(raw))
        (out / f"{name}.CR2").write_bytes(f)
        listing.append(f"{name} {want}\n")
    (out / "LIST.txt").write_text("".join(listing))


if __name__ == "__main__":
    main()
