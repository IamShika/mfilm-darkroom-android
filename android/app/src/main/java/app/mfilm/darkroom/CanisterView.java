package app.mfilm.darkroom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

/**
 * One film canister, drawn the way the camera's shelf draws it: a cap, the
 * film's box colours, its name and its speed. Tapping picks the film.
 */
final class CanisterView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF body = new RectF();
    private Film film;
    private boolean chosen;
    private boolean favourite;

    CanisterView(Context context) {
        super(context);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        setClickable(true);
        setFocusable(true);
    }

    void setFilm(Film f) {
        film = f;
        invalidate();
    }

    void setChosen(boolean c) {
        chosen = c;
        invalidate();
    }

    /** kept by hand: an amber star pinned on the lid */
    void setFavourite(boolean f) {
        favourite = f;
        invalidate();
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(resolveSize((int) dp(74), widthSpec), resolveSize((int) dp(126), heightSpec));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (film == null) return;
        final float w = getWidth(), h = getHeight();
        final float lift = chosen ? 0 : dp(6);           // the chosen one stands up
        final float l = w * 0.12f, r = w * 0.88f;
        final float top = h * 0.14f + lift, bot = h * 0.96f;

        /* the lid */
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF2A2622);
        float capL = l + w * 0.11f, capR = r - w * 0.11f;
        c.drawRoundRect(capL, top - dp(9), capR, top + dp(3), dp(4), dp(4), paint);

        /* the body */
        body.set(l, top, r, bot);
        paint.setColor(film.bg);
        c.drawRoundRect(body, w * 0.07f, w * 0.07f, paint);

        c.save();
        c.clipRect(body);
        /* an accent band above the name, the film's own colour under it */
        paint.setColor(film.accent);
        c.drawRect(l, top, r, top + h * 0.10f, paint);
        paint.setColor(film.band);
        c.drawRect(l, bot - h * 0.11f, r, bot, paint);
        c.restore();

        /* the lid's shadow over the top band */
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, w * 0.012f));
        paint.setColor(0x40FFFFFF);
        c.drawRoundRect(body, w * 0.07f, w * 0.07f, paint);
        paint.setStyle(Paint.Style.FILL);

        /* brand on the accent band */
        text(c, film.brand, (l + r) / 2f, top + h * 0.075f, h * 0.055f, film.ink, true);
        /* the name, then the speed */
        text(c, film.name, (l + r) / 2f, top + h * 0.29f, h * 0.085f, film.ink, true);
        text(c, film.boxSpeed(), (l + r) / 2f, top + h * 0.53f, h * 0.17f, film.ink, true);
        text(c, "135-36", (l + r) / 2f, bot - h * 0.035f, h * 0.05f,
                contrast(film.band), false);

        /* kept by hand: a star on the lid */
        if (favourite) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFE0A33E);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            paint.setTextSize(dp(10));
            c.drawText("\u2605", w / 2f, top - dp(1), paint);
        }

        /* the one on the camera's shelf sits under the amber marker */
        if (chosen) {
            paint.setColor(0xFFE0A33E);
            c.drawRoundRect(l - dp(2), top - dp(12), r + dp(2), bot + dp(2), w * 0.08f, w * 0.08f, stroke());
            c.drawCircle(w / 2f, h - dp(3), dp(2.5f), paint);
        }
    }

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Paint stroke() {
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(1.5f, dp(1.6f)));
        strokePaint.setColor(0xFFE0A33E);
        return strokePaint;
    }

    /** centred text, shrunk until it fits the canister */
    private void text(Canvas c, String s, float cx, float baseline, float size, int color, boolean bold) {
        if (s == null || s.isEmpty()) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
        float limit = getWidth() * 0.74f;
        float sz = size;
        paint.setTextSize(sz);
        while (sz > 4 && paint.measureText(s) > limit) {
            sz -= 1f;
            paint.setTextSize(sz);
        }
        c.drawText(s, cx, baseline, paint);
    }

    private static int contrast(int background) {
        int r = (background >> 16) & 0xFF, g = (background >> 8) & 0xFF, b = background & 0xFF;
        double l = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return l > 0.55 ? 0xFF151515 : 0xFFF7F3EA;
    }
}
