package app.mfilm.darkroom;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Sharpening, the same on the preview and on the print: an unsharp mask a
 * pixel or two across the finished print, so the frame's own detail stands a
 * little further out of the film's softness. It works on the picture's
 * luminance alone, so the film's colour is not touched, and nothing here goes
 * near the engine: at 0 the print is the one the engine wrote, byte for byte.
 */
final class Finish {

    private Finish() { }

    /**
     * The frame with the mask on it, or the same bitmap when there is
     * nothing to do. The mask is a [1 2 1] blur each way (a 3 x 3 average),
     * and `amount` 0..100 is how much of the difference between the pixel
     * and that average is added back: 100 is the whole of it.
     */
    static Bitmap sharpen(Bitmap src, int amount) {
        final int w = src.getWidth(), h = src.getHeight();
        if (amount <= 0 || w < 3 || h < 3) return src;
        final int k = Math.round(amount * 4096 / 100f);
        int[] up = new int[w], cur = new int[w], down = new int[w];
        int[] upH = new int[w], curH = new int[w], downH = new int[w];
        int[] row = new int[w];
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < h; y++) {
            final boolean hasUp = y > 0, hasDown = y + 1 < h;
            src.getPixels(cur, 0, w, 0, y, w, 1);
            blurAcross(cur, curH, w);
            if (hasUp) {
                src.getPixels(up, 0, w, 0, y - 1, w, 1);
                blurAcross(up, upH, w);
            }
            if (hasDown) {
                src.getPixels(down, 0, w, 0, y + 1, w, 1);
                blurAcross(down, downH, w);
            }
            for (int x = 0; x < w; x++) {
                /* the 3 x 3 average: the blur across this row, and the rows about it */
                int blur = (2 * curH[x] + (hasUp ? upH[x] : curH[x])
                        + (hasDown ? downH[x] : curH[x]) + 2) >> 2;
                int p = cur[x];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                int l = (r * 77 + g * 150 + b * 29) >> 8;
                int d = (int) ((long) (l - blur) * k / 4096);
                row[x] = (p & 0xFF000000)
                        | clamp(r + d) << 16 | clamp(g + d) << 8 | clamp(b + d);
            }
            out.setPixels(row, 0, w, 0, y, w, 1);
        }
        return out;
    }

    /** the luminance of a row, blurred across by [1 2 1] */
    private static void blurAcross(int[] src, int[] dst, int w) {
        for (int x = 0; x < w; x++) {
            int l = src[x > 0 ? x - 1 : 0], m = src[x], r = src[x + 1 < w ? x + 1 : w - 1];
            dst[x] = (luma(l) + 2 * luma(m) + luma(r) + 2) >> 2;
        }
    }

    private static int luma(int p) {
        return (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /**
     * The developed print sharpened in place. Its EXIF and the caption are
     * carried over exactly, because the JPEG is written again here.
     */
    static void rewrite(File jpeg, int amount) throws IOException {
        if (amount <= 0) return;
        Bitmap in = BitmapFactory.decodeFile(jpeg.getAbsolutePath());
        if (in == null) throw new IOException("can't read the print back");
        File sharp = new File(jpeg.getParentFile(), jpeg.getName() + ".sharp");
        File kept = new File(jpeg.getParentFile(), jpeg.getName() + ".exif2");
        try {
            Bitmap out = sharpen(in, amount);
            if (out == in) return;
            try (OutputStream o = new FileOutputStream(sharp)) {
                if (!out.compress(Bitmap.CompressFormat.JPEG, 95, o)) {
                    throw new IOException("can't write the sharpened print");
                }
            } finally {
                out.recycle();
            }
            Geometry.keepMetadata(jpeg, sharp, kept);
        } finally {
            in.recycle();
        }
        sharp.delete();
        try (InputStream a = new FileInputStream(kept);
             OutputStream b = new FileOutputStream(jpeg)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = a.read(buf)) > 0) b.write(buf, 0, n);
        } finally {
            kept.delete();
        }
    }
}
