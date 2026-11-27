package app.mfilm.darkroom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * The preview's tones, as bars: 64 bins of luma from black on the left to
 * white on the right. The two piles at the ends are what's clipped -- a black
 * with nothing in it is crushed, a white that tall is blown -- so they are
 * drawn in the warning colour.
 */
public final class HistogramView extends View {

    private int[] bins = new int[0];
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public HistogramView(Context c) {
        this(c, null);
    }

    public HistogramView(Context c, AttributeSet a) {
        super(c, a);
        bar.setColor(0xFF9A7F6B);
        clip.setColor(0xFFC4553D);
    }

    void setBins(int[] b) {
        bins = b == null ? new int[0] : b;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int n = bins.length;
        if (n == 0) return;
        int max = 1;
        long total = 0;
        for (int v : bins) {
            if (v > max) max = v;
            total += v;
        }
        float w = getWidth(), h = getHeight();
        float step = w / n;
        for (int i = 0; i < n; i++) {
            float bh = h * bins[i] / (float) max;
            if (bh < 1f && bins[i] > 0) bh = 1f;
            r.set(i * step, h - bh, i * step + Math.max(1f, step - 1f), h);
            canvas.drawRect(r, bar);
        }
        // a pile at either end is a clipped end: mark it
        long pile = Math.max(1, total / 50);
        if (bins[0] > pile) canvas.drawRect(0, 0, Math.max(2f, step), h, clip);
        if (bins[n - 1] > pile) canvas.drawRect(w - Math.max(2f, step), 0, w, h, clip);
    }
}
