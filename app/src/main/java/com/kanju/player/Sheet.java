package com.kanju.player;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 底部面板：直接叠加在 Activity 内容之上（不用 Dialog）。
 * Dialog 在横屏首帧会把内容量成 0 宽（表现为空白面板），
 * 叠在内容层上则始终拿到真实屏幕宽度。
 */
public final class Sheet {

    public interface ToggleListener {
        void onChanged(boolean checked);
    }

    private static Sheet showing;

    private final Activity act;
    private final ViewGroup host;
    private final View overlay;
    private final View card;
    private boolean closing;

    private Sheet(Activity act, ViewGroup host, View overlay, View card) {
        this.act = act;
        this.host = host;
        this.overlay = overlay;
        this.card = card;
    }

    /** 返回键用：有面板则先关面板 */
    public static boolean dismissTop() {
        if (showing == null) return false;
        showing.dismiss();
        return true;
    }

    public void dismiss() {
        if (closing) return;
        closing = true;
        if (showing == this) showing = null;
        int h = Math.max(card.getHeight(), Ui.dp(act, 200));
        card.animate().translationY(h).setDuration(170).start();
        overlay.animate().alpha(0f).setDuration(170).withEndAction(() -> {
            try {
                host.removeView(overlay);
            } catch (Throwable ignored) {
            }
        }).start();
    }

    public static final class Builder {
        private final Activity act;
        private final LinearLayout card;
        private final LinearLayout content;
        private final MaxScroll scroll;
        private final Map<String, Ui.Toggle> toggles = new LinkedHashMap<>();

        public Builder(Activity a) {
            act = a;

            card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.roundTop(Ui.SURFACE, Ui.dp(a, 22)));
            card.setClickable(true);

            View grab = new View(a);
            grab.setBackground(Ui.round(0xFF3A4441, Ui.dp(a, 2)));
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                    Ui.dp(a, 34), Ui.dp(a, 3));
            glp.gravity = Gravity.CENTER_HORIZONTAL;
            glp.topMargin = Ui.dp(a, 9);
            glp.bottomMargin = Ui.dp(a, 5);
            card.addView(grab, glp);

            content = new LinearLayout(a);
            content.setOrientation(LinearLayout.VERTICAL);

            scroll = new MaxScroll(a);
            scroll.addView(content, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            View bottom = new View(a);
            card.addView(bottom, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, 14)));
        }

        public Builder title(String text, String subtitle) {
            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(Ui.dp(act, 22), Ui.dp(act, 4), Ui.dp(act, 22), Ui.dp(act, 12));

            box.addView(Ui.bold(act, text, 18f, Ui.TEXT));

            if (!TextUtils.isEmpty(subtitle)) {
                TextView s = Ui.text(act, subtitle, 12.5f, Ui.TEXT_3);
                s.setLineSpacing(Ui.dp(act, 2), 1f);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = Ui.dp(act, 5);
                box.addView(s, lp);
            }
            content.addView(box);
            return this;
        }

        public Builder section(String label) {
            TextView t = Ui.text(act, label, 11f, Ui.TEXT_3);
            t.setLetterSpacing(0.09f);
            t.setPadding(Ui.dp(act, 22), Ui.dp(act, 12), Ui.dp(act, 22), Ui.dp(act, 4));
            content.addView(t);
            return this;
        }

        public Builder divider() {
            View v = new View(act);
            v.setBackgroundColor(Ui.HAIRLINE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(act, 0.7f)));
            lp.leftMargin = Ui.dp(act, 22);
            lp.rightMargin = Ui.dp(act, 22);
            lp.topMargin = Ui.dp(act, 4);
            lp.bottomMargin = Ui.dp(act, 4);
            content.addView(v, lp);
            return this;
        }

        public Builder item(int icon, String label, Runnable action) {
            return item(icon, label, null, 0, action);
        }

        public Builder item(int icon, String label, String value, Runnable action) {
            return item(icon, label, value, 0, action);
        }

        /** state: 0 无、1 勾选、2 箭头 */
        public Builder item(int icon, String label, String value, int state, Runnable action) {
            LinearLayout row = rowBase();
            if (icon != 0) row.addView(iconView(icon), iconParams());

            TextView tv = Ui.text(act, label, 15f, Ui.TEXT);
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(new View(act), new LinearLayout.LayoutParams(0, 1, 1f));

            if (!TextUtils.isEmpty(value)) {
                TextView v = Ui.text(act, value, 12.5f, state == 1 ? Ui.ACCENT : Ui.TEXT_3);
                v.setSingleLine(true);
                v.setEllipsize(TextUtils.TruncateAt.END);
                v.setPadding(Ui.dp(act, 10), 0, Ui.dp(act, 6), 0);
                row.addView(v, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (state == 1) {
                int s = Ui.dp(act, 17);
                row.addView(Ui.icon(act, Ui.IC_CHECK, 17, Ui.ACCENT),
                        new LinearLayout.LayoutParams(s, s));
            } else if (state == 2) {
                int s = Ui.dp(act, 15);
                row.addView(Ui.icon(act, Ui.IC_CHEVRON, 15, Ui.TEXT_3),
                        new LinearLayout.LayoutParams(s, s));
            }

            if (action != null) {
                row.setOnClickListener(v -> {
                    dismissAnimated();
                    new Handler(Looper.getMainLooper()).postDelayed(action, 170);
                });
            }
            content.addView(row);
            return this;
        }

        public Builder itemColor(int icon, String label, int labelColor, Runnable action) {
            LinearLayout row = rowBase();
            if (icon != 0) row.addView(iconView(icon), iconParams());
            TextView tv = Ui.text(act, label, 15f, labelColor);
            row.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setOnClickListener(v -> {
                dismissAnimated();
                new Handler(Looper.getMainLooper()).postDelayed(action, 170);
            });
            content.addView(row);
            return this;
        }

        public Builder toggle(int icon, String label, boolean checked, ToggleListener l) {
            LinearLayout row = rowBase();
            row.addView(iconView(icon), iconParams());

            TextView tv = Ui.text(act, label, 15f, Ui.TEXT);
            row.addView(tv, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, 1, 1f);
            slp.rightMargin = Ui.dp(act, 10);
            row.addView(new View(act), slp);

            Ui.Toggle tg = Ui.toggle(act, checked);
            row.addView(tg, new LinearLayout.LayoutParams(
                    Ui.dp(act, 42), Ui.dp(act, 26)));
            toggles.put(label, tg);

            row.setOnClickListener(v -> {
                boolean next = !tg.isChecked();
                tg.animateTo(next);
                if (l != null) l.onChanged(next);
            });
            content.addView(row);
            return this;
        }

        public Builder note(String text) {
            TextView t = Ui.text(act, text, 12.5f, Ui.TEXT_3);
            t.setLineSpacing(Ui.dp(act, 3), 1f);
            t.setPadding(Ui.dp(act, 22), Ui.dp(act, 6), Ui.dp(act, 22), Ui.dp(act, 8));
            content.addView(t);
            return this;
        }

        public Builder custom(View v) {
            content.addView(v);
            return this;
        }

        public Builder primary(String text, Runnable action) {
            TextView b = Ui.bold(act, text, 15f, Ui.BG);
            b.setGravity(Gravity.CENTER);
            b.setBackground(Ui.round(Ui.ACCENT, Ui.dp(act, 13)));
            b.setClickable(true);
            b.setFocusable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 48));
            lp.leftMargin = Ui.dp(act, 22);
            lp.rightMargin = Ui.dp(act, 22);
            lp.topMargin = Ui.dp(act, 14);
            lp.bottomMargin = Ui.dp(act, 6);
            b.setOnClickListener(v -> {
                dismissAnimated();
                new Handler(Looper.getMainLooper()).postDelayed(action, 170);
            });
            content.addView(b, lp);
            return this;
        }

        public Builder ghost(String text, Runnable action) {
            TextView b = Ui.text(act, text, 14f, Ui.TEXT_2);
            b.setGravity(Gravity.CENTER);
            b.setBackground(Ui.roundStroke(Ui.SURFACE_2, Ui.dp(act, 13), 1, Ui.HAIRLINE));
            b.setClickable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 46));
            lp.leftMargin = Ui.dp(act, 22);
            lp.rightMargin = Ui.dp(act, 22);
            lp.bottomMargin = Ui.dp(act, 4);
            b.setOnClickListener(v -> {
                dismissAnimated();
                new Handler(Looper.getMainLooper()).postDelayed(action, 170);
            });
            content.addView(b, lp);
            return this;
        }

        private void dismissAnimated() {
            if (showing != null) showing.dismiss();
        }

        private LinearLayout rowBase() {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(act, 22), 0, Ui.dp(act, 22), 0);
            row.setMinimumHeight(Ui.dp(act, 50));
            row.setBackground(Ui.ripple(0x1FFFFFFF));
            row.setClickable(true);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return row;
        }

        private View iconView(int kind) {
            if (kind == 0) {
                View spacer = new View(act);
                spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1));
                return spacer;
            }
            return Ui.icon(act, kind, 20, Ui.TEXT_2);
        }

        private LinearLayout.LayoutParams iconParams() {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(act, 14);
            return lp;
        }

        public Sheet show() {
            ViewGroup host = act.findViewById(android.R.id.content);
            if (host == null) return null;

            final FrameLayout overlay = new FrameLayout(act);
            overlay.setClickable(true);
            overlay.setBackgroundColor(0xB3000000);
            overlay.setOnClickListener(v -> dismissAnimated());

            int screenH = host.getHeight() > 0 ? host.getHeight()
                    : act.getResources().getDisplayMetrics().heightPixels;
            scroll.maxH = (int) (screenH * 0.82f);

            FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM);
            overlay.addView(card, cardLp);
            // 吞掉面板内部点击，避免误触关闭
            card.setOnClickListener(v -> {
            });

            host.addView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            overlay.setAlpha(0f);
            overlay.animate().alpha(1f).setDuration(150).start();
            card.post(() -> {
                int h = Math.max(card.getHeight(), Ui.dp(act, 160));
                card.setTranslationY(h);
                card.animate().translationY(0f).setDuration(210).start();
            });

            if (Build.VERSION.SDK_INT >= 21) {
                overlay.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }

            Sheet s = new Sheet(act, host, overlay, card);
            showing = s;
            return s;
        }
    }

    private static final class MaxScroll extends ScrollView {
        int maxH = Integer.MAX_VALUE;

        MaxScroll(Context c) {
            super(c);
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setBackgroundColor(Color.TRANSPARENT);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            if (maxH != Integer.MAX_VALUE) {
                heightSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }
}
