package com.kanju.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 海报图加载：内存 LRU + 磁盘缓存 + 采样解码，避免 ListView 滚动时抖动。 */
public final class ImgLoader {

    public interface Callback {
        void onBitmap(String url, Bitmap bitmap);
    }

    private static LruCache<String, Bitmap> mem;
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> LOADING = new HashSet<>();

    private ImgLoader() {}

    private static LruCache<String, Bitmap> mem(Context c) {
        if (mem == null) {
            int kb = (int) (Runtime.getRuntime().maxMemory() / 1024);
            mem = new LruCache<String, Bitmap>(kb / 8) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount() / 1024;
                }
            };
        }
        return mem;
    }

    public static void load(final Context ctx, final String url, final int reqW, final int reqH,
                            final Callback cb) {
        if (url == null || url.isEmpty()) return;
        Bitmap cached = mem(ctx).get(url);
        if (cached != null) {
            cb.onBitmap(url, cached);
            return;
        }
        synchronized (LOADING) {
            if (LOADING.contains(url)) return;
            LOADING.add(url);
        }
        final Context app = ctx.getApplicationContext();
        POOL.execute(() -> {
            Bitmap bmp = null;
            try {
                File f = diskFile(app, url);
                if (f.exists()) {
                    bmp = decodeFile(f, reqW, reqH);
                }
                if (bmp == null) {
                    bmp = download(url, reqW, reqH);
                    if (bmp != null) writeDisk(app, url, bmp);
                }
            } catch (Throwable ignored) {
            }
            final Bitmap out = bmp;
            synchronized (LOADING) {
                LOADING.remove(url);
            }
            if (out != null) mem(app).put(url, out);
            MAIN.post(() -> {
                if (out != null) cb.onBitmap(url, out);
            });
        });
    }

    // ------------------------------------------------------------ 内部

    private static Bitmap download(String url, int reqW, int reqH) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            c.setRequestProperty("Accept", "image/*");
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            byte[] data = bos.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            o.inSampleSize = sample(o.outWidth, o.outHeight, reqW, reqH);
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Throwable e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static Bitmap decodeFile(File f, int reqW, int reqH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            o.inSampleSize = sample(o.outWidth, o.outHeight, reqW, reqH);
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable e) {
            return null;
        }
    }

    private static int sample(int w, int h, int reqW, int reqH) {
        if (w <= 0 || h <= 0 || reqW <= 0 || reqH <= 0) return 1;
        int s = 1;
        while (w / (s * 2) >= reqW && h / (s * 2) >= reqH) s *= 2;
        return s;
    }

    private static void writeDisk(Context c, String url, Bitmap bmp) {
        try {
            File f = diskFile(c, url);
            OutputStream os = new FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.JPEG, 86, os);
            os.close();
        } catch (Throwable ignored) {
        }
    }

    private static File diskFile(Context c, String url) {
        File dir = new File(c.getCacheDir(), "posters");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, md5(url) + ".img");
    }

    private static String md5(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            byte[] b = d.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
