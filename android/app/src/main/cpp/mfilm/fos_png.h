#ifndef _fos_png_h_
#define _fos_png_h_

#include <stdint.h>

/* A print as a lossless PNG: 8 bits a colour, RGB, no filter (the print is
 * written once and nothing reads it back here), deflated with zlib. Written a
 * band of rows at a time, so a full-sensor print never has to be held whole
 * twice: begin, then its rows from the top, then end. NULL: can't be opened. */
struct fos_png;
struct fos_png *fos_png_begin(const char *path, int width, int height);
void fos_png_rows(struct fos_png *p, const uint8_t *rgb, int stride, int rows);
int  fos_png_end(struct fos_png *p);                /* 1 when all of it was written */

/* the whole picture in one go, rows stride bytes apart */
int  fos_png_write(const char *path, const uint8_t *rgb, int stride, int width, int height);

#endif
