package app.mfilm.darkroom;

/** The open M-Film darkroom (the camera's own engine) through JNI. */
final class MfilmNative {
    static {
        System.loadLibrary("mfilmdarkroom");
    }

    private MfilmNative() {}

    /** Loads the 68 films from FOSFILMS.BIN. Returns the count, or -1. */
    static native int loadFilms(String path);

    /** The folder of LOOKnn.LUT film colour tables. */
    static native void setTables(String dir);

    /** "brand|name|iso|process|group|key" for the i-th film on the shelf. */
    static native String filmInfo(int i);

    /** "desc|shortname|grain|punch|colour|warmth" for the i-th film: what the shelf shows. */
    static native String filmDetail(int i);

    static native String lastError();

    static native String version();

    /**
     * Develop one CR2 into a JPEG.
     *
     * @param film    index on the shelf
     * @param size    0 = the screen's print, 1 = medium (a third of the sensor), 2 = large (half)
     * @param stamp   1 to print the date in the corner
     * @param shadows 0..100: lift the dark end of the print
     * @param style   0 off, 1 bloom, 2 diffusion, 3 halation, 4 motion, 5 streak
     * @param amount  0..100: how much glow lands on the print
     * @param range   0..100: how far down the tones it is gathered from
     * @param spread  0..100: how far it reaches
     * @param warmth  -100..100: its tint, blue to amber
     * @param grain   0..200: the stock's grain on the print, 100 is its own
     * @param when    "2026-10-22 17:45" for the EXIF, or ""
     * @return 0 when the JPEG is written, -1 otherwise (see lastError)
     */
    static native int develop(String cr2, String jpeg, int film, int size, int stamp,
                              int shadows, int style, int amount, int range, int spread, int warmth,
                              int grain, String when, Progress progress);

    /**
     * Develop a picture that is not a CR2 -- an image, or the preview a RAW of
     * another make carries -- into a JPEG. pixels is ARGB, w x h, as
     * Bitmap.getPixels gives them.
     */
    static native int developImage(int[] pixels, int w, int h, String jpeg, int film, int size,
                                   int stamp, int shadows, int style, int amount, int range,
                                   int spread, int warmth, int grain, String when, Progress progress);

    /**
     * Keep a CR2 decoded for the next previews, keyed by key (the app's own
     * name for the file it copied out of its URI). The RAW is most of a
     * develop, so the sliders then redraw the frame from it instead of
     * reading them again. 0 when it is kept and ready, -1 otherwise.
     */
    static native int previewOpen(String cr2, int film, String when, String key);

    /** The same for a picture (ARGB pixels): they are only needed the first time. */
    static native int previewOpenImage(int[] pixels, int w, int h, int film, String when, String key);

    /** 1 when this key is the frame the preview has kept. */
    static native int previewCached(String key);

    /** Draw the kept frame with the knobs on screen, into jpeg. 0 when written. */
    static native int previewRender(int film, int shadows, int style, int amount, int range,
                                    int spread, int warmth, int grain, int stamp, String jpeg);

    /** Let the kept frame go (before a batch, or when the app is done). */
    static native void previewClose();

    interface Progress {
        /** 0..1000 while the frame develops. */
        void onProgress(int permille);
    }
}
