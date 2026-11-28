/* mfilm_jni: the open M-Film darkroom (ml-dev/filmos/fos_dev.c) behind the
 * Android app.
 *
 * The camera develops a frame in slices to stay inside its boot memory; here
 * the same code runs on the phone's whole memory at once, so a roll develops
 * in seconds instead of a minute a frame. Nothing of the closed-source
 * desktop program (Darkrooms) is used: this is the camera's own engine and
 * the film colour tables the release ships on the card (ML/DARKROOM).
 */

#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "fos.h"
#include "fos_dev.h"

#define JNI_FN(name) Java_app_mfilm_darkroom_MfilmNative_##name

static char last_error[512] = "";

/* one frame at a time: the film's table, the grain plate and the preview's
 * kept decode are all in one darkroom */
static pthread_mutex_t engine_lock = PTHREAD_MUTEX_INITIALIZER;

/* the films from FOSFILMS.BIN, as the camera loads them off its card; the
 * blob has to outlive the films because they point into it */
static uint8_t *film_blob;

JNIEXPORT jint JNICALL JNI_FN(loadFilms)(JNIEnv *env, jclass cls, jstring jpath)
{
    const char *path = (*env)->GetStringUTFChars(env, jpath, 0);
    if (!path) return -1;
    FILE *f = fopen(path, "rb");
    if (!f)
    {
        snprintf(last_error, sizeof(last_error), "no film list at %s", path);
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -1;
    }
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    uint8_t *blob = n > 0 ? malloc((size_t) n) : 0;
    if (!blob || fread(blob, 1, (size_t) n, f) != (size_t) n)
    {
        free(blob);
        fclose(f);
        snprintf(last_error, sizeof(last_error), "can't read %s", path);
        (*env)->ReleaseStringUTFChars(env, jpath, path);
        return -1;
    }
    fclose(f);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    if (!fos_films_load(blob, (int) n))
    {
        free(blob);
        snprintf(last_error, sizeof(last_error), "not a film file");
        return -1;
    }
    free(film_blob);
    film_blob = blob;
    return fos_nfilms;
}

/* where the film colour tables are (the card's ML/DARKROOM, as LOOKnn.LUT);
 * the darkroom reads them, and makes none itself */
JNIEXPORT void JNICALL JNI_FN(setTables)(JNIEnv *env, jclass cls, jstring jdir)
{
    const char *dir = (*env)->GetStringUTFChars(env, jdir, 0);
    if (dir)
    {
        fos_dev_cache(dir);
        (*env)->ReleaseStringUTFChars(env, jdir, dir);
    }
}

/* "brand|name|iso|process|group|key" for a film on the shelf */
JNIEXPORT jstring JNICALL JNI_FN(filmInfo)(JNIEnv *env, jclass cls, jint i)
{
    if (i < 0 || i >= fos_nfilms) return (*env)->NewStringUTF(env, "");
    const struct fos_film *f = &fos_films[i];
    char buf[512];
    snprintf(buf, sizeof(buf), "%s|%s|%d|%s|%s|%s", f->brand ? f->brand : "", f->name ? f->name : "",
             f->box_iso, f->process ? f->process : "", fos_group_names[f->group], f->key);
    return (*env)->NewStringUTF(env, buf);
}

/* "desc|shortname|grain|punch|colour|warmth": what the shelf shows under the name */
JNIEXPORT jstring JNICALL JNI_FN(filmDetail)(JNIEnv *env, jclass cls, jint i)
{
    if (i < 0 || i >= fos_nfilms) return (*env)->NewStringUTF(env, "");
    const struct fos_film *f = &fos_films[i];
    char buf[512];
    snprintf(buf, sizeof(buf), "%s|%s|%d|%d|%d|%d", f->desc ? f->desc : "",
             f->shortname ? f->shortname : "", f->grain, f->punch, f->colour, f->warmth);
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT jstring JNICALL JNI_FN(lastError)(JNIEnv *env, jclass cls)
{
    return (*env)->NewStringUTF(env, last_error);
}

JNIEXPORT jstring JNICALL JNI_FN(version)(JNIEnv *env, jclass cls)
{
#ifdef MFILM_VERSION
    return (*env)->NewStringUTF(env, MFILM_VERSION);
#else
    return (*env)->NewStringUTF(env, "?");
#endif
}

/* The steps, the progress reported as it goes, and the print the engine
 * keeps beside the JPEG taken away again: the app wants only the JPEG. */
static int run_develop(JNIEnv *env, struct fos_dev *d, const char *out, jint size, jint stamp,
                       jint quality, jint png, jobject progress)
{
    char print[1024];
    snprintf(print, sizeof(print), "%s.fop", out);

    jmethodID mid = 0;
    if (progress)
    {
        jclass pc = (*env)->GetObjectClass(env, progress);
        mid = (*env)->GetMethodID(env, pc, "onProgress", "(I)V");
        (*env)->DeleteLocalRef(env, pc);
    }

    /* the JPEG (or the lossless PNG) before the first step, and the bigger print too */
    fos_dev_quality(d, quality);
    if (png) fos_dev_png(d, out); else fos_dev_jpeg(d, out);
    if (size > FOS_SIZE_S) fos_dev_big(d, size, 0);
    if (stamp) fos_dev_stamp(d, 1);

    int p = 0;
    while ((p = fos_dev_step(d, 40)) >= 0 && p < 1000)
    {
        if (mid) (*env)->CallVoidMethod(env, progress, mid, p);
    }
    if (p < 0)
    {
        const char *err = fos_dev_error(d);
        snprintf(last_error, sizeof(last_error), "%s", err ? err : "develop failed");
        fos_dev_free(d);
        remove(print);
        return -1;
    }
    fos_dev_free(d);
    remove(print);
    return 0;
}

/* Develop one CR2 into a JPEG at full print size.
 *
 *   cr2   the RAW on disk
 *   jpeg  where the JPEG goes
 *   film  index on the shelf (its table is LOOKnn.LUT in the tables folder)
 *   size  FOS_SIZE_M (a third of the sensor each way), FOS_SIZE_L (half) or
 *         FOS_SIZE_XL (all of it, 5184 x 3456 from a 24 MP frame)
 *   stamp 1 to print the date in the corner
 *   shadows, style, amount, range, spread, warmth: the phone's knobs, as
 *         fos_dev_adjust takes them (0 leaves the camera's print)
 *   grain the stock's grain on the print, 0..200 percent (100 is its own)
 *   quality the JPEG's, 1..100
 *   png  1 to write the print as a lossless PNG instead of a JPEG
 *   when  "2026-10-22 17:45" for the EXIF, or empty
 *
 * Returns 0 when the JPEG is written, -1 when the frame can't be developed
 * (lastError() says why). progress.onProgress runs 0..1000 while it works.
 */
JNIEXPORT jint JNICALL JNI_FN(develop)(JNIEnv *env, jclass cls, jstring jcr2, jstring jout,
                                       jint film, jint size, jint stamp,
                                       jint shadows, jint style, jint amount, jint range,
                                       jint spread, jint warmth, jint grain, jint quality, jint png,
                                       jstring jwhen, jobject progress)
{
    const char *cr2 = (*env)->GetStringUTFChars(env, jcr2, 0);
    const char *out = (*env)->GetStringUTFChars(env, jout, 0);
    const char *when = jwhen ? (*env)->GetStringUTFChars(env, jwhen, 0) : 0;
    if (!cr2 || !out)
    {
        snprintf(last_error, sizeof(last_error), "bad paths");
        return -1;
    }
    last_error[0] = 0;
    pthread_mutex_lock(&engine_lock);

    /* fos_dev_start wants a print path: the app doesn't keep the camera's
     * palette-indexed screen print, so it goes beside the JPEG and is removed */
    char print[1024];
    snprintf(print, sizeof(print), "%s.fop", out);

    int r = -1;
    struct fos_dev *d = fos_dev_start(cr2, print, film, 1, when ? when : "", "");
    if (!d)
    {
        snprintf(last_error, sizeof(last_error), "can't start the frame: %s", cr2);
    }
    else
    {
        fos_dev_adjust(d, shadows, style, amount, range, spread, warmth);
        fos_dev_grain(d, grain);
        r = run_develop(env, d, out, size, stamp, quality, png, progress);
    }
    (*env)->ReleaseStringUTFChars(env, jcr2, cr2);
    (*env)->ReleaseStringUTFChars(env, jout, out);
    if (when) (*env)->ReleaseStringUTFChars(env, jwhen, when);
    pthread_mutex_unlock(&engine_lock);
    return r;
}

/* The same, for a frame that is not a CR2: an image file, or the full-size
 * preview lifted out of a RAW of another make. The pixels are ARGB as
 * Bitmap.getPixels gives them, pic_w x pic_h of them. */
JNIEXPORT jint JNICALL JNI_FN(developImage)(JNIEnv *env, jclass cls, jintArray jpixels, jint pic_w,
                                            jint pic_h, jstring jout, jint film, jint size,
                                            jint stamp, jint shadows, jint style, jint amount,
                                            jint range, jint spread, jint warmth, jint grain, jint quality,
                                            jint png, jstring jwhen, jobject progress)
{
    const char *out = (*env)->GetStringUTFChars(env, jout, 0);
    const char *when = jwhen ? (*env)->GetStringUTFChars(env, jwhen, 0) : 0;
    if (!out || pic_w < 1 || pic_h < 1)
    {
        snprintf(last_error, sizeof(last_error), "bad picture");
        if (out) (*env)->ReleaseStringUTFChars(env, jout, out);
        if (when) (*env)->ReleaseStringUTFChars(env, jwhen, when);
        return -1;
    }
    last_error[0] = 0;
    pthread_mutex_lock(&engine_lock);

    size_t n = (size_t) pic_w * (size_t) pic_h;
    uint8_t *rgb = malloc(n * 3);
    jint *px = jpixels ? (*env)->GetIntArrayElements(env, jpixels, 0) : 0;
    if (!rgb || !px)
    {
        snprintf(last_error, sizeof(last_error), "out of memory for a %d x %d picture", pic_w, pic_h);
        goto done;
    }
    for (size_t i = 0; i < n; i++)
    {
        unsigned v = (unsigned) px[i];
        rgb[3 * i] = (uint8_t) (v >> 16 & 255);
        rgb[3 * i + 1] = (uint8_t) (v >> 8 & 255);
        rgb[3 * i + 2] = (uint8_t) (v & 255);
    }
    (*env)->ReleaseIntArrayElements(env, jpixels, px, JNI_ABORT);
    px = 0;

    char print[1024];
    snprintf(print, sizeof(print), "%s.fop", out);
    struct fos_dev *d = fos_dev_start_image(rgb, pic_w, pic_h, print, film, 1, when ? when : "", "");
    int r = -1;
    if (!d)
    {
        snprintf(last_error, sizeof(last_error), "can't start a %d x %d picture", pic_w, pic_h);
    }
    else
    {
        fos_dev_adjust(d, shadows, style, amount, range, spread, warmth);
        fos_dev_grain(d, grain);
        r = run_develop(env, d, out, size, stamp, quality, png, progress);
    }
    free(rgb);
    (*env)->ReleaseStringUTFChars(env, jout, out);
    if (when) (*env)->ReleaseStringUTFChars(env, jwhen, when);
    pthread_mutex_unlock(&engine_lock);
    return r;

done:
    if (px) (*env)->ReleaseIntArrayElements(env, jpixels, px, JNI_ABORT);
    free(rgb);
    (*env)->ReleaseStringUTFChars(env, jout, out);
    if (when) (*env)->ReleaseStringUTFChars(env, jwhen, when);
    pthread_mutex_unlock(&engine_lock);
    return -1;
}

/* ----------------------------------------------------------- the preview */
/*
 * The app draws the frame again at every slider. The RAW is most of a develop,
 * so one frame is kept decoded here (the engine's own fos_dev_preview_mode),
 * keyed by the file the app copied out of its content:// URI: the same frame
 * at the same film or another one reprints without reading the RAW again.
 */
static struct fos_dev *pv;
static char pv_key[512];
static int pv_image;                    /* the kept frame is a picture, not a CR2 */
static uint8_t *pv_pixels;              /* its RGB, which the engine points into */

static void pv_drop_locked(void)
{
    if (pv) { fos_dev_free(pv); pv = 0; }
    free(pv_pixels);
    pv_pixels = 0;
    pv_image = 0;
    pv_key[0] = 0;
}

JNIEXPORT jint JNICALL JNI_FN(previewCached)(JNIEnv *env, jclass cls, jstring jkey)
{
    if (!jkey) return 0;
    const char *key = (*env)->GetStringUTFChars(env, jkey, 0);
    if (!key) return 0;
    pthread_mutex_lock(&engine_lock);
    int have = pv && pv_key[0] && strcmp(pv_key, key) == 0;
    pthread_mutex_unlock(&engine_lock);
    (*env)->ReleaseStringUTFChars(env, jkey, key);
    return have;
}

/* Decode a CR2 and keep it: 0 when it is ready to reprint. */
JNIEXPORT jint JNICALL JNI_FN(previewOpen)(JNIEnv *env, jclass cls, jstring jcr2, jint film,
                                           jstring jwhen, jstring jkey)
{
    const char *path = (*env)->GetStringUTFChars(env, jcr2, 0);
    const char *when = jwhen ? (*env)->GetStringUTFChars(env, jwhen, 0) : 0;
    const char *key = jkey ? (*env)->GetStringUTFChars(env, jkey, 0) : 0;
    if (!path || !key)
    {
        snprintf(last_error, sizeof(last_error), "bad preview paths");
        return -1;
    }
    pthread_mutex_lock(&engine_lock);
    if (pv && !pv_image && pv_key[0] && strcmp(pv_key, key) == 0)
    {
        pthread_mutex_unlock(&engine_lock);
        (*env)->ReleaseStringUTFChars(env, jcr2, path);
        if (when) (*env)->ReleaseStringUTFChars(env, jwhen, when);
        (*env)->ReleaseStringUTFChars(env, jkey, key);
        return 0;
    }
    pv_drop_locked();
    struct fos_dev *d = fos_dev_start(path, "", film, 1, when ? when : "", "");
    if (!d)
    {
        snprintf(last_error, sizeof(last_error), "can't start the frame: %s", path);
        pthread_mutex_unlock(&engine_lock);
        return -1;
    }
    fos_dev_preview_mode(d, 1);
    int p = 0;
    while ((p = fos_dev_step(d, 40)) >= 0 && !fos_dev_prepared(d)) { }
    if (p < 0 || !fos_dev_prepared(d) || !fos_dev_keep_raw(d))
    {
        snprintf(last_error, sizeof(last_error), "%s", fos_dev_error(d));
        fos_dev_free(d);
        pthread_mutex_unlock(&engine_lock);
        return -1;
    }
    pv = d;
    pv_image = 0;
    snprintf(pv_key, sizeof(pv_key), "%s", key);
    pthread_mutex_unlock(&engine_lock);
    (*env)->ReleaseStringUTFChars(env, jcr2, path);
    if (when) (*env)->ReleaseStringUTFChars(env, jwhen, when);
    if (jkey) (*env)->ReleaseStringUTFChars(env, jkey, key);
    return 0;
}

/* The same for a picture: its pixels are ARGB, as Bitmap.getPixels gives them,
 * and only needed the first time -- the engine keeps its own copy. */
JNIEXPORT jint JNICALL JNI_FN(previewOpenImage)(JNIEnv *env, jclass cls, jintArray jpixels,
                                                jint pic_w, jint pic_h, jint film, jstring jwhen,
                                                jstring jkey)
{
    const char *when = jwhen ? (*env)->GetStringUTFChars(env, jwhen, 0) : 0;
    const char *key = jkey ? (*env)->GetStringUTFChars(env, jkey, 0) : 0;
    if (!key)
    {
        snprintf(last_error, sizeof(last_error), "bad preview key");
        return -1;
    }
    pthread_mutex_lock(&engine_lock);
    if (pv && pv_image && pv_key[0] && strcmp(pv_key, key) == 0)
    {
        pthread_mutex_unlock(&engine_lock);
        if (when) (*env)->ReleaseStringUTFChars(env, jwhen, when);
        (*env)->ReleaseStringUTFChars(env, jkey, key);
        return 0;
    }
    if (!jpixels || pic_w < 1 || pic_h < 1)
    {
        snprintf(last_error, sizeof(last_error), "the picture wasn't handed over");
        pthread_mutex_unlock(&engine_lock);
        return -1;
    }
    pv_drop_locked();
    size_t n = (size_t) pic_w * (size_t) pic_h;
    uint8_t *rgb = malloc(n * 3);
    jint *px = (*env)->GetIntArrayElements(env, jpixels, 0);
    if (!rgb || !px)
    {
        free(rgb);
        if (px) (*env)->ReleaseIntArrayElements(env, jpixels, px, JNI_ABORT);
        snprintf(last_error, sizeof(last_error), "out of memory for a %d x %d picture", pic_w, pic_h);
        pthread_mutex_unlock(&engine_lock);
        return -1;
    }
    for (size_t i = 0; i < n; i++)
    {
        unsigned v = (unsigned) px[i];
        rgb[3 * i] = (uint8_t) (v >> 16 & 255);
        rgb[3 * i + 1] = (uint8_t) (v >> 8 & 255);
        rgb[3 * i + 2] = (uint8_t) (v & 255);
    }
    (*env)->ReleaseIntArrayElements(env, jpixels, px, JNI_ABORT);

    struct fos_dev *d = fos_dev_start_image(rgb, pic_w, pic_h, "", film, 1, when ? when : "", "");
    if (!d)
    {
        free(rgb);
        snprintf(last_error, sizeof(last_error), "can't start a %d x %d picture", pic_w, pic_h);
        pthread_mutex_unlock(&engine_lock);
        return -1;
    }
    fos_dev_preview_mode(d, 1);
    int p = 0;
    while ((p = fos_dev_step(d, 40)) >= 0 && !fos_dev_prepared(d)) { }
    if (p < 0 || !fos_dev_prepared(d))
    {
        snprintf(last_error, sizeof(last_error), "%s", fos_dev_error(d));
        fos_dev_free(d);
        free(rgb);
        pthread_mutex_unlock(&engine_lock);
        return -1;
    }
    pv = d;
    pv_pixels = rgb;
    pv_image = 1;
    snprintf(pv_key, sizeof(pv_key), "%s", key);
    pthread_mutex_unlock(&engine_lock);
    if (when) (*env)->ReleaseStringUTFChars(env, jwhen, when);
    (*env)->ReleaseStringUTFChars(env, jkey, key);
    return 0;
}

/* Draw the kept frame with the knobs on screen: the same 720 x 480 print a
 * develop writes, without reading the RAW again. */
JNIEXPORT jint JNICALL JNI_FN(previewRender)(JNIEnv *env, jclass cls, jint film, jint shadows,
                                             jint style, jint amount, jint range, jint spread,
                                             jint warmth, jint grain, jint stamp, jstring jout)
{
    const char *out = (*env)->GetStringUTFChars(env, jout, 0);
    if (!out)
    {
        snprintf(last_error, sizeof(last_error), "bad preview path");
        return -1;
    }
    pthread_mutex_lock(&engine_lock);
    if (!pv)
    {
        snprintf(last_error, sizeof(last_error), "no frame is kept");
        pthread_mutex_unlock(&engine_lock);
        (*env)->ReleaseStringUTFChars(env, jout, out);
        return -1;
    }
    if (!fos_dev_reprint(pv, film))
    {
        snprintf(last_error, sizeof(last_error), "the kept frame can't be drawn again");
        pv_drop_locked();
        pthread_mutex_unlock(&engine_lock);
        (*env)->ReleaseStringUTFChars(env, jout, out);
        return -1;
    }
    fos_dev_adjust(pv, shadows, style, amount, range, spread, warmth);
    fos_dev_grain(pv, grain);
    fos_dev_stamp(pv, stamp);
    fos_dev_jpeg(pv, out);
    int p = 0;
    while ((p = fos_dev_step(pv, 40)) >= 0 && p < 1000) { }
    if (p < 0)
    {
        snprintf(last_error, sizeof(last_error), "%s", fos_dev_error(pv));
        pv_drop_locked();
        pthread_mutex_unlock(&engine_lock);
        (*env)->ReleaseStringUTFChars(env, jout, out);
        return -1;
    }
    pthread_mutex_unlock(&engine_lock);
    (*env)->ReleaseStringUTFChars(env, jout, out);
    return 0;
}

JNIEXPORT void JNICALL JNI_FN(previewClose)(JNIEnv *env, jclass cls)
{
    pthread_mutex_lock(&engine_lock);
    pv_drop_locked();
    pthread_mutex_unlock(&engine_lock);
}
