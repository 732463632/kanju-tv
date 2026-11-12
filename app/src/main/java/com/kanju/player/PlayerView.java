package com.kanju.player;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.Map;

/**
 * 原生播放内核：TextureView + MediaPlayer。
 * 用 TextureView 而不是 SurfaceView，画面会进入 View 层级，缩放/截图/切后台都更稳。
 */
public final class PlayerView extends FrameLayout implements TextureView.SurfaceTextureListener {

    public interface Listener {
        void onPrepared(int durationMs);

        void onProgress(int positionMs, int durationMs);

        void onCompletion();

        void onError(String message);

        void onBuffering(boolean buffering);

        void onVideoSize(int w, int h);
    }

    private final TextureView texture;
    private MediaPlayer mp;
    private Surface surface;
    private Listener listener;
    private String url;
    private Map<String, String> headers;
    private boolean prepared;
    private boolean released;
    private boolean playWhenReady = true;
    private float speed = 1f;
    private int videoW, videoH;
    private int startPosMs;

    public PlayerView(Context c) {
        super(c);
        setBackgroundColor(0xFF000000);
        texture = new TextureView(c);
        texture.setSurfaceTextureListener(this);
        addView(texture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        setKeepScreenOn(true);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setSpeed(float s) {
        speed = s;
        applySpeed();
    }

    /** 是否已经对播放器设过非 1x 倍速（1x 时若从未设过就完全不碰 PlaybackParams） */
    private boolean speedDirty;

    private void applySpeed() {
        if (mp == null || !prepared) return;
        boolean normal = Math.abs(speed - 1f) < 0.001f;
        if (normal && !speedDirty) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                boolean wasPlaying = mp.isPlaying();
                PlaybackParams p = mp.getPlaybackParams();
                p.setSpeed(speed);
                p.setPitch(1f);
                mp.setPlaybackParams(p);
                if (wasPlaying) mp.start();
                speedDirty = !normal;
            }
        } catch (Throwable ignored) {
        }
    }

    public void setSource(String u, Map<String, String> h, int startMs) {
        url = u;
        headers = h;
        startPosMs = Math.max(0, startMs);
        prepared = false;
        releasePlayer();
        if (texture.isAvailable()) {
            surface = new Surface(texture.getSurfaceTexture());
            prepare();
        }
    }

    public void playWhenReady(boolean b) {
        playWhenReady = b;
    }

    private void prepare() {
        if (url == null || released || surface == null) return;
        try {
            mp = new MediaPlayer();
            if (Build.VERSION.SDK_INT >= 21) {
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build());
            }
            mp.setSurface(surface);
            mp.setOnPreparedListener(m -> {
                prepared = true;
                videoW = m.getVideoWidth();
                videoH = m.getVideoHeight();
                int dur = m.getDuration();
                if (startPosMs > 0 && startPosMs < dur - 1000) m.seekTo(startPosMs);
                if (listener != null) {
                    listener.onPrepared(dur);
                    listener.onVideoSize(videoW, videoH);
                }
                if (playWhenReady) {
                    try {
                        m.start();
                    } catch (Throwable ignored) {
                    }
                }
                applySpeed();
                requestLayout();
            });
            mp.setOnCompletionListener(m -> {
                if (listener != null) listener.onCompletion();
            });
            mp.setOnErrorListener((m, what, extra) -> {
                if (listener != null) listener.onError("播放失败（" + what + "/" + extra + "）");
                return true;
            });
            mp.setOnInfoListener((m, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    if (listener != null) listener.onBuffering(true);
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END
                        || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    if (listener != null) listener.onBuffering(false);
                }
                return false;
            });
            mp.setOnVideoSizeChangedListener((m, w, h) -> {
                videoW = w;
                videoH = h;
                requestLayout();
                if (listener != null) listener.onVideoSize(w, h);
            });
            if (headers != null && !headers.isEmpty()) {
                mp.setDataSource(getContext(), Uri.parse(url), headers);
            } else {
                mp.setDataSource(url);
            }
            mp.prepareAsync();
        } catch (Throwable e) {
            if (listener != null) listener.onError("打开片源失败：" + e.getClass().getSimpleName());
        }
    }

    public void start() {
        playWhenReady = true;
        try {
            if (mp != null && prepared) mp.start();
        } catch (Throwable ignored) {
        }
    }

    public void pause() {
        playWhenReady = false;
        try {
            if (mp != null && prepared && mp.isPlaying()) mp.pause();
        } catch (Throwable ignored) {
        }
    }

    public boolean isPlaying() {
        try {
            return mp != null && prepared && mp.isPlaying();
        } catch (Throwable e) {
            return false;
        }
    }

    public int position() {
        try {
            return mp != null && prepared ? mp.getCurrentPosition() : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    public int duration() {
        try {
            return mp != null && prepared ? mp.getDuration() : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    public void seekTo(int ms) {
        try {
            if (mp != null && prepared) mp.seekTo(ms);
        } catch (Throwable ignored) {
        }
    }

    public boolean isPrepared() {
        return prepared;
    }

    public void release() {
        released = true;
        releasePlayer();
    }

    private void releasePlayer() {
        prepared = false;
        if (mp != null) {
            try {
                mp.reset();
                mp.release();
            } catch (Throwable ignored) {
            }
            mp = null;
        }
    }

    // ------------------------------------------------------------ TextureView 回调

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        if (url != null && !prepared) prepare();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        // 保留解码器，仅解绑画面，切后台或旋转回来能继续
        if (mp != null) {
            try {
                mp.setSurface(null);
            } catch (Throwable ignored) {
            }
        }
        surface = null;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }

    // ------------------------------------------------------------ 尺寸

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        if (videoW > 0 && videoH > 0 && w > 0 && h > 0) {
            float ratio = videoW / (float) videoH;
            int vw = w, vh = (int) (w / ratio);
            if (vh > h) {
                vh = h;
                vw = (int) (h * ratio);
            }
            texture.measure(MeasureSpec.makeMeasureSpec(vw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(vh, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        if (videoW > 0 && videoH > 0) {
            float ratio = videoW / (float) videoH;
            int vw = w, vh = (int) (w / ratio);
            if (vh > h) {
                vh = h;
                vw = (int) (h * ratio);
            }
            int left = (w - vw) / 2, top = (h - vh) / 2;
            texture.layout(left, top, left + vw, top + vh);
        } else {
            texture.layout(0, 0, w, h);
        }
    }
}
