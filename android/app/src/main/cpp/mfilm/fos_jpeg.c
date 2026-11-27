/* fos_jpeg: a small baseline JPEG writer for the developed frames - 4:2:0,
 * the standard Huffman tables, integer DCT (the camera has no FPU), and
 * EXIF with the date, the film and the f-stop.
 */

#include "fos_jpeg.h"
#include "fos.h"
#include <string.h>

/* ---------------------------------------------------------------- tables */

static const uint8_t zigzag[64] =
{
     0,  1,  8, 16,  9,  2,  3, 10, 17, 24, 32, 25, 18, 11,  4,  5,
    12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13,  6,  7, 14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
    58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
};

/* the example tables of the standard (K.1, K.2), in natural order */
static const uint8_t std_q[2][64] =
{
    { 16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56,
      14, 17, 22, 29, 51, 87, 80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
      49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99 },
    { 17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99, 24, 26, 56, 99, 99, 99, 99, 99,
      47, 66, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
      99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99 },
};

/* the standard's Huffman tables (K.3): code counts by length, then values */
static const uint8_t dc_bits[2][16] = { { 0, 1, 5, 1, 1, 1, 1, 1, 1 }, { 0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1 } };
static const uint8_t dc_vals[12] = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11 };
static const uint8_t ac_bits[2][16] =
{
    { 0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d },
    { 0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77 },
};
static const uint8_t ac_vals[2][162] =
{
    { 0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
      0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
      0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
      0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
      0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
      0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
      0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
      0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
      0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
      0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
      0xf9, 0xfa },
    { 0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
      0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
      0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
      0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
      0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
      0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
      0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
      0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
      0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
      0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
      0xf9, 0xfa },
};

/* ---------------------------------------------------------------- output */

struct jw
{
    void *f;
    uint8_t buf[8192];
    int n, ok;
    uint32_t bits;
    int nbits;
    uint16_t dc_code[2][12], ac_code[2][256];
    uint8_t dc_len[2][12], ac_len[2][256];
    int32_t recip[2][64];       /* 65536 / (8 q), zigzag order */
    uint8_t q[2][64];           /* zigzag order, as written */
};

static void flush(struct jw *w)
{
    if (w->n && fos_port_write(w->f, w->buf, w->n) != w->n) w->ok = 0;
    w->n = 0;
}

static void put8(struct jw *w, int b)
{
    if (w->n == (int) sizeof(w->buf)) flush(w);
    w->buf[w->n++] = (uint8_t) b;
}

static void put16(struct jw *w, int v) { put8(w, v >> 8); put8(w, v); }

static void put_bits(struct jw *w, uint32_t code, int len)
{
    w->bits = w->bits << len | (code & ((1u << len) - 1));
    w->nbits += len;
    while (w->nbits >= 8)
    {
        int b = (int) (w->bits >> (w->nbits - 8)) & 0xFF;
        put8(w, b);
        if (b == 0xFF) put8(w, 0);          /* stuffed */
        w->nbits -= 8;
    }
}

static void huff_codes(const uint8_t *bits, const uint8_t *vals, uint16_t *code, uint8_t *len)
{
    int c = 0, k = 0;
    for (int l = 1; l <= 16; l++)
    {
        for (int i = 0; i < bits[l - 1]; i++, k++)
        {
            code[vals[k]] = (uint16_t) c++;
            len[vals[k]] = (uint8_t) l;
        }
        c <<= 1;
    }
}

/* ---------------------------------------------------------------- one block */

/* forward DCT, integer (libjpeg's islow, its constants in 1/8192): the
 * output 8 times too large */
enum { C0298 = 2446, C0390 = 3196, C0541 = 4433, C0765 = 6270, C0899 = 7373, C1175 = 9633,
       C1501 = 12299, C1847 = 15137, C1961 = 16069, C2053 = 16819, C2562 = 20995, C3072 = 25172 };
static void fdct(int32_t *d)
{
    for (int pass = 0; pass < 2; pass++)
    {
        for (int i = 0; i < 8; i++)
        {
            int32_t *p = pass ? d + i : d + i * 8;
            const int s = pass ? 8 : 1;
            int32_t t0 = p[0] + p[7 * s], t7 = p[0] - p[7 * s], t1 = p[s] + p[6 * s], t6 = p[s] - p[6 * s];
            int32_t t2 = p[2 * s] + p[5 * s], t5 = p[2 * s] - p[5 * s], t3 = p[3 * s] + p[4 * s], t4 = p[3 * s] - p[4 * s];
            int32_t t10 = t0 + t3, t13 = t0 - t3, t11 = t1 + t2, t12 = t1 - t2;
            const int sh = pass ? 15 : 11;
            if (pass) { p[0] = (t10 + t11 + 2) >> 2; p[4 * s] = (t10 - t11 + 2) >> 2; }
            else      { p[0] = (t10 + t11) * 4;      p[4 * s] = (t10 - t11) * 4; }
            int32_t z1 = (t12 + t13) * C0541;
            p[2 * s] = (z1 + t13 * C0765 + (1 << (sh - 1))) >> sh;
            p[6 * s] = (z1 - t12 * C1847 + (1 << (sh - 1))) >> sh;
            int32_t a = t4 + t7, b = t5 + t6, c = t4 + t6, e = t5 + t7;
            int32_t z5 = (c + e) * C1175;
            t4 *= C0298; t5 *= C2053; t6 *= C3072; t7 *= C1501;
            a *= -C0899; b *= -C2562; c = c * -C1961 + z5; e = e * -C0390 + z5;
            p[7 * s] = (t4 + a + c + (1 << (sh - 1))) >> sh;
            p[5 * s] = (t5 + b + e + (1 << (sh - 1))) >> sh;
            p[3 * s] = (t6 + b + c + (1 << (sh - 1))) >> sh;
            p[s]     = (t7 + a + e + (1 << (sh - 1))) >> sh;
        }
    }
}

static int bit_size(int v)
{
    int n = 0;
    if (v < 0) v = -v;
    while (v) { n++; v >>= 1; }
    return n;
}

/* a block of samples (level-shifted) to the file; t: 0 luma, 1 chroma */
static void block(struct jw *w, int32_t *d, int t, int *dc_prev)
{
    fdct(d);
    int q[64];
    for (int i = 0; i < 64; i++)
    {
        int32_t v = d[zigzag[i]], r = w->recip[t][i];
        q[i] = v >= 0 ? (int) ((v * r + 32768) >> 16) : -(int) ((-v * r + 32768) >> 16);
    }
    int diff = q[0] - *dc_prev, n = bit_size(diff);
    *dc_prev = q[0];
    put_bits(w, w->dc_code[t][n], w->dc_len[t][n]);
    if (n) put_bits(w, (uint32_t) (diff < 0 ? diff - 1 : diff), n);
    int run = 0;
    for (int i = 1; i < 64; i++)
    {
        if (!q[i]) { run++; continue; }
        while (run > 15) { put_bits(w, w->ac_code[t][0xF0], w->ac_len[t][0xF0]); run -= 16; }
        int s = bit_size(q[i]), sym = run << 4 | s;
        put_bits(w, w->ac_code[t][sym], w->ac_len[t][sym]);
        put_bits(w, (uint32_t) (q[i] < 0 ? q[i] - 1 : q[i]), s);
        run = 0;
    }
    if (run) put_bits(w, w->ac_code[t][0], w->ac_len[t][0]);
}

/* ---------------------------------------------------------------- EXIF */

/* a tiny TIFF: IFD0 (description, make, model, software, date) and the
 * EXIF IFD (date taken, exposure, f-number, ISO, focal length, the lens) */

/* the most the APP1 body comes to: two IFDs and the strings they point at */
#define EXIF_MAX 1024

/* one tag of an IFD: text (type 2), a short (type 3) or a rational (type 5) */
struct ti { int tag, type; const char *text; unsigned n, d, v; };

static struct ti ti_text(int tag, const char *s)
{
    struct ti e; e.tag = tag; e.type = 2; e.text = s; e.n = e.d = e.v = 0;
    return e;
}

static struct ti ti_short(int tag, unsigned v)
{
    struct ti e; e.tag = tag; e.type = 3; e.text = 0; e.n = e.d = 0; e.v = v;
    return e;
}

static struct ti ti_ratio(int tag, unsigned n, unsigned d)
{
    struct ti e; e.tag = tag; e.type = 5; e.text = 0; e.n = n; e.d = d; e.v = 0;
    return e;
}

/* text cut to what the IFD has room for */
static const char *ti_clamp(const char *s, char *buf, int size)
{
    buf[0] = 0;
    if (!s || !*s) return buf;
    int n = (int) strlen(s);
    if (n > size - 1) n = size - 1;
    memcpy(buf, s, n);
    buf[n] = 0;
    return buf;
}

/* one entry, and the bytes it points at after the IFDs (p: where the entry
 * goes, data: where the next out-of-line value goes) */
static void ti_write(uint8_t *out, int *p, int *data, const struct ti *e)
{
    int len = e->type == 2 ? (int) strlen(e->text) + 1 : e->type == 5 ? 8 : 2;
    unsigned cnt = e->type == 2 ? (unsigned) len : 1;
    out[(*p)++] = (uint8_t) e->tag; out[(*p)++] = (uint8_t) (e->tag >> 8);
    out[(*p)++] = (uint8_t) e->type; out[(*p)++] = 0;
    for (int i = 0; i < 4; i++) out[(*p)++] = (uint8_t) (cnt >> (8 * i));
    if (e->type == 2 && len <= 4)
    {
        memset(out + *p, 0, 4);
        memcpy(out + *p, e->text, len);
        *p += 4;
    }
    else if (e->type == 3)
    {
        for (int i = 0; i < 4; i++) out[(*p)++] = (uint8_t) (e->v >> (8 * i));
    }
    else
    {
        for (int i = 0; i < 4; i++) out[(*p)++] = (uint8_t) ((unsigned) *data >> (8 * i));
        if (e->type == 2)
        {
            memcpy(out + *data, e->text, len);
            *data += (len + 1) & ~1;
        }
        else
        {
            unsigned q[2];
            q[0] = e->n; q[1] = e->d;
            for (int i = 0; i < 8; i++) out[(*data)++] = (uint8_t) (q[i >> 2] >> (8 * (i & 3)));
        }
    }
}

/* text for XML: the few characters that can't stand as they are */
static int xml_text(char *out, int size, const char *s)
{
    int n = 0;
    for (; *s && n < size - 6; s++)
    {
        const char *e = *s == '&' ? "&amp;" : *s == '<' ? "&lt;" : *s == '>' ? "&gt;" : 0;
        if (e) { int l = (int) strlen(e); memcpy(out + n, e, l); n += l; }
        else out[n++] = *s;
    }
    out[n] = 0;
    return n;
}

static int xmp(char *out, int size, const char *film)
{
    char f[160];
    xml_text(f, sizeof(f), film);
    int n = snprintf(out, size,
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
        "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
        "<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">%s</rdf:li></rdf:Alt></dc:description>"
        "<dc:subject><rdf:Bag><rdf:li>%s</rdf:li><rdf:li>M-Film</rdf:li><rdf:li>film simulation</rdf:li></rdf:Bag></dc:subject>"
        "</rdf:Description></rdf:RDF></x:xmpmeta>", f, f);
    return n < size ? n : size - 1;
}

/* the APP1 body: IFD0 with the film and the camera, then the EXIF IFD with
 * when the frame was shot and the lens's numbers -- carried over from the
 * CR2, so a developed print still knows what it was shot with */
static int exif(uint8_t *out, const struct fos_jpeg_meta *m)
{
    char date[20] = "", shot[20], buf[4][80];
    if (m->when && strlen(m->when) >= 16)
    {
        /* "2026-10-22 17:45" -> "2026:10:22 17:45:00" */
        memcpy(date, m->when, 16);
        date[4] = date[7] = ':';
        memcpy(date + 16, ":00", 4);
    }
    /* when it was shot, as the camera wrote it (TIFF's own format), else the
     * app's date */
    if (m->shot && strlen(m->shot) >= 19)
    {
        memcpy(shot, m->shot, 19);
        shot[19] = 0;
    }
    else snprintf(shot, sizeof(shot), "%s", date[0] ? date : "                   ");

    int fnum = 0, fden = 10;                    /* the lens's f-number */
    if (m->fstop && *m->fstop)
    {
        const char *s = m->fstop;
        while (*s >= '0' && *s <= '9') fnum = fnum * 10 + (*s++ - '0');
        fnum *= 10;
        if (*s == '.' && s[1] >= '0' && s[1] <= '9') fnum += s[1] - '0';
    }
    else if (m->f_n > 0 && m->f_d > 0)
    {
        fnum = m->f_n;
        fden = m->f_d;
    }

    const char *desc = ti_clamp(m->desc, buf[0], sizeof(buf[0]));
    const char *make = ti_clamp(m->make, buf[1], sizeof(buf[1]));
    const char *model = ti_clamp(m->model, buf[2], sizeof(buf[2]));
    const char *lens = ti_clamp(m->lens, buf[3], sizeof(buf[3]));

    struct ti ifd0[6], ex[6];
    int n0 = 0, n1 = 0;
    ifd0[n0++] = ti_text(0x010E, *desc ? desc : " ");
    ifd0[n0++] = ti_text(0x010F, *make ? make : "Canon");
    ifd0[n0++] = ti_text(0x0110, *model ? model : fos_port_model());
    ifd0[n0++] = ti_text(0x0131, "M-FILM darkroom");
    ifd0[n0++] = ti_text(0x0132, date[0] ? date : " ");
    /* the EXIF IFD: tags in ascending order */
    if (m->shutter_n > 0 && m->shutter_d > 0)
        ex[n1++] = ti_ratio(0x829A, (unsigned) m->shutter_n, (unsigned) m->shutter_d);
    if (fnum > 0) ex[n1++] = ti_ratio(0x829D, (unsigned) fnum, (unsigned) fden);
    if (m->iso > 0) ex[n1++] = ti_short(0x8827, (unsigned) m->iso);
    ex[n1++] = ti_text(0x9003, shot);
    if (m->focal_n > 0 && m->focal_d > 0)
        ex[n1++] = ti_ratio(0x920A, (unsigned) m->focal_n, (unsigned) m->focal_d);
    if (*lens) ex[n1++] = ti_text(0xA434, lens);

    int p = 8;
    int exif_at = 8 + 2 + (n0 + 1) * 12 + 4;          /* IFD0, then its pointer */
    int data = exif_at + 2 + n1 * 12 + 4;
    if (data > EXIF_MAX) return 0;
    memcpy(out, "II*\0\x08\0\0\0", 8);
    out[p++] = (uint8_t) (n0 + 1); out[p++] = 0;
    for (int i = 0; i < n0; i++) ti_write(out, &p, &data, &ifd0[i]);
    /* where the EXIF IFD is (LONG, 1) */
    out[p++] = 0x69; out[p++] = 0x87; out[p++] = 4; out[p++] = 0;
    out[p++] = 1; out[p++] = 0; out[p++] = 0; out[p++] = 0;
    for (int i = 0; i < 4; i++) out[p++] = (uint8_t) ((unsigned) exif_at >> (8 * i));
    for (int i = 0; i < 4; i++) out[p++] = 0;          /* no IFD1 */
    out[p++] = (uint8_t) n1; out[p++] = 0;
    for (int i = 0; i < n1; i++) ti_write(out, &p, &data, &ex[i]);
    for (int i = 0; i < 4; i++) out[p++] = 0;          /* the last IFD */
    return data;
}

/* ---------------------------------------------------------------- the file */

/* a file being written: its rows come 16 at a time (fos_jpeg_rows), so a
 * picture bigger than the memory at hand can be written as it is made */
struct fos_jpeg
{
    struct jw w;
    int width, height, y;       /* y: rows written so far */
    int dc[3];
};

struct fos_jpeg *fos_jpeg_begin(const char *path, int width, int height, int quality, const struct fos_jpeg_meta *meta)
{
    struct fos_jpeg *j = fos_port_alloc(sizeof(struct fos_jpeg));
    if (!j) return 0;
    memset(j, 0, sizeof(*j));
    struct jw *w = &j->w;
    j->width = width;
    j->height = height;
    w->ok = 1;
    int scale = quality < 50 ? 5000 / quality : 200 - quality * 2;
    for (int t = 0; t < 2; t++)
    {
        for (int i = 0; i < 64; i++)
        {
            int v = (std_q[t][zigzag[i]] * scale + 50) / 100;
            v = v < 1 ? 1 : v > 255 ? 255 : v;
            w->q[t][i] = (uint8_t) v;
            w->recip[t][i] = 65536 / (8 * v);
        }
        huff_codes(dc_bits[t], dc_vals, w->dc_code[t], w->dc_len[t]);
        huff_codes(ac_bits[t], ac_vals[t], w->ac_code[t], w->ac_len[t]);
    }
    w->f = fos_port_open(path, 1);
    if (!w->f) { fos_port_free(j); return 0; }

    put16(w, 0xFFD8);
    /* JFIF */
    put16(w, 0xFFE0); put16(w, 16);
    static const uint8_t jfif[14] = { 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0 };
    for (int i = 0; i < 14; i++) put8(w, jfif[i]);
    /* EXIF */
    if (meta)
    {
        uint8_t ex[EXIF_MAX];
        memset(ex, 0, sizeof(ex));
        int n = exif(ex, meta);
        if (n > 0)
        {
            put16(w, 0xFFE1); put16(w, n + 8);
            static const uint8_t tag[6] = { 'E', 'x', 'i', 'f', 0, 0 };
            for (int i = 0; i < 6; i++) put8(w, tag[i]);
            for (int i = 0; i < n; i++) put8(w, ex[i]);
        }
        /* XMP: the film as the caption and as keywords, where photo apps
         * (Lightroom, Photos, Bridge) look for them; EXIF's ImageDescription
         * above is the same caption for the ones that read only EXIF */
        if (meta->desc && *meta->desc)
        {
            static char x[1024];
            int k = xmp(x, sizeof(x), meta->desc);
            static const char ns[] = "http://ns.adobe.com/xap/1.0/";
            put16(w, 0xFFE1); put16(w, 2 + (int) sizeof(ns) + k);
            for (int i = 0; i < (int) sizeof(ns); i++) put8(w, (uint8_t) ns[i]);    /* with its 0 */
            for (int i = 0; i < k; i++) put8(w, (uint8_t) x[i]);
        }
    }
    /* quantisation, frame, Huffman tables */
    for (int t = 0; t < 2; t++)
    {
        put16(w, 0xFFDB); put16(w, 67); put8(w, t);
        for (int i = 0; i < 64; i++) put8(w, w->q[t][i]);
    }
    put16(w, 0xFFC0); put16(w, 17); put8(w, 8); put16(w, height); put16(w, width); put8(w, 3);
    put8(w, 1); put8(w, 0x22); put8(w, 0);
    put8(w, 2); put8(w, 0x11); put8(w, 1);
    put8(w, 3); put8(w, 0x11); put8(w, 1);
    for (int t = 0; t < 2; t++)
    {
        int nd = 0, na = 0;
        for (int i = 0; i < 16; i++) { nd += dc_bits[t][i]; na += ac_bits[t][i]; }
        put16(w, 0xFFC4); put16(w, 2 + 17 + nd + 17 + na);
        put8(w, t);
        for (int i = 0; i < 16; i++) put8(w, dc_bits[t][i]);
        for (int i = 0; i < nd; i++) put8(w, dc_vals[i]);
        put8(w, 0x10 | t);
        for (int i = 0; i < 16; i++) put8(w, ac_bits[t][i]);
        for (int i = 0; i < na; i++) put8(w, ac_vals[t][i]);
    }
    put16(w, 0xFFDA); put16(w, 12); put8(w, 3);
    put8(w, 1); put8(w, 0x00); put8(w, 2); put8(w, 0x11); put8(w, 3); put8(w, 0x11);
    put8(w, 0); put8(w, 63); put8(w, 0);
    return j;
}

/* the picture's next rows (rgb: the first of them), 16 x 16 at a time: four
 * luma blocks, then Cb and Cr from the means of 2 x 2 pixels; the edges
 * repeat the last pixel. Every call but the last brings a multiple of 16. */
void fos_jpeg_rows(struct fos_jpeg *j, const uint8_t *rgb, int stride, int rows)
{
    struct jw *w = &j->w;
    const int width = j->width;
    int32_t blk[64], cb[64], cr[64];
    for (int my = 0; my < rows; my += 16)
        for (int mx = 0; mx < width; mx += 16)
        {
            memset(cb, 0, sizeof(cb));
            memset(cr, 0, sizeof(cr));
            for (int b = 0; b < 4; b++)
            {
                int bx = mx + (b & 1) * 8, by = my + (b >> 1) * 8;
                for (int y = 0; y < 8; y++)
                {
                    const uint8_t *row = rgb + (by + y < rows ? by + y : rows - 1) * stride;
                    for (int x = 0; x < 8; x++)
                    {
                        const uint8_t *px = row + 3 * (bx + x < width ? bx + x : width - 1);
                        int r = px[0], g = px[1], bl = px[2];
                        blk[y * 8 + x] = ((19595 * r + 38470 * g + 7471 * bl + 32768) >> 16) - 128;
                        int k = ((b >> 1) * 4 + (y >> 1)) * 8 + (b & 1) * 4 + (x >> 1);
                        cb[k] += -11059 * r - 21709 * g + 32768 * bl;
                        cr[k] += 32768 * r - 27439 * g - 5329 * bl;
                    }
                }
                block(w, blk, 0, &j->dc[0]);
            }
            for (int i = 0; i < 64; i++) { cb[i] = (cb[i] + 131072) >> 18; cr[i] = (cr[i] + 131072) >> 18; }
            block(w, cb, 1, &j->dc[1]);
            block(w, cr, 1, &j->dc[2]);
        }
    j->y += rows;
}

int fos_jpeg_end(struct fos_jpeg *j)
{
    if (!j) return 0;
    struct jw *w = &j->w;
    if (w->nbits) put_bits(w, 0x7F, 8 - w->nbits);     /* pad with ones */
    put16(w, 0xFFD9);
    flush(w);
    fos_port_close(w->f);
    int ok = w->ok && j->y >= j->height;
    fos_port_free(j);
    return ok;
}

int fos_jpeg_write(const char *path, const uint8_t *rgb, int stride, int width, int height,
                   int quality, const struct fos_jpeg_meta *meta)
{
    struct fos_jpeg *j = fos_jpeg_begin(path, width, height, quality, meta);
    if (!j) return 0;
    fos_jpeg_rows(j, rgb, stride, height);
    return fos_jpeg_end(j);
}
