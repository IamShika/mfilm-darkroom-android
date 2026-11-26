/* The film OS's card, clock and memory on Android: the same hooks the camera
 * (ml-dev/fork/src/filmroll.c) and the PC simulator (ml-dev/filmos/sim) fill in.
 * Only the darkroom needs them, so this is the small subset fos_dev.c,
 * fos_print.c and fos_jpeg.c call.
 */

#include <stdio.h>
#include <stdlib.h>
#include <time.h>

#include <android/log.h>

#include "fos.h"

void *fos_port_open(const char *path, int write)
{
    return fopen(path, write ? "wb" : "rb");
}

int fos_port_read(void *f, void *buf, int size) { return (int) fread(buf, 1, size, (FILE *) f); }
int fos_port_write(void *f, const void *buf, int size) { return (int) fwrite(buf, 1, size, (FILE *) f); }
int fos_port_seek(void *f, int offset) { return fseek((FILE *) f, offset, SEEK_SET); }
void fos_port_close(void *f) { if (f) fclose((FILE *) f); }

int fos_port_ms(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int) (t.tv_sec * 1000 + t.tv_nsec / 1000000);
}

void *fos_port_alloc(int size) { return malloc((size_t) size); }
void fos_port_free(void *p) { free(p); }

/* a phone has no camera-sized cap on one block: 0 = no limit */
int fos_port_dev_cap(void) { return 0; }

void fos_port_log(const char *msg)
{
    __android_log_print(ANDROID_LOG_INFO, "mfilm", "%s", msg);
}

const char *fos_port_model(void) { return "M-Film for Android"; }
