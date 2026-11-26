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
     * @param size    1 = medium (a third of the sensor), 2 = large (half)
     * @param stamp   1 to print the date in the corner
     * @param when    "2026-10-22 17:45" for the EXIF, or ""
     * @return 0 when the JPEG is written, -1 otherwise (see lastError)
     */
    static native int develop(String cr2, String jpeg, int film, int size, int stamp,
                              String when, Progress progress);

    interface Progress {
        /** 0..1000 while the frame develops. */
        void onProgress(int permille);
    }
}
