package app.mfilm.darkroom;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Locale;

/**
 * A picture out of any file the phone can pick: an image as it is, or the
 * biggest JPEG a RAW of another make carries inside it -- every RAW has one,
 * from a thumbnail to the whole frame. The darkroom develops that picture
 * with the same film look it gives a CR2.
 */
final class PictureLoader {

    /** enough for the biggest print, and what a phone can hold */
    static final int MAX_SIDE = 2600;

    /** the RAWs other makes write: TIFF, or the newer boxed ones */
    private static final String[] RAW = {
            "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "dng", "raf", "orf", "rw2",
            "raw", "pef", "srw", "3fr", "fff", "iiq", "mos", "mrw", "x3f", "rwl", "kdc", "dcr",
            "mef", "mdc", "erf", "cap", "srw" };

    private PictureLoader() {}

    /** the camera's own format, which the engine decodes in full */
    static boolean isCr2(String name) {
        return ext(name).equals("cr2");
    }

    /** a RAW of another make: its preview is what we can develop */
    static boolean isOtherRaw(String name) {
        String e = ext(name);
        for (String r : RAW) if (r.equals(e)) return true;
        return false;
    }

    /**
     * The picture in a file: for a RAW, the largest JPEG inside it; for an
     * image, the image. Upright, and no wider than MAX_SIDE.
     */
    static Bitmap load(File file, String name) throws IOException {
        Bitmap b = null;
        if (isOtherRaw(name)) b = embeddedJpeg(file);
        if (b == null) b = decode(file);
        if (b == null) throw new IOException(name + " has no picture this app can read");
        return upright(file, b);
    }

    private static String ext(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
    }

    /** the file an image comes from, scaled down to MAX_SIDE */
    private static Bitmap decode(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), o);
        if (o.outWidth < 1 || o.outHeight < 1) return null;
        o.inSampleSize = 1;
        while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > MAX_SIDE * 2) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        Bitmap b = BitmapFactory.decodeFile(file.getAbsolutePath(), o);
        if (b != null && Math.max(b.getWidth(), b.getHeight()) > MAX_SIDE) {
            float k = (float) MAX_SIDE / Math.max(b.getWidth(), b.getHeight());
            Bitmap small = Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * k)),
                    Math.max(1, (int) (b.getHeight() * k)), true);
            if (small != b) b.recycle();
            b = small;
        }
        return b;
    }

    /**
     * The largest JPEG a RAW carries. The file is walked a megabyte at a time,
     * pairing 0xFFD8 with 0xFFD9 (nested ones, as a preview's own thumbnail is,
     * counted); the biggest pair is the frame, and only its bytes are read.
     */
    private static Bitmap embeddedJpeg(File file) {
        long start = -1, bestStart = -1, bestLen = 0;
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long n = f.length();
            byte[] buf = new byte[1 << 20];
            long pos = 0;
            int depth = 0;
            boolean ff = false;
            while (pos < n) {
                int want = (int) Math.min(buf.length, n - pos);
                f.seek(pos);
                f.readFully(buf, 0, want);
                for (int i = 0; i < want; i++) {
                    int c = buf[i] & 255;
                    if (ff) {
                        if (c == 0xD8) {
                            if (depth++ == 0) start = pos + i - 1;
                        } else if (c == 0xD9 && depth > 0) {
                            if (--depth == 0) {
                                long len = pos + i + 1 - start;
                                if (len > bestLen) { bestLen = len; bestStart = start; }
                            }
                        }
                    }
                    ff = c == 0xFF;
                }
                pos += want;
            }
            if (bestStart < 0 || bestLen < 4096 || bestLen > (48L << 20)) return null;
            byte[] data = new byte[(int) bestLen];
            f.seek(bestStart);
            f.readFully(data);

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth < 1 || o.outHeight < 1) return null;
            o.inSampleSize = 1;
            while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > MAX_SIDE * 2) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (b != null && Math.max(b.getWidth(), b.getHeight()) > MAX_SIDE) {
                float k = (float) MAX_SIDE / Math.max(b.getWidth(), b.getHeight());
                Bitmap small = Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * k)),
                        Math.max(1, (int) (b.getHeight() * k)), true);
                if (small != b) b.recycle();
                b = small;
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /** the file's own rotation, as the camera wrote it */
    private static Bitmap upright(File file, Bitmap b) {
        int turn = 0, flip = 0;
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) turn = 90;
            else if (o == ExifInterface.ORIENTATION_ROTATE_180) turn = 180;
            else if (o == ExifInterface.ORIENTATION_ROTATE_270) turn = 270;
            else if (o == ExifInterface.ORIENTATION_FLIP_HORIZONTAL) flip = 1;
            else if (o == ExifInterface.ORIENTATION_FLIP_VERTICAL) flip = 2;
        } catch (Exception ignored) {
            // a RAW whose preview has no EXIF: as it is
        }
        if (turn == 0 && flip == 0) return b;
        Matrix m = new Matrix();
        if (flip == 1) m.postScale(-1, 1);
        else if (flip == 2) m.postScale(1, -1);
        if (turn != 0) m.postRotate(turn);
        Bitmap out = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (out != b) b.recycle();
        return out;
    }
}
