package com.kanju.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** 圆角海报位图视图：中心裁剪 + 占位骨架。 */
public final class PosterView extends View {

    private Bitmap bitmap;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private float radius = 10f;

    public PosterView(Context c) {
        super(c);
        bg.setColor(0xFF1E2523);
        radius = Ui.dpf(c, 10);
    }

    public void setBitmap(Bitmap b) {
        bitmap = b;
        invalidate();
    }

    public void setRadiusDp(float dp) {
        radius = Ui.dpf(getContext(), dp);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        rect.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        cv.save();
        cv.clipPath(clip);
        cv.drawRect(rect, bg);
        if (bitmap != null && !bitmap.isRecycled()) {
            float bw = bitmap.getWidth(), bh = bitmap.getHeight();
            float scale = Math.max(w / bw, h / bh);
            float dw = bw * scale, dh = bh * scale;
            float left = (w - dw) / 2f, top = (h - dh) / 2f;
            cv.drawBitmap(bitmap, null, new RectF(left, top, left + dw, top + dh), paint);
        } else {
            // 占位：一条淡淡的胶片横纹
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0x14FFFFFF);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dpf(getContext(), 1.2f));
            cv.drawRoundRect(new RectF(w * 0.26f, h * 0.34f, w * 0.74f, h * 0.66f),
                    Ui.dpf(getContext(), 6), Ui.dpf(getContext(), 6), p);
        }
        cv.restore();
    }
}
