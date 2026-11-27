#ifndef _fos_jpeg_h_
#define _fos_jpeg_h_

#include <stdint.h>

/* what goes into the JPEG's EXIF (any may be NULL, or 0) */
struct fos_jpeg_meta
{
    const char *when;           /* "2026-10-22 17:45" */
    const char *desc;           /* the film */
    const char *fstop;          /* "2.8" */

    /* carried over from the frame's own EXIF, so a developed print still says
     * what camera, lens and exposure it was shot with (a picture: all empty) */
    const char *shot;           /* "2026-10-22 17:45:08", when the frame was shot */
    const char *make;           /* "Canon" */
    const char *model;          /* "Canon EOS 5D Mark II" */
    const char *lens;           /* "EF50mm f/1.8 STM" */
    int iso;                    /* 400 */
    int shutter_n, shutter_d;   /* 1/125 as 1 and 125 */
    int focal_n, focal_d;       /* 50 mm as 50 and 1 */
    int f_n, f_d;               /* the lens's f-number as 28 and 10, when fstop is empty */
};

/* width x height RGB pixels (rows stride bytes apart) as a baseline JPEG
 * at path; quality 1..100. 1 when written. */
/* the same a band of rows at a time, for a picture made as it is written:
 * begin, then its rows from the top, 16 at a time (fewer only at the
 * bottom), then end (1 if all of it was written). NULL: can't be opened. */
struct fos_jpeg;
struct fos_jpeg *fos_jpeg_begin(const char *path, int width, int height, int quality, const struct fos_jpeg_meta *meta);
void fos_jpeg_rows(struct fos_jpeg *j, const uint8_t *rgb, int stride, int rows);
int  fos_jpeg_end(struct fos_jpeg *j);

int fos_jpeg_write(const char *path, const uint8_t *rgb, int stride, int width, int height,
                   int quality, const struct fos_jpeg_meta *meta);

#endif
