package app.mfilm.darkroom;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
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

/**
 * M-Film Darkroom: develops the CR2s a Magic Lantern M-Film camera shot, on
 * the phone, with the camera's own open engine and the release's film colour
 * tables. A frame takes seconds here instead of the camera's minute.
 */
public class MainActivity extends Activity {

    private static final int PICK_RAWS = 42;

    private final List<Uri> picked = new ArrayList<>();
    private Uri shared;                      // a frame opened or shared into us
    private String[] filmLabels = new String[0];
    private String[] filmKeys = new String[0];

    private TextView filesView, logView, statusView, versionView;
    private Spinner filmSpinner, sizeSpinner;
    private Switch stampSwitch;
    private ProgressBar progressBar;
    private Button pickButton, developButton, galleryButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        filesView = findViewById(R.id.files);
        logView = findViewById(R.id.log);
        statusView = findViewById(R.id.status);
        versionView = findViewById(R.id.version);
        filmSpinner = findViewById(R.id.film);
        sizeSpinner = findViewById(R.id.size);
        stampSwitch = findViewById(R.id.stamp);
        progressBar = findViewById(R.id.progress);
        pickButton = findViewById(R.id.pick);
        developButton = findViewById(R.id.develop);
        galleryButton = findViewById(R.id.gallery);

        ArrayAdapter<String> sizes = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"Medium · a third of the sensor", "Large · half the sensor"});
        sizes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sizeSpinner.setAdapter(sizes);
        sizeSpinner.setSelection(1);

        pickButton.setOnClickListener(v -> pickRaws());
        developButton.setOnClickListener(v -> developAll());
        galleryButton.setOnClickListener(v -> openGallery());
        versionView.setOnClickListener(v -> showLicences());

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
        filesView.setText(getResources().getQuantityString(R.plurals.frames, picked.size(), picked.size()));
        developButton.setEnabled(!picked.isEmpty() && filmLabels.length > 0);
        if (filmLabels.length == 0) shared = uri;   // still loading the shelf
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

            int n = MfilmNative.loadFilms(bin.getAbsolutePath());
            if (n <= 0) throw new IOException(MfilmNative.lastError());
            MfilmNative.setTables(tables.getAbsolutePath());

            String[] labels = new String[n];
            String[] keys = new String[n];
            for (int i = 0; i < n; i++) {
                String info = MfilmNative.filmInfo(i);
                labels[i] = label(info);
                String[] p = info.split("\\|", -1);
                keys[i] = p.length > 5 ? p[5] : "film";
            }
            filmLabels = labels;
            filmKeys = keys;
            final String version = MfilmNative.version();
            runOnUiThread(() -> {
                ArrayAdapter<String> films = new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item, filmLabels);
                films.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                filmSpinner.setAdapter(films);
                int portra = indexOfKey("portra400");
                if (portra >= 0) filmSpinner.setSelection(portra);
                versionView.setText(getString(R.string.version, version));
                statusView.setText(getString(R.string.ready, filmLabels.length));
                setBusy(false);
                log(getString(R.string.engine_ready, filmLabels.length));
                if (shared != null) addFrame(shared);
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

    /** "Kodak|Portra 400|400|C-41|COLOUR|portra400" -> "Kodak Portra 400 · 400 ISO · C-41" */
    private String label(String info) {
        String[] p = info.split("\\|", -1);
        if (p.length < 6) return info;
        return (p[0] + " " + p[1]).trim() + " · " + p[2] + " ISO · " + p[3];
    }

    private int indexOfKey(String key) {
        for (int i = 0; i < filmKeys.length; i++) {
            if (filmKeys[i].equalsIgnoreCase(key)) return i;
        }
        return -1;
    }

    private void copyAsset(String name, File dst) throws IOException {
        try (InputStream in = getAssets().open(name);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /* -------------------------------------------------------------- picking */

    private void pickRaws() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK_RAWS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_RAWS || resultCode != RESULT_OK || data == null) return;
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
        filesView.setText(getResources().getQuantityString(R.plurals.frames, picked.size(), picked.size()));
        developButton.setEnabled(!picked.isEmpty() && filmLabels.length > 0);
    }

    /** keeps the read permission across the session */
    private void grant(Uri u) {
        try {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // some providers hand out one-off grants only
        }
    }

    /* ------------------------------------------------------------ developing */

    private void developAll() {
        if (picked.isEmpty()) {
            toast(getString(R.string.choose_first));
            return;
        }
        final int film = filmSpinner.getSelectedItemPosition();
        final int size = sizeSpinner.getSelectedItemPosition() + 1;      // FOS_SIZE_M, _L
        final boolean stamp = stampSwitch.isChecked();
        final List<Uri> raws = new ArrayList<>(picked);
        final String filmKey = filmKeys.length > film ? filmKeys[film] : "film";

        setBusy(true);
        progressBar.setProgress(0);
        log(getString(R.string.developing, raws.size(), filmLabels[film]));

        new Thread(() -> {
            File work = new File(getCacheDir(), "raw");
            if (!work.isDirectory() && !work.mkdirs()) {
                ui(() -> { log(getString(R.string.engine_failed, "no cache dir")); setBusy(false); });
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
                        ui(() -> log("✗ " + name + ": " + err));
                        jpeg.delete();
                        continue;
                    }
                    long ms = System.currentTimeMillis() - s0;
                    String out = save(jpeg, "MFILM_" + stampFile(when) + "_" + filmKey + "_" + base + ".jpg");
                    jpeg.delete();
                    ok++;
                    final String line = getString(R.string.frame_done, name, ms / 1000.0, out);
                    ui(() -> log("✓ " + line));
                } catch (Exception e) {
                    failed++;
                    final String err = e.getMessage() == null ? e.toString() : e.getMessage();
                    ui(() -> log("✗ " + name + ": " + err));
                }
            }
            final int okf = ok, bad = failed;
            final long total = System.currentTimeMillis() - t0;
            ui(() -> {
                progressBar.setProgress(100);
                statusView.setText(getString(R.string.done, okf, bad));
                log(getString(R.string.done, okf, bad) + " "
                        + getString(R.string.took, total / 1000.0));
                setBusy(false);
                if (okf > 0) openGalleryButton(true);
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

    private void openGalleryButton(boolean on) {
        galleryButton.setVisibility(on ? View.VISIBLE : View.GONE);
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

    private void setBusy(boolean busy) {
        pickButton.setEnabled(!busy);
        developButton.setEnabled(!busy && !picked.isEmpty() && filmLabels.length > 0);
        filmSpinner.setEnabled(!busy);
        sizeSpinner.setEnabled(!busy);
        stampSwitch.setEnabled(!busy);
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) progressBar.setProgress(0);
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
