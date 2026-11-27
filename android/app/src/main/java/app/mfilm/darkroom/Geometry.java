package app.mfilm.darkroom;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Straightening and cropping, the same way on the preview and on the print:
 * the frame is turned about its centre and the biggest rectangle of the
 * chosen shape that lies inside the turned frame is kept. Nothing here
 * touches the engine -- the RAW is developed as it always was, and the print
 * is cut afterwards, so a frame with no straightening and no crop comes out
 * exactly as the engine wrote it.
 */
final class Geometry
{
    /* the shapes the crop offers; 0 is the frame as it is */
    static final int SHAPES = 5;
    private static final int[] RATIO_N = { 0, 1, 4, 3, 16 };
    private static final int[] RATIO_D = { 0, 1, 5, 2, 9 };

    private Geometry() { }

    static boolean isSet(int tenths, int shape, int zoom) {
        return tenths != 0 || shape > 0 || zoom != 100;
    }

    /**
     * The rectangle the print is cut to: half its width and half its height.
     * The box has the shape's proportion, and is the biggest that still lies
     * inside the frame turned by `tenths` of a degree -- a corner of it, turned
     * back by the same angle, has to stay within the frame. `zoom` then
     * tightens it (100: as big as it goes, 200: half of that).
     */
    static float[] window(int w, int h, int tenths, int shape, int zoom) {
        double t = Math.toRadians(tenths / 10.0);
        double c = Math.abs(Math.cos(t)), s = Math.abs(Math.sin(t));
        double a = shape > 0 && shape < SHAPES ? (double) RATIO_N[shape] / RATIO_D[shape]
                                              : (double) w / h;
        double hh = Math.min(w / (2 * (a * c + s)), h / (2 * (a * s + c)));
        hh *= 0.998;                         /* a hair inside: no edge of the frame on the print */
        hh /= Math.max(1, zoom) / 100.0;
        return new float[]{ (float) (2 * a * hh), (float) (2 * hh) };
    }

    /** the frame turned and cropped: what the print will be */
    static Bitmap apply(Bitmap src, int tenths, int shape, int zoom) {
        float[] win = window(src.getWidth(), src.getHeight(), tenths, shape, zoom);
        int w = Math.max(1, Math.round(win[0]));
        int h = Math.max(1, Math.round(win[1]));
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.translate(w / 2f, h / 2f);
        cv.rotate(tenths / 10f);
        cv.translate(-src.getWidth() / 2f, -src.getHeight() / 2f);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        cv.drawBitmap(src, 0, 0, paint);
        return out;
    }

    /**
     * The developed print, straightened and cropped in place. Its APP0/APP1
     * segments -- the EXIF with the film and the camera, and the caption --
     * are carried over byte for byte, because the JPEG is written again here.
     */
    static void rewrite(File jpeg, int tenths, int shape, int zoom) throws IOException {
        Bitmap in = BitmapFactory.decodeFile(jpeg.getAbsolutePath());
        if (in == null) throw new IOException("can't read the print back");
        File turned = new File(jpeg.getParentFile(), jpeg.getName() + ".crop");
        File kept = new File(jpeg.getParentFile(), jpeg.getName() + ".exif");
        try {
            Bitmap out = apply(in, tenths, shape, zoom);
            try (OutputStream o = new FileOutputStream(turned)) {
                if (!out.compress(Bitmap.CompressFormat.JPEG, 92, o)) {
                    throw new IOException("can't write the cropped print");
                }
            } finally {
                if (out != in) out.recycle();
            }
            keepMetadata(jpeg, turned, kept);
        } finally {
            in.recycle();
        }
        turned.delete();
        try (InputStream a = new FileInputStream(kept);
             OutputStream b = new FileOutputStream(jpeg)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = a.read(buf)) > 0) b.write(buf, 0, n);
        } finally {
            kept.delete();
        }
    }

    /** the original's JFIF/EXIF/XMP segments, then the new file's JPEG data */
    private static void keepMetadata(File original, File encoded, File out) throws IOException {
        byte[] a = bytes(original), b = bytes(encoded);
        try (OutputStream o = new FileOutputStream(out)) {
            o.write(a, 0, Math.min(2, a.length));            // the original's SOI
            segments(a, 2, o);                                // its APP0..APPn, if any
            int q = skipSegments(b, 2);                       // past the new file's own
            o.write(b, q, b.length - q);                      // its tables and its scan
        }
    }

    /** copies the APP0..APPn segments at `from` to out, and says where they end */
    private static int segments(byte[] d, int from, OutputStream out) throws IOException {
        int p = from;
        while (appSegment(d, p)) {
            int len = ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
            if (out != null) out.write(d, p, 2 + len);
            p += 2 + len;
        }
        return p;
    }

    private static int skipSegments(byte[] d, int from) {
        return segments(d, from, null);
    }

    /** the marker at p is an APP0..APPn with a whole segment after it */
    private static boolean appSegment(byte[] d, int p) {
        if (p + 4 > d.length || (d[p] & 0xFF) != 0xFF) return false;
        int m = d[p + 1] & 0xFF;
        if (m < 0xE0 || m > 0xEF) return false;
        int len = ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
        return len >= 2 && p + 2 + len <= d.length;
    }

    private static byte[] bytes(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
