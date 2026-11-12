package com.kanju.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 手绘 UI 小工具：颜色、圆角、图标、开关。不依赖任何图片资源或第三方库。 */
public final class Ui {

    public static final int BG = 0xFF0A0C0B;
    public static final int SURFACE = 0xFF141817;
    public static final int SURFACE_2 = 0xFF1C2220;
    public static final int HAIRLINE = 0xFF2A322F;
    public static final int TEXT = 0xFFF2F5F4;
    public static final int TEXT_2 = 0xFF9AA6A1;
    public static final int TEXT_3 = 0xFF6B7671;
    public static final int ACCENT = 0xFF2FD79B;
    public static final int ACCENT_DEEP = 0xFF123A2C;
    public static final int DANGER = 0xFFFF5C5C;

    private Ui() {}

    // ------------------------------------------------------------ 尺寸

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static float dpf(Context c, float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------ 背景

    public static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    public static GradientDrawable round(int color, float[] radii) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadii(radii);
        return d;
    }

    public static GradientDrawable roundStroke(int color, float radius, int strokeW, int strokeColor) {
        GradientDrawable d = round(color, radius);
        d.setStroke(Math.max(1, strokeW), strokeColor);
        return d;
    }

    public static GradientDrawable roundTop(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        return d;
    }

    public static View hairline(Context c, int color) {
        View v = new View(c);
        v.setBackgroundColor(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    public static RippleDrawable ripple(int maskColor) {
        return new RippleDrawable(ColorStateList.valueOf(maskColor), null, null);
    }

    /** 带底色 + 按压水波纹（Chrome 那种「填充块上压一下」的手感） */
    public static RippleDrawable ripple(int maskColor, android.graphics.drawable.Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(maskColor), content, null);
    }

    // ------------------------------------------------------------ 文字

    public static TextView text(Context c, String s, float size, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static TextView bold(Context c, String s, float size, int color) {
        TextView t = text(c, s, size, color);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    // ------------------------------------------------------------ 图标

    public static final int IC_SEARCH = 1;
    public static final int IC_BACK = 2;
    public static final int IC_PLAY = 3;
    public static final int IC_PAUSE = 4;
    public static final int IC_NEXT = 5;
    public static final int IC_PREV = 6;
    public static final int IC_LOCK = 7;
    public static final int IC_UNLOCK = 8;
    public static final int IC_EPISODES = 9;
    public static final int IC_SWAP = 10;
    public static final int IC_SPEED = 11;
    public static final int IC_FULLSCREEN = 12;
    public static final int IC_CLOSE = 13;
    public static final int IC_DOTS = 14;
    public static final int IC_CHECK = 15;
    public static final int IC_CHEVRON = 16;
    public static final int IC_FILM = 17;
    public static final int IC_SETTINGS = 18;
    public static final int IC_FORWARD = 19;
    public static final int IC_INFO = 20;

    public static View icon(Context c, int kind, float sizeDp, int color) {
        Icon v = new Icon(c, kind, color);
        int s = dp(c, sizeDp);
        v.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return v;
    }

    /** 可点图标按钮：自带 44dp 触摸区和圆形按压反馈 */
    public static View iconButton(Context c, int kind, float sizeDp, int color, float padDp,
                                  View.OnClickListener l) {
        Icon v = new Icon(c, kind, color);
        int s = dp(c, sizeDp + padDp * 2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(s, s);
        v.setLayoutParams(lp);
        v.setPadding(dp(c, padDp), dp(c, padDp), dp(c, padDp), dp(c, padDp));
        v.setClickable(true);
        v.setFocusable(true);
        v.setBackground(ripple(0x33FFFFFF));
        if (l != null) v.setOnClickListener(l);
        return v;
    }

    static final class Icon extends View {
        private final int kind;
        private final int want;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Icon(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            this.want = dp(c, 20);
            p.setColor(color);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dpf(c, 1.9f));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = resolveSize(want, widthSpec);
            int h = resolveSize(want, heightSpec);
            setMeasuredDimension(w, h);
        }

        void setColor(int color) {
            p.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            // 关键：先扣除 padding，否则 iconButton(icon=34, pad=20) 的箭头会被放大到 74dp
            int l = getPaddingLeft(), t = getPaddingTop();
            int w = getWidth() - l - getPaddingRight();
            int h = getHeight() - t - getPaddingBottom();
            float s = Math.max(1, Math.min(w, h));
            cv.save();
            cv.translate(l + (w - s) / 2f, t + (h - s) / 2f);
            cv.scale(s / 24f, s / 24f);
            drawIcon(cv);
            cv.restore();
        }

        private void drawIcon(Canvas cv) {
            Path path = new Path();
            p.setStyle(Paint.Style.STROKE);
            switch (kind) {
                case IC_SEARCH:
                    cv.drawCircle(10.5f, 10.5f, 6.2f, p);
                    cv.drawLine(15.2f, 15.2f, 20f, 20f, p);
                    break;
                case IC_BACK:
                    path.moveTo(14.5f, 5.5f);
                    path.lineTo(8f, 12f);
                    path.lineTo(14.5f, 18.5f);
                    cv.drawPath(path, p);
                    break;
                case IC_PLAY: {
                    Paint f = new Paint(p);
                    f.setStyle(Paint.Style.FILL);
                    Path t = new Path();
                    t.moveTo(8f, 5.4f);
                    t.lineTo(19f, 12f);
                    t.lineTo(8f, 18.6f);
                    t.close();
                    cv.drawPath(t, f);
                    break;
                }
                case IC_PAUSE: {
                    Paint f = new Paint(p);
                    f.setStyle(Paint.Style.FILL);
                    f.setStrokeCap(Paint.Cap.ROUND);
                    f.setStrokeWidth(3.8f);
                    cv.drawLine(8.9f, 5.2f, 8.9f, 18.8f, f);
                    cv.drawLine(15.1f, 5.2f, 15.1f, 18.8f, f);
                    break;
                }
                case IC_NEXT: {
                    Paint f = new Paint(p);
                    f.setStyle(Paint.Style.FILL);
                    Path t = new Path();
                    t.moveTo(6.5f, 6.4f);
                    t.lineTo(14f, 12f);
                    t.lineTo(6.5f, 17.6f);
                    t.close();
                    cv.drawPath(t, f);
                    f.setStrokeWidth(2.6f);
                    f.setStrokeCap(Paint.Cap.ROUND);
                    cv.drawLine(17.6f, 6.4f, 17.6f, 17.6f, f);
                    break;
                }
                case IC_PREV: {
                    Paint f = new Paint(p);
                    f.setStyle(Paint.Style.FILL);
                    Path t = new Path();
                    t.moveTo(17.5f, 6.4f);
                    t.lineTo(10f, 12f);
                    t.lineTo(17.5f, 17.6f);
                    t.close();
                    cv.drawPath(t, f);
                    f.setStrokeWidth(2.6f);
                    f.setStrokeCap(Paint.Cap.ROUND);
                    cv.drawLine(6.4f, 6.4f, 6.4f, 17.6f, f);
                    break;
                }
                case IC_LOCK:
                case IC_UNLOCK: {
                    float top = kind == IC_LOCK ? 7.6f : 5.4f;
                    RectF arc = new RectF(8.2f, top, 15.8f, top + 7.6f);
                    path.addArc(arc, 180f, 180f);
                    cv.drawPath(path, p);
                    if (kind == IC_UNLOCK) {
                        cv.drawLine(15.8f, top + 3.8f, 15.8f, 10.6f, p);
                    }
                    RectF body = new RectF(6.2f, 11.4f, 17.8f, 19.4f);
                    cv.drawRoundRect(body, 2.2f, 2.2f, p);
                    break;
                }
                case IC_EPISODES:
                    for (int i = 0; i < 3; i++) {
                        float y = 7f + i * 5f;
                        cv.drawCircle(5.6f, y, 1.25f, fill());
                        cv.drawLine(9.6f, y, 18.6f, y, p);
                    }
                    break;
                case IC_SWAP:
                    cv.drawLine(5f, 9f, 18f, 9f, p);
                    cv.drawLine(15f, 6f, 18f, 9f, p);
                    cv.drawLine(18f, 9f, 15f, 12f, p);
                    cv.drawLine(19f, 15f, 6f, 15f, p);
                    cv.drawLine(9f, 12f, 6f, 15f, p);
                    cv.drawLine(6f, 15f, 9f, 18f, p);
                    break;
                case IC_SPEED:
                    path.addArc(new RectF(4.5f, 5.5f, 19.5f, 20.5f), 200f, 140f);
                    cv.drawPath(path, p);
                    cv.drawLine(12f, 13f, 16.2f, 8.6f, p);
                    cv.drawCircle(12f, 13f, 1.4f, fill());
                    break;
                case IC_FULLSCREEN:
                    cv.drawLine(4.6f, 9f, 4.6f, 4.6f, p);
                    cv.drawLine(4.6f, 4.6f, 9f, 4.6f, p);
                    cv.drawLine(19.4f, 15f, 19.4f, 19.4f, p);
                    cv.drawLine(19.4f, 19.4f, 15f, 19.4f, p);
                    cv.drawLine(19.4f, 9f, 19.4f, 4.6f, p);
                    cv.drawLine(19.4f, 4.6f, 15f, 4.6f, p);
                    cv.drawLine(4.6f, 15f, 4.6f, 19.4f, p);
                    cv.drawLine(4.6f, 19.4f, 9f, 19.4f, p);
                    break;
                case IC_CLOSE:
                    cv.drawLine(6.2f, 6.2f, 17.8f, 17.8f, p);
                    cv.drawLine(17.8f, 6.2f, 6.2f, 17.8f, p);
                    break;
                case IC_DOTS:
                    cv.drawCircle(6f, 12f, 1.5f, fill());
                    cv.drawCircle(12f, 12f, 1.5f, fill());
                    cv.drawCircle(18f, 12f, 1.5f, fill());
                    break;
                case IC_CHECK:
                    path.moveTo(5.5f, 12.6f);
                    path.lineTo(10f, 17f);
                    path.lineTo(18.6f, 7.4f);
                    cv.drawPath(path, p);
                    break;
                case IC_CHEVRON:
                    path.moveTo(9.8f, 6f);
                    path.lineTo(15.8f, 12f);
                    path.lineTo(9.8f, 18f);
                    cv.drawPath(path, p);
                    break;
                case IC_FILM:
                    cv.drawRoundRect(new RectF(3.6f, 5.6f, 20.4f, 18.4f), 2.4f, 2.4f, p);
                    for (int i = 0; i < 4; i++) {
                        float x = 6.6f + i * 3.6f;
                        cv.drawLine(x, 5.6f, x, 8.2f, p);
                        cv.drawLine(x, 15.8f, x, 18.4f, p);
                    }
                    break;
                case IC_SETTINGS:
                    cv.drawCircle(12f, 12f, 3.2f, p);
                    for (int i = 0; i < 8; i++) {
                        double a = Math.PI * 2 * i / 8;
                        float x1 = (float) (12 + Math.cos(a) * 6.2f);
                        float y1 = (float) (12 + Math.sin(a) * 6.2f);
                        float x2 = (float) (12 + Math.cos(a) * 8.4f);
                        float y2 = (float) (12 + Math.sin(a) * 8.4f);
                        cv.drawLine(x1, y1, x2, y2, p);
                    }
                    break;
                case IC_FORWARD: {
                    Paint f = new Paint(p);
                    f.setStyle(Paint.Style.FILL);
                    for (int i = 0; i < 2; i++) {
                        Path t = new Path();
                        float o = i * 7.2f;
                        t.moveTo(4.4f + o, 6.6f);
                        t.lineTo(11.2f + o, 12f);
                        t.lineTo(4.4f + o, 17.4f);
                        t.close();
                        cv.drawPath(t, f);
                    }
                    break;
                }
                case IC_INFO:
                    cv.drawCircle(12f, 12f, 8.4f, p);
                    cv.drawCircle(12f, 8.2f, 0.95f, fill());
                    cv.drawLine(12f, 11.4f, 12f, 16.4f, p);
                    break;
                default:
                    cv.drawCircle(12f, 12f, 8f, p);
                    break;
            }
        }

        private Paint fill() {
            Paint f = new Paint(p);
            f.setStyle(Paint.Style.FILL);
            return f;
        }
    }

    // ------------------------------------------------------------ 开关

    public static final class Toggle extends View {
        private boolean checked;
        private float pos;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);

        public Toggle(Context c) {
            super(c);
            knob.setColor(0xFFFFFFFF);
            setLayoutParams(new LinearLayout.LayoutParams(dp(c, 42), dp(c, 26)));
        }

        public boolean isChecked() {
            return checked;
        }

        public void setCheckedImmediate(boolean v) {
            checked = v;
            pos = v ? 1f : 0f;
            invalidate();
        }

        public void animateTo(boolean v) {
            checked = v;
            final long t0 = System.currentTimeMillis();
            post(new Runnable() {
                @Override
                public void run() {
                    float k = Math.min(1f, (System.currentTimeMillis() - t0) / 150f);
                    pos = checked ? 1f - (float) Math.pow(1 - k, 3) : (float) Math.pow(1 - k, 3);
                    invalidate();
                    if (k < 1f) postOnAnimation(this);
                }
            });
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float r = h / 2f;
            int off = 0xFF353D3A;
            int on = ACCENT;
            track.setColor(blend(off, on, pos));
            cv.drawRoundRect(new RectF(0, 0, w, h), r, r, track);
            float pad = dpf(getContext(), 3f);
            float kr = r - pad;
            float cx = r + (w - 2 * r) * pos + (pos - 0.5f) * 2 * pad * 0;
            cv.drawCircle(cx, h / 2f, kr, knob);
        }

        private int blend(int a, int b, float k) {
            int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
            int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
            int rr = (int) (ar + (br - ar) * k);
            int rg = (int) (ag + (bg - ag) * k);
            int rb = (int) (ab + (bb - ab) * k);
            return (0xFF << 24) | (rr << 16) | (rg << 8) | rb;
        }
    }

    public static Toggle toggle(Context c, boolean checked) {
        Toggle t = new Toggle(c);
        t.setCheckedImmediate(checked);
        return t;
    }

    // ------------------------------------------------------------ 布局助手

    public static LinearLayout row(Context c, int gravity) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(gravity);
        return l;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static void setPadding(View v, Context c, float l, float t, float r, float b) {
        v.setPadding(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
    }

    public static int statusBarHeight(Context c) {
        int id = c.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? c.getResources().getDimensionPixelSize(id) : dp(c, 24);
    }

    public static int navBarHeight(Context c) {
        int id = c.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? c.getResources().getDimensionPixelSize(id) : dp(c, 24);
    }

    public static int accentA(int alpha) {
        return (alpha << 24) | (ACCENT & 0x00FFFFFF);
    }

    public static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    public static LinearLayout.LayoutParams dpLp(Context c, float w, float h) {
        return new LinearLayout.LayoutParams(
                w < 0 ? ViewGroup.LayoutParams.MATCH_PARENT : dp(c, w),
                h < 0 ? ViewGroup.LayoutParams.WRAP_CONTENT : dp(c, h));
    }

    public static TextView chip(Context c, String label, boolean active) {
        TextView t = text(c, label, 13f, active ? ACCENT : TEXT_2);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        t.setBackground(active
                ? roundStroke(ACCENT_DEEP, dp(c, 30), Math.max(1, dp(c, 1)), ACCENT)
                : roundStroke(SURFACE_2, dp(c, 30), 1, HAIRLINE));
        return t;
    }
}
