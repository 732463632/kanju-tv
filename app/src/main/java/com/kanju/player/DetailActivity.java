package com.kanju.player;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 竖屏详情页：先选集，再进播放页。
 * <p>
 * 版式对齐 B 站 / 爱奇艺 / 腾讯视频的竖屏详情：顶部海报做背景并向下渐隐，
 * 压在上面的是海报封面 + 标题 / 状态 / 类型 / 演员，往下依次是主播放按钮、
 * 选集网格、简介。点任意一集才进入横屏播放页。
 */
public class DetailActivity extends Activity {

    public static final String EXTRA_VARIANT = "variant";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_POSTER = "poster";
    public static final String EXTRA_REMARKS = "remarks";
    public static final String EXTRA_META = "meta";

    private static final int PAD = 18;       // 左右边距 dp
    private static final int COLS = 5;       // 选集列数
    private static final int CELL_H = 46;    // 选集格子高度 dp

    private String variantId = "";
    private String title = "";
    private String posterUrl = "";

    private Models.Detail detail;
    private final List<Models.Episode> episodes = new ArrayList<>();
    private boolean loaded;
    private long loadStamp;

    private ImageView heroBg;
    private PosterView posterView;
    private TextView tvTitle, tvBadge, tvMeta, tvCast;
    private TextView tvDesc, tvExpand, tvSourceNote;
    private TextView tvEpCount;
    private LinearLayout epSection, epGrid;
    private LinearLayout mainBtn;
    private TextView mainLabel;
    private View loadingBox, errorBox;
    private TextView errorText;

    private Store store;

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = Store.get(this);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        Intent it = getIntent();
        variantId = orEmpty(it.getStringExtra(EXTRA_VARIANT));
        title = orEmpty(it.getStringExtra(EXTRA_TITLE));
        posterUrl = orEmpty(it.getStringExtra(EXTRA_POSTER));
        String remarks = orEmpty(it.getStringExtra(EXTRA_REMARKS));
        String meta = orEmpty(it.getStringExtra(EXTRA_META));

        buildUi(remarks, meta);
        loadDetail();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从播放页返回时刷新「继续观看」与选集高亮
        if (loaded) {
            updatePlayBar();
            renderEpisodes();
        }
    }

    @Override
    public void onBackPressed() {
        if (Sheet.dismissTop()) return;
        super.onBackPressed();
    }

    // ------------------------------------------------------------ 骨架

    private void buildUi(String remarks, String meta) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        sc.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sc.setClipToPadding(false);
        sc.setFillViewport(true);
        sc.setPadding(0, 0, 0, Ui.navBarHeight(this) + Ui.dp(this, 24));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        sc.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(buildHero(remarks, meta));
        col.addView(buildPlayBar());
        col.addView(buildEpisodeSection());
        col.addView(buildDescSection());

        root.addView(sc, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 返回键固定在左上，不跟随滚动
        View back = Ui.iconButton(this, Ui.IC_BACK, 19, Ui.TEXT, 11, v -> finish());
        back.setBackground(Ui.ripple(0x33FFFFFF, Ui.round(0x8C000000, Ui.dp(this, 19))));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                Ui.dp(this, 38), Ui.dp(this, 38), Gravity.TOP | Gravity.START);
        blp.leftMargin = Ui.dp(this, 14);
        blp.topMargin = Ui.statusBarHeight(this) + Ui.dp(this, 10);
        root.addView(back, blp);

        loadingBox = buildLoading();
        loadingBox.setVisibility(View.GONE);
        root.addView(loadingBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 列表已经带了标题/海报，加载态延迟 260ms 再出现，避免闪一下白
        root.postDelayed(() -> {
            if (!loaded && errorBox.getVisibility() != View.VISIBLE) {
                loadingBox.setVisibility(View.VISIBLE);
            }
        }, 260);

        errorBox = buildError();
        errorBox.setVisibility(View.GONE);
        root.addView(errorBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        applyEdgeToEdge();
    }

    /** 顶部海报背景 + 底部压着的封面 / 标题 / 类型 / 演员 */
    private View buildHero(String remarks, String meta) {
        FrameLayout hero = new FrameLayout(this);

        heroBg = new ImageView(this);
        heroBg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        heroBg.setAlpha(0.82f);
        hero.addView(heroBg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hero.addView(new Scrim(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.HORIZONTAL);
        info.setGravity(Gravity.BOTTOM);
        info.setPadding(Ui.dp(this, PAD), Ui.dp(this, 8), Ui.dp(this, PAD), Ui.dp(this, 14));

        posterView = new PosterView(this);
        posterView.setRadiusDp(9);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                Ui.dp(this, 96), Ui.dp(this, 134));
        info.addView(posterView, plp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 14), 0, 0, Ui.dp(this, 2));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.gravity = Gravity.BOTTOM;
        info.addView(col, clp);

        tvTitle = Ui.bold(this, title, 19f, Ui.TEXT);
        tvTitle.setMaxLines(2);
        tvTitle.setEllipsize(TruncateAt.END);
        tvTitle.setLineSpacing(Ui.dp(this, 2), 1f);
        col.addView(tvTitle);

        tvBadge = Ui.text(this, remarks, 10.5f, Ui.ACCENT);
        tvBadge.setPadding(Ui.dp(this, 7), Ui.dp(this, 2), Ui.dp(this, 7), Ui.dp(this, 2));
        tvBadge.setBackground(Ui.round(Ui.ACCENT_DEEP, Ui.dp(this, 5)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 6);
        col.addView(tvBadge, blp);

        tvMeta = Ui.text(this, meta, 12f, Ui.TEXT_2);
        tvMeta.setMaxLines(2);
        tvMeta.setEllipsize(TruncateAt.END);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.dp(this, 6);
        col.addView(tvMeta, mlp);

        tvCast = Ui.text(this, "", 11.5f, Ui.TEXT_3);
        tvCast.setMaxLines(2);
        tvCast.setEllipsize(TruncateAt.END);
        LinearLayout.LayoutParams castlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        castlp.topMargin = Ui.dp(this, 4);
        col.addView(tvCast, castlp);

        hero.addView(info, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        hero.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heroHeight()));

        if (!TextUtils.isEmpty(posterUrl)) loadPoster(posterUrl);
        if (TextUtils.isEmpty(title)) tvTitle.setText("加载中…");
        if (TextUtils.isEmpty(remarks)) tvBadge.setVisibility(View.GONE);
        return hero;
    }

    private View buildPlayBar() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, PAD), Ui.dp(this, 4), Ui.dp(this, PAD), Ui.dp(this, 4));

        mainBtn = new LinearLayout(this);
        mainBtn.setOrientation(LinearLayout.HORIZONTAL);
        mainBtn.setGravity(Gravity.CENTER);
        mainBtn.setBackground(Ui.ripple(0x33FFFFFF, Ui.round(Ui.ACCENT, Ui.dp(this, 14))));
        mainBtn.setClickable(true);
        mainBtn.setFocusable(true);
        mainBtn.setOnClickListener(v -> openPlayer(defaultStartIndex()));

        View play = Ui.icon(this, Ui.IC_PLAY, 17, Ui.BG);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                Ui.dp(this, 17), Ui.dp(this, 17));
        plp.rightMargin = Ui.dp(this, 8);
        mainBtn.addView(play, plp);

        mainLabel = Ui.bold(this, "立即播放", 15.5f, Ui.BG);
        mainLabel.setSingleLine(true);
        mainLabel.setEllipsize(TruncateAt.END);
        mainBtn.addView(mainLabel);

        row.addView(mainBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1f));

        // 换源：只改「线路优先级」，真正 33 条线路在播放页里换
        LinearLayout src = new LinearLayout(this);
        src.setOrientation(LinearLayout.VERTICAL);
        src.setGravity(Gravity.CENTER);
        src.setBackground(Ui.ripple(0x22FFFFFF,
                Ui.roundStroke(Ui.SURFACE_2, Ui.dp(this, 14), 1, Ui.HAIRLINE)));
        src.setClickable(true);
        src.setOnClickListener(v -> showSourceSheet());
        src.addView(Ui.icon(this, Ui.IC_SWAP, 18, Ui.TEXT_2),
                new LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)));
        TextView st = Ui.text(this, "换源", 9.5f, Ui.TEXT_3);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = Ui.dp(this, 3);
        src.addView(st, stlp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                Ui.dp(this, 54), Ui.dp(this, 50));
        slp.leftMargin = Ui.dp(this, 10);
        row.addView(src, slp);

        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(row);

        tvSourceNote = Ui.text(this, "", 11f, Ui.TEXT_3);
        tvSourceNote.setPadding(Ui.dp(this, PAD), Ui.dp(this, 7), Ui.dp(this, PAD), Ui.dp(this, 2));
        wrap.addView(tvSourceNote);
        updateSourceNote();
        return wrap;
    }

    private View buildEpisodeSection() {
        epSection = new LinearLayout(this);
        epSection.setOrientation(LinearLayout.VERTICAL);
        epSection.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(this, PAD), Ui.dp(this, 6), Ui.dp(this, PAD), Ui.dp(this, 10));

        TextView t = Ui.bold(this, "选集", 16f, Ui.TEXT);
        head.addView(t);
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        tvEpCount = Ui.text(this, "", 12f, Ui.TEXT_3);
        head.addView(tvEpCount);
        epSection.addView(head);

        epGrid = new LinearLayout(this);
        epGrid.setOrientation(LinearLayout.VERTICAL);
        epGrid.setPadding(Ui.dp(this, PAD), 0, Ui.dp(this, PAD), 0);
        epSection.addView(epGrid);

        epSection.setVisibility(View.GONE);
        return epSection;
    }

    private View buildDescSection() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, PAD), Ui.dp(this, 16), Ui.dp(this, PAD), 0);

        box.addView(Ui.bold(this, "简介", 14.5f, Ui.TEXT));

        tvDesc = Ui.text(this, "暂无简介", 12.5f, Ui.TEXT_2);
        tvDesc.setLineSpacing(Ui.dp(this, 4), 1f);
        tvDesc.setMaxLines(4);
        tvDesc.setEllipsize(TruncateAt.END);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Ui.dp(this, 8);
        box.addView(tvDesc, dlp);

        tvExpand = Ui.text(this, "展开", 12f, Ui.ACCENT);
        tvExpand.setPadding(0, Ui.dp(this, 8), 0, 0);
        tvExpand.setVisibility(View.GONE);
        tvExpand.setOnClickListener(v -> {
            boolean expanded = tvDesc.getMaxLines() != Integer.MAX_VALUE;
            tvDesc.setMaxLines(expanded ? Integer.MAX_VALUE : 4);
            tvExpand.setText(expanded ? "收起" : "展开");
        });
        box.addView(tvExpand, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private View buildLoading() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xF20A0C0B);

        android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
        pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        box.addView(pb, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));

        TextView t = Ui.text(this, "正在加载详情…", 12.5f, Ui.TEXT_3);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 14);
        box.addView(t, lp);
        return box;
    }

    private View buildError() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Ui.BG);
        box.setClickable(true);

        errorText = Ui.text(this, "", 13f, Ui.TEXT_2);
        errorText.setGravity(Gravity.CENTER);
        box.addView(errorText);

        TextView retry = Ui.text(this, "点这里重试", 12.5f, Ui.ACCENT);
        retry.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        retry.setBackground(Ui.ripple(0x22FFFFFF, Ui.roundStroke(
                Ui.SURFACE_2, Ui.dp(this, 12), 1, Ui.HAIRLINE)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 14);
        box.addView(retry, lp);
        retry.setOnClickListener(v -> {
            errorBox.setVisibility(View.GONE);
            loadingBox.setVisibility(View.VISIBLE);
            loadDetail();
        });
        return box;
    }

    // ------------------------------------------------------------ 数据

    private void loadDetail() {
        final long stamp = ++loadStamp;
        new Thread(() -> {
            try {
                final Models.Detail d = Api.detail(variantId);
                final List<Models.Episode> eps = Api.episodes(variantId);
                runOnUiThread(() -> {
                    if (stamp != loadStamp) return;
                    detail = d;
                    episodes.clear();
                    episodes.addAll(eps);
                    loaded = true;
                    bindDetail();
                    loadingBox.setVisibility(View.GONE);
                });
            } catch (final Api.ApiException e) {
                runOnUiThread(() -> {
                    if (stamp != loadStamp) return;
                    loadingBox.setVisibility(View.GONE);
                    if (detail == null && episodes.isEmpty()) {
                        errorBox.setVisibility(View.VISIBLE);
                        errorText.setText("详情加载失败\n" + e.getMessage());
                    } else {
                        toast("加载失败：" + e.getMessage());
                    }
                });
            }
        }).start();
    }

    private void bindDetail() {
        Models.Detail d = detail;
        if (d == null) return;

        if (!TextUtils.isEmpty(d.title)) {
            title = d.title;
            tvTitle.setText(title);
        }
        if (!TextUtils.isEmpty(d.poster) && !d.poster.equals(posterUrl)) {
            posterUrl = d.poster;
            loadPoster(posterUrl);
            applyHeroHeight();
        }

        StringBuilder meta = new StringBuilder();
        if (d.year > 0) meta.append(d.year);
        if (!TextUtils.isEmpty(d.area)) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(d.area);
        }
        // genres 里混着「中国·2026·悬疑」这类聚合串和「连续剧」这类泛型词，都跳过
        int picked = 0;
        for (String g : d.genres) {
            if (picked >= 3) break;
            if (g == null || g.isEmpty() || g.contains("·")) continue;
            if (g.equals("连续剧") || g.equals("电视剧") || g.equals("电影") || g.equals("连续")) continue;
            if (meta.indexOf(g) >= 0) continue;
            if (meta.length() > 0) meta.append(" · ");
            meta.append(g);
            picked++;
        }
        tvMeta.setText(meta.toString());
        tvMeta.setVisibility(meta.length() == 0 ? View.GONE : View.VISIBLE);

        String badge = !TextUtils.isEmpty(d.statusText) ? d.statusText : d.remarks;
        if (!TextUtils.isEmpty(badge)) {
            tvBadge.setText(badge);
            tvBadge.setVisibility(View.VISIBLE);
        }

        String cast = d.actorsLine();
        tvCast.setText(cast);
        tvCast.setVisibility(cast.isEmpty() ? View.GONE : View.VISIBLE);

        if (!TextUtils.isEmpty(d.description)) {
            final String desc = d.description.replace("\n", "  ").trim();
            tvDesc.setText(desc);
            // maxLines 会把 Layout 的行数也截断，getLineCount() 永远 <= 4，
            // 所以按字数粗判是否需要「展开」（约每行 24 个汉字）
            tvExpand.setVisibility(desc.length() > 86 ? View.VISIBLE : View.GONE);
        }

        updatePlayBar();
        renderEpisodes();
    }

    private void updatePlayBar() {
        if (mainLabel == null) return;
        int total = episodes.size();
        if (total == 0) {
            mainLabel.setText(loaded ? "暂无播放源" : "立即播放");
            mainLabel.setTextColor(loaded ? Ui.TEXT_3 : Ui.BG);
            mainBtn.setBackground(loaded
                    ? Ui.round(Ui.SURFACE_2, Ui.dp(this, 14))
                    : Ui.ripple(0x33FFFFFF, Ui.round(Ui.ACCENT, Ui.dp(this, 14))));
            return;
        }
        int idx = defaultStartIndex();
        Models.Episode ep = episodes.get(Math.max(0, Math.min(idx, total - 1)));
        long pos = store.positionOf(ep.token);
        if (pos > 5000 && total > 1) {
            mainLabel.setText("继续观看 " + ep.title + " · " + fmt(pos));
        } else if (total > 1) {
            mainLabel.setText("播放 " + ep.title);
        } else {
            mainLabel.setText("立即播放");
        }
    }

    private void updateSourceNote() {
        if (tvSourceNote == null) return;
        tvSourceNote.setText("线路：" + Api.prefName(store.sourcePref())
                + " · 卡顿可点右侧「换源」，播放页内也能逐条切换");
    }

    /** 优先用「最近看过的那一集」，退而求其次用第一条有进度的集 */
    private int defaultStartIndex() {
        String recent = store.recentToken(variantId);
        if (!TextUtils.isEmpty(recent)) {
            for (int i = 0; i < episodes.size(); i++) {
                if (recent.equals(episodes.get(i).token)
                        && store.positionOf(recent) > 5000L) return i;
            }
        }
        for (int i = 0; i < episodes.size(); i++) {
            if (store.positionOf(episodes.get(i).token) > 5000L) return i;
        }
        return 0;
    }

    // ------------------------------------------------------------ 选集

    private void renderEpisodes() {
        if (epGrid == null) return;
        epGrid.removeAllViews();
        int total = episodes.size();
        epSection.setVisibility(total > 1 ? View.VISIBLE : View.GONE);
        if (total <= 1) return;

        tvEpCount.setText("共 " + total + " 集");

        int gap = Ui.dp(this, 8);
        int avail = getResources().getDisplayMetrics().widthPixels - Ui.dp(this, PAD) * 2;
        int cellW = (avail - gap * (COLS - 1)) / COLS;
        int lastWatched = defaultStartIndex();

        LinearLayout row = null;
        for (int i = 0; i < total; i++) {
            int colIdx = i % COLS;
            if (colIdx == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = gap;
                epGrid.addView(row, rlp);
            }
            final int index = i;
            Models.Episode ep = episodes.get(i);
            boolean watched = store.positionOf(ep.token) > 5000L;
            boolean active = i == lastWatched && watched;
            TextView cell = Ui.text(this, String.valueOf(ep.number), 13f,
                    active ? Ui.ACCENT : (watched ? Ui.TEXT : Ui.TEXT_2));
            cell.setGravity(Gravity.CENTER);
            cell.setSingleLine(true);
            cell.setEllipsize(TruncateAt.END);
            if (active) {
                cell.setBackground(Ui.ripple(0x33FFFFFF, Ui.roundStroke(
                        Ui.ACCENT_DEEP, Ui.dp(this, 10), 1, Ui.ACCENT)));
            } else {
                cell.setBackground(Ui.ripple(0x1FFFFFFF, Ui.round(Ui.SURFACE_2, Ui.dp(this, 10))));
            }
            cell.setClickable(true);
            cell.setOnClickListener(v -> openPlayer(index));

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    cellW, Ui.dp(this, CELL_H));
            if (colIdx > 0) clp.leftMargin = gap;
            row.addView(cell, clp);
        }

        // 末行补空位，保证最后一行格子不会被拉宽
        int filled = total % COLS;
        if (filled != 0 && row != null) {
            for (int i = filled; i < COLS; i++) {
                View blank = new View(this);
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        cellW, Ui.dp(this, CELL_H));
                if (i > 0) blp.leftMargin = gap;
                row.addView(blank, blp);
            }
        }
    }

    // ------------------------------------------------------------ 交互

    private void openPlayer(int index) {
        if (episodes.isEmpty()) {
            toast("这部片暂时没有可播放的剧集，换一部试试");
            return;
        }
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_VARIANT, variantId);
        i.putExtra(PlayerActivity.EXTRA_TITLE, title);
        i.putExtra(PlayerActivity.EXTRA_POSTER, posterUrl);
        i.putExtra(PlayerActivity.EXTRA_EPISODE, index);
        startActivity(i);
        overridePendingTransition(0, 0);
    }

    private void showSourceSheet() {
        Sheet.Builder sb = new Sheet.Builder(this)
                .title("换源", "按此顺序尝试线路，播放页内还能逐条切换");
        int[] prefs = {Api.PREF_AUTO, Api.PREF_OFFICIAL, Api.PREF_UHD,
                Api.PREF_OVERSEAS, Api.PREF_RESOURCE};
        for (int p : prefs) {
            final int v = p;
            boolean active = store.sourcePref() == p;
            sb.item(Ui.IC_SWAP, Api.prefName(p),
                    p == Api.PREF_AUTO ? "直链优先" : "", active ? 1 : 0, () -> {
                        store.setSourcePref(v);
                        updateSourceNote();
                        toast("已切换为「" + Api.prefName(v) + "」");
                    });
        }
        sb.note("「自动优选」会先试 mp4 直链，再试 HLS；官方线路通常画质更高但偶尔限流。");
        sb.show();
    }

    /** 有海报时 hero 高一些，纯色背景（无海报）时收紧，避免顶部一大片黑 */
    private int heroHeight() {
        return Ui.statusBarHeight(this)
                + Ui.dp(this, TextUtils.isEmpty(posterUrl) ? 190 : 214);
    }

    private void applyHeroHeight() {
        if (heroBg == null || !(heroBg.getParent() instanceof View)) return;
        View hero = (View) heroBg.getParent();
        ViewGroup.LayoutParams lp = hero.getLayoutParams();
        if (lp == null) return;
        lp.height = heroHeight();
        hero.setLayoutParams(lp);
    }

    private void loadPoster(String url) {
        // hero 背景要铺满屏宽，所以按大图请求一次，封面复用同一张（PosterView 自己缩放）
        int w = getResources().getDisplayMetrics().widthPixels;
        ImgLoader.load(this, url, w, Ui.dp(this, 260), (u, bmp) -> {
            if (bmp == null) return;
            posterView.setBitmap(bmp);
            if (heroBg != null) {
                heroBg.setImageBitmap(bmp);
                heroBg.setAlpha(0.82f);
            }
        });
    }

    private void applyEdgeToEdge() {
        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        // 海报铺到状态栏下：内容延伸到系统栏区域，但状态栏图标保持浅色（深色海报上才看得见）。
        // 这里用老的 systemUiVisibility：新版 setSystemBarsAppearance 在部分 ROM 上不生效。
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        flags |= View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(flags);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(java.util.Locale.US, "%02d:%02d", m, sec);
    }

    /** 海报顶部压一层向下加深的渐变，让信息区和页面底色自然衔接 */
    private static final class Scrim extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private LinearGradient shader;
        private int shaderH;

        Scrim(android.content.Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas cv) {
            int h = getHeight();
            if (h <= 0) return;
            if (shader == null || shaderH != h) {
                shader = new LinearGradient(0, 0, 0, h,
                        new int[]{0x400A0C0B, 0x730A0C0B, 0xD90A0C0B, Ui.BG},
                        new float[]{0f, 0.38f, 0.76f, 1f}, Shader.TileMode.CLAMP);
                shaderH = h;
            }
            p.setShader(shader);
            cv.drawRect(0, 0, getWidth(), h, p);
        }
    }
}
