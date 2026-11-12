package com.kanju.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 首页：只保留「搜索 + 换源」。
 * 布局对齐 Chrome 移动端新标签页：顶部一条胶囊搜索框，正文是结果列表 / 空状态。
 */
public class HomeActivity extends Activity {

    /** 搜索框底色，比页面底色亮一档（Chrome 深色模式就是这样） */
    private static final int SEARCH_BG = 0xFF202624;
    private static final int SEARCH_BG_FOCUS = 0xFF272E2B;

    private final List<Models.Movie> items = new ArrayList<>();
    private Adapter adapter;
    private ListView list;
    private EditText input;
    private TextView status;
    private TextView emptyTitle;
    private TextView emptyDesc;
    private View emptyBox;
    private View footer;
    private TextView footerText;
    private View clearBtn;
    private View goBtn;
    private View pill;
    private LinearLayout historyRow;
    private View historyLabel;

    private String query = "";
    private int page = 1;
    private boolean loading;
    private boolean hasMore;
    private boolean searched;
    private String pendingError;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        refreshHistory();
        input.clearFocus();
    }

    // ------------------------------------------------------------ UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, Ui.statusBarHeight(this), 0, 0);

        col.addView(buildHeader());
        col.addView(buildSearchBar());
        col.addView(buildStatusLine());

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipToPadding(false);
        list.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 18));
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> openPlayer(items.get(pos)));
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView v, int state) {}

            @Override
            public void onScroll(AbsListView v, int first, int visible, int total) {
                if (!loading && hasMore && total > 0 && first + visible >= total - 2) loadPage(page + 1);
            }
        });

        footer = buildFooter();
        list.addFooterView(footer, null, false);
        footer.setVisibility(View.GONE);

        col.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        emptyBox = buildEmpty();
        col.addView(emptyBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        showEmpty("搜片名，直接看", "输入片名或演员，回车即可搜索", false);
    }

    /** 顶部一行：品牌 + 换源。刻意做得很轻，把视觉重点留给搜索框。 */
    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 14), 0);

        View mark = new View(this) {
            private final android.graphics.Paint p =
                    new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

            {
                p.setColor(Ui.ACCENT);
            }

            @Override
            protected void onDraw(android.graphics.Canvas cv) {
                float w = getWidth(), h = getHeight();
                float r = Math.min(w, h) / 2f;
                cv.save();
                cv.translate(w / 2f, h / 2f);
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(-r * 0.30f, -r * 0.48f);
                path.lineTo(r * 0.50f, 0);
                path.lineTo(-r * 0.30f, r * 0.48f);
                path.close();
                p.setStyle(android.graphics.Paint.Style.FILL);
                cv.drawPath(path, p);
                cv.restore();
            }
        };
        row.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 17), Ui.dp(this, 17)));

        TextView brand = Ui.bold(this, "看剧", 17.5f, Ui.TEXT);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = Ui.dp(this, 7);
        row.addView(brand, blp);

        TextView sub = Ui.text(this, "KANJU", 9f, Ui.TEXT_3);
        sub.setLetterSpacing(0.22f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Ui.dp(this, 8);
        slp.bottomMargin = Ui.dp(this, 1);
        row.addView(sub, slp);

        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        // 换源：图标 + 文字的轻量胶囊
        LinearLayout src = new LinearLayout(this);
        src.setOrientation(LinearLayout.HORIZONTAL);
        src.setGravity(Gravity.CENTER_VERTICAL);
        src.setPadding(Ui.dp(this, 11), Ui.dp(this, 7), Ui.dp(this, 13), Ui.dp(this, 7));
        src.setBackground(Ui.ripple(0x22FFFFFF,
                Ui.round(Ui.SURFACE_2, Ui.dp(this, 16))));
        src.setClickable(true);
        src.setOnClickListener(v -> showSourceSheet());
        src.addView(Ui.icon(this, Ui.IC_SWAP, 13, Ui.ACCENT),
                new LinearLayout.LayoutParams(Ui.dp(this, 13), Ui.dp(this, 13)));
        TextView st = Ui.text(this, "换源", 12.5f, Ui.TEXT_2);
        st.setPadding(Ui.dp(this, 5), 0, 0, 0);
        src.addView(st);
        row.addView(src);
        return row;
    }

    /**
     * Chrome 式搜索框：整条胶囊，深色填充、无描边、左内嵌放大镜，
     * 右侧只在有输入时出现「清除 / 前往」。
     */
    private View buildSearchBar() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));

        LinearLayout box = new LinearLayout(this);
        pill = box;
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 5), 0);
        box.setBackground(Ui.ripple(0x1AFFFFFF, Ui.round(SEARCH_BG, Ui.dp(this, 26))));
        box.setClickable(true);
        box.setOnClickListener(v -> {
            input.requestFocus();
            showKeyboard(true);
        });

        box.addView(Ui.icon(this, Ui.IC_SEARCH, 17, Ui.TEXT_3),
                new LinearLayout.LayoutParams(Ui.dp(this, 17), Ui.dp(this, 17)));

        input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(15.5f);
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.TEXT_3);
        input.setHint("搜索片名、演员");
        input.setBackground(null);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setPadding(Ui.dp(this, 11), Ui.dp(this, 15), Ui.dp(this, 6), Ui.dp(this, 15));
        box.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        input.setOnFocusChangeListener((v, has) -> box.setBackground(Ui.ripple(0x1AFFFFFF,
                Ui.round(has ? SEARCH_BG_FOCUS : SEARCH_BG, Ui.dp(this, 26)))));

        clearBtn = Ui.iconButton(this, Ui.IC_CLOSE, 14, Ui.TEXT_2, 6, v -> {
            input.setText("");
            input.requestFocus();
            showKeyboard(true);
        });
        clearBtn.setVisibility(View.GONE);
        box.addView(clearBtn);

        goBtn = Ui.iconButton(this, Ui.IC_FORWARD, 14, Ui.BG, 9, v -> doSearch());
        goBtn.setBackground(Ui.ripple(0x40FFFFFF, Ui.round(Ui.ACCENT, Ui.dp(this, 32))));
        goBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                Ui.dp(this, 32), Ui.dp(this, 32));
        glp.rightMargin = Ui.dp(this, 2);
        box.addView(goBtn, glp);

        wrap.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50)));

        input.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                doSearch();
                return true;
            }
            return false;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                boolean has = s.length() > 0;
                clearBtn.setVisibility(has ? View.VISIBLE : View.GONE);
                goBtn.setVisibility(has ? View.VISIBLE : View.GONE);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
        return wrap;
    }

    private void doSearch() {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) {
            input.requestFocus();
            showKeyboard(true);
            return;
        }
        showKeyboard(false);
        input.clearFocus();
        startSearch(q);
    }

    private View buildStatusLine() {
        status = Ui.text(this, "", 11.5f, Ui.TEXT_3);
        status.setPadding(Ui.dp(this, 20), Ui.dp(this, 2), Ui.dp(this, 20), Ui.dp(this, 8));
        status.setVisibility(View.GONE);
        return status;
    }

    private View buildFooter() {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER);
        box.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 20));
        footerText = Ui.text(this, "加载更多…", 12.5f, Ui.TEXT_3);
        box.addView(footerText);
        return box;
    }

    private View buildEmpty() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(this, 24), Ui.dp(this, 54), Ui.dp(this, 24), Ui.dp(this, 40));
        box.setClickable(true);

        // 空状态标记：一个柔和的圆形底 + 放大镜，和搜索框呼应
        LinearLayout badge = new LinearLayout(this);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.round(Ui.SURFACE, Ui.dp(this, 40)));
        badge.addView(Ui.icon(this, Ui.IC_SEARCH, 30, 0xFF46534E),
                new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                Ui.dp(this, 80), Ui.dp(this, 80));
        ilp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(badge, ilp);

        emptyTitle = Ui.text(this, "", 16f, Ui.TEXT_2);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyTitle.setTypeface(emptyTitle.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(this, 14);
        tlp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(emptyTitle, tlp);

        emptyDesc = Ui.text(this, "", 12.5f, Ui.TEXT_3);
        emptyDesc.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Ui.dp(this, 7);
        dlp.gravity = Gravity.CENTER_HORIZONTAL;
        box.addView(emptyDesc, dlp);

        // 最近搜索（Chrome 新标签页里也有「最近搜索」）
        historyLabel = Ui.text(this, "最近搜索", 11.5f, Ui.TEXT_3);
        LinearLayout.LayoutParams hllp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hllp.topMargin = Ui.dp(this, 34);
        box.addView(historyLabel, hllp);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hsv.setClipToPadding(false);
        historyRow = new LinearLayout(this);
        historyRow.setOrientation(LinearLayout.HORIZONTAL);
        historyRow.setPadding(0, Ui.dp(this, 10), 0, 0);
        hsv.addView(historyRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams hsvlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hsvlp.topMargin = 0;
        box.addView(hsv, hsvlp);

        box.setVisibility(View.GONE);
        return box;
    }

    private void refreshHistory() {
        if (historyRow == null) return;
        historyRow.removeAllViews();
        List<String> his = Store.get(this).history();
        boolean show = !his.isEmpty();
        historyLabel.setVisibility(show ? View.VISIBLE : View.GONE);
        historyRow.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        for (String q : his) {
            final String v = q;
            TextView chip = Ui.chip(this, q, false);
            chip.setClickable(true);
            chip.setOnClickListener(cv -> {
                input.setText(v);
                input.setSelection(v.length());
                startSearch(v);
                showKeyboard(false);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 8);
            historyRow.addView(chip, lp);
        }
    }

    private void showEmpty(String title, String desc, boolean error) {
        emptyTitle.setText(title);
        emptyTitle.setTextColor(error ? Ui.DANGER : Ui.TEXT_2);
        emptyDesc.setText(desc);
        boolean show = items.isEmpty();
        emptyBox.setVisibility(show ? View.VISIBLE : View.GONE);
        list.setVisibility(show ? View.GONE : View.VISIBLE);
        emptyBox.setOnClickListener(error ? v -> startSearch(query) : null);
        if (show && !error) refreshHistory();
    }

    private void showKeyboard(boolean show) {
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (im == null) return;
        if (show) im.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        else im.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    // ------------------------------------------------------------ 数据

    private void startSearch(String q) {
        query = q;
        Store.get(this).setLastQuery(q);
        Store.get(this).addHistory(q);
        page = 1;
        hasMore = false;
        searched = true;
        items.clear();
        adapter.notifyDataSetChanged();
        emptyBox.setVisibility(View.GONE);
        status.setVisibility(View.GONE);
        loadPage(1);
    }

    private void loadPage(final int target) {
        if (loading) return;
        loading = true;
        footer.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        footerText.setText("加载中…");
        if (items.isEmpty() && searched) {
            showEmpty("正在搜索…", "正在从站点获取结果", false);
        }
        final String q = query;
        new Thread(() -> {
            try {
                final Models.Page p = Api.search(q, target);
                runOnUiThread(() -> {
                    loading = false;
                    footerText.setText("加载更多…");
                    if (!q.equals(query)) return;
                    page = target;
                    hasMore = p.hasMore;
                    if (target == 1) items.clear();
                    items.addAll(p.items);
                    adapter.notifyDataSetChanged();
                    footer.setVisibility(hasMore ? View.VISIBLE : View.GONE);
                    status.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
                    status.setText("共 " + items.size() + " 部" + (hasMore ? " · 继续下滑加载" : ""));
                    if (items.isEmpty()) {
                        showEmpty("没有找到《" + q + "》", "换个片名或演员名再试，也可以点这里重试", true);
                    } else {
                        emptyBox.setVisibility(View.GONE);
                        list.setVisibility(View.VISIBLE);
                    }
                });
            } catch (final Api.ApiException e) {
                runOnUiThread(() -> {
                    loading = false;
                    footer.setVisibility(View.GONE);
                    if (items.isEmpty()) {
                        showEmpty("搜索失败", e.getMessage() + "\n点这里重试", true);
                    } else {
                        status.setText("加载失败：" + e.getMessage());
                    }
                });
            }
        }).start();
    }

    private void openPlayer(Models.Movie m) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_VARIANT, m.variantId);
        i.putExtra(PlayerActivity.EXTRA_TITLE, m.title);
        i.putExtra(PlayerActivity.EXTRA_POSTER, m.poster);
        startActivity(i);
    }

    private void showSourceSheet() {
        final Store store = Store.get(this);
        Sheet.Builder sb = new Sheet.Builder(this)
                .title("换源", "播放时按此顺序尝试线路，失败会自动切换下一条");

        int[] prefs = {Api.PREF_AUTO, Api.PREF_OFFICIAL, Api.PREF_UHD, Api.PREF_OVERSEAS, Api.PREF_RESOURCE};
        for (int p : prefs) {
            final int v = p;
            boolean active = store.sourcePref() == p;
            sb.item(Ui.IC_SWAP, Api.prefName(p),
                    p == Api.PREF_AUTO ? "直链优先" : "", active ? 1 : 0, () -> {
                        store.setSourcePref(v);
                        toast("已切换为「" + Api.prefName(v) + "」");
                    });
        }
        sb.note("「自动优选」会先试 mp4 直链，再试 HLS；官方线路通常画质更高但偶尔限流。");
        sb.show();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (Sheet.dismissTop()) return;
        if (emptyBox.getVisibility() == View.VISIBLE && searched) {
            searched = false;
            showEmpty("搜片名，直接看", "输入片名或演员，回车即可搜索", false);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------ 列表

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            Holder h;
            if (convert == null) {
                LinearLayout row = new LinearLayout(HomeActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(Ui.dp(HomeActivity.this, 16), Ui.dp(HomeActivity.this, 9),
                        Ui.dp(HomeActivity.this, 16), Ui.dp(HomeActivity.this, 9));
                row.setBackground(Ui.ripple(0x14FFFFFF));

                h = new Holder();
                h.poster = new PosterView(HomeActivity.this);
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                        Ui.dp(HomeActivity.this, 66), Ui.dp(HomeActivity.this, 92));
                row.addView(h.poster, plp);

                LinearLayout info = new LinearLayout(HomeActivity.this);
                info.setOrientation(LinearLayout.VERTICAL);
                info.setPadding(Ui.dp(HomeActivity.this, 13), Ui.dp(HomeActivity.this, 2), 0, 0);
                row.addView(info, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f));

                h.title = Ui.text(HomeActivity.this, "", 15.5f, Ui.TEXT);
                h.title.setSingleLine(true);
                h.title.setEllipsize(TextUtils.TruncateAt.END);
                h.title.setTypeface(h.title.getTypeface(), android.graphics.Typeface.BOLD);
                info.addView(h.title);

                h.meta = Ui.text(HomeActivity.this, "", 12f, Ui.TEXT_2);
                h.meta.setSingleLine(true);
                h.meta.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                mlp.topMargin = Ui.dp(HomeActivity.this, 4);
                info.addView(h.meta, mlp);

                h.cast = Ui.text(HomeActivity.this, "", 11.5f, Ui.TEXT_3);
                h.cast.setSingleLine(true);
                h.cast.setEllipsize(TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.topMargin = Ui.dp(HomeActivity.this, 3);
                info.addView(h.cast, clp);

                h.badge = Ui.text(HomeActivity.this, "", 10.5f, Ui.ACCENT);
                h.badge.setPadding(Ui.dp(HomeActivity.this, 7), Ui.dp(HomeActivity.this, 2),
                        Ui.dp(HomeActivity.this, 7), Ui.dp(HomeActivity.this, 2));
                h.badge.setBackground(Ui.round(Ui.ACCENT_DEEP, Ui.dp(HomeActivity.this, 5)));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.topMargin = Ui.dp(HomeActivity.this, 6);
                info.addView(h.badge, blp);

                row.setTag(h);
                convert = row;
            } else {
                h = (Holder) convert.getTag();
            }

            Models.Movie m = items.get(position);
            h.title.setText(m.title);
            h.meta.setText(m.subtitle());
            String cast = m.castLine();
            h.cast.setText(cast);
            h.cast.setVisibility(cast.isEmpty() ? View.GONE : View.VISIBLE);
            String badge = !TextUtils.isEmpty(m.remarks) ? m.remarks
                    : ("series".equals(m.kind) && m.episodeCount > 0 ? ("全 " + m.episodeCount + " 集") : "");
            h.badge.setText(badge);
            h.badge.setVisibility(badge.isEmpty() ? View.GONE : View.VISIBLE);

            final String url = m.poster;
            h.poster.setBitmap(null);
            h.poster.setTag(url);
            if (!TextUtils.isEmpty(url)) {
                ImgLoader.load(HomeActivity.this, url,
                        Ui.dp(HomeActivity.this, 66), Ui.dp(HomeActivity.this, 92),
                        (u, bmp) -> {
                            if (h.poster.getTag() != null && h.poster.getTag().equals(u)) {
                                h.poster.setBitmap(bmp);
                            }
                        });
            }
            return convert;
        }
    }

    private static final class Holder {
        PosterView poster;
        TextView title;
        TextView meta;
        TextView cast;
        TextView badge;
    }
}
