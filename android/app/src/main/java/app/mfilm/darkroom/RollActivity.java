package app.mfilm.darkroom;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.SparseBooleanArray;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A roll from the camera: reads ROLL_NNN.TXT out of the folder, finds the
 * frames it lists, and shows them as a contact sheet developed with the film
 * the roll was shot on. Frames picked here go back to the darkroom.
 */
public class RollActivity extends Activity {

    static final String EXTRA_TREE = "app.mfilm.darkroom.tree";
    static final String EXTRA_FRAMES = "app.mfilm.darkroom.frames";
    static final String EXTRA_FILM = "app.mfilm.darkroom.film";
    static final String EXTRA_ROLL = "app.mfilm.darkroom.roll";

    private Uri tree;
    private final Map<String, Uri> docs = new LinkedHashMap<>();   // CR2s in the folder, by lower-case name
    private final Map<String, String> docNames = new LinkedHashMap<>();  // and the name as the card spells it
    private final List<RollLog> logs = new ArrayList<>();          // every ROLL_NNN.TXT, newest first
    private RollLog current;
    private int rollSeq;                                           // a new roll cancels the one still developing
    private final List<Uri> frames = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final List<Bitmap> thumbs = new ArrayList<>();
    private final SparseBooleanArray chosen = new SparseBooleanArray();

    private String filmKey = "";
    private String rollNumber = "";
    private int filmIndex = -1;

    private TextView title, sub, status;
    private ProgressBar bar;
    private Button add, pickLog, sheet;
    private GridView grid;
    private RollAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_roll);

        title = findViewById(R.id.rollTitle);
        sub = findViewById(R.id.rollSub);
        status = findViewById(R.id.rollStatus);
        bar = findViewById(R.id.rollBar);
        add = findViewById(R.id.rollAdd);
        pickLog = findViewById(R.id.rollPick);
        sheet = findViewById(R.id.rollSheet);
        grid = findViewById(R.id.grid);

        adapter = new RollAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> showFrame(position));

        findViewById(R.id.rollAll).setOnClickListener(v -> {
            for (int i = 0; i < frames.size(); i++) chosen.put(i, true);
            adapter.notifyDataSetChanged();
            updateAdd();
        });
        findViewById(R.id.rollNothing).setOnClickListener(v -> {
            chosen.clear();
            adapter.notifyDataSetChanged();
            updateAdd();
        });
        add.setOnClickListener(v -> finishWith(chosenFrames()));
        pickLog.setOnClickListener(v -> chooseRoll());
        sheet.setOnClickListener(v -> exportSheet());

        String extra = getIntent().getStringExtra(EXTRA_TREE);
        if (extra == null) {
            toast(getString(R.string.roll_none));
            finish();
            return;
        }
        tree = Uri.parse(extra);
        new Thread(this::loadRoll).start();
    }

    /* --------------------------------------------------------- reading a roll */

    private void loadRoll() {
        try {
            final List<RollLog> found = new ArrayList<>();
            docs.clear();
            docNames.clear();

            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            try (Cursor c = getContentResolver().query(children,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED},
                    null, null, null)) {
                if (c == null) throw new IOException("no listing");
                while (c.moveToNext()) {
                    String id = c.getString(0);
                    String name = c.getString(1);
                    if (id == null || name == null) continue;
                    long when = c.isNull(2) ? 0 : c.getLong(2);
                    Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    String lower = name.toLowerCase(Locale.US);
                    if (lower.endsWith(".cr2")) {
                        docs.put(lower, doc);
                        docNames.put(lower, name);
                    } else if (lower.startsWith("roll_") && lower.endsWith(".txt")) {
                        RollLog log = new RollLog();
                        log.name = name;
                        log.doc = doc;
                        log.when = when;
                        found.add(log);
                    }
                }
            }

            if (found.isEmpty()) {
                ui(() -> {
                    sub.setText(getString(R.string.roll_none));
                    status.setText("");
                    bar.setVisibility(View.GONE);
                });
                return;
            }

            found.sort((a, b) -> a.when != b.when ? Long.compare(b.when, a.when)
                    : b.name.compareToIgnoreCase(a.name));
            for (RollLog log : found) {
                log.roll = Roll.parse(read(log.doc));
                for (String file : log.roll.files)
                    if (frameKey(file) != null) log.here++;
            }

            /* The newest log is the last roll the camera wrote, but a card can
               still hold logs whose frames have been copied off it: start with
               the newest one that has frames in this very folder, and let the
               roller pick another from ROLL LOG. */
            RollLog pick = found.get(0);
            for (RollLog log : found) if (log.here > 0) { pick = log; break; }

            final RollLog start = pick;
            ui(() -> {
                logs.clear();
                logs.addAll(found);
                pickLog.setEnabled(logs.size() > 1);
                applyRoll(start);
            });
        } catch (Exception e) {
            final String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            ui(() -> {
                sub.setText(getString(R.string.roll_failed, msg));
                bar.setVisibility(View.GONE);
            });
        }
    }

    /** one ROLL_NNN.TXT and what of it this folder has */
    private static final class RollLog {
        String name;
        Uri doc;
        long when;
        Roll roll;
        int here;
    }

    /** Everything the sheet shows comes from this one log, and it says which. */
    private void applyRoll(RollLog log) {
        current = log;
        frames.clear();
        names.clear();
        thumbs.clear();
        chosen.clear();
        filmIndex = -1;
        adapter.notifyDataSetChanged();
        add.setEnabled(false);
        add.setText(getString(R.string.roll_add_empty));
        sheet.setEnabled(false);
        bar.setVisibility(View.GONE);
        bar.setProgress(0);

        rollNumber = log.roll.number.isEmpty() ? digitsOf(log.name) : log.roll.number;
        filmKey = log.roll.filmKey;
        title.setText(getString(R.string.roll_title, rollNumber));

        for (String file : log.roll.files) {
            String key = frameKey(file);
            if (key == null) continue;
            frames.add(docs.get(key));
            names.add(docNames.get(key));
        }
        adapter.notifyDataSetChanged();

        if (frames.isEmpty()) {
            sub.setText(getString(R.string.roll_no_frames_in, log.name, log.roll.files.size()));
            status.setText(logs.size() > 1 ? getString(R.string.roll_switch) : "");
            return;
        }

        String film = filmKey.isEmpty() ? getString(R.string.roll_no_film) : filmKey;
        sub.setText(getString(R.string.roll_sub, film, frames.size(), log.name));
        for (int i = 0; i < frames.size(); i++) thumbs.add(null);
        bar.setVisibility(View.VISIBLE);
        bar.setProgress(0);
        status.setText(getString(R.string.roll_thumbs, 0, frames.size()));

        final int seq = ++rollSeq;
        new Thread(() -> {
            loadEngine();
            developThumbs(seq);
        }).start();
    }

    /** the folder's logs, newest first, with how many of each one's frames are here */
    private void chooseRoll() {
        if (logs.isEmpty()) {
            toast(getString(R.string.roll_none));
            return;
        }
        String[] items = new String[logs.size()];
        for (int i = 0; i < logs.size(); i++) {
            RollLog log = logs.get(i);
            items[i] = getString(R.string.roll_log_entry, log.name, log.roll.files.size(), log.here)
                    + (log == current ? "  \u2713" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.roll_choose)
                .setItems(items, (d, which) -> applyRoll(logs.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * The CR2 a log line means: by name first, then by the frame number,
     * because Canon names its files after the colour space the camera is set
     * to. In Adobe RGB a frame on the card is _MG_3687.CR2, while the film
     * module, reading Magic Lantern's file prefix, logs it as IMG_3687.CR2.
     * The number is the same on both, so that is what a frame is found by.
     */
    private String frameKey(String listed) {
        String base = listed.toLowerCase(Locale.US);
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        if (docs.containsKey(base)) return base;

        String want = digitsOf(base).replaceAll("^0+(?=\\d)", "");
        if (want.isEmpty()) return null;
        for (String key : docs.keySet()) {
            if (digitsOf(key).replaceAll("^0+(?=\\d)", "").equals(want)) return key;
        }
        return null;
    }

    /** The engine the darkroom already unpacked, so a roll can show its own look. */    private void loadEngine() {
        File base = new File(getFilesDir(), "mfilm");
        File bin = new File(base, "FOSFILMS.BIN");
        if (!bin.isFile() || filmKey.isEmpty()) return;
        int n = MfilmNative.loadFilms(bin.getAbsolutePath());
        if (n <= 0) return;
        MfilmNative.setTables(new File(base, "tables").getAbsolutePath());
        for (int i = 0; i < n; i++) {
            String[] p = MfilmNative.filmInfo(i).split("\\|", -1);
            if (p.length > 5 && p[5].equalsIgnoreCase(filmKey)) {
                filmIndex = i;
                return;
            }
        }
    }

    /** One 720 x 480 develop a frame, as the contact sheet's thumbnails. */
    private void developThumbs(int seq) {
        if (seq != rollSeq) return;
        if (filmIndex < 0) {
            ui(() -> {
                if (seq != rollSeq) return;
                status.setText(getString(R.string.roll_no_film));
                bar.setVisibility(View.GONE);
            });
            return;
        }
        ui(() -> bar.setVisibility(View.VISIBLE));
        File dir = new File(getCacheDir(), "roll");
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File raw = new File(dir, "frame.CR2");
        File jpeg = new File(dir, "frame.jpg");

        for (int i = 0; i < frames.size(); i++) {
            if (seq != rollSeq) return;
            final int n = i;
            try {
                copy(frames.get(i), raw);
                int r = MfilmNative.develop(raw.getAbsolutePath(), jpeg.getAbsolutePath(),
                        filmIndex, 0, 0, 0, 0, 0, 0, 0, 0, 100, "", null);
                raw.delete();
                if (r == 0) {
                    Bitmap b = decode(jpeg, 480);
                    if (b != null) {
                        ui(() -> {
                            if (seq != rollSeq || n >= thumbs.size()) return;
                            thumbs.set(n, b);
                            adapter.notifyDataSetChanged();
                        });
                    }
                }
            } catch (Exception ignored) {
                raw.delete();
            }
            final int done = i + 1;
            final int total = frames.size();
            ui(() -> {
                if (seq != rollSeq) return;
                bar.setProgress(done * 100 / total);
                status.setText(getString(R.string.roll_thumbs, done, total));
            });
        }
        ui(() -> {
            if (seq != rollSeq) return;
            status.setText(getString(R.string.roll_thumbs_done));
            bar.setVisibility(View.GONE);
            sheet.setEnabled(true);
        });
    }

    /* ------------------------------------------------------- the sheet, on file */

    /**
     * The roll as one picture: every developed frame of the sheet, three
     * lines of them to a page, in the same order and with the same film the
     * camera's contact sheet has. It goes in the roll's folder under
     * Pictures/M-Film and then to whichever app the phone offers.
     */
    private void exportSheet() {
        if (frames.isEmpty()) {
            toast(getString(R.string.roll_nothing));
            return;
        }
        final List<Bitmap> cells = new ArrayList<>(thumbs);
        final String film = filmKey.isEmpty() ? getString(R.string.roll_no_film) : filmKey;
        sheet.setEnabled(false);
        status.setText(getString(R.string.roll_sheet_working));
        new Thread(() -> {
            Bitmap made = composeSheet(cells, film);
            Uri saved = null;
            String bad = null;
            try {
                saved = saveSheet(made);
            } catch (Exception e) {
                bad = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final Uri uri = saved;
            final String err = bad;
            ui(() -> {
                sheet.setEnabled(true);
                status.setText(err == null ? getString(R.string.roll_sheet_done)
                        : getString(R.string.roll_sheet_failed, err));
                if (uri != null) shareSheet(uri);
            });
        }).start();
    }

    /** the frames laid out in a grid, with the roll and the film above them */
    private Bitmap composeSheet(List<Bitmap> cells, String film) {
        final int cols = 4, cellW = 384, cellH = 256, pad = 14, cap = 26, head = 92;
        final int n = cells.size();
        final int rows = Math.max(1, (n + cols - 1) / cols);
        final int w = pad + cols * (cellW + pad);
        final int h = head + rows * (cellH + cap + pad) + pad;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(0xFF120E0C);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFE0A33E);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(34);
        c.drawText("M-FILM \u00B7 " + getString(R.string.roll_title, rollNumber), pad, 44, p);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(20);
        p.setColor(0xFFDDBB90);
        c.drawText(getString(R.string.roll_sheet_sub, film, n), pad, 76, p);
        Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        Paint cell = new Paint();
        cell.setColor(0xFF1E1210);
        p.setTextSize(18);
        p.setColor(0xFF9A7F6B);
        for (int i = 0; i < n; i++) {
            int x = pad + (i % cols) * (cellW + pad);
            int y = head + (i / cols) * (cellH + cap + pad);
            c.drawRect(x, y, x + cellW, y + cellH, cell);
            Bitmap b = cells.get(i);
            if (b != null) {
                float s = Math.min((float) cellW / b.getWidth(), (float) cellH / b.getHeight());
                float dw = b.getWidth() * s, dh = b.getHeight() * s;
                c.drawBitmap(b, x + (cellW - dw) / 2f, y + (cellH - dh) / 2f, image);
            }
            String name = i < names.size() ? names.get(i).replaceAll("(?i)\\.cr2$", "") : String.valueOf(i + 1);
            c.drawText((i + 1) + " \u00B7 " + name, x + 2, y + cellH + 20, p);
        }
        return out;
    }

    /** the sheet into the roll's folder under Pictures/M-Film, as a JPEG */
    private Uri saveSheet(Bitmap sheetBm) throws IOException {
        String folder = "Pictures/M-Film" + (rollNumber.isEmpty() ? "" : "/ROLL_" + rollNumber);
        String name = "MFILM_SHEET" + (rollNumber.isEmpty() ? "" : "_ROLL_" + rollNumber) + "_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, folder);
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        ContentResolver cr = getContentResolver();
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("MediaStore refused " + name);
        try (OutputStream out = cr.openOutputStream(uri)) {
            if (out == null || !sheetBm.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                cr.delete(uri, null, null);
                throw new IOException("can't write " + name);
            }
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        return uri;
    }

    private void shareSheet(Uri uri) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("image/jpeg");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.roll_sheet_share)));
        } catch (Exception e) {
            toast(getString(R.string.no_gallery));
        }
    }

    /* ------------------------------------------------------------ the sheet */

    private void showFrame(int i) {
        Bitmap b = thumbs.get(i);
        if (b == null) {
            toast(getString(R.string.preview_working));
            return;
        }
        ImageView view = new ImageView(this);
        view.setImageBitmap(b);
        view.setAdjustViewBounds(true);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        new AlertDialog.Builder(this)
                .setTitle(names.get(i))
                .setView(view)
                .setPositiveButton(chosen.get(i) ? R.string.deselect : R.string.select,
                        (d, w) -> {
                            chosen.put(i, !chosen.get(i));
                            adapter.notifyDataSetChanged();
                            updateAdd();
                        })
                .setNeutralButton(R.string.develop_one, (d, w) -> {
                    ArrayList<Integer> one = new ArrayList<>();
                    one.add(i);
                    finishWith(one);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private ArrayList<Integer> chosenFrames() {
        ArrayList<Integer> out = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) if (chosen.get(i)) out.add(i);
        return out;
    }

    private void updateAdd() {
        int n = chosenFrames().size();
        add.setEnabled(n > 0);
        add.setText(n > 0 ? getString(R.string.roll_add, n) : getString(R.string.roll_add_empty));
    }

    private void finishWith(List<Integer> which) {
        if (which.isEmpty()) {
            toast(getString(R.string.roll_nothing));
            return;
        }
        ArrayList<String> out = new ArrayList<>();
        for (int i : which) out.add(frames.get(i).toString());
        Intent data = new Intent();
        data.putStringArrayListExtra(EXTRA_FRAMES, out);
        data.putExtra(EXTRA_FILM, filmKey);
        data.putExtra(EXTRA_ROLL, rollNumber);
        setResult(RESULT_OK, data);
        finish();
    }

    private final class RollAdapter extends BaseAdapter {
        @Override public int getCount() { return frames.size(); }
        @Override public Object getItem(int i) { return frames.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View reuse, ViewGroup parent) {
            LinearLayout cell = new LinearLayout(RollActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL);

            ImageView iv = new ImageView(RollActivity.this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFF1E1210);
            iv.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (int) dp(92)));
            Bitmap b = thumbs.size() > i ? thumbs.get(i) : null;
            if (b != null) iv.setImageBitmap(b);
            cell.addView(iv);

            TextView cap = new TextView(RollActivity.this);
            cap.setText((i + 1) + " · " + names.get(i).replaceAll("(?i)\\.cr2$", ""));
            cap.setTextSize(10);
            cap.setTextColor(chosen.get(i) ? 0xFFE0A33E : 0xFF9A7F6B);
            cap.setSingleLine(true);
            cap.setPadding((int) dp(2), (int) dp(3), (int) dp(2), (int) dp(3));
            cell.addView(cap);

            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(6));
            bg.setColor(0x00000000);
            bg.setStroke(Math.max(1, (int) dp(2)), chosen.get(i) ? 0xFFE0A33E : 0x00000000);
            cell.setBackground(bg);
            return cell;
        }
    }

    /* ------------------------------------------------------------- plumbing */

    private String read(Uri doc) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(doc)) {
            if (in == null) throw new IOException("can't open " + doc);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    private void copy(Uri uri, File dst) throws IOException {
        try (InputStream src = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dst)) {
            if (src == null) throw new IOException("can't open " + uri);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = src.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private Bitmap decode(File jpeg, int want) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(jpeg.getAbsolutePath(), bounds);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= want) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(jpeg.getAbsolutePath(), opts);
    }

    private static String digitsOf(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) if (Character.isDigit(c)) sb.append(c);
        return sb.toString();
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void ui(Runnable r) { runOnUiThread(r); }

    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
}
