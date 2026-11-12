package com.kanju.player;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 站点 /v1 接口客户端。
 *
 * 站点要求每个请求带 HMAC-SHA256 签名（Web 端由前端 JS 计算）：
 *   msg = METHOD \n path+query \n timestamp \n nonce
 *   signature = hex(HMAC_SHA256(key, msg))
 * 这里在原生端复刻同一套算法，因此不再需要 WebView。
 */
public final class Api {

    private static final String SIGN_KEY =
            "557d0e4ae929f438da6bd84412374e6086b8af09b3fed54bf22601d5bf8c54a0";

    private static final String CLIENT_NAME = "dianyingtiantang-frontend";
    private static final String CLIENT_VERSION = "1.0.0";
    private static final String BUILD_VERSION =
            "dianyingtiantang-v2026.11.06.2-search-header-f6f73501a448-web";
    private static final String PROTOCOL_VERSION = "2026-07-05.library-v2.playback-v1";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final String[] BASES = {
            "https://kanju.ai",
            "https://www.kanju.ai"
    };

    private static String activeBase = BASES[0];
    private static String threadId;

    private Api() {}

    // ------------------------------------------------------------ 异常

    public static final class ApiException extends Exception {
        public final String code;

        public ApiException(String code, String message) {
            super(message);
            this.code = code == null ? "" : code;
        }
    }

    // ------------------------------------------------------------ 基础请求

    private static String sign(String method, String pathQuery, String ts, String nonce) throws Exception {
        String msg = method + "\n" + pathQuery + "\n" + ts + "\n" + nonce;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SIGN_KEY.getBytes("UTF-8"), "HmacSHA256"));
        byte[] out = mac.doFinal(msg.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder(out.length * 2);
        for (byte b : out) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    private static String nonce() {
        byte[] n = new byte[16];
        new SecureRandom().nextBytes(n);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : n) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }

    private static String once(String method, String pathQuery, String body) throws ApiException {
        IOException last = null;
        for (int attempt = 0; attempt < BASES.length; attempt++) {
            String base = attempt == 0 ? activeBase : BASES[attempt % BASES.length];
            try {
                String r = execute(base, method, pathQuery, body);
                activeBase = base;
                return r;
            } catch (IOException e) {
                last = e;
            }
        }
        throw new ApiException("network", last == null ? "网络不可用" : "网络异常：" + last.getMessage());
    }

    private static String execute(String base, String method, String pathQuery, String body)
            throws IOException, ApiException {
        HttpURLConnection c = null;
        try {
            String ts = String.valueOf(System.currentTimeMillis());
            String nonce = nonce();
            String sig;
            try {
                sig = sign(method, pathQuery, ts, nonce);
            } catch (Exception e) {
                throw new ApiException("sign", "签名失败");
            }

            URL url = new URL(base + pathQuery);
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Origin", base);
            c.setRequestProperty("Referer", base + "/");
            c.setRequestProperty("x-ai-movie-timestamp", ts);
            c.setRequestProperty("x-ai-movie-nonce", nonce);
            c.setRequestProperty("x-ai-movie-signature", sig);
            c.setRequestProperty("x-ai-movie-client-name", CLIENT_NAME);
            c.setRequestProperty("x-ai-movie-client-version", CLIENT_VERSION);
            c.setRequestProperty("x-ai-movie-build-version", BUILD_VERSION);
            c.setRequestProperty("x-ai-movie-protocol-version", PROTOCOL_VERSION);

            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] payload = body.getBytes("UTF-8");
                c.setFixedLengthStreamingMode(payload.length);
                OutputStream os = c.getOutputStream();
                os.write(payload);
                os.flush();
                os.close();
            }

            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) {
                String errCode = "";
                String msg = "请求失败（" + code + "）";
                try {
                    JSONObject o = new JSONObject(text);
                    JSONObject e = o.optJSONObject("error");
                    if (e != null) {
                        errCode = e.optString("code");
                        msg = e.optString("message", msg);
                    } else {
                        errCode = o.optString("code");
                        String m = o.optString("message");
                        if (!TextUtils.isEmpty(m)) msg = m;
                    }
                } catch (JSONException ignored) {
                }
                throw new ApiException(errCode, msg);
            }
            return text;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static JSONObject json(String method, String pathQuery, String body) throws ApiException {
        String text = once(method, pathQuery, body);
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            throw new ApiException("parse", "响应解析失败");
        }
    }

    public static String base() {
        return activeBase;
    }

    // ------------------------------------------------------------ 具体接口

    /** 搜索必须先有一个会话 id，否则 catalog 接口拿不到结果 */
    private static synchronized void ensureThread(String query) throws ApiException {
        if (!TextUtils.isEmpty(threadId)) return;
        try {
            JSONObject body = new JSONObject();
            body.put("title", TextUtils.isEmpty(query) ? "搜索" : query);
            JSONObject meta = new JSONObject();
            meta.put("source", "session_search");
            body.put("metadata", meta);
            JSONObject r = json("POST", "/v1/threads", body.toString());
            threadId = r.optString("id", "");
        } catch (JSONException e) {
            throw new ApiException("parse", "创建会话失败");
        }
    }

    public static Models.Page search(String query, int page) throws ApiException {
        ensureThread(query);
        String q;
        try {
            q = URLEncoder.encode(query, "UTF-8");
        } catch (Exception e) {
            q = query;
        }
        String path = "/v1/browse/catalog?page=" + Math.max(1, page)
                + "&limit=20&query_mode=fast_v3&search_fields=all"
                + "&thread_id=" + enc(threadId)
                + "&q=" + q;
        JSONObject o = json("GET", path, null);

        Models.Page p = new Models.Page();
        JSONArray cards = o.optJSONArray("cards");
        if (cards != null) {
            for (int i = 0; i < cards.length(); i++) {
                JSONObject c = cards.optJSONObject(i);
                if (c == null) continue;
                Models.Movie m = new Models.Movie();
                m.variantId = c.optString("selected_variant_id", c.optString("variant_id", c.optString("id")));
                m.title = unescape(c.optString("title"));
                m.year = c.optInt("year");
                m.kind = c.optString("content_kind");
                m.area = unescape(c.optString("area"));
                m.poster = c.optString("poster_url");
                m.remarks = unescape(c.optString("remarks"));
                m.episodeCount = c.optInt("current_episode_count", c.optInt("episode_count"));
                fillStrings(c.optJSONArray("genres"), m.genres);
                fillStrings(c.optJSONArray("actors"), m.actors);
                if (!TextUtils.isEmpty(m.title) && !TextUtils.isEmpty(m.variantId)) p.items.add(m);
            }
        }
        JSONObject pg = o.optJSONObject("pagination");
        if (pg != null) {
            p.hasMore = pg.optBoolean("has_more", false);
            p.nextPage = pg.optInt("next_page", page + 1);
            if (p.nextPage <= page) p.nextPage = page + 1;
        }
        return p;
    }

    public static Models.Detail detail(String variantId) throws ApiException {
        JSONObject o = json("GET", "/v1/catalog/" + enc(variantId) + "/detail", null);
        Models.Detail d = new Models.Detail();
        d.variantId = o.optString("variant_id", variantId);
        d.title = unescape(o.optString("title"));
        d.description = unescape(o.optString("description"));
        d.remarks = unescape(o.optString("remarks"));
        d.poster = o.optString("poster_url");
        d.year = o.optInt("year");
        d.area = unescape(o.optString("area"));
        d.statusText = unescape(o.optString("episode_progress_text", o.optString("remarks")));
        d.totalEpisodes = o.optInt("total_episode_count", o.optInt("episode_count"));
        d.availableEpisodes = o.optInt("available_episode_count", o.optInt("current_episode_count"));
        fillStrings(o.optJSONArray("genres"), d.genres);
        fillStrings(o.optJSONArray("actors"), d.actors);
        fillStrings(o.optJSONArray("directors"), d.directors);

        JSONObject preset = o.optJSONObject("playback_skip_settings_preset");
        if (preset != null && "community_median".equals(preset.optString("source"))) {
            int intro = preset.optInt("intro_end_seconds", -1);
            int outro = (int) Math.round(preset.optDouble("outro_from_end_seconds", -1));
            int samples = preset.optInt("sample_count", 0);
            if (samples >= 3 && intro >= 0 && intro <= 300 && outro >= 0) {
                d.introEndSeconds = intro;
                d.outroFromEndSeconds = outro;
                d.skipSampleCount = samples;
            }
        }
        return d;
    }

    public static List<Models.Episode> episodes(String variantId) throws ApiException {
        List<Models.Episode> list = new ArrayList<>();
        int offset = 0;
        for (int guard = 0; guard < 6; guard++) {
            JSONObject o = json("GET", "/v1/catalog/" + enc(variantId)
                    + "/episodes?limit=48&offset=" + offset, null);
            JSONArray arr = o.optJSONArray("episodes");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject e = arr.optJSONObject(i);
                    if (e == null) continue;
                    String track = e.optString("episode_track", "main");
                    if (!"main".equals(track)) continue;
                    Models.Episode ep = new Models.Episode();
                    ep.token = e.optString("token");
                    ep.number = e.optInt("number", list.size() + 1);
                    ep.title = e.optString("title", "第" + ep.number + "集");
                    if (!TextUtils.isEmpty(ep.token)) list.add(ep);
                }
            }
            JSONObject pg = o.optJSONObject("episode_pagination");
            boolean more = pg != null && pg.optBoolean("has_more", false);
            if (!more) break;
            int returned = pg.optInt("returned_count", 0);
            if (returned <= 0) break;
            offset += returned;
        }
        Collections.sort(list, new Comparator<Models.Episode>() {
            @Override
            public int compare(Models.Episode a, Models.Episode b) {
                return Integer.compare(a.number, b.number);
            }
        });
        return list;
    }

    public static Models.Playback playback(String token) throws ApiException {
        JSONObject o = json("GET", "/v1/playback/resolve/" + enc(token) + "?view=compact", null);
        Models.Playback p = new Models.Playback();
        JSONObject cur = o.optJSONObject("current_episode");
        if (cur != null) {
            p.episodeTitle = cur.optString("display_name");
            p.episodeNumber = cur.optInt("episode_number");
            p.nextToken = cur.optString("next_episode_token");
            p.prevToken = cur.optString("previous_episode_token");
        }
        JSONArray lines = o.optJSONArray("line_options");
        if (lines != null) {
            for (int i = 0; i < lines.length(); i++) {
                JSONObject l = lines.optJSONObject(i);
                if (l == null) continue;
                Models.Line line = new Models.Line();
                line.label = l.optString("label", "线路 " + (i + 1));
                line.provider = l.optString("provider_id");
                line.urlKind = l.optString("url_kind");
                line.weight = l.optInt("preference_weight", 0);
                line.resolveRequired = l.optBoolean("resolve_required", true);
                String u = l.optString("url");
                if (u.startsWith("resolve://")) line.ticket = u.substring("resolve://".length());
                else if (!line.resolveRequired && u.startsWith("http")) {
                    line.url = u;
                    line.finalKind = line.urlKind;
                }
                if (!TextUtils.isEmpty(line.ticket) || !TextUtils.isEmpty(line.url)) p.lines.add(line);
            }
        }
        return p;
    }

    /** 把 resolve 票根换成真实播放地址 */
    public static Models.Line resolveLine(Models.Line line) throws ApiException {
        if (!TextUtils.isEmpty(line.url)) return line;
        try {
            JSONObject body = new JSONObject();
            body.put("ticket", line.ticket);
            JSONObject o = json("POST", "/v1/playback/resolve-line?view=compact", body.toString());
            JSONObject l = o.optJSONObject("line");
            if (l != null) {
                line.url = l.optString("url");
                line.finalKind = l.optString("url_kind");
                String label = l.optString("label");
                if (!TextUtils.isEmpty(label)) line.label = label;
            }
        } catch (JSONException e) {
            throw new ApiException("parse", "线路解析失败");
        }
        if (TextUtils.isEmpty(line.url)) throw new ApiException("empty_line", "线路暂无地址");
        return line;
    }

    // ------------------------------------------------------------ 线路排序

    public static final int PREF_AUTO = 0;
    public static final int PREF_OFFICIAL = 1;
    public static final int PREF_UHD = 2;
    public static final int PREF_OVERSEAS = 3;
    public static final int PREF_RESOURCE = 4;

    public static String prefName(int pref) {
        switch (pref) {
            case PREF_OFFICIAL: return "官方线路优先";
            case PREF_UHD: return "4K / 高清优先";
            case PREF_OVERSEAS: return "海外线路优先";
            case PREF_RESOURCE: return "资源站优先";
            default: return "自动优选";
        }
    }

    /**
     * 按用户偏好把线路排成"尝试顺序"：
     * mp4 直链最稳，排在同类前面；不可播的图片位直接排除。
     */
    public static List<Models.Line> order(Models.Playback pb, final int pref) {
        List<Models.Line> list = new ArrayList<>();
        for (Models.Line l : pb.lines) {
            if (isImageLine(l)) continue;
            list.add(l);
        }
        Collections.sort(list, new Comparator<Models.Line>() {
            @Override
            public int compare(Models.Line a, Models.Line b) {
                int sa = score(a, pref), sb = score(b, pref);
                if (sa != sb) return sb - sa;
                int ka = kindRank(a), kb = kindRank(b);
                if (ka != kb) return ka - kb;
                return b.weight - a.weight;
            }
        });
        return list;
    }

    private static boolean isImageLine(Models.Line l) {
        String u = l.url.toLowerCase(Locale.ROOT);
        if (u.isEmpty()) return false;
        return u.endsWith(".jpg") || u.endsWith(".png") || u.endsWith(".webp")
                || u.endsWith(".gif") || u.contains("image.image");
    }

    private static int kindRank(Models.Line l) {
        String k = l.finalKind.isEmpty() ? l.urlKind : l.finalKind;
        if ("mp4".equals(k)) return 0;
        if ("m3u8".equals(k)) return 1;
        return 2;
    }

    private static int score(Models.Line l, int pref) {
        String label = l.label == null ? "" : l.label;
        boolean official = label.contains("官方") || l.provider.contains("official");
        boolean uhd = label.contains("4K") || label.contains("1080");
        boolean overseas = label.contains("海外");
        switch (pref) {
            case PREF_OFFICIAL: return official ? 100 : 0;
            case PREF_UHD: return uhd ? 100 : (official ? 10 : 0);
            case PREF_OVERSEAS: return overseas ? 100 : (official ? 10 : 0);
            case PREF_RESOURCE: return official ? 0 : 100;
            default: return 0;
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static void fillStrings(JSONArray arr, List<String> out) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String v = unescape(arr.optString(i, ""));
            if (!TextUtils.isEmpty(v)) out.add(v);
        }
    }

    /**
     * 站点把标题 / 演员里的撇号转成了 HTML 实体，而且偶尔会双重转义
     * （「O&amp;#039;039」），所以这里循环还原到稳定为止。
     */
    static String unescape(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        String out = s;
        for (int pass = 0; pass < 3; pass++) {
            String next = out
                    .replace("&amp;", "&")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                    .replace("&nbsp;", " ")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&#039;", "'")
                    .replace("&#039", "'")
                    .replace("&#39;", "'")
                    .replace("&#39", "'");
            if (next.equals(out)) break;
            out = next;
        }
        return out;
    }
}
