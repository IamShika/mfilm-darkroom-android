package app.mfilm.darkroom;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * M-Film Darkroom: develops the CR2s a Magic Lantern M-Film camera shot, on
 * the phone, with the camera's own open engine and the release's film colour
 * tables. A frame takes seconds here instead of the camera's minute.
 */
public class MainActivity extends Activity {

    private static final int PICK_RAWS = 42;
    private static final int PICK_ROLL_FOLDER = 43;
    private static final int ROLL_FRAMES = 44;

    /* what a roll's folder is called under Pictures/M-Film, and what it records */
    private static final String MANIFEST = "manifest.csv";
    private static final String MANIFEST_HEADER =
            "frame,developed,film,shadows,style,amount,range,spread,warmth,size,stamp,shot,seconds";

    private final List<Uri> picked = new ArrayList<>();
    private Uri shared;                      // a frame opened or shared into us
    private Film[] films = new Film[0];
    private int filmIndex = -1;
    private String currentGroup = "COLOUR";
    private boolean busy = true;

    private TextView filesView, logView, statusView, versionView;
    private TextView filmBrandView, filmNameView, filmDescView, previewNote;
    private LinearLayout tabs, canisters, chips;
    private HorizontalScrollView canisterScroll;
    private ImageView previewImage;
    private Spinner sizeSpinner;
    private Switch stampSwitch;
    private ProgressBar progressBar;
    private Button pickButton, developButton, galleryButton, rollButton, shareButton;
    private LinearLayout adjust;
    private HistogramView histogram;
    private SeekBar shadowsBar, amountBar, rangeBar, spreadBar, warmthBar;
    private Spinner styleSpinner;

    /* the print as the knobs leave it, and the same print without them, held side by side */
    private Bitmap previewBitmap, plainBitmap;
    private String plainOf;                  // the frame and film the plain print belongs to
    private boolean holding;
    private String heldNote;
    private long pressedAt;                 // when the finger went down on the preview

    /* straighten and crop: the same window on the preview and on the print */
    private Bitmap framedPreview, framedPlain;
    private TextView frameNote;
    private int straighten;                 // tenths of a degree, -100..100
    private int cropShape;                  // 0: the whole frame
    private int cropZoom = 100;             // percent of the biggest crop of that shape

    /* the print DEVELOP last wrote, for the share sheet */
    private volatile Uri lastPrint;
    private volatile String lastPrintName = "";

    /* the darkrooms kept by name, and the row of them above the sliders */
    private final List<Preset> presets = new ArrayList<>();
    private LinearLayout presetRow;
    private boolean applyingPreset;

    /* the roll the frames came from: its prints go in their own folder */
    private String rollFolder;

    /* the darkroom's knobs, as the phone's sliders leave them */
    private int shadows;
    private int bloomStyle;
    private int bloomAmount = 50;
    private int bloomRange = 50;
    private int bloomSpread = 50;
    private int bloomWarmth;

    /* which of the picked frames the preview shows */
    private int previewIndex;

    /* the film, the knobs and the frames the last session left: a roll shot on
     * Gold 200 stays on Gold 200, and its trail values stay where they were put */
    private SharedPreferences prefs;

    /* the frame the preview has copied out of its URI (the kept decode is keyed by it) */
    private String previewFileKey;

    /* previews run one at a time; a newer request makes an older one stale */
    private final ExecutorService previews = Executors.newSingleThreadExecutor();
    private final AtomicInteger previewSeq = new AtomicInteger();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("darkroom", MODE_PRIVATE);
        shadows = prefs.getInt("shadows", 0);
        bloomStyle = prefs.getInt("style", 0);
        bloomAmount = prefs.getInt("amount", 50);
        bloomRange = prefs.getInt("range", 50);
        bloomSpread = prefs.getInt("spread", 50);
        bloomWarmth = prefs.getInt("warmth", 0);
        straighten = prefs.getInt("straighten", 0);
        cropShape = prefs.getInt("shape", 0);
        cropZoom = prefs.getInt("zoom", 100);

        filesView = findViewById(R.id.files);
        logView = findViewById(R.id.log);
        statusView = findViewById(R.id.status);
        versionView = findViewById(R.id.version);
        sizeSpinner = findViewById(R.id.size);
        stampSwitch = findViewById(R.id.stamp);
        progressBar = findViewById(R.id.progress);
        pickButton = findViewById(R.id.pick);
        developButton = findViewById(R.id.develop);
        galleryButton = findViewById(R.id.gallery);
        rollButton = findViewById(R.id.roll);
        shareButton = findViewById(R.id.share);
        previewImage = findViewById(R.id.preview);
        previewNote = findViewById(R.id.previewNote);
        filmBrandView = findViewById(R.id.filmBrand);
        filmNameView = findViewById(R.id.filmName);
        filmDescView = findViewById(R.id.filmDesc);
        tabs = findViewById(R.id.tabs);
        canisters = findViewById(R.id.canisters);
        chips = findViewById(R.id.chips);
        canisterScroll = findViewById(R.id.canisterScroll);
        adjust = findViewById(R.id.adjust);
        histogram = findViewById(R.id.histogram);
        loadPresets();

        ArrayAdapter<String> sizes = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"Medium · a third of the sensor", "Large · half the sensor"});
        sizes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sizeSpinner.setAdapter(sizes);
        sizeSpinner.setSelection(Math.max(0, Math.min(1, prefs.getInt("size", 1))));
        sizeSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View v, int pos, long id) {
                prefs.edit().putInt("size", pos).apply();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        stampSwitch.setChecked(prefs.getBoolean("stamp", false));

        buildAdjustments();
        /*
         * One gesture on the preview, two things: held down it shows the frame
         * without the knobs, tapped it goes to the next picked frame. The
         * listener takes the whole gesture, because a view that doesn't claim
         * the press never hears the release -- and the release is the print.
         */
        previewImage.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressedAt = System.currentTimeMillis();
                    v.setPressed(true);
                    compare(true);
                    return true;
                case MotionEvent.ACTION_UP:
                    boolean tap = System.currentTimeMillis() - pressedAt < 250;
                    compare(false);
                    v.setPressed(false);
                    if (tap) nextPreview();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    compare(false);
                    v.setPressed(false);
                    return true;
                default:
                    return false;
            }
        });
        pickButton.setOnClickListener(v -> pickRaws());
        rollButton.setOnClickListener(v -> pickRollFolder());
        developButton.setOnClickListener(v -> developAll());
        galleryButton.setOnClickListener(v -> openGallery());
        shareButton.setOnClickListener(v -> sharePrint());
        versionView.setOnClickListener(v -> showLicences());
        stampSwitch.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean("stamp", on).apply();
            if (!applyingPreset) refreshPreview();
        });

        shared = uriFrom(getIntent());
        restoreFrames();
        updateFiles();

        setBusy(true);
        new Thread(this::prepareEngine).start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        addFrame(uriFrom(intent));
    }

    /* ------------------------------------------------ opened from elsewhere */

    /** A CR2 a file manager opens us with, or one shared to us from a gallery. */
    private Uri uriFrom(Intent intent) {
        if (intent == null) return null;
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action)) return intent.getData();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            return u != null ? u : intent.getData();
        }
        return null;
    }

    private void addFrame(Uri uri) {
        if (uri == null) return;
        if (uri.equals(shared)) shared = null;
        grant(uri);
        if (!picked.contains(uri)) picked.add(uri);
        updateFiles();
        refreshPreview();
        if (films.length == 0) shared = uri;   // still loading the shelf
    }

    /* ------------------------------------------------------------ the engine */

    /** Copies the film list and colour tables out of the APK, then loads them. */
    private void prepareEngine() {
        try {
            File base = new File(getFilesDir(), "mfilm");
            File tables = new File(base, "tables");
            if (!tables.isDirectory() && !tables.mkdirs()) throw new IOException("no " + tables);
            String[] luts = getAssets().list("mfilm/tables");
            if (luts != null) {
                for (String lut : luts) {
                    File f = new File(tables, lut);
                    if (!f.exists()) copyAsset("mfilm/tables/" + lut, f);
                }
            }
            File bin = new File(base, "FOSFILMS.BIN");
            if (!bin.exists()) copyAsset("mfilm/FOSFILMS.BIN", bin);

            final int n = MfilmNative.loadFilms(bin.getAbsolutePath());
            if (n <= 0) throw new IOException(MfilmNative.lastError());
            MfilmNative.setTables(tables.getAbsolutePath());

            final Film[] shelf = new Film[n];
            for (int i = 0; i < n; i++) {
                shelf[i] = new Film(i, MfilmNative.filmInfo(i), MfilmNative.filmDetail(i));
            }
            final String version = MfilmNative.version();
            runOnUiThread(() -> {
                films = shelf;
                filmIndex = indexOfKey(prefs.getString("film", "portra400"));
                if (filmIndex < 0) filmIndex = indexOfKey("portra400");
                if (filmIndex < 0 && films.length > 0) filmIndex = 0;
                if (filmIndex >= 0 && !films[filmIndex].group.isEmpty()) currentGroup = films[filmIndex].group;
                versionView.setText(getString(R.string.version, version));
                statusView.setText(getString(R.string.ready, films.length));
                buildShelf();
                setBusy(false);
                log(getString(R.string.engine_ready, films.length));
                if (shared != null) addFrame(shared);
                else refreshPreview();
            });
        } catch (Exception e) {
            final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            runOnUiThread(() -> {
                setBusy(true);
                statusView.setText(getString(R.string.engine_failed, msg));
                log(getString(R.string.engine_failed, msg));
            });
        }
    }

    private void copyAsset(String name, File dst) throws IOException {
        try (InputStream in = getAssets().open(name);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private int indexOfKey(String key) {
        if (key == null || key.isEmpty()) return -1;
        for (int i = 0; i < films.length; i++) {
            if (films[i].key.equalsIgnoreCase(key)) return i;
        }
        return -1;
    }

    /* ---------------------------------------------------------- film shelf */

    /** The camera's shelf: group tabs, the canisters, then the film's name and numbers. */
    private void buildShelf() {
        tabs.removeAllViews();
        for (String g : new String[]{"COLOUR", "SLIDE", "B&W", "CINE"}) {
            TextView t = new TextView(this);
            t.setText(g);
            t.setTextSize(11);
            t.setLetterSpacing(0.18f);
            t.setPadding((int) dp(9), (int) dp(6), (int) dp(9), (int) dp(6));
            t.setTextColor(g.equalsIgnoreCase(currentGroup) ? 0xFFE0A33E : 0xFF6E5A4C);
            t.setOnClickListener(v -> {
                if (busy) return;
                currentGroup = g;
                buildShelf();
            });
            tabs.addView(t);
        }

        canisters.removeAllViews();
        for (Film f : films) {
            String group = f.group.isEmpty() ? "COLOUR" : f.group;
            if (!group.equalsIgnoreCase(currentGroup)) continue;
            CanisterView c = new CanisterView(this);
            c.setFilm(f);
            c.setChosen(f.index == filmIndex);
            c.setTag(f.index);
            c.setOnClickListener(v -> {
                if (busy) return;
                chooseFilm((Integer) v.getTag());
            });
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams((int) dp(74), (int) dp(126));
            lp.rightMargin = (int) dp(8);
            lp.leftMargin = (int) dp(8);
            canisters.addView(c, lp);
        }
        scrollToChosen();

        chips.removeAllViews();
        if (filmIndex < 0 || filmIndex >= films.length) {
            filmBrandView.setText("");
            filmNameView.setText("");
            filmDescView.setText("");
            return;
        }
        Film f = films[filmIndex];
        filmBrandView.setText(f.brand.toUpperCase(Locale.US));
        filmNameView.setText(f.name);
        filmDescView.setText(f.desc);
        chips.addView(chip(getString(R.string.chip_iso), f.boxSpeed(), false));
        chips.addView(chip(getString(R.string.chip_process), f.process, false));
        chips.addView(chip(getString(R.string.chip_exp), "36", true));
    }

    private void scrollToChosen() {
        if (filmIndex < 0) return;
        canisterScroll.post(() -> {
            for (int i = 0; i < canisters.getChildCount(); i++) {
                View v = canisters.getChildAt(i);
                if (v.getTag() instanceof Integer && (Integer) v.getTag() == filmIndex) {
                    int x = v.getLeft() - (canisterScroll.getWidth() - v.getWidth()) / 2;
                    canisterScroll.smoothScrollTo(Math.max(0, x), 0);
                    return;
                }
            }
        });
    }

    private TextView chip(String label, String value, boolean accent) {
        TextView t = new TextView(this);
        t.setText(label + "  " + value);
        t.setTextSize(11);
        t.setLetterSpacing(0.06f);
        t.setTextColor(accent ? 0xFFE0A33E : 0xFFF1DFC0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x00000000);
        bg.setStroke(Math.max(1, (int) dp(1)), accent ? 0xFFE0A33E : 0x66DDBB90);
        t.setBackground(bg);
        t.setPadding((int) dp(11), (int) dp(5), (int) dp(11), (int) dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int) dp(3);
        lp.rightMargin = (int) dp(3);
        t.setLayoutParams(lp);
        return t;
    }

    private void chooseFilm(int i) {
        if (i < 0 || i >= films.length) return;
        filmIndex = i;
        if (!films[i].group.isEmpty()) currentGroup = films[i].group;
        prefs.edit().putString("film", films[i].key).apply();
        buildShelf();
        refreshPreview();
    }

    /* ---------------------------------------------------------- the session */

    private void saveKnobs() {
        prefs.edit().putInt("shadows", shadows).putInt("style", bloomStyle)
                .putInt("amount", bloomAmount).putInt("range", bloomRange)
                .putInt("spread", bloomSpread).putInt("warmth", bloomWarmth).apply();
    }

    /** the frames last session had, if Android still lets us read them */
    private void restoreFrames() {
        String saved = prefs.getString("frames", "");
        if (saved == null || saved.isEmpty()) return;
        for (String s : saved.split("\n")) {
            if (s.isEmpty()) continue;
            try {
                Uri u = Uri.parse(s);
                grant(u);
                if (!picked.contains(u)) picked.add(u);
            } catch (Exception ignored) {
                // a frame the card no longer has
            }
        }
    }

    private void saveFrames() {
        StringBuilder sb = new StringBuilder();
        for (Uri u : picked) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(u.toString());
        }
        prefs.edit().putString("frames", sb.toString()).apply();
    }

    /* ------------------------------------------------------------- preview */

    /**
     * The frame with the chosen film on it, at the engine's 720 x 480 print.
     * Same code path as a finished JPEG, just smaller, so what you see is
     * what DEVELOP writes. Tapping the preview goes to the next picked frame,
     * so a roll can be looked through with one film on it.
     */
    private void refreshPreview() {
        final int seq = previewSeq.incrementAndGet();
        if (picked.isEmpty() || filmIndex < 0) {
            previewImage.setImageDrawable(null);
            previewNote.setText(getString(R.string.preview_empty));
            return;
        }
        final int count = picked.size();
        final int at = Math.floorMod(previewIndex, count);
        final Uri src = picked.get(at);
        final String name = displayName(src);
        final int film = filmIndex;
        final boolean stamp = stampSwitch.isChecked();
        final String filmName = films[film].name;
        previewBitmap = null;                 // the old frame's prints are not this one's
        plainBitmap = null;
        framedPreview = null;
        framedPlain = null;
        holding = false;
        previewNote.setText(getString(R.string.preview_working));

        previews.execute(() -> {
            File dir = new File(getCacheDir(), "preview");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                ui(() -> previewNote.setText(getString(R.string.preview_failed, "no cache dir")));
                return;
            }
            File jpeg = new File(dir, "frame.jpg");
            final String key = src.toString();
            File work = new File(dir, name);
            try {
                int r;
                if (!key.equals(previewFileKey) || !work.isFile()) {
                    copy(src, work);          // once a frame: the decode is kept after this
                    previewFileKey = key;
                }
                if (PictureLoader.isCr2(name)) {
                    r = MfilmNative.previewOpen(work.getAbsolutePath(), film, when(lastModified(src)), key);
                    if (r == 0) r = renderKept(film, stamp, jpeg);
                    if (r != 0) r = developOne(src, dir, jpeg, film, 0, stamp, when(lastModified(src)), null);
                } else {
                    r = previewPicture(key, work, name, jpeg, film, stamp, when(lastModified(src)));
                    if (r != 0) r = developOne(src, dir, jpeg, film, 0, stamp, when(lastModified(src)), null);
                }
                if (seq != previewSeq.get()) return;         // a newer frame or film won
                if (r != 0 || !jpeg.isFile()) {
                    final String err = MfilmNative.lastError();
                    ui(() -> previewNote.setText(getString(R.string.preview_failed,
                            err.isEmpty() ? "engine" : err)));
                    return;
                }
                Bitmap b = BitmapFactory.decodeFile(jpeg.getAbsolutePath());
                if (b == null) return;
                final Bitmap plain = plainFor(key, film, dir, stamp);
                final String note = count > 1
                        ? getString(R.string.preview_ready_tap, filmName, name, at + 1, count)
                        : getString(R.string.preview_ready, filmName, name);
                ui(() -> {
                    if (seq != previewSeq.get()) return;
                    previewBitmap = b;
                    plainBitmap = plain;
                    heldNote = note;
                    renderFramed();
                    previewNote.setText(holding ? getString(R.string.compare_note) : note);
                });
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                ui(() -> previewNote.setText(getString(R.string.preview_failed, msg)));
            }
        });
    }

    /** the kept frame with the knobs on screen: seconds of work, not a RAW read */
    private int renderKept(int film, boolean stamp, File jpeg) {
        return MfilmNative.previewRender(film, shadows, bloomStyle, bloomAmount, bloomRange,
                bloomSpread, bloomWarmth, stamp ? 1 : 0, jpeg.getAbsolutePath());
    }

    /** a picture's preview: its pixels go to the engine once, which keeps them */
    private int previewPicture(String key, File work, String name, File jpeg, int film,
                               boolean stamp, String when) throws IOException {
        if (MfilmNative.previewCached(key) == 0) {
            Bitmap b = PictureLoader.load(work, name);
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            b.recycle();
            if (MfilmNative.previewOpenImage(px, w, h, film, when, key) != 0) return -1;
        }
        return renderKept(film, stamp, jpeg);
    }

    /**
     * The same frame with no Shadows and no glow: the print the knobs work on.
     * It is rendered once per frame and film, so a held finger only swaps two
     * bitmaps and never waits for the engine.
     */
    private Bitmap plainFor(String key, int film, File dir, boolean stamp) {
        File out = new File(dir, "plain.jpg");
        String tag = key + "|" + film + "|" + (stamp ? 1 : 0);
        if (!tag.equals(plainOf) || !out.isFile()) {
            if (MfilmNative.previewRender(film, 0, 0, 50, 50, 50, 0, stamp ? 1 : 0,
                    out.getAbsolutePath()) != 0) {
                return null;
            }
            plainOf = tag;
        }
        return BitmapFactory.decodeFile(out.getAbsolutePath());
    }

    /** the tones the preview came out with: 64 bins of luma, for the bar under it */
    private static int[] histogramOf(Bitmap b) {
        int[] bins = new int[64];
        int w = b.getWidth(), h = b.getHeight();
        if (w <= 0 || h <= 0) return bins;
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int p : px) {
            int l = (((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8;
            bins[Math.min(63, Math.max(0, l >> 2))]++;
        }
        return bins;
    }

    /** Press and hold the preview: the same frame with the knobs taken off. */
    private void compare(boolean on) {
        if (on) {
            if (holding || framedPlain == null || framedPreview == null) return;
            holding = true;
            heldNote = previewNote.getText().toString();
            previewImage.setImageBitmap(framedPlain);
            previewNote.setText(getString(R.string.compare_note));
        } else {
            if (!holding) return;
            holding = false;
            previewImage.setImageBitmap(framedPreview);
            if (heldNote != null) previewNote.setText(heldNote);
        }
    }

    /* ---------------------------------------------------- straighten · crop */

    /**
     * The frame turned and cut to the crop, from the bitmaps already in hand:
     * dragging the angle costs a rotation, not a develop. The print gets the
     * same window, in Geometry.rewrite, once the engine has written it.
     */
    private void renderFramed() {
        if (previewBitmap == null) {
            previewImage.setImageDrawable(null);
            return;
        }
        if (Geometry.isSet(straighten, cropShape, cropZoom)) {
            if (framedPreview != previewBitmap && framedPreview != null) framedPreview.recycle();
            if (framedPlain != plainBitmap && framedPlain != null) framedPlain.recycle();
            framedPreview = Geometry.apply(previewBitmap, straighten, cropShape, cropZoom);
            framedPlain = plainBitmap == null ? null
                    : Geometry.apply(plainBitmap, straighten, cropShape, cropZoom);
        } else {
            framedPreview = previewBitmap;
            framedPlain = plainBitmap;
        }
        previewImage.setImageBitmap(holding && framedPlain != null ? framedPlain : framedPreview);
        histogram.setBins(histogramOf(framedPreview));
    }

    /** the crop the phone is on, as the line under its title says */
    private String shapeName(int shape) {
        switch (shape) {
            case 1: return getString(R.string.shape_square);
            case 2: return getString(R.string.shape_portrait);
            case 3: return getString(R.string.shape_three_two);
            case 4: return getString(R.string.shape_wide);
            default: return getString(R.string.shape_whole);
        }
    }

    private String[] shapeNames() {
        String[] all = new String[Geometry.SHAPES];
        for (int i = 0; i < all.length; i++) all[i] = shapeName(i);
        return all;
    }

    private String frameText() {
        if (!Geometry.isSet(straighten, cropShape, cropZoom)) return getString(R.string.frame_none);
        StringBuilder sb = new StringBuilder();
        if (straighten != 0) sb.append(String.format(Locale.US, "%+.1f\u00B0", straighten / 10.0));
        if (cropShape > 0) {
            if (sb.length() > 0) sb.append(" \u00B7 ");
            sb.append(shapeName(cropShape));
        }
        if (cropZoom != 100) {
            if (sb.length() > 0) sb.append(" \u00B7 ");
            sb.append(getString(R.string.frame_zoom_line, cropZoom));
        }
        return sb.toString();
    }

    /** the angle or the zoom moved: the preview again, and what it is on the line */
    private void framePreview() {
        if (frameNote != null) frameNote.setText(frameText());
        renderFramed();
    }

    private void saveFrame() {
        prefs.edit().putInt("straighten", straighten).putInt("shape", cropShape)
                .putInt("zoom", cropZoom).apply();
        framePreview();
    }

    /**
     * Straighten and crop, above the presets: one slider for the angle, one
     * for the shape, one for how tight the crop is. They cost no develop --
     * the preview's bitmaps are rotated as the finger moves -- and DEVELOP
     * cuts the print with the same window.
     */
    private void buildFrame() {
        TextView title = new TextView(this);
        title.setText(getString(R.string.frame_title));
        title.setTextSize(11);
        title.setLetterSpacing(0.16f);
        title.setTextColor(0xFFDDBB90);
        title.setPadding(0, (int) dp(12), 0, 0);
        adjust.addView(title);

        frameNote = new TextView(this);
        frameNote.setTextSize(11);
        frameNote.setTextColor(0xFF9A7F6B);
        frameNote.setText(frameText());
        adjust.addView(frameNote);

        /* the angle, in tenths of a degree */
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        TextView head = new TextView(this);
        head.setTextSize(11);
        head.setLetterSpacing(0.06f);
        head.setTextColor(0xFFDDBB90);
        SeekBar bar = new SeekBar(this);
        bar.setMax(200);
        bar.setProgress(straighten + 100);
        head.setText(getString(R.string.frame_straighten) + "   "
                + String.format(Locale.US, "%+.1f\u00B0", straighten / 10.0));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                straighten = p - 100;
                head.setText(getString(R.string.frame_straighten) + "   "
                        + String.format(Locale.US, "%+.1f\u00B0", straighten / 10.0));
                if (fromUser) framePreview();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { saveFrame(); }
        });
        row.addView(head);
        row.addView(bar);
        adjust.addView(row);

        addChoice(getString(R.string.frame_crop), shapeNames(), cropShape,
                v -> cropShape = v, this::saveFrame);

        /* how tight the crop is: 100% is as big as the turned frame allows */
        LinearLayout zrow = new LinearLayout(this);
        zrow.setOrientation(LinearLayout.VERTICAL);
        TextView zhead = new TextView(this);
        zhead.setTextSize(11);
        zhead.setLetterSpacing(0.06f);
        zhead.setTextColor(0xFFDDBB90);
        zhead.setText(getString(R.string.frame_zoom) + "   " + cropZoom + "%");
        SeekBar zbar = new SeekBar(this);
        zbar.setMax(100);
        zbar.setProgress(cropZoom - 100);
        zbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                cropZoom = 100 + p;
                zhead.setText(getString(R.string.frame_zoom) + "   " + cropZoom + "%");
                if (fromUser) framePreview();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { saveFrame(); }
        });
        zrow.addView(zhead);
        zrow.addView(zbar);
        adjust.addView(zrow);
    }

    /** the next picked frame, for the preview: a roll, one film at a time */
    private void nextPreview() {
        if (picked.size() < 2) return;
        previewIndex = Math.floorMod(previewIndex + 1, picked.size());
        refreshPreview();
    }

    /**
     * One frame through the engine: a CR2 as it comes, and anything else as
     * the picture inside it (an image, or the JPEG a RAW of another make
     * carries). Leaves the finished JPEG at jpeg and says where the engine
     * went wrong, if it did.
     */
    private int developOne(Uri src, File dir, File jpeg, int film, int size, boolean stamp,
                           String when, MfilmNative.Progress progress) throws IOException {
        String name = displayName(src);
        File work = new File(dir, name);
        try {
            copy(src, work);
            if (PictureLoader.isCr2(name)) {
                return MfilmNative.develop(work.getAbsolutePath(), jpeg.getAbsolutePath(), film, size,
                        stamp ? 1 : 0, shadows, bloomStyle, bloomAmount, bloomRange, bloomSpread,
                        bloomWarmth, when, progress);
            }
            Bitmap b = PictureLoader.load(work, name);
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            b.recycle();
            return MfilmNative.developImage(px, w, h, jpeg.getAbsolutePath(), film, size,
                    stamp ? 1 : 0, shadows, bloomStyle, bloomAmount, bloomRange, bloomSpread,
                    bloomWarmth, when, progress);
        } finally {
            work.delete();
        }
    }

    /* -------------------------------------------------------------- the knobs */

    /**
     * Shadows and the glow panel: the same set of things Adobe's Glow tab
     * has, at the values it starts from (all at 50, warmth 0). They go to the
     * engine with every preview and every DEVELOP.
     */
    private void buildAdjustments() {
        buildFrame();
        buildPresets();
        shadowsBar = addSlider(getString(R.string.shadow), 0, 100, shadows, v -> shadows = v);
        addChoice(getString(R.string.bloom_style),
                new String[]{ getString(R.string.bloom_off), getString(R.string.bloom_bloom),
                        getString(R.string.bloom_diffusion), getString(R.string.bloom_halation),
                        getString(R.string.bloom_motion), getString(R.string.bloom_streak) },
                bloomStyle, v -> bloomStyle = v);
        amountBar = addSlider(getString(R.string.bloom_amount), 0, 100, bloomAmount, v -> bloomAmount = v);
        rangeBar = addSlider(getString(R.string.bloom_range), 0, 100, bloomRange, v -> bloomRange = v);
        spreadBar = addSlider(getString(R.string.bloom_spread), 0, 100, bloomSpread, v -> bloomSpread = v);
        warmthBar = addSlider(getString(R.string.bloom_warmth), -100, 100, bloomWarmth, v -> bloomWarmth = v);
    }

    /** one slider: its value shown beside its name, and the preview again once the finger is lifted */
    private SeekBar addSlider(String label, int min, int max, int value, IntConsumer set) {
        return addSlider(label, min, max, value, set, this::knobsChanged);
    }

    private SeekBar addSlider(String label, int min, int max, int value, IntConsumer set, Runnable after) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        TextView head = new TextView(this);
        head.setTextSize(11);
        head.setLetterSpacing(0.06f);
        head.setTextColor(0xFFDDBB90);
        head.setText(label + "   " + (value > 0 && min < 0 ? "+" + value : String.valueOf(value)));
        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setProgress(value - min);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = p + min;
                set.accept(v);
                head.setText(label + "   " + (v > 0 && min < 0 ? "+" + v : String.valueOf(v)));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                if (applyingPreset) return;
                after.run();
            }
        });
        row.addView(head);
        row.addView(bar);
        adjust.addView(row);
        return bar;
    }

    /** the knobs moved: kept, and the frame developed again at the new values */
    private void knobsChanged() {
        saveKnobs();
        refreshPreview();
    }

    /** the glow's style: a spinner, as the darkroom's own pages have */
    private void addChoice(String label, String[] choices, int value, IntConsumer set) {
        addChoice(label, choices, value, set, this::knobsChanged);
    }

    private void addChoice(String label, String[] choices, int value, IntConsumer set, Runnable after) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView head = new TextView(this);
        head.setTextSize(11);
        head.setLetterSpacing(0.06f);
        head.setTextColor(0xFFDDBB90);
        head.setText(label);
        head.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, choices);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(a);
        sp.setSelection(value);
        styleSpinner = sp;              // the glow's style is the only choice row there is
        sp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View v, int pos, long id) {
                set.accept(pos);
                if (applyingPreset) return;
                after.run();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        row.addView(head);
        row.addView(sp);
        adjust.addView(row);
    }

    /* -------------------------------------------------------------- presets */

    /** one darkroom kept by name: the film, every knob, the size and the stamp */
    private static final class Preset {
        String name = "", film = "";
        int shadows, style, amount = 50, range = 50, spread = 50, warmth;
        int size = 1;
        boolean stamp;

        String line() {
            return name + '\t' + film + '\t' + shadows + '\t' + style + '\t' + amount + '\t'
                    + range + '\t' + spread + '\t' + warmth + '\t' + size + '\t' + (stamp ? 1 : 0);
        }

        static Preset parse(String line) {
            String[] p = line.split("\t", -1);
            if (p.length < 10) return null;
            Preset s = new Preset();
            try {
                s.name = p[0];
                s.film = p[1];
                s.shadows = Integer.parseInt(p[2]);
                s.style = Integer.parseInt(p[3]);
                s.amount = Integer.parseInt(p[4]);
                s.range = Integer.parseInt(p[5]);
                s.spread = Integer.parseInt(p[6]);
                s.warmth = Integer.parseInt(p[7]);
                s.size = Integer.parseInt(p[8]);
                s.stamp = "1".equals(p[9]);
            } catch (NumberFormatException e) {
                return null;
            }
            return s.name.isEmpty() ? null : s;
        }
    }

    /**
     * The darkrooms kept by name sit above the sliders: a tap puts the film,
     * every knob, the size and the stamp back where they were, and a long
     * press forgets the name.
     */
    private void buildPresets() {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, 0, 0, (int) dp(8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(getString(R.string.presets_title));
        title.setTextSize(11);
        title.setLetterSpacing(0.16f);
        title.setTextColor(0xFFDDBB90);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        head.addView(title);

        Button save = new Button(this);
        save.setText(getString(R.string.preset_save));
        save.setTextSize(11);
        save.setOnClickListener(v -> askPresetName());
        head.addView(save);
        block.addView(head);

        presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(presetRow);
        block.addView(scroll);

        adjust.addView(block);
        fillPresetRow();
    }

    private void fillPresetRow() {
        if (presetRow == null) return;
        presetRow.removeAllViews();
        if (presets.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText(getString(R.string.preset_none));
            hint.setTextSize(11);
            hint.setTextColor(0xFF9A7F6B);
            hint.setPadding(0, (int) dp(2), 0, (int) dp(2));
            presetRow.addView(hint);
            return;
        }
        for (Preset p : presets) {
            TextView chip = new TextView(this);
            chip.setText(p.name);
            chip.setTextSize(11);
            chip.setTextColor(0xFFF1DFC0);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
            bg.setColor(0x00000000);
            bg.setStroke(Math.max(1, (int) dp(1)), 0x66DDBB90);
            chip.setBackground(bg);
            chip.setPadding((int) dp(11), (int) dp(5), (int) dp(11), (int) dp(5));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) dp(6);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> applyPreset(p));
            chip.setOnLongClickListener(v -> {
                confirmDelete(p);
                return true;
            });
            presetRow.addView(chip);
        }
    }

    private void askPresetName() {
        if (filmIndex < 0) {
            toast(getString(R.string.choose_first));
            return;
        }
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setHint(getString(R.string.preset_hint));
        input.setText(films[filmIndex].name);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle(R.string.preset_save)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = getString(R.string.preset_unnamed);
                    if (name.length() > 28) name = name.substring(0, 28);
                    storePreset(name);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void storePreset(String name) {
        Preset p = new Preset();
        p.name = name;
        p.film = films[filmIndex].key;
        p.shadows = shadows;
        p.style = bloomStyle;
        p.amount = bloomAmount;
        p.range = bloomRange;
        p.spread = bloomSpread;
        p.warmth = bloomWarmth;
        p.size = sizeSpinner.getSelectedItemPosition();
        p.stamp = stampSwitch.isChecked();
        for (int i = presets.size() - 1; i >= 0; i--) {
            if (presets.get(i).name.equalsIgnoreCase(name)) presets.remove(i);
        }
        presets.add(p);
        savePresets();
        fillPresetRow();
        toast(getString(R.string.preset_saved, name));
    }

    private void confirmDelete(Preset p) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.preset_delete, p.name))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    presets.remove(p);
                    savePresets();
                    fillPresetRow();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** everything the name kept, back on the shelf and on the sliders */
    private void applyPreset(Preset p) {
        applyingPreset = true;
        try {
            int idx = indexOfKey(p.film);
            if (idx >= 0) {
                filmIndex = idx;
                if (!films[idx].group.isEmpty()) currentGroup = films[idx].group;
                prefs.edit().putString("film", films[idx].key).apply();
                buildShelf();
            }
            shadowsBar.setProgress(clamp(p.shadows, 0, 100));
            amountBar.setProgress(clamp(p.amount, 0, 100));
            rangeBar.setProgress(clamp(p.range, 0, 100));
            spreadBar.setProgress(clamp(p.spread, 0, 100));
            warmthBar.setProgress(clamp(p.warmth, -100, 100) + 100);
            styleSpinner.setSelection(clamp(p.style, 0, 5));
            sizeSpinner.setSelection(clamp(p.size, 0, 1));
            stampSwitch.setChecked(p.stamp);
        } finally {
            applyingPreset = false;
        }
        saveKnobs();
        prefs.edit().putInt("size", sizeSpinner.getSelectedItemPosition())
                .putBoolean("stamp", stampSwitch.isChecked()).apply();
        refreshPreview();
        toast(getString(R.string.preset_applied, p.name));
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void loadPresets() {
        presets.clear();
        String saved = prefs.getString("presets", "");
        if (saved != null) {
            for (String line : saved.split("\n")) {
                if (line.isEmpty()) continue;
                Preset p = Preset.parse(line);
                if (p != null) presets.add(p);
            }
        }
    }

    private void savePresets() {
        StringBuilder sb = new StringBuilder();
        for (Preset p : presets) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(p.line());
        }
        prefs.edit().putString("presets", sb.toString()).apply();
    }

    /* -------------------------------------------------------------- picking */

    private void pickRaws() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK_RAWS);
    }

    /** The folder holding ROLL_NNN.TXT; the roll screen finds the frames in it. */
    private void pickRollFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try {
            startActivityForResult(i, PICK_ROLL_FOLDER);
        } catch (Exception e) {
            toast(getString(R.string.no_gallery));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == PICK_RAWS) {
            picked.clear();
            previewIndex = 0;
            rollFolder = null;                 // frames picked one by one are no roll
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    Uri u = data.getClipData().getItemAt(i).getUri();
                    grant(u);
                    picked.add(u);
                }
            } else if (data.getData() != null) {
                grant(data.getData());
                picked.add(data.getData());
            }
            updateFiles();
            refreshPreview();
        } else if (requestCode == PICK_ROLL_FOLDER && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // some providers hand out one-off grants only
            }
            Intent roll = new Intent(this, RollActivity.class);
            roll.putExtra(RollActivity.EXTRA_TREE, tree.toString());
            startActivityForResult(roll, ROLL_FRAMES);
        } else if (requestCode == ROLL_FRAMES) {
            applyRoll(data);
        }
    }

    /** The frames the roll screen handed back, with the roll's own film. */
    private void applyRoll(Intent data) {
        ArrayList<String> uris = data.getStringArrayListExtra(RollActivity.EXTRA_FRAMES);
        if (uris == null || uris.isEmpty()) return;
        String key = data.getStringExtra(RollActivity.EXTRA_FILM);
        String roll = data.getStringExtra(RollActivity.EXTRA_ROLL);
        rollFolder = folderFor(roll);          // its prints go in the roll's own folder
        picked.clear();
        previewIndex = 0;
        for (String s : uris) {
            Uri u = Uri.parse(s);
            grant(u);
            picked.add(u);
        }
        int idx = indexOfKey(key);
        if (idx >= 0) {
            filmIndex = idx;
            if (!films[idx].group.isEmpty()) currentGroup = films[idx].group;
            prefs.edit().putString("film", films[idx].key).apply();
        }
        buildShelf();
        updateFiles();
        refreshPreview();
        log(getString(R.string.roll_added, roll == null ? "" : roll, picked.size()));
    }

    /** keeps the read permission across the session */
    private void grant(Uri u) {
        try {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // some providers hand out one-off grants only
        }
    }

    private void updateFiles() {
        filesView.setText(getResources().getQuantityString(R.plurals.frames, picked.size(), picked.size()));
        developButton.setEnabled(!busy && !picked.isEmpty() && filmIndex >= 0);
        saveFrames();
    }

    /* ------------------------------------------------------------ developing */

    private void developAll() {
        if (picked.isEmpty()) {
            toast(getString(R.string.choose_first));
            return;
        }
        if (filmIndex < 0) {
            toast(getString(R.string.choose_first));
            return;
        }
        final int film = filmIndex;
        final int size = sizeSpinner.getSelectedItemPosition() + 1;      // FOS_SIZE_M, _L
        final boolean stamp = stampSwitch.isChecked();
        final int turn = straighten, shape = cropShape, zoom = cropZoom;
        final List<Uri> raws = new ArrayList<>(picked);
        final String filmName = films[film].name;
        final String filmKey = films[film].key;
        final String folder = rollFolder;

        previewSeq.incrementAndGet();     // a preview in flight must not fight for the frame
        keepAwake(getString(R.string.keep_awake_start, raws.size()), true);
        setBusy(true);
        progressBar.setProgress(0);
        log(getString(R.string.developing, raws.size(), films[film].label()));

        new Thread(() -> {
            MfilmNative.previewClose();       // the kept frame gives its memory to the batch
            previewFileKey = null;
            File work = new File(getCacheDir(), "raw");
            if (!work.isDirectory() && !work.mkdirs()) {
                ui(() -> {
                    log(getString(R.string.engine_failed, "no cache dir"));
                    setBusy(false);
                });
                return;
            }
            int ok = 0, failed = 0;
            final List<String[]> rows = new ArrayList<>();      // what the manifest records
            final long t0 = System.currentTimeMillis();
            for (int i = 0; i < raws.size(); i++) {
                final int n = i + 1;
                Uri uri = raws.get(i);
                String name = displayName(uri);
                String base = name.replaceAll("\\.[^.]*$", "");
                String when = when(lastModified(uri));
                try {
                    File jpeg = new File(work, base + ".jpg");
                    ui(() -> statusView.setText(getString(R.string.working, n, raws.size(), name)));
                    keepAwake(getString(R.string.keep_awake_frame, n, raws.size(), name), false);
                    long s0 = System.currentTimeMillis();
                    int r = developOne(uri, work, jpeg, film, size, stamp, when,
                            p -> runOnUiThread(() -> progressBar.setProgress(p / 10)));
                    if (r != 0) {
                        failed++;
                        final String err = MfilmNative.lastError();
                        ui(() -> log("\u2717 " + name + ": " + err));
                        jpeg.delete();
                        continue;
                    }
                    /* the print the preview showed: turned and cut the same way */
                    if (Geometry.isSet(turn, shape, zoom)) Geometry.rewrite(jpeg, turn, shape, zoom);
                    long ms = System.currentTimeMillis() - s0;
                    String out = save(jpeg, "MFILM_" + stampFile(when) + "_" + filmKey + "_" + base + ".jpg",
                            folder);
                    jpeg.delete();
                    ok++;
                    rows.add(new String[]{name, out, filmKey, String.valueOf(shadows),
                            String.valueOf(bloomStyle), String.valueOf(bloomAmount),
                            String.valueOf(bloomRange), String.valueOf(bloomSpread),
                            String.valueOf(bloomWarmth), size == 1 ? "medium" : "large",
                            stamp ? "yes" : "no", when,
                            String.format(Locale.US, "%.1f", ms / 1000.0)});
                    final String line = getString(R.string.frame_done, name, ms / 1000.0, out);
                    ui(() -> log("\u2713 " + line));
                } catch (Exception e) {
                    failed++;
                    final String err = e.getMessage() == null ? e.toString() : e.getMessage();
                    ui(() -> log("\u2717 " + name + ": " + err));
                }
            }
            if (folder != null && !rows.isEmpty()) {
                try {
                    appendManifest(folder, rows);
                    final String where = "Pictures/M-Film/" + folder + "/" + MANIFEST;
                    ui(() -> log(getString(R.string.manifest_written, where)));
                } catch (Exception e) {
                    final String err = e.getMessage() == null ? e.toString() : e.getMessage();
                    ui(() -> log(getString(R.string.manifest_failed, err)));
                }
            }
            keepAwake(null, false);
            final int okf = ok, bad = failed;
            final long total = System.currentTimeMillis() - t0;
            ui(() -> {
                progressBar.setProgress(100);
                statusView.setText(getString(R.string.done, okf, bad));
                log(getString(R.string.done, okf, bad) + " " + getString(R.string.took, total / 1000.0));
                setBusy(false);
                if (okf > 0) {
                    galleryButton.setVisibility(View.VISIBLE);
                    shareButton.setVisibility(View.VISIBLE);
                }
                refreshPreview();
            });
        }).start();
    }

    private void ui(Runnable r) { runOnUiThread(r); }

    /**
     * A roll takes minutes, and the person developing it will look at the
     * gallery meanwhile: a foreground service with a wake lock keeps the batch
     * running and the screen's timeout out of it. The same service carries the
     * notification's line as the frames go by.
     */
    private void keepAwake(String text, boolean start) {
        Intent i = new Intent(this, KeepAwakeService.class);
        if (text == null) {
            i.setAction(KeepAwakeService.ACTION_STOP);
        } else {
            i.setAction(KeepAwakeService.ACTION_START).putExtra(KeepAwakeService.EXTRA_TEXT, text);
        }
        try {
            if (start && Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception ignored) {
            // the batch still runs; only the notification is missing
        }
        if (start && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

    /* ---------------------------------------------------------------- saving */

    /**
     * Copies the developed JPEG into Pictures/M-Film -- or into the roll's own
     * folder under it -- and returns where it went.
     */
    private String save(File jpeg, String name, String folder) throws IOException {
        String relative = Environment.DIRECTORY_PICTURES + "/M-Film"
                + (folder == null ? "" : "/" + folder);
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, relative);
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        ContentResolver cr = getContentResolver();
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("MediaStore refused " + name);
        try (InputStream in = new FileInputStream(jpeg);
             OutputStream out = cr.openOutputStream(uri)) {
            if (out == null) throw new IOException("can't write " + name);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException e) {
            cr.delete(uri, null, null);
            throw e;
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        lastPrint = uri;                  // what SHARE THE PRINT hands on
        lastPrintName = name;
        return relative + "/" + name;
    }

    /** "ROLL_003" for roll 003: one folder per roll in Pictures/M-Film. */
    private static String folderFor(String roll) {
        if (roll == null) return null;
        String digits = roll.replaceAll("[^0-9A-Za-z]", "");
        return digits.isEmpty() ? null : "ROLL_" + digits;
    }

    /**
     * A roll keeps a manifest.csv beside its prints, one row a frame: which
     * film, every knob, the size, the stamp and how long it took. Each batch
     * appends to it, so a roll developed over two evenings is one file.
     */
    private void appendManifest(String folder, List<String[]> rows) throws IOException {
        ContentResolver cr = getContentResolver();
        Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String relative = Environment.DIRECTORY_PICTURES + "/M-Film/" + folder;
        Uri existing = findFile(cr, collection, relative, MANIFEST);

        StringBuilder sb = new StringBuilder();
        if (existing == null) {
            sb.append(MANIFEST_HEADER).append('\n');
        } else {
            String old = readText(cr, existing);
            sb.append(old);
            if (!old.isEmpty() && !old.endsWith("\n")) sb.append('\n');
        }
        for (String[] row : rows) sb.append(csv(row)).append('\n');

        if (existing == null) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, MANIFEST);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "text/csv");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, relative);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            existing = cr.insert(collection, v);
            if (existing == null) throw new IOException("MediaStore refused " + MANIFEST);
        }
        try (OutputStream out = cr.openOutputStream(existing, "wt")) {
            if (out == null) throw new IOException("can't write " + MANIFEST);
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(existing, done, null, null);
    }

    private Uri findFile(ContentResolver cr, Uri collection, String relative, String name) {
        String[] where = {relative + "/", relative, name};
        try (Cursor c = cr.query(collection, new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + " IN (?, ?) AND "
                        + MediaStore.MediaColumns.DISPLAY_NAME + " = ?", where, null)) {
            if (c != null && c.moveToFirst()) return ContentUris.withAppendedId(collection, c.getLong(0));
        } catch (Exception ignored) {
            // a provider that won't answer: the manifest is written afresh
        }
        return null;
    }

    private String readText(ContentResolver cr, Uri uri) throws IOException {
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) return "";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** one CSV line, quoting whatever would break a column */
    private static String csv(String[] row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.length; i++) {
            if (i > 0) sb.append(',');
            String cell = row[i] == null ? "" : row[i];
            if (cell.indexOf(',') >= 0 || cell.indexOf('"') >= 0 || cell.indexOf('\n') >= 0) {
                sb.append('"').append(cell.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(cell);
            }
        }
        return sb.toString();
    }

    /** The print DEVELOP last wrote, to whichever app the phone offers. */
    private void sharePrint() {
        if (lastPrint == null) {
            toast(getString(R.string.share_nothing));
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("image/jpeg");
        send.putExtra(Intent.EXTRA_STREAM, lastPrint);
        if (!lastPrintName.isEmpty()) send.putExtra(Intent.EXTRA_SUBJECT, lastPrintName);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_print)));
        } catch (Exception e) {
            toast(getString(R.string.no_gallery));
        }
    }

    private void openGallery() {
        Intent i = new Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        try {
            startActivity(i);
        } catch (Exception e) {
            toast(getString(R.string.no_gallery));
        }
    }

    /* ------------------------------------------------------------- plumbing */

    private void copy(Uri uri, File dst) throws IOException {
        InputStream in = "file".equals(uri.getScheme())
                ? new FileInputStream(uri.getPath())
                : getContentResolver().openInputStream(uri);
        try (InputStream src = in; OutputStream out = new FileOutputStream(dst)) {
            if (src == null) throw new IOException("can't open " + uri);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = src.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private String displayName(Uri uri) {
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            return path == null ? "frame" : new File(path).getName();
        }
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        } catch (Exception ignored) {
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "frame";
        return name;
    }

    private long lastModified(Uri uri) {
        if ("file".equals(uri.getScheme())) {
            String path = uri.getPath();
            if (path != null) return new File(path).lastModified();
        }
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Exception ignored) {
        }
        return System.currentTimeMillis();
    }

    private String when(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(millis));
    }

    private String stampFile(String when) {
        return when.replace("-", "").replace(" ", "_").replace(":", "");
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void setBusy(boolean on) {
        busy = on;
        pickButton.setEnabled(!on);
        rollButton.setEnabled(!on);
        developButton.setEnabled(!on && !picked.isEmpty() && filmIndex >= 0);
        sizeSpinner.setEnabled(!on);
        stampSwitch.setEnabled(!on);
        progressBar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) progressBar.setProgress(0);
    }

    private void log(String line) {
        logView.append(line + "\n");
        View parent = (View) logView.getParent();
        if (parent instanceof ScrollView) {
            ScrollView sv = (ScrollView) parent;
            sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }

    private void showLicences() {
        StringBuilder sb = new StringBuilder();
        for (String asset : new String[]{"mfilm/LICENSE.txt", "mfilm/THIRD_PARTY.txt"}) {
            try (InputStream in = getAssets().open(asset)) {
                byte[] b = new byte[(int) in.available()];
                int n = in.read(b);
                if (n > 0) sb.append(new String(b, 0, n));
            } catch (IOException ignored) {
            }
            sb.append("\n\n");
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.licences)
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
