package app.mfilm.darkroom;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private Button pickButton, developButton, galleryButton, rollButton;

    /* previews run one at a time; a newer request makes an older one stale */
    private final ExecutorService previews = Executors.newSingleThreadExecutor();
    private final AtomicInteger previewSeq = new AtomicInteger();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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
        previewImage = findViewById(R.id.preview);
        previewNote = findViewById(R.id.previewNote);
        filmBrandView = findViewById(R.id.filmBrand);
        filmNameView = findViewById(R.id.filmName);
        filmDescView = findViewById(R.id.filmDesc);
        tabs = findViewById(R.id.tabs);
        canisters = findViewById(R.id.canisters);
        chips = findViewById(R.id.chips);
        canisterScroll = findViewById(R.id.canisterScroll);

        ArrayAdapter<String> sizes = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"Medium · a third of the sensor", "Large · half the sensor"});
        sizes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sizeSpinner.setAdapter(sizes);
        sizeSpinner.setSelection(1);

        pickButton.setOnClickListener(v -> pickRaws());
        rollButton.setOnClickListener(v -> pickRollFolder());
        developButton.setOnClickListener(v -> developAll());
        galleryButton.setOnClickListener(v -> openGallery());
        versionView.setOnClickListener(v -> showLicences());
        stampSwitch.setOnCheckedChangeListener((b, on) -> refreshPreview());

        shared = uriFrom(getIntent());

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
                filmIndex = indexOfKey("portra400");
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
        buildShelf();
        refreshPreview();
    }

    /* ------------------------------------------------------------- preview */

    /**
     * The frame with the chosen film on it, at the engine's 720 x 480 print.
     * Same code path as a finished JPEG, just smaller, so what you see is
     * what DEVELOP writes.
     */
    private void refreshPreview() {
        final int seq = previewSeq.incrementAndGet();
        if (picked.isEmpty() || filmIndex < 0) {
            previewImage.setImageDrawable(null);
            previewNote.setText(getString(R.string.preview_empty));
            return;
        }
        final Uri src = picked.get(0);
        final int film = filmIndex;
        final boolean stamp = stampSwitch.isChecked();
        final String filmName = films[film].name;
        previewNote.setText(getString(R.string.preview_working));

        previews.execute(() -> {
            File dir = new File(getCacheDir(), "preview");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                ui(() -> previewNote.setText(getString(R.string.preview_failed, "no cache dir")));
                return;
            }
            File raw = new File(dir, "frame.CR2");
            File jpeg = new File(dir, "frame.jpg");
            try {
                copy(src, raw);
                int r = MfilmNative.develop(raw.getAbsolutePath(), jpeg.getAbsolutePath(),
                        film, 0, stamp ? 1 : 0, when(lastModified(src)), null);
                raw.delete();
                if (seq != previewSeq.get()) return;         // a newer film won
                if (r != 0 || !jpeg.isFile()) {
                    final String err = MfilmNative.lastError();
                    ui(() -> previewNote.setText(getString(R.string.preview_failed,
                            err.isEmpty() ? "engine" : err)));
                    return;
                }
                Bitmap b = BitmapFactory.decodeFile(jpeg.getAbsolutePath());
                if (b == null) return;
                ui(() -> {
                    if (seq != previewSeq.get()) return;
                    previewImage.setImageBitmap(b);
                    previewNote.setText(getString(R.string.preview_ready, filmName));
                });
            } catch (Exception e) {
                raw.delete();
                final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                ui(() -> previewNote.setText(getString(R.string.preview_failed, msg)));
            }
        });
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
        picked.clear();
        for (String s : uris) {
            Uri u = Uri.parse(s);
            grant(u);
            picked.add(u);
        }
        int idx = indexOfKey(key);
        if (idx >= 0) {
            filmIndex = idx;
            if (!films[idx].group.isEmpty()) currentGroup = films[idx].group;
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
        final List<Uri> raws = new ArrayList<>(picked);
        final String filmName = films[film].name;
        final String filmKey = films[film].key;

        previewSeq.incrementAndGet();     // a preview in flight must not fight for the frame
        setBusy(true);
        progressBar.setProgress(0);
        log(getString(R.string.developing, raws.size(), films[film].label()));

        new Thread(() -> {
            File work = new File(getCacheDir(), "raw");
            if (!work.isDirectory() && !work.mkdirs()) {
                ui(() -> {
                    log(getString(R.string.engine_failed, "no cache dir"));
                    setBusy(false);
                });
                return;
            }
            int ok = 0, failed = 0;
            final long t0 = System.currentTimeMillis();
            for (int i = 0; i < raws.size(); i++) {
                final int n = i + 1;
                Uri uri = raws.get(i);
                String name = displayName(uri);
                String base = name.replaceAll("\\.[^.]*$", "");
                String when = when(lastModified(uri));
                try {
                    File raw = new File(work, name);
                    copy(uri, raw);
                    File jpeg = new File(work, base + ".jpg");
                    ui(() -> statusView.setText(getString(R.string.working, n, raws.size(), name)));
                    long s0 = System.currentTimeMillis();
                    int r = MfilmNative.develop(raw.getAbsolutePath(), jpeg.getAbsolutePath(),
                            film, size, stamp ? 1 : 0, when,
                            p -> runOnUiThread(() -> progressBar.setProgress(p / 10)));
                    raw.delete();
                    if (r != 0) {
                        failed++;
                        final String err = MfilmNative.lastError();
                        ui(() -> log("\u2717 " + name + ": " + err));
                        jpeg.delete();
                        continue;
                    }
                    long ms = System.currentTimeMillis() - s0;
                    String out = save(jpeg, "MFILM_" + stampFile(when) + "_" + filmKey + "_" + base + ".jpg");
                    jpeg.delete();
                    ok++;
                    final String line = getString(R.string.frame_done, name, ms / 1000.0, out);
                    ui(() -> log("\u2713 " + line));
                } catch (Exception e) {
                    failed++;
                    final String err = e.getMessage() == null ? e.toString() : e.getMessage();
                    ui(() -> log("\u2717 " + name + ": " + err));
                }
            }
            final int okf = ok, bad = failed;
            final long total = System.currentTimeMillis() - t0;
            ui(() -> {
                progressBar.setProgress(100);
                statusView.setText(getString(R.string.done, okf, bad));
                log(getString(R.string.done, okf, bad) + " " + getString(R.string.took, total / 1000.0));
                setBusy(false);
                if (okf > 0) galleryButton.setVisibility(View.VISIBLE);
                refreshPreview();
            });
        }).start();
    }

    private void ui(Runnable r) { runOnUiThread(r); }

    /* ---------------------------------------------------------------- saving */

    /** Copies the developed JPEG into Pictures/M-Film and returns where it went. */
    private String save(File jpeg, String name) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/M-Film");
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
        return "Pictures/M-Film/" + name;
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
