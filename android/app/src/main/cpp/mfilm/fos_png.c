/* fos_png: a developed print as a lossless PNG (the app's "Lossless" export).
 *
 * The print is the engine's own RGB, 8 bits a colour, so the file is what the
 * film made of the frame with nothing of a JPEG's in it -- which is what shows
 * on grain. Rows go through zlib's deflate as they are finished and each
 * filled block becomes an IDAT chunk, so the writer holds 64 KB and not the
 * picture. Every row is written unfiltered: the print is compressed once and
 * never read back here, and a filter would only pay for itself on a second
 * pass.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <zlib.h>

#include "fos_png.h"

struct fos_png
{
    FILE *f;
    z_stream z;
    uint8_t *buf;               /* the deflate's output, a chunk at a time */
    int cap;
    int w, h, rows;             /* the size, and the rows written so far */
    int err;
};

static void put32(uint8_t *p, uint32_t v)
{
    p[0] = (uint8_t) (v >> 24); p[1] = (uint8_t) (v >> 16);
    p[2] = (uint8_t) (v >> 8); p[3] = (uint8_t) v;
}

/* one chunk: its length, its type, its data, the CRC of type and data */
static int chunk(struct fos_png *p, const char *type, const uint8_t *data, int n)
{
    uint8_t head[8];
    put32(head, (uint32_t) n);
    memcpy(head + 4, type, 4);
    if (fwrite(head, 1, 8, p->f) != 8) return 0;
    if (n && fwrite(data, 1, (size_t) n, p->f) != (size_t) n) return 0;
    uLong c = crc32(0L, (const Bytef *) type, 4);
    if (n) c = crc32(c, (const Bytef *) data, (uInt) n);
    uint8_t tail[4];
    put32(tail, (uint32_t) c);
    return fwrite(tail, 1, 4, p->f) == 4;
}

/* n bytes into the stream, whatever comes out written as IDAT */
static int deflate_in(struct fos_png *p, const uint8_t *d, int n)
{
    p->z.next_in = (Bytef *) d;
    p->z.avail_in = (uInt) n;
    while (p->z.avail_in)
    {
        p->z.next_out = p->buf;
        p->z.avail_out = (uInt) p->cap;
        if (deflate(&p->z, Z_NO_FLUSH) != Z_OK) return 0;
        int have = p->cap - (int) p->z.avail_out;
        if (have && !chunk(p, "IDAT", p->buf, have)) return 0;
    }
    return 1;
}

static void png_free(struct fos_png *p)
{
    free(p->buf);
    free(p);
}

struct fos_png *fos_png_begin(const char *path, int width, int height)
{
    if (!path || !path[0] || width < 1 || height < 1) return 0;
    struct fos_png *p = calloc(1, sizeof(*p));
    if (!p) return 0;
    p->cap = 1 << 16;
    p->buf = malloc((size_t) p->cap);
    if (!p->buf) { png_free(p); return 0; }
    p->f = fopen(path, "wb");
    if (!p->f) { png_free(p); return 0; }
    if (deflateInit(&p->z, 6) != Z_OK) { fclose(p->f); png_free(p); return 0; }

    static const uint8_t sig[8] = { 137, 80, 78, 71, 13, 10, 26, 10 };
    uint8_t ihdr[13];
    put32(ihdr, (uint32_t) width);
    put32(ihdr + 4, (uint32_t) height);
    ihdr[8] = 8;                        /* 8 bits a colour */
    ihdr[9] = 2;                        /* RGB, no alpha */
    ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;       /* deflate, no filter, no interlace */
    int ok = fwrite(sig, 1, 8, p->f) == 8 && chunk(p, "IHDR", ihdr, 13);
    if (!ok)
    {
        deflateEnd(&p->z);
        fclose(p->f);
        png_free(p);
        return 0;
    }
    p->w = width; p->h = height;
    return p;
}

void fos_png_rows(struct fos_png *p, const uint8_t *rgb, int stride, int rows)
{
    if (!p || p->err) return;
    for (int i = 0; i < rows && p->rows < p->h; i++, p->rows++)
    {
        static const uint8_t filter = 0;
        if (!deflate_in(p, &filter, 1)
            || !deflate_in(p, rgb + (size_t) i * (size_t) stride, p->w * 3))
        {
            p->err = 1;
            return;
        }
    }
}

int fos_png_end(struct fos_png *p)
{
    if (!p) return 0;
    int ok = !p->err && p->rows == p->h;
    if (ok)
    {
        for (;;)
        {
            p->z.next_in = 0;
            p->z.avail_in = 0;
            p->z.next_out = p->buf;
            p->z.avail_out = (uInt) p->cap;
            int r = deflate(&p->z, Z_FINISH);
            int have = p->cap - (int) p->z.avail_out;
            if (have && !chunk(p, "IDAT", p->buf, have)) { ok = 0; break; }
            if (r == Z_STREAM_END) break;
            if (r != Z_OK) { ok = 0; break; }
        }
    }
    if (ok && !chunk(p, "IEND", 0, 0)) ok = 0;
    deflateEnd(&p->z);
    if (fclose(p->f) != 0) ok = 0;
    png_free(p);
    return ok;
}

int fos_png_write(const char *path, const uint8_t *rgb, int stride, int width, int height)
{
    struct fos_png *p = fos_png_begin(path, width, height);
    if (!p) return 0;
    fos_png_rows(p, rgb, stride, height);
    return fos_png_end(p);
}
