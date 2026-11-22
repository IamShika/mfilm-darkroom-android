/* graintest: the grain the darkroom draws is the film stock's.
 *
 *   ./out/graintest
 *
 * A film stock's look gives a strength (grain) and a clump width (grain_size). The print's
 * grain has to be as strong at every clump width, its clumps as wide as the film stock's on
 * a print a third of the frame wide and wider on a bigger print, and a clump must not turn
 * into a pattern of its own (no alternation: #104).
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include "../fos.h"
#include "../fos_dev.h"

/* the camera's port, as far as a test needs it: nothing */
void *fos_port_alloc(int size) { return malloc(size); }
void  fos_port_free(void *p) { free(p); }
void  fos_port_log(const char *msg) { (void) msg; }
int   fos_port_ms(void) { return 0; }
int   fos_port_dev_cap(void) { return 0; }
void  fos_dev_test_sample(int x, int y, int v) { (void) x; (void) y; (void) v; }

#define W 720
#define H 160

static uint8_t print[H * W * 3];
static int fail;

static void ok(const char *what, int pass, const char *detail)
{
    if (!pass) fail = 1;
    printf("%s  %s%s%s\n", pass ? "ok  " : "FAIL", what, *detail ? ": " : "", detail);
}

/* the grain drawn: its strength in levels, and how much a pixel's grain agrees with the one
 * beside it (the clump's width) and with the one two along */
static void measure(int mono, int c, int level, double *strength, double *r1, double *r2)
{
    fos_dev_test_grain(print, W, H, c, 2400, mono, level);
    double m = 0, v = 0, c1 = 0, c2 = 0;
    long n = 0;
    for (int y = 0; y < H; y++)
        for (int x = 0; x < W; x++) m += print[3 * (y * W + x) + 1], n++;
    m /= n;
    for (int y = 0; y < H; y++)
        for (int x = 0; x < W; x++)
        {
            double a = print[3 * (y * W + x) + 1] - m;
            v += a * a;
            if (x + 1 < W) c1 += a * (print[3 * (y * W + x + 1) + 1] - m);
            if (x + 2 < W) c2 += a * (print[3 * (y * W + x + 2) + 1] - m);
        }
    v /= n;
    *strength = sqrt(v);
    *r1 = c1 / (v * n);
    *r2 = c2 / (v * n);
}

int main(void)
{
    char b[200];
    static const int mono[2] = { 0, 1 };
    /* the clumps, in 1/256 of a print pixel: a draw a pixel, then a quarter of one and up */
    static const int cs[] = { 0, 64, 128, 256, 384, 512, 640 };

    /* the strength is the film stock's at every clump */
    for (int m = 0; m < 2; m++)
    {
        double s0, r1, r2, s, a1, a2;
        measure(mono[m], 0, 120, &s0, &r1, &r2);
        for (unsigned i = 1; i < sizeof(cs) / sizeof(cs[0]); i++)
        {
            measure(mono[m], cs[i], 120, &s, &a1, &a2);
            snprintf(b, sizeof(b), "%s clump of %d/256 of a pixel: %.2f levels against %.2f at a draw a pixel",
                     mono[m] ? "mono" : "colour", cs[i], s, s0);
            ok("the same strength", s > s0 * 0.85 && s < s0 * 1.15, b);
        }
    }

    /* and the clump drawn is the clump asked for: its agreement with the pixel beside it is the
     * Gaussian's, exp(-1/(2 sigma^2)), and with the one two along exp(-2/sigma^2), from a quarter of
     * a pixel to a pixel and a half (#104) */
    for (unsigned i = 1; i < sizeof(cs) / sizeof(cs[0]) && cs[i] <= 384; i++)
    {
        double s, a1, a2, sd = cs[i] / 256.0;
        measure(0, cs[i], 120, &s, &a1, &a2);
        double want1 = exp(-1 / (2 * sd * sd)), want2 = exp(-2 / (sd * sd));
        snprintf(b, sizeof(b), "sigma %.2f px: %.3f against %.3f at one, %.3f against %.3f at two",
                 sd, a1, want1, a2, want2);
        ok("the clump is the sigma asked for", fabs(a1 - want1) < 0.12 && fabs(a2 - want2) < 0.12, b);
    }

    /* the clumps are the film stock's: a wider one agrees more with its neighbour */
    for (int m = 0; m < 2; m++)
    {
        double s, a1, a2, prev = -1;
        /* from a clump of a third of a pixel up: below that the grain is one draw a pixel and cannot
         * be finer, so there is nothing there to widen */
        for (int c = 96; c <= 384; c += 32)
        {
            measure(mono[m], c, 120, &s, &a1, &a2);
            snprintf(b, sizeof(b), "%s, %d/256 of a pixel: %.3f (was %.3f at less)", mono[m] ? "mono" : "colour", c, a1, prev);
            ok("clumps wider with the size", a1 > prev, b);
            prev = a1;
        }
    }

    /* the weight the grain is added by is the lab's, strongest in the mid tones:
     * 160/247 at 40, 206/247 at 190 (#104, #186) */
    {
        double s40, s96, s190, d1, d2;
        measure(0, 1, 40, &s40, &d1, &d2);
        measure(0, 1, 96, &s96, &d1, &d2);
        measure(0, 1, 190, &s190, &d1, &d2);
        snprintf(b, sizeof(b), "40: %.2f, 96: %.2f, 190: %.2f levels", s40, s96, s190);
        ok("the weight is a dome over the tones", s96 > s40 * 1.4 && s96 > s190 * 1.1, b);
        snprintf(b, sizeof(b), "%.2f levels against the mid tones' %.2f, the lab's 160/247", s40, s96);
        ok("the weight over the shadows is the lab's", fabs(s40 - s96 * 160.0 / 247.0) < s96 * 0.05, b);
        snprintf(b, sizeof(b), "%.2f levels against the mid tones' %.2f, the lab's 206/247", s190, s96);
        ok("the weight at 190 is the lab's", fabs(s190 - s96 * 206.0 / 247.0) < s96 * 0.05, b);
    }

    /* and a clump is not a pattern: no alternation of the grain */
    for (int m = 0; m < 2; m++)
        for (int c = 0; c <= 768; c += 32)
        {
            double s, a1, a2;
            measure(mono[m], c, 120, &s, &a1, &a2);
            if (a2 <= -0.05)
            {
                snprintf(b, sizeof(b), "%s, %d/256 of a pixel: %.3f at two", mono[m] ? "mono" : "colour", c, a2);
                ok("no alternation", 0, b);
            }
        }
    ok("no alternation", 1, "every clump 0..1.5 pixels agrees with the one two along");

    /* the looks map: a coarser stock's grain is wider on every print, on a print a third of
     * the frame wide as wide as the stock's, and wider on a bigger print */
    {
        const struct fos_look *fast = 0, *slow = 0;
        for (int i = 0; i < fos_nlooks; i++)
        {
            if (!strcmp(fos_looks[i].key, "trix400")) fast = &fos_looks[i];
            if (!strcmp(fos_looks[i].key, "ektar100")) slow = &fos_looks[i];
        }
        if (!fast || !slow) ok("two film stocks to compare", 0, "trix400 and ektar100 are not in fos_looks");
        else
        {
            int f, s;
            f = fos_dev_test_grain_clump(fast, 1728, 5184);
            s = fos_dev_test_grain_clump(slow, 1728, 5184);
            snprintf(b, sizeof(b), "trix400 %d/256 of a pixel, ektar100 %d/256, on a third of the frame", f, s);
            ok("the coarser stock's grain is wider", f > s, b);

            int f3 = fos_dev_test_grain_clump(fast, 2592, 5184), s3 = fos_dev_test_grain_clump(slow, 2592, 5184);
            snprintf(b, sizeof(b), "large: trix400 %d/256 of a pixel, ektar100 %d/256", f3, s3);
            ok("a bigger print shows the same grain wider", f3 > f && s3 > s, b);

            /* the clump's width goes with the print's, one to one */
            snprintf(b, sizeof(b), "a third of the frame %d/256, half of it %d/256 (1.5 times)", f, f3);
            ok("in step with the print's width", (f3 * 10 > f * 14 && f3 * 10 < f * 16), b);

            snprintf(b, sizeof(b), "a print smaller than a twelfth of the frame: %d/256 of a pixel",
                     fos_dev_test_grain_clump(slow, 400, 5184));
            ok("no clump at all where the print can't show it", fos_dev_test_grain_clump(slow, 400, 5184) <= 26, b);
        }
    }

    printf("graintest: %s\n", fail ? "failed" : "all passed");
    return fail;
}