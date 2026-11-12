package com.kanju.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 播放页：全部使用原生控件。
 * 暂停 / 拖动进度 / 倍速 / 锁屏 / 选集 / 换源 / 跳过片头片尾。
 */
public class PlayerActivity extends Activity implements PlayerView.Listener {

    public static final String EXTRA_VARIANT = "variant";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_POSTER = "poster";
    /** 详情页指定从第几集开始（0 基） */
    public static final String EXTRA_EPISODE = "episode_index";

    private String variantId = "";
    private String title = "";

    private PlayerView pv;
    private FrameLayout root;
    private LinearLayout topBar, centerBar, bottomBar;
    private LinearLayout lockLayer;
    private SeekBar seek;
    private TextView tvCur, tvDur, tvTitle, tvEp, tvLoading, tvSpeedBtn;
    private View btnPlayIcon;
    private View btnPlayState;
    private View btnPauseState;
    private Sheet activeSheet;
    private TextView btnSkipIntro, btnSkipOutro, btnLoadMore;
    private ProgressBar spinner;
    private View loadingBox;

    private Models.Detail detail;
    private final List<Models.Episode> episodes = new ArrayList<>();
    private int epIndex;
    private Models.Playback playback;
    private final List<Models.Line> lineOrder = new ArrayList<>();
    private int lineIndex;
    private Models.Line currentLine;
    private boolean lineSwitching;

    private int introEnd = -1;      // 秒
    private int outroFrom = -1;     // 距结尾秒
    private boolean introDone;
    private int pendingSeek = -1;
    private boolean userSeeking;
    private boolean locked;
    private boolean controlsVisible = true;
    private int loadGen;            // 防止过期回调（每次切换集数/线路自增）
    private long lastSave;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable hideTask;
    private Runnable watchdog;

    private Store store;

    // ------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = Store.get(this);
        variantId = getIntent().getStringExtra(EXTRA_VARIANT);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null) title = "";
        if (variantId == null) variantId = "";
        startIndex = getIntent().getIntExtra(EXTRA_EPISODE, -1);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        buildUi();
        enterImmersive();
        showLoading("正在解析播放线路…");

        new Thread(this::loadInitial).start();
    }

    private void loadInitial() {
        try {
            final Models.Detail d = Api.detail(variantId);
            final List<Models.Episode> eps = Api.episodes(variantId);
            runOnUiThread(() -> {
                detail = d;
                episodes.clear();
                episodes.addAll(eps);
                if (!TextUtils.isEmpty(d.title)) title = d.title;
                computeSkip();
                if (episodes.isEmpty()) {
                    showError("这部影片暂无可播放剧集");
                    return;
                }
                startEpisode(pickStartIndex(), true);
            });
        } catch (final Api.ApiException e) {
            runOnUiThread(() -> showError("加载失败：" + e.getMessage()));
        }
    }

    /** 优先回到上次没看完的那一集；详情页指定了集数就用指定的 */
    private int startIndex;

    private int pickStartIndex() {
        if (startIndex >= 0 && startIndex < episodes.size()) return startIndex;
        for (int i = 0; i < episodes.size(); i++) {
            long p = store.positionOf(episodes.get(i).token);
            if (p > 5000L) return i;
        }
        return 0;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pv != null) {
            pv.pause();
            savePosition();
        }
        updatePlayIcon();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (pv != null) pv.release();
        savePosition();
    }

    @Override
    public void onBackPressed() {
        if (Sheet.dismissTop()) return;
        if (locked) {
            locked = false;
            lockLayer.setVisibility(View.GONE);
            showControls(true);
            return;
        }
        // 转屏交给底栏的全屏按钮，返回键直接退回详情页
        savePosition();
        super.onBackPressed();
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        ui.postDelayed(this::scheduleHide, 300);
    }

    // ------------------------------------------------------------ UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        pv = new PlayerView(this);
        pv.setListener(this);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        plp.gravity = Gravity.CENTER;
        root.addView(pv, plp);

        // 顶栏
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 22));
        topBar.setBackground(gradientTop());

        topBar.addView(Ui.iconButton(this, Ui.IC_BACK, 22, Ui.TEXT, 9, v -> {
            savePosition();
            finish();
        }));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(this, 4);
        tvTitle = Ui.text(this, title, 15f, Ui.TEXT);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        tvTitle.setTypeface(tvTitle.getTypeface(), android.graphics.Typeface.BOLD);
        tvEp = Ui.text(this, "", 11.5f, 0xFFB9C4C0);
        tvEp.setSingleLine(true);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = Ui.dp(this, 2);
        titles.addView(tvTitle);
        titles.addView(tvEp, elp);
        topBar.addView(titles, tlp);

        topBar.addView(textChip("换源", v -> showLineSheet()));
        topBar.addView(textChip("选集", v -> showEpisodeSheet()));
        topBar.addView(Ui.iconButton(this, Ui.IC_DOTS, 21, Ui.TEXT, 8, v -> showMoreSheet()));

        // 中间控制
        centerBar = new LinearLayout(this);
        centerBar.setOrientation(LinearLayout.HORIZONTAL);
        centerBar.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER;
        root.addView(centerBar, clp);

        View prevBtn = Ui.iconButton(this, Ui.IC_PREV, 27, 0xFFE8EFEC, 15, v -> stepEpisode(-1));
        prevBtn.setBackground(Ui.ripple(0x33FFFFFF, Ui.round(0x4A000000, Ui.dp(this, 28))));
        centerBar.addView(prevBtn);

        LinearLayout playWrap = new LinearLayout(this);
        playWrap.setGravity(Gravity.CENTER);
        playWrap.setBackground(Ui.ripple(0x26FFFFFF, Ui.round(0x59000000, Ui.dp(this, 36))));
        LinearLayout.LayoutParams pplp = new LinearLayout.LayoutParams(
                Ui.dp(this, 72), Ui.dp(this, 72));
        pplp.leftMargin = Ui.dp(this, 22);
        pplp.rightMargin = Ui.dp(this, 22);
        btnPlayState = Ui.iconButton(this, Ui.IC_PLAY, 32, Ui.TEXT, 20, v -> togglePlay());
        btnPauseState = Ui.iconButton(this, Ui.IC_PAUSE, 32, Ui.TEXT, 20, v -> togglePlay());
        btnPauseState.setVisibility(View.GONE);
        playWrap.addView(btnPlayState);
        playWrap.addView(btnPauseState);
        btnPlayIcon = btnPauseState;
        centerBar.addView(playWrap, pplp);

        View nextBtn = Ui.iconButton(this, Ui.IC_NEXT, 27, 0xFFE8EFEC, 15, v -> stepEpisode(1));
        nextBtn.setBackground(Ui.ripple(0x33FFFFFF, Ui.round(0x4A000000, Ui.dp(this, 28))));
        centerBar.addView(nextBtn);

        // 底栏
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8),
                Ui.dp(this, 8) + Ui.navBarHeight(this) / 3);
        bottomBar.setBackground(gradientBottom());

        LinearLayout seekRow = new LinearLayout(this);
        seekRow.setOrientation(LinearLayout.HORIZONTAL);
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        tvCur = Ui.text(this, "00:00", 11.5f, 0xFFDDE5E2);
        seekRow.addView(tvCur);
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        seek.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF4A5654));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = Ui.dp(this, 10);
        slp.rightMargin = Ui.dp(this, 10);
        seekRow.addView(seek, slp);
        tvDur = Ui.text(this, "00:00", 11.5f, 0xFFDDE5E2);
        seekRow.addView(tvDur);
        bottomBar.addView(seekRow);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6), 0);

        btnSkipIntro = pill("跳过片头", v -> skipIntro(true));
        btnSkipOutro = pill("跳过片尾", v -> skipOutro());
        btnSkipIntro.setVisibility(View.GONE);
        btnSkipOutro.setVisibility(View.GONE);
        actions.addView(btnSkipIntro);
        actions.addView(btnSkipOutro);

        View spacer = new View(this);
        actions.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        tvSpeedBtn = pill(speedLabel(store.speed()), v -> showSpeedSheet());
        actions.addView(tvSpeedBtn);

        actions.addView(Ui.iconButton(this, Ui.IC_LOCK, 20, 0xFFDDE5E2, 8, v -> lockScreen()));
        actions.addView(Ui.iconButton(this, Ui.IC_FULLSCREEN, 20, 0xFFDDE5E2, 8,
                v -> toggleOrientation()));
        bottomBar.addView(actions);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        root.addView(bottomBar, blp);
        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 加载 / 错误层
        loadingBox = buildLoadingBox();
        FrameLayout.LayoutParams loadLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        loadLp.topMargin = Ui.dp(this, 64);
        root.addView(loadingBox, loadLp);

        // 锁屏层
        lockLayer = new LinearLayout(this);
        lockLayer.setOrientation(LinearLayout.VERTICAL);
        lockLayer.setGravity(Gravity.CENTER);
        lockLayer.setBackgroundColor(0x66000000);
        lockLayer.setVisibility(View.GONE);
        lockLayer.setOnClickListener(v -> {
            lockLayer.setVisibility(View.GONE);
            locked = false;
            showControls(true);
        });
        TextView lockHint = Ui.text(this, "已锁定 · 点击解锁", 13f, 0xE6FFFFFF);
        lockHint.setGravity(Gravity.CENTER);
        lockHint.setPadding(Ui.dp(this, 20), Ui.dp(this, 11), Ui.dp(this, 20), Ui.dp(this, 11));
        lockHint.setBackground(Ui.round(0x99000000, Ui.dp(this, 20)));
        // LinearLayout(VERTICAL) 默认宽度是 MATCH_PARENT，这里必须显式收成 wrap_content
        lockLayer.addView(lockHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(lockLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.setOnClickListener(v -> {
            if (locked) {
                lockLayer.setVisibility(View.VISIBLE);
            } else {
                showControls(!controlsVisible);
            }
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                int dur = pv.duration();
                if (dur > 0) tvCur.setText(fmt((int) ((long) dur * progress / 1000L)));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                userSeeking = true;
                ui.removeCallbacks(hideTask);
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                userSeeking = false;
                int dur = pv.duration();
                if (dur > 0) {
                    // 注意：dur*progress 必须用 long，否则 45 分钟 × 962 会 int 溢出成负数
                    pv.seekTo((int) ((long) dur * sb.getProgress() / 1000L));
                    if (sb.getProgress() < 995) introDone = false;
                }
                scheduleHide();
            }
        });

        setContentView(root);
        ui.postDelayed(() -> {
            if (controlsVisible) scheduleHide();
        }, 1800);
    }

    private View buildLoadingBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        spinner.getIndeterminateDrawable().setTint(Ui.ACCENT);
        box.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
        tvLoading = Ui.text(this, "", 12.5f, 0xFFC8D2CF);
        tvLoading.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        box.addView(tvLoading, lp);
        return box;
    }

    private GradientDrawable gradientTop() {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF2000000, 0xB3000000, 0x00000000});
        return d;
    }

    private GradientDrawable gradientBottom() {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF2000000, 0xB3000000, 0x00000000});
        return d;
    }

    private TextView textChip(String label, View.OnClickListener l) {
        TextView t = Ui.text(this, label, 12.5f, Ui.TEXT);
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 7), Ui.dp(this, 12), Ui.dp(this, 7));
        t.setBackground(Ui.round(0x4DFFFFFF, Ui.dp(this, 18)));
        t.setClickable(true);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Ui.dp(this, 6);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView pill(String label, View.OnClickListener l) {
        TextView t = Ui.text(this, label, 12.5f, Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(Ui.dp(this, 64));
        t.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        t.setBackground(Ui.round(0x33FFFFFF, Ui.dp(this, 18)));
        t.setClickable(true);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 8);
        t.setLayoutParams(lp);
        return t;
    }

    // ------------------------------------------------------------ 控制层显隐

    private void showControls(boolean show) {
        controlsVisible = show;
        if (show) {
            topBar.setVisibility(View.VISIBLE);
            bottomBar.setVisibility(View.VISIBLE);
            centerBar.setVisibility(View.VISIBLE);
            topBar.animate().alpha(1f).setDuration(180).start();
            bottomBar.animate().alpha(1f).setDuration(180).start();
            centerBar.animate().alpha(1f).setDuration(180).start();
            scheduleHide();
        } else {
            ui.removeCallbacks(hideTask);
            // 先淡出，动画结束再 GONE，避免控制栏「啪」一下消失
            fadeOut(topBar);
            fadeOut(bottomBar);
            fadeOut(centerBar);
        }
    }

    private void fadeOut(final View v) {
        v.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            if (!controlsVisible) v.setVisibility(View.GONE);
        }).start();
    }

    private void scheduleHide() {
        ui.removeCallbacks(hideTask);
        hideTask = () -> {
            if (!userSeeking && !locked && pv.isPlaying()) showControls(false);
        };
        ui.postDelayed(hideTask, 4200);
    }

    private void lockScreen() {
        locked = true;
        showControls(false);
        lockLayer.setVisibility(View.VISIBLE);
        toast("已锁屏，防误触");
    }

    // ------------------------------------------------------------ 播放流程

    private void startEpisode(int index, boolean resetPosition) {
        epIndex = index;
        // 先把上一集停住，避免解析期间旧视频继续播
        if (pv.isPlaying()) pv.pause();
        updatePlayIcon();
        introDone = false;
        pendingSeek = resetPosition ? -1 : pv.position();
        Models.Episode ep = episodes.get(index);
        long saved = store.positionOf(ep.token);
        if (resetPosition && saved > 5000) pendingSeek = (int) saved;
        tvEp.setText(ep.title + (episodes.size() > 1 ? (" · 共 " + episodes.size() + " 集") : ""));
        showLoading("正在解析线路…");
        final String token = ep.token;
        store.setRecentToken(variantId, token);
        final int gen = ++loadGen;
        new Thread(() -> {
            try {
                final Models.Playback pb = Api.playback(token);
                runOnUiThread(() -> {
                    if (gen != loadGen) return;
                    playback = pb;
                    buildLineOrder();
                    if (lineOrder.isEmpty()) {
                        showError("这部剧暂时没有可用线路");
                        return;
                    }
                    lineIndex = 0;
                    playLine(0, pendingSeek > 0 ? pendingSeek : 0);
                });
            } catch (final Api.ApiException e) {
                runOnUiThread(() -> showError("取播放地址失败：" + e.getMessage()));
            }
        }).start();
    }


    private void buildLineOrder() {
        lineOrder.clear();
        lineOrder.addAll(Api.order(playback, store.sourcePref()));
    }

    private void playLine(final int idx, final int startMs) {
        if (idx >= lineOrder.size()) {
            showError("所有线路都无法播放，请稍后重试或换一部影片");
            return;
        }
        final Models.Line candidate = lineOrder.get(idx);
        lineIndex = idx;
        lineSwitching = true;
        showLoading("连接 " + candidate.label + " …");
        final int gen = loadGen;
        new Thread(() -> {
            try {
                final Models.Line resolved = Api.resolveLine(candidate);
                runOnUiThread(() -> {
                    if (gen != loadGen) return;
                    currentLine = resolved;
                    pv.setSource(resolved.url, null, startMs);
                    pv.playWhenReady(true);
                    startWatchdog(idx);
                    updatePlayIcon();
                });
            } catch (final Api.ApiException e) {
                runOnUiThread(() -> {
                    if (gen != loadGen) return;
                    playLine(idx + 1, startMs);
                });
            }
        }).start();
    }

    private void startWatchdog(final int idx) {
        ui.removeCallbacks(watchdog);
        watchdog = () -> {
            if (!pv.isPrepared()) {
                toast("线路超时，自动换下一条");
                playLine(idx + 1, pv.duration() > 0 ? pv.position() : 0);
            }
        };
        ui.postDelayed(watchdog, 16000);
    }

    private void switchLine(int idx, int startMs) {
        lineIndex = idx;
        playLine(idx, startMs);
    }

    private void stepEpisode(int delta) {
        int next = epIndex + delta;
        if (next < 0) {
            toast("已经是第一集");
            return;
        }
        if (next >= episodes.size()) {
            toast("已经是最后一集");
            return;
        }
        savePosition();
        startEpisode(next, true);
        scheduleHide();
    }

    private void togglePlay() {
        if (pv.isPlaying()) {
            pv.pause();
            savePosition();
            showControls(true);
        } else {
            pv.start();
            scheduleHide();
        }
        updatePlayIcon();
    }

    private void savePosition() {
        if (episodes.isEmpty() || epIndex >= episodes.size()) return;
        int pos = pv.position();
        if (pos > 3000) store.setPosition(episodes.get(epIndex).token, pos);
        lastSave = System.currentTimeMillis();
    }

    // ------------------------------------------------------------ 跳片头片尾

    private void computeSkip() {
        if (detail == null) return;
        int i = store.introFor(variantId);
        int o = store.outroFor(variantId);
        introEnd = i >= 0 ? i : detail.introEndSeconds;
        outroFrom = o >= 0 ? o : detail.outroFromEndSeconds;
    }

    private void skipIntro(boolean manual) {
        if (introEnd <= 0) return;
        pv.seekTo(introEnd * 1000);
        introDone = true;
        btnSkipIntro.setVisibility(View.GONE);
        if (manual) toast("已跳过片头 " + introEnd + " 秒");
    }

    private void skipOutro() {
        if (epIndex + 1 < episodes.size()) {
            stepEpisode(1);
            toast("已跳过片尾");
        } else {
            toast("已经是最后一集");
        }
    }

    // ------------------------------------------------------------ PlayerView 回调

    @Override
    public void onPrepared(int durationMs) {
        runOnUiThread(() -> {
            ui.removeCallbacks(watchdog);
            lineSwitching = false;
            hideLoading();
            tvDur.setText(fmt(durationMs));
            pv.setSpeed(store.speed());
            if (pendingSeek > 0 && durationMs > pendingSeek) {
                pv.seekTo(pendingSeek);
                pendingSeek = -1;
            }
            updatePlayIcon();
            startTicker();
            if (store.autoSkipIntro() && introEnd > 0) {
                btnSkipIntro.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    public void onProgress(int positionMs, int durationMs) {
    }

    @Override
    public void onCompletion() {
        runOnUiThread(() -> {
            savePosition();
            if (store.autoNext() && epIndex + 1 < episodes.size()) {
                startEpisode(epIndex + 1, true);
            } else {
                showControls(true);
                toast("播放结束");
            }
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            ui.removeCallbacks(watchdog);
            if (lineIndex + 1 < lineOrder.size()) {
                playLine(lineIndex + 1, pv.position());
            } else {
                showError(message + "\n所有线路均已尝试");
            }
        });
    }

    @Override
    public void onBuffering(boolean buffering) {
        runOnUiThread(() -> {
            // 只在还没出画时给提示，播放中缓冲交给进度条自然停住
            if (!buffering && pv.isPrepared()) hideLoading();
        });
    }

    @Override
    public void onVideoSize(int w, int h) {
    }

    // ------------------------------------------------------------ 定时刷新

    private void startTicker() {
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            int dur = pv.duration();
            int pos = pv.position();
            if (dur > 0) {
                if (!userSeeking) {
                    seek.setProgress((int) (pos * 1000L / dur));
                    tvCur.setText(fmt(pos));
                    tvDur.setText(fmt(dur));
                }
                updateSkipChips(pos, dur);
                if (store.autoSkipIntro() && !introDone && introEnd > 0
                        && pos > 2200 && pos < introEnd * 1000 - 1200) {
                    skipIntro(false);
                }
            }
            updatePlayIcon();
            if (System.currentTimeMillis() - lastSave > 5000) savePosition();
            ui.postDelayed(this, 500);
        }
    };

    private void updateSkipChips(int pos, int dur) {
        if (introEnd > 0 && !introDone && pos < introEnd * 1000 - 1500) {
            btnSkipIntro.setText("跳过片头 " + introEnd + "s");
            btnSkipIntro.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
        } else {
            btnSkipIntro.setVisibility(View.GONE);
        }
        if (outroFrom > 0 && dur > 0) {
            int remain = dur - pos;
            if (remain <= outroFrom * 1000 && remain > 2000) {
                btnSkipOutro.setText("跳过片尾 " + Math.max(0, remain / 1000) + "s");
                btnSkipOutro.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
                return;
            }
        }
        btnSkipOutro.setVisibility(View.GONE);
    }

    private void updatePlayIcon() {
        if (btnPlayState == null || btnPauseState == null) return;
        boolean playing = pv.isPlaying();
        btnPlayState.setVisibility(playing ? View.GONE : View.VISIBLE);
        btnPauseState.setVisibility(playing ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------ 面板

    private void showSpeedSheet() {
        Sheet.Builder sb = new Sheet.Builder(this).title("播放倍速", "选中立即生效，下次播放沿用");
        final float[] speeds = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f};

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 14));

        final java.util.List<TextView> chips = new java.util.ArrayList<>();
        for (int i = 0; i < speeds.length; i++) {
            final float v = speeds[i];
            String label = v == 1f ? "原速 1.0x" : v + "x";
            TextView chip = Ui.chip(this, label, Math.abs(store.speed() - v) < 0.001f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f);
            lp.leftMargin = Ui.dp(this, 4);
            lp.rightMargin = Ui.dp(this, 4);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(c -> {
                store.setSpeed(v);
                pv.setSpeed(v);
                tvSpeedBtn.setText(speedLabel(v));
                for (int k = 0; k < chips.size(); k++) {
                    paintSpeedChip(chips.get(k), Math.abs(speeds[k] - v) < 0.001f);
                }
                toast("倍速 " + (v == 1f ? "已恢复原速" : v + "x"));
            });
            chips.add(chip);
            row.addView(chip);
        }

        sb.custom(row);
        sb.note("倍速只改变播放速度，不改变清晰度；拖动进度条时依旧按原片时间定位。");
        sb.show();
    }

    /** 统一的倍速文案：1x / 1.25x / 2x */
    private static String speedLabel(float v) {
        if (Math.abs(v - Math.round(v)) < 0.001f) return Math.round(v) + "x";
        return v + "x";
    }

    private void paintSpeedChip(TextView chip, boolean active) {
        chip.setTextColor(active ? Ui.ACCENT : Ui.TEXT_2);
        chip.setBackground(active
                ? Ui.roundStroke(Ui.ACCENT_DEEP, Ui.dp(this, 30), Math.max(1, Ui.dp(this, 1)), Ui.ACCENT)
                : Ui.roundStroke(Ui.SURFACE_2, Ui.dp(this, 30), 1, Ui.HAIRLINE));
    }

    private void showLineSheet() {
        if (playback == null || lineOrder.isEmpty()) {
            toast("线路还没准备好");
            return;
        }
        Sheet.Builder sb = new Sheet.Builder(this)
                .title("换源", "共 " + lineOrder.size() + " 条线路 · 当前 " + (currentLine == null ? "-" : currentLine.label));
        int count = Math.min(lineOrder.size(), 24);
        for (int i = 0; i < count; i++) {
            final int idx = i;
            Models.Line l = lineOrder.get(i);
            boolean active = currentLine != null && l.label.equals(currentLine.label)
                    && l.provider.equals(currentLine.provider);
            String kind = l.finalKind.isEmpty() ? l.urlKind : l.finalKind;
            sb.item(Ui.IC_SWAP, l.label, kind.toUpperCase(), active ? 1 : 0,
                    () -> switchLine(idx, pv.position()));
        }
        if (lineOrder.size() > count) sb.note("仅列出前 " + count + " 条；当前偏好：“"
                + Api.prefName(store.sourcePref()) + "”。");
        sb.show();
    }

    private void showEpisodeSheet() {
        if (episodes.isEmpty()) return;
        Sheet.Builder sb = new Sheet.Builder(this)
                .title("选集", detail != null && !TextUtils.isEmpty(detail.statusText)
                        ? detail.statusText : ("共 " + episodes.size() + " 集"));

        sb.custom(buildEpisodeGrid());
        activeSheet = sb.show();
    }

    private View buildEpisodeGrid() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 16));

        int perRow = 6;
        LinearLayout row = null;
        for (int i = 0; i < episodes.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rlp.topMargin = Ui.dp(this, 8);
                wrap.addView(row, rlp);
            }
            final int idx = i;
            Models.Episode ep = episodes.get(i);
            boolean active = idx == epIndex;
            TextView cell = Ui.text(this, String.valueOf(ep.number), 13.5f,
                    active ? Ui.BG : Ui.TEXT);
            cell.setGravity(Gravity.CENTER);
            cell.setBackground(active
                    ? Ui.round(Ui.ACCENT, Ui.dp(this, 10))
                    : Ui.roundStroke(Ui.SURFACE_2, Ui.dp(this, 10), 1, Ui.HAIRLINE));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f);
            clp.rightMargin = Ui.dp(this, 8);
            cell.setLayoutParams(clp);
            cell.setOnClickListener(v -> {
                if (idx != epIndex) {
                    savePosition();
                    startEpisode(idx, true);
                }
                if (activeSheet != null) activeSheet.dismiss();
            });
            if (row != null) row.addView(cell);
        }
        return wrap;
    }

    private void showMoreSheet() {
        Sheet.Builder sb = new Sheet.Builder(this).title("播放设置", null);

        sb.toggle(Ui.IC_FORWARD, "自动跳过片头", store.autoSkipIntro(), on -> {
            store.setAutoSkipIntro(on);
            if (on && introEnd > 0) {
                btnSkipIntro.setVisibility(View.VISIBLE);
                toast("将在每集开头自动跳过前 " + introEnd + " 秒");
            }
        });
        sb.toggle(Ui.IC_NEXT, "播完自动下一集", store.autoNext(), store::setAutoNext);

        sb.divider();
        sb.section("片头片尾校正");
        final int posSec = Math.max(0, pv.position() / 1000);
        sb.item(Ui.IC_FORWARD, "把当前进度设为片头结束",
                posSec + "s", 0, () -> {
                    store.setIntroFor(variantId, posSec);
                    computeSkip();
                    toast("已记住本剧片头 " + posSec + " 秒");
                });
        sb.item(Ui.IC_NEXT, "把当前进度设为片尾起点",
                posSec + "s", 0, () -> {
                    int dur = pv.duration() / 1000;
                    if (dur <= 0) return;
                    // 进度太靠前时设出来的「片尾」会覆盖整集，直接拒掉
                    if (posSec < dur / 2) {
                        toast("请先拖到片尾附近（后半段）再设置");
                        return;
                    }
                    int fromEnd = Math.max(0, dur - posSec);
                    store.setOutroFor(variantId, fromEnd);
                    computeSkip();
                    toast("已记住片尾提前 " + fromEnd + " 秒");
                });
        sb.item(Ui.IC_INFO, "恢复站点默认值", null, 0, () -> {
            store.setIntroFor(variantId, -1);
            store.setOutroFor(variantId, -1);
            computeSkip();
            toast("已恢复社区统计值");
        });

        if (detail != null && detail.introEndSeconds >= 0) {
            sb.note("社区统计：片头 " + detail.introEndSeconds + "s · 片尾提前 "
                    + detail.outroFromEndSeconds + "s（" + detail.skipSampleCount + " 人上报）");
        } else {
            sb.note("这部剧暂无社区片头片尾数据，可用上面两项手动标定。");
        }

        sb.divider();
        sb.section("当前线路");
        if (currentLine != null) {
            String host = "";
            try {
                host = android.net.Uri.parse(currentLine.url).getHost();
            } catch (Exception ignored) {
            }
            sb.note(currentLine.label + " · " + (currentLine.finalKind.isEmpty()
                    ? currentLine.urlKind : currentLine.finalKind)
                    + (TextUtils.isEmpty(host) ? "" : "\n" + host));
        }
        sb.item(Ui.IC_CLOSE, "关闭", () -> {
        });
        activeSheet = sb.show();
    }

    // ------------------------------------------------------------ 状态

    private void showLoading(String text) {
        loadingBox.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.VISIBLE);
        tvLoading.setText(text);
    }

    private void hideLoading() {
        loadingBox.setVisibility(View.GONE);
    }

    private void showError(String msg) {
        loadingBox.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.GONE);
        tvLoading.setText(msg + "\n\n点击重试");
        loadingBox.setOnClickListener(v -> {
            loadingBox.setOnClickListener(null);
            if (episodes.isEmpty()) loadInitialInThread();
            else startEpisode(epIndex, true);
        });
    }

    private void loadInitialInThread() {
        showLoading("正在重试…");
        new Thread(this::loadInitial).start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String fmt(int ms) {
        if (ms < 0) ms = 0;
        int total = ms / 1000;
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%02d:%02d", m, s);
    }

    // ------------------------------------------------------------ 屏幕

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    private void toggleOrientation() {
        if (isLandscape()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
    }

    private void enterImmersive() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}
