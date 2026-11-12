package com.kanju.player;

import android.content.Context;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 播放预载内核：一个只监听 127.0.0.1 的极简 HTTP 缓存服务。
 *
 * MediaPlayer 播的是 http://127.0.0.1:port/v/<key>，真实数据由这里从源站
 * 顺序预读到磁盘（提前 readAheadMB 兆），播放器读的是本地文件，
 * 于是网络抖动只会影响「预载进度」，不会直接怼到画面上造成卡顿。
 *
 * 行为约定：
 *  - 请求落在磁盘窗口内 → 从文件喂，等预载追上来；
 *  - 请求落在窗口外（例如 MP4 的 moov 在文件尾部）→ 直连源站透传，不打乱预载；
 *  - 拖动到大跨度位置 → 窗口跟随跳转，预载从新位置重新开始。
 */
public final class CacheProxy {

    private static final String TAG = "CacheProxy";

    /** 单次读盘 / 落盘块大小 */
    private static final int BLOCK = 48 * 1024;
    /** 缓存目录上限，超了按最久没用的先删 */
    private static final long MAX_CACHE = 1200L * 1024 * 1024;
    /** 小范围请求（读取文件头/尾索引）不走缓存，直接透传 */
    private static final long PROBE_BYTES = 2L * 1024 * 1024;

    private static CacheProxy inst;

    public static synchronized CacheProxy get(Context c) {
        if (inst == null) inst = new CacheProxy(c.getApplicationContext());
        return inst;
    }

    private final File dir;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, Playlist> playlists = new ConcurrentHashMap<>();
    private ServerSocket server;
    private int port;

    /** 单个分片预取多少个 */
    private static final int SEG_AHEAD = 8;
    /** 起播前先下完几片 */
    private static final int PRIME_SEGMENTS = 4;

    /** 应用在前台才继续预载，退到后台就停，别偷跑流量 */
    private volatile boolean active = true;
    /** 预读窗口（字节）：比播放点提前下载这么多 */
    private volatile long readAhead = 80L * 1024 * 1024;
    /** 整集预载 */
    private volatile boolean fullPrefetch = false;
    /** 最近一次在播的清单：清缓存时要保住它 */
    private volatile Playlist current;

    private CacheProxy(Context c) {
        dir = new File(c.getCacheDir(), "vcache");
        if (!dir.exists() && !dir.mkdirs()) Log.w(TAG, "缓存目录创建失败");
        try {
            server = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
            port = server.getLocalPort();
            Thread t = new Thread(this::acceptLoop, "cache-proxy");
            t.setDaemon(true);
            t.start();
            Log.i(TAG, "预载服务已启动 127.0.0.1:" + port);
        } catch (Throwable e) {
            port = 0;
            Log.w(TAG, "预载服务启动失败，回落到直连：" + e);
        }
        trim(null);
    }

    // ------------------------------------------------------------ 对外接口

    public boolean enabled() {
        return port > 0;
    }

    public void setActive(boolean b) {
        active = b;
        wakeAll();
    }

    public void setFullPrefetch(boolean b) {
        fullPrefetch = b;
        wakeAll();
    }

    public void setReadAheadMB(int mb) {
        readAhead = Math.max(8, mb) * 1024L * 1024L;
        wakeAll();
    }

    /**
     * 把源站地址包成本地地址；返回 null 表示代理不可用，调用方直接播原地址。
     * 同一个源地址重复调用会复用同一个缓存文件（续播、暂停恢复都能命中）。
     */
    public String localUrl(String origin, Map<String, String> headers) {
        if (port == 0 || origin == null || origin.isEmpty()) return null;
        if (origin.startsWith("http://127.0.0.1")) return origin;
        Entry e = entryFor(origin, headers);
        return "http://127.0.0.1:" + port + "/v/" + e.key;
    }

    /** HLS：返回改写过的本地播放清单地址，清单里的分片同样走预载 */
    public String hlsUrl(String origin, Map<String, String> headers) {
        if (port == 0 || origin == null || origin.isEmpty()) return null;
        String key = keyOf(origin);
        synchronized (playlists) {
            Playlist p = playlists.get(key);
            if (p == null) {
                p = new Playlist(origin, headers);
                playlists.put(key, p);
            }
        }
        try {
            return "http://127.0.0.1:" + port + "/hls/" + key + ".m3u8?u="
                    + java.net.URLEncoder.encode(origin, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private Entry entryFor(String origin, Map<String, String> headers) {
        String key = keyOf(origin);
        Entry e = entries.get(key);
        if (e == null) {
            trim(null);
            e = new Entry(key, origin, headers, new File(dir, key + ".dat"));
            Entry old = entries.put(key, e);
            if (old != null) e = old;
        }
        ensurePrefetch(e);
        return e;
    }

    /** 停止所有预载（换线路 / 退出播放页），缓存文件保留 */
    public void stopPrefetch() {
        for (Entry e : entries.values()) {
            synchronized (e) {
                e.dead = true;
                e.notifyAll();
            }
        }
        entries.clear();
        synchronized (playlists) {
            for (Playlist p : playlists.values()) {
                p.dead = true;
            }
            playlists.clear();
        }
    }

    /** 当前这条地址的预载进度：{已下载字节, 总字节(-1 未知)} */
    public long[] progressOf(String origin) {
        if (origin == null) return new long[]{0, -1};
        Entry e = entries.get(keyOf(origin));
        if (e != null) {
            synchronized (e) {
                long done = Math.max(0, e.downloaded - e.windowStart);
                return new long[]{done, e.total};
            }
        }
        // HLS：把清单里各分片的进度累加起来
        Playlist p;
        synchronized (playlists) {
            p = playlists.get(keyOf(origin));
        }
        if (p == null) return new long[]{0, -1};
        List<String> segs;
        synchronized (p) {
            segs = new ArrayList<>(p.segs);
        }
        if (segs.isEmpty()) return new long[]{0, -1};
        long done = 0, total = 0;
        int counted = 0;
        for (String s : segs) {
            Entry se = entries.get(keyOf(s));
            if (se == null) continue;
            counted++;
            synchronized (se) {
                done += Math.max(0, se.downloaded - se.windowStart);
                total += se.total > 0 ? se.total : 0;
            }
        }
        if (counted == 0) return new long[]{0, -1};
        // 分片大小不均匀，用已下过的平均片长 × 总片数估算整集大小，供进度展示
        long est = (long) ((double) done / counted * segs.size());
        return new long[]{done, Math.max(est, total)};
    }

    public long cacheSize() {
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        long n = 0;
        for (File f : fs) n += f.length();
        return n;
    }

    public void clearAll() {
        // 正在播的这集要留着，否则播放器下一次要分片会直接 404
        Playlist keep = current;
        List<String> keepKeys = new ArrayList<>();
        if (keep != null) {
            synchronized (keep) {
                for (String s : keep.segs) keepKeys.add(keyOf(s));
            }
        }
        List<String> drop = new ArrayList<>();
        for (Map.Entry<String, Entry> kv : entries.entrySet()) {
            if (keepKeys.contains(kv.getKey())) continue;
            drop.add(kv.getKey());
        }
        for (String k : drop) {
            Entry e = entries.remove(k);
            if (e == null) continue;
            synchronized (e) {
                e.dead = true;
                e.notifyAll();
            }
            //noinspection ResultOfMethodCallIgnored
            e.file.delete();
        }
        synchronized (playlists) {
            List<String> keys = new ArrayList<>(playlists.keySet());
            for (String k : keys) {
                Playlist p = playlists.get(k);
                if (p == keep) continue;
                if (p != null) p.dead = true;
                playlists.remove(k);
            }
        }
        // 目录里可能还有上次残留的文件
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (keepKeys.contains(f.getName().replace(".dat", ""))) continue;
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private void wakeAll() {
        for (Entry e : entries.values()) {
            synchronized (e) {
                e.notifyAll();
            }
        }
    }

    // ------------------------------------------------------------ 入口

    private void acceptLoop() {
        while (true) {
            try {
                final Socket s = server.accept();
                Thread t = new Thread(() -> handle(s), "cache-conn");
                t.setDaemon(true);
                t.start();
            } catch (Throwable e) {
                if (server == null || server.isClosed()) return;
            }
        }
    }

    private void handle(Socket s) {
        OutputStream out = null;
        try {
            s.setSoTimeout(30000);
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream(), 4096);
            out = new BufferedOutputStream(s.getOutputStream(), BLOCK);

            String req = readLine(in);
            if (req == null) return;
            String[] parts = req.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1];

            Map<String, String> hdrs = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) {
                    hdrs.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT),
                            line.substring(i + 1).trim());
                }
            }

            if (path.startsWith("/ping")) {
                writeHead(out, 200, "OK", 0, null, null, -1);
                return;
            }
            if (path.startsWith("/hls/")) {
                String query = path.indexOf('?') > 0 ? path.substring(path.indexOf('?') + 1) : "";
                String origin = queryParam(query, "u");
                if (origin == null || origin.isEmpty()) {
                    writeHead(out, 404, "Not Found", 0, null, null, -1);
                    return;
                }
                handlePlaylist(out, origin);
                return;
            }
            if (path.startsWith("/seg/")) {
                String p2 = path.indexOf('?') > 0 ? path.substring(0, path.indexOf('?')) : path;
                String[] seg = p2.split("/");
                if (seg.length < 4) {
                    writeHead(out, 404, "Not Found", 0, null, null, -1);
                    return;
                }
                long[] r = parseRange(hdrs.get("range"));
                handleSegment(out, seg[2], seg[3], r[0], r[1]);
                return;
            }
            if (!path.startsWith("/v/")) {
                writeHead(out, 404, "Not Found", 0, null, null, -1);
                return;
            }

            Entry e = entries.get(path.substring(3));
            if (e == null) {
                writeHead(out, 404, "Not Found", 0, null, null, -1);
                return;
            }

            long[] range = parseRange(hdrs.get("range"));
            long start = range[0];
            long end = range[1];

            boolean inside;
            long downloaded, windowStart;
            int readers;
            synchronized (e) {
                inside = start >= e.windowStart && start <= e.downloaded;
                downloaded = e.downloaded;
                windowStart = e.windowStart;
                readers = e.readers;
            }
            if (!inside) {
                // 窗口外（含读取器探到未下载位置）：能用直连就用直连，
                // 绝不能把正在发送的缓存窗口推倒，否则连接会被截断成坏数据。
                boolean nothingCached = downloaded <= windowStart;
                boolean bigForward = start > downloaded + PROBE_BYTES && readers == 0;
                if (nothingCached || bigForward) {
                    resetWindow(e, start);
                    if ("HEAD".equals(method)) {
                        headFromDisk(e, out);
                    } else {
                        fromDisk(e, out, start, end);
                    }
                } else {
                    passthrough(e, out, start, end, "HEAD".equals(method));
                }
            } else if ("HEAD".equals(method)) {
                headFromDisk(e, out);
            } else {
                fromDisk(e, out, start, end);
            }
        } catch (Throwable e) {
            // 播放器主动断开是常态，忽略
        } finally {
            try {
                if (out != null) out.flush();
            } catch (Throwable ignored) {
            }
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------ HLS

    /**
     * 把 m3u8 清单整体改写成走本机的版本：
     *  - 主清单里的子清单 → 继续走本机；
     *  - 媒体清单里的分片 → /seg/<key>/<n>，请求到就被预载并落盘。
     */
    private void handlePlaylist(OutputStream out, String origin) throws IOException {
        HttpURLConnection c = null;
        try {
            c = open(origin, headersOf(origin), -1, -1);
            int code = c.getResponseCode();
            if (code >= 400) {
                writeHead(out, 502, "Bad Gateway", 0, "application/vnd.apple.mpegurl", null, 0);
                return;
            }
            String base = c.getURL().toString();
            String text = readText(c.getInputStream());
            String key = keyOf(origin);
            boolean master = text.contains("#EXT-X-STREAM-INF");

            Playlist pl;
            synchronized (playlists) {
                pl = playlists.get(key);
                if (pl == null) {
                    pl = new Playlist(origin, null);
                    playlists.put(key, pl);
                }
            }
            current = pl;

            StringBuilder sb = new StringBuilder(text.length() + 1024);
            String[] lines = text.split("\n", -1);
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#")) {
                    if (line.startsWith("#EXT-X-MAP")) {
                        sb.append(rewriteUriAttr(line, base, key, pl));
                    } else if (line.startsWith("#EXT-X-KEY")) {
                        sb.append(absolutizeUriAttr(line, base));
                    } else {
                        sb.append(line);
                    }
                    sb.append('\n');
                    continue;
                }
                String abs = resolve(base, line);
                if (master) {
                    sb.append("http://127.0.0.1:").append(port).append("/hls/")
                            .append(keyOf(abs)).append(".m3u8?u=")
                            .append(java.net.URLEncoder.encode(abs, "UTF-8"));
                } else {
                    // 序号按地址固定分配：清单被重复拉取时序号不会漂移
                    sb.append("/seg/").append(key).append('/').append(segIndex(pl, abs));
                }
                sb.append('\n');
            }

            int segCount;
            synchronized (pl) {
                segCount = pl.segs.size();
            }

            // 起播缓冲：先把开头几片下完再回清单，播放器一启动就是本地速度
            if (!master) prime(pl);

            byte[] body = sb.toString().getBytes("UTF-8");
            writeHead(out, 200, "OK", body.length, "application/vnd.apple.mpegurl", null, body.length);
            out.write(body);
            out.flush();

            // 清单还没被播放器消费，先自己把开头几个分片预载下来
            if (!master) {
                for (int i = 0; i < Math.min(SEG_AHEAD, segCount); i++) {
                    String u;
                    synchronized (pl) {
                        u = i < pl.segs.size() ? pl.segs.get(i) : null;
                    }
                    if (u != null) entryFor(u, pl.headers);
                }
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 分片地址 → 稳定序号（只增不减，清单重拉也保持一致） */
    private static int segIndex(Playlist pl, String url) {
        synchronized (pl) {
            Integer i = pl.indexOf.get(url);
            if (i != null) return i;
            pl.segs.add(url);
            int idx = pl.segs.size() - 1;
            pl.indexOf.put(url, idx);
            return idx;
        }
    }

    /** 等前几片落盘，换取起播时不抖 */
    private void prime(Playlist pl) {
        boolean first;
        synchronized (pl) {
            first = !pl.primed;
            pl.primed = true;
        }
        if (!first) return;
        int n;
        synchronized (pl) {
            n = Math.min(PRIME_SEGMENTS, pl.segs.size());
        }
        long deadline = System.currentTimeMillis() + 9000;
        for (int i = 0; i < n && System.currentTimeMillis() < deadline; i++) {
            String url;
            synchronized (pl) {
                url = i < pl.segs.size() ? pl.segs.get(i) : null;
            }
            if (url == null) continue;
            Entry e = entryFor(url, pl.headers);
            waitFinished(e, Math.max(500, deadline - System.currentTimeMillis()));
        }
    }

    /**
     * 分片预取：一条独立的顺序管道，始终保证「当前片之后 SEG_AHEAD 片」有货。
     * 串行而不是并发，是为了让下一片优先到齐，慢网下不容易被自己挤兑。
     */
    private void ensureSegPrefetch(Playlist p, int nextIdx) {
        synchronized (p) {
            p.target = fullPrefetch ? p.segs.size() : Math.max(p.target, nextIdx + SEG_AHEAD);
            if (p.cursor < nextIdx || p.cursor > nextIdx + SEG_AHEAD * 2) p.cursor = nextIdx;
            if (p.prefetching) return;
            p.prefetching = true;
        }
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    int cur;
                    synchronized (p) {
                        if (p.dead || !active || p.cursor >= p.target
                                || p.cursor >= p.segs.size()) return;
                        cur = p.cursor++;
                    }
                    String url;
                    synchronized (p) {
                        url = cur >= 0 && cur < p.segs.size() ? p.segs.get(cur) : null;
                    }
                    if (url == null) continue;
                    Entry e = entryFor(url, p.headers);
                    waitFinished(e, 30000);
                }
            } finally {
                synchronized (p) {
                    p.prefetching = false;
                }
            }
        }, "seg-prefetch");
        t.setDaemon(true);
        t.start();
    }

    private static void waitFinished(Entry e, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (e) {
            while (System.currentTimeMillis() < deadline) {
                if (e.finished || e.failed || e.dead) return;
                waitOn(e, 200);
            }
        }
    }

    private void handleSegment(OutputStream out, String key, String idxText,
                               long start, long end) throws IOException {
        Playlist p;
        synchronized (playlists) {
            p = playlists.get(key);
        }
        if (p == null) {
            writeHead(out, 404, "Not Found", 0, null, null, -1);
            return;
        }
        int idx;
        try {
            idx = Integer.parseInt(idxText.trim());
        } catch (NumberFormatException e) {
            writeHead(out, 404, "Not Found", 0, null, null, -1);
            return;
        }
        List<String> segs;
        synchronized (p) {
            segs = new ArrayList<>(p.segs);
        }
        if (idx < 0 || idx >= segs.size()) {
            Log.w(TAG, "404 分片越界 idx=" + idx + " size=" + segs.size());
            writeHead(out, 404, "Not Found", 0, null, null, -1);
            return;
        }
        Entry e = entryFor(segs.get(idx), p.headers);
        ensureSegPrefetch(p, idx + 1);
        long from = Math.max(0, start);
        // 分片绝不重置缓存窗口：窗口一翻，正在发送的连接就会截断成坏数据
        boolean inside;
        synchronized (e) {
            inside = from >= e.windowStart && from <= e.downloaded;
        }
        if (!inside) {
            passthrough(e, out, from, end, false);
            return;
        }
        long total = waitTotal(e, 1200);
        boolean inflight;
        synchronized (e) {
            inflight = e.prefetching && !e.failed;
        }
        if (total <= 0 && !inflight) {
            // 预载还没起来，这一片先直连，别让播放器等
            passthrough(e, out, from, end, false);
            return;
        }
        fromDisk(e, out, from, end);
    }

    private Map<String, String> headersOf(String origin) {
        Playlist p;
        synchronized (playlists) {
            p = playlists.get(keyOf(origin));
        }
        return p == null ? null : p.headers;
    }

    /** #EXT-X-MAP:URI="init.mp4" → 走本机分片通道（也会被预载） */
    private String rewriteUriAttr(String line, String base, String key, Playlist pl) {
        int i = line.indexOf("URI=\"");
        if (i < 0) return line;
        int j = line.indexOf('"', i + 5);
        if (j < 0) return line;
        String abs = resolve(base, line.substring(i + 5, j));
        return line.substring(0, i + 5) + "/seg/" + key + "/" + segIndex(pl, abs)
                + line.substring(j);
    }

    /** #EXT-X-KEY:URI="key.bin" → 补成绝对地址，播放器自己取 */
    private String absolutizeUriAttr(String line, String base) {
        int i = line.indexOf("URI=\"");
        if (i < 0) return line;
        int j = line.indexOf('"', i + 5);
        if (j < 0) return line;
        String abs = resolve(base, line.substring(i + 5, j));
        return line.substring(0, i + 5) + abs + line.substring(j);
    }

    // ------------------------------------------------------------ 从磁盘供给

    private void headFromDisk(Entry e, OutputStream out) throws IOException {
        long total = waitTotal(e, 8000);
        if (total <= 0) {
            writeHead(out, 404, "Not Found", 0, null, null, -1);
            return;
        }
        writeHead(out, 200, "OK", 0, e.contentType, null, total);
    }

    private void fromDisk(Entry e, OutputStream out, long start, long end) throws IOException {
        long total = waitTotal(e, 15000);
        if (total <= 0) {
            // 拿不到长度（源站不给 Content-Length）就退回直连
            passthrough(e, out, start, end, false);
            return;
        }
        long realEnd = (end < 0 || end >= total) ? total - 1 : end;
        if (start > realEnd) {
            writeHead(out, 416, "Range Not Satisfiable", 0, null,
                    "bytes */" + total, 0);
            return;
        }
        long len = realEnd - start + 1;
        boolean partial = start > 0 || realEnd < total - 1;
        writeHead(out, partial ? 206 : 200, partial ? "Partial Content" : "OK",
                len, e.contentType,
                partial ? ("bytes " + start + "-" + realEnd + "/" + total) : null, len);

        RandomAccessFile r = null;
        long sent = 0;
        boolean broken = false;
        synchronized (e) {
            e.readers++;
            e.playhead = start;
        }
        try {
            r = new RandomAccessFile(e.file, "r");
            long pos = start;
            byte[] buf = new byte[BLOCK];
            while (pos <= realEnd) {
                long avail;
                long ws;
                synchronized (e) {
                    int gen = e.gen;
                    int guard = 0;
                    while (true) {
                        if (e.gen != gen) return;          // 窗口被跳转，放弃本连接
                        avail = Math.min(e.downloaded, realEnd + 1) - pos;
                        if (avail > 0 || e.finished || e.failed || e.dead) break;
                        if (guard++ > 60) break;           // 最多等 ~30s
                        waitOn(e, 500);
                    }
                    ws = e.windowStart;
                    // 记住播放点，预载据此决定还要往前跑多远
                    e.playhead = pos;
                }
                if (avail <= 0) {
                    if (e.finished) {
                        Log.w(TAG, "缓存长度不足，截断结束 pos=" + pos + " end=" + realEnd);
                        return;
                    }
                    // 预载卡住了：剩下的部分直连源站补上，别让画面停下来
                    Log.w(TAG, "预载跟不上，改直连补数据 pos=" + pos);
                    sent += passthroughInto(e, out, pos, realEnd);
                    return;
                }
                int want = (int) Math.min(avail, buf.length);
                long off = pos - ws;
                if (off < 0) return;
                r.seek(off);
                int n = r.read(buf, 0, want);
                if (n <= 0) {
                    sent += passthroughInto(e, out, pos, realEnd);
                    return;
                }
                out.write(buf, 0, n);
                out.flush();
                pos += n;
                sent += n;
            }
        } catch (IOException ex) {
            broken = true;   // 客户端自己断开是常态，不算我们的问题
            throw ex;
        } finally {
            synchronized (e) {
                e.readers--;
                e.notifyAll();
            }
            closeQuietly(r);
            if (sent < len && !broken) {
                Log.w(TAG, "响应被截断 sent=" + sent + " len=" + len + " start=" + start
                        + " total=" + total);
            }
        }
    }

    // ------------------------------------------------------------ 直连透传

    private void passthrough(Entry e, OutputStream out, long start, long end, boolean headOnly)
            throws IOException {
        HttpURLConnection c = null;
        try {
            c = open(e.origin, e.headers, start, end);
            int code = c.getResponseCode();
            if (code >= 400) {
                Log.w(TAG, "直连失败 code=" + code + " " + hostOf(e.origin));
                writeHead(out, 502, "Bad Gateway", 0, null, null, -1);
                return;
            }
            long total = parseTotal(c.getHeaderField("Content-Range"), c.getContentLength());
            String type = c.getContentType();
            if (total < 0) total = c.getContentLength();
            String rangeHdr = null;
            if (code == 206) {
                rangeHdr = c.getHeaderField("Content-Range");
                if (rangeHdr == null && total > 0) {
                    rangeHdr = "bytes " + start + "-" + (total - 1) + "/" + total;
                }
            }
            writeHead(out, code, code == 206 ? "Partial Content" : "OK",
                    c.getContentLength(), type, rangeHdr, c.getContentLength());
            if (headOnly) return;
            InputStream in = c.getInputStream();
            // 源站无视 Range 直接给了整份：先把不要的前缀丢掉，否则数据会错位
            if (code == 200 && start > 0) skipFully(in, start);
            byte[] buf = new byte[BLOCK];
            int n;
            long sent = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
                sent += n;
            }
            long want = c.getContentLength() > 0 ? c.getContentLength() : -1;
            if (code == 200 && start > 0) want = want > 0 ? want - start : -1;
            if (want > 0 && sent != want) {
                Log.w(TAG, "直连响应长度不符 sent=" + sent + " want=" + want + " code=" + code);
            }
            closeQuietly(in);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 只把 [start, end] 这段补发到已经写过头部的连接里，返回补发的字节数 */
    private long passthroughInto(Entry e, OutputStream out, long start, long end) {
        HttpURLConnection c = null;
        long sent = 0;
        try {
            c = open(e.origin, e.headers, start, end);
            int code = c.getResponseCode();
            if (code >= 400) return 0;
            InputStream in = c.getInputStream();
            if (code == 200 && start > 0) skipFully(in, start);
            byte[] buf = new byte[BLOCK];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
                sent += n;
            }
            closeQuietly(in);
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
        return sent;
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Throwable e) {
            return "?";
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        byte[] tmp = new byte[8192];
        long left = n;
        while (left > 0) {
            int r = in.read(tmp, 0, (int) Math.min(tmp.length, left));
            if (r < 0) return;
            left -= r;
        }
    }

    // ------------------------------------------------------------ 预载线程

    private void ensurePrefetch(final Entry e) {
        synchronized (e) {
            if (e.prefetching || e.dead) return;
            e.prefetching = true;
        }
        Thread t = new Thread(() -> {
            try {
                prefetchLoop(e);
            } finally {
                synchronized (e) {
                    e.prefetching = false;
                    e.notifyAll();
                }
            }
        }, "prefetch");
        t.setDaemon(true);
        t.start();
    }

    private void prefetchLoop(Entry e) {
        int errors = 0;
        while (true) {
            long from;
            int gen;
            synchronized (e) {
                if (e.dead) return;
                // 预读够了 / 退到后台 → 先歇着
                while (!e.dead && !e.finished
                        && (!active || (!fullPrefetch && e.downloaded - e.playhead > readAhead))) {
                    waitOn(e, 600);
                }
                if (e.dead || e.finished) return;
                from = e.downloaded;
                gen = e.gen;
            }

            HttpURLConnection c = null;
            RandomAccessFile w = null;
            try {
                c = open(e.origin, e.headers, from, -1);
                int code = c.getResponseCode();
                if (code == 416) {
                    synchronized (e) {
                        e.finished = true;
                        if (e.total < 0) e.total = from;
                        e.notifyAll();
                    }
                    return;
                }
                if (code != 206 && code != 200) throw new IOException("HTTP " + code);
                if (code == 200 && from > 0) {
                    // 源站不支持 Range，只能从 0 顺序读；这条线路放弃预载
                    Log.w(TAG, "源站不支持 Range，放弃预载");
                    synchronized (e) {
                        e.finished = true;
                        e.failed = true;
                        e.notifyAll();
                    }
                    return;
                }
                long total = parseTotal(c.getHeaderField("Content-Range"), c.getContentLength());
                synchronized (e) {
                    if (gen != e.gen) continue;              // 窗口刚被跳转过
                    if (total > 0) e.total = total;
                    if (e.contentType == null) e.contentType = c.getContentType();
                    e.notifyAll();
                }
                w = new RandomAccessFile(e.file, "rw");
                InputStream in = c.getInputStream();
                byte[] buf = new byte[BLOCK];
                while (true) {
                    synchronized (e) {
                        if (e.dead || e.gen != gen || !active) break;
                        if (!fullPrefetch && e.downloaded - e.playhead > readAhead) break;
                    }
                    int n = in.read(buf);
                    if (n < 0) {
                        synchronized (e) {
                            if (gen == e.gen) {
                                e.finished = true;
                                if (e.total < 0) e.total = e.downloaded;
                                e.notifyAll();
                            }
                        }
                        return;
                    }
                    synchronized (e) {
                        if (e.dead || e.gen != gen) break;
                        w.seek(e.downloaded - e.windowStart);
                        w.write(buf, 0, n);
                        e.downloaded += n;
                        e.notifyAll();
                    }
                }
                errors = 0;
            } catch (Throwable ex) {
                errors++;
                Log.w(TAG, "预载失败(" + errors + ")：" + ex);
                if (errors >= 4) {
                    synchronized (e) {
                        e.failed = true;
                        e.notifyAll();
                    }
                    Log.w(TAG, "预载放弃（重试 4 次仍失败）");
                    return;
                }
                sleepQuietly(400L * errors);
            } finally {
                closeQuietly(w);
                if (c != null) c.disconnect();
            }
        }
    }

    private void resetWindow(Entry e, long start) {
        synchronized (e) {
            e.gen++;
            e.windowStart = start;
            e.downloaded = start;
            e.finished = false;
            e.failed = false;
            e.playhead = start;
            e.notifyAll();
        }
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile(e.file, "rw");
            f.setLength(0);
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(f);
        }
        ensurePrefetch(e);
    }

    // ------------------------------------------------------------ 工具

    private long waitTotal(Entry e, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (e) {
            while (e.total < 0 && !e.failed && !e.dead
                    && System.currentTimeMillis() < deadline) {
                waitOn(e, 200);
            }
            return e.total;
        }
    }

    private HttpURLConnection open(String origin, Map<String, String> headers,
                                   long start, long end) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(origin).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", Api.userAgent());
        c.setRequestProperty("Accept", "*/*");
        String referer = headers != null ? headers.get("Referer") : null;
        c.setRequestProperty("Referer", referer != null ? referer : Api.baseUrl() + "/");
        if (headers != null) {
            for (Map.Entry<String, String> kv : headers.entrySet()) {
                if (kv.getKey() != null && kv.getValue() != null) {
                    c.setRequestProperty(kv.getKey(), kv.getValue());
                }
            }
        }
        if (start >= 0) {
            c.setRequestProperty("Range", "bytes=" + start + "-"
                    + (end >= 0 ? String.valueOf(end) : ""));
        }
        return c;
    }

    private static long[] parseRange(String h) {
        long start = 0, end = -1;
        if (h == null) return new long[]{start, end};
        h = h.trim();
        if (!h.startsWith("bytes=")) return new long[]{start, end};
        h = h.substring(6);
        int dash = h.indexOf('-');
        if (dash < 0) return new long[]{start, end};
        try {
            String a = h.substring(0, dash).trim();
            String b = h.substring(dash + 1).trim();
            if (!a.isEmpty()) start = Long.parseLong(a);
            if (!b.isEmpty()) end = Long.parseLong(b);
        } catch (NumberFormatException ignored) {
        }
        if (start < 0) start = 0;
        return new long[]{start, end};
    }

    private static long parseTotal(String contentRange, long contentLength) {
        if (contentRange != null) {
            int slash = contentRange.lastIndexOf('/');
            if (slash > 0) {
                try {
                    return Long.parseLong(contentRange.substring(slash + 1).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return contentLength > 0 ? contentLength : -1;
    }

    private static void writeHead(OutputStream out, int code, String msg, long len,
                                  String type, String contentRange, long total)
            throws IOException {
        StringBuilder sb = new StringBuilder(160);
        sb.append("HTTP/1.1 ").append(code).append(' ').append(msg).append("\r\n");
        sb.append("Content-Type: ").append(type == null ? "video/mp4" : type).append("\r\n");
        sb.append("Accept-Ranges: bytes\r\n");
        if (contentRange != null) sb.append("Content-Range: ").append(contentRange).append("\r\n");
        // len < 0 表示长度未知：不带 Content-Length，靠 Connection: close 界定结尾
        if (len >= 0) sb.append("Content-Length: ").append(len).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        out.flush();
    }

    private static String readText(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            total += n;
            if (total > 8 * 1024 * 1024) break;
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static String resolve(String base, String rel) {
        try {
            return new URL(new URL(base), rel).toString();
        } catch (Throwable e) {
            return rel;
        }
    }

    private static String queryParam(String query, String name) {
        if (query == null || query.isEmpty()) return null;
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            if (!kv.substring(0, i).equals(name)) continue;
            try {
                return java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8");
            } catch (Throwable e) {
                return kv.substring(i + 1);
            }
        }
        return null;
    }

    private static String readLine(InputStream in) throws IOException {        StringBuilder sb = new StringBuilder(64);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
            if (sb.length() > 4096) break;
        }
        if (b == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static void waitOn(Object o, long ms) {
        try {
            o.wait(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    private static String keyOf(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format(Locale.ROOT, "%02x", d[i]));
            return sb.toString();
        } catch (Throwable e) {
            return Integer.toHexString(url.hashCode());
        }
    }

    /** 缓存目录超限时，删掉最久没动过的文件 */
    private void trim(File keep) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        long total = 0;
        List<File> list = new ArrayList<>();
        for (File f : fs) {
            total += f.length();
            list.add(f);
        }
        if (total <= MAX_CACHE) return;
        // 正在播放的条目不能删
        List<String> live = new ArrayList<>();
        for (Entry e : entries.values()) live.add(e.file.getName());
        Collections.sort(list, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File f : list) {
            if (total <= MAX_CACHE * 3 / 4) break;
            if (keep != null && keep.equals(f)) continue;
            if (live.contains(f.getName())) continue;
            long n = f.length();
            if (f.delete()) total -= n;
        }
    }

    // ------------------------------------------------------------ 条目

    /** 一份 m3u8 的解析结果：分片地址表 */
    static final class Playlist {
        final String origin;
        final Map<String, String> headers;
        final List<String> segs = new ArrayList<>();
        final Map<String, Integer> indexOf = new java.util.HashMap<>();
        boolean primed;
        volatile boolean dead;
        boolean prefetching;
        int cursor;
        int target;

        Playlist(String origin, Map<String, String> headers) {
            this.origin = origin;
            this.headers = headers;
        }
    }

    static final class Entry {
        final String key;
        final String origin;
        final Map<String, String> headers;
        final File file;

        long total = -1;          // 源站文件总长
        long windowStart;         // 缓存窗口起点（文件偏移 0 对应这里）
        long downloaded;          // 已连续落盘的末尾（绝对位置）
        long playhead;            // 播放器最近读到的位置
        int gen;                  // 窗口代次，用于打断旧的连接/预载
        int readers;              // 正在从磁盘读这条缓存的连接数
        String contentType;
        boolean prefetching;
        boolean finished;
        boolean failed;
        boolean dead;

        Entry(String key, String origin, Map<String, String> headers, File file) {
            this.key = key;
            this.origin = origin;
            this.headers = headers;
            this.file = file;
        }
    }
}
