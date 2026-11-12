package com.kanju.player;

import android.content.Context;
import android.content.SharedPreferences;

/** 轻量偏好存储：换源偏好、播放倍速、按剧记忆的片头片尾、续播进度。 */
public final class Store {

    private static final String NAME = "kanju_native";
    private static Store instance;

    private final SharedPreferences sp;

    private Store(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static synchronized Store get(Context c) {
        if (instance == null) instance = new Store(c);
        return instance;
    }

    // ------------------------------------------------------------ 全局

    public int sourcePref() {
        return sp.getInt("source_pref", Api.PREF_AUTO);
    }

    public void setSourcePref(int v) {
        sp.edit().putInt("source_pref", v).apply();
    }

    public float speed() {
        return sp.getFloat("speed", 1f);
    }

    public void setSpeed(float v) {
        sp.edit().putFloat("speed", v).apply();
    }

    public boolean autoNext() {
        return sp.getBoolean("auto_next", true);
    }

    public void setAutoNext(boolean v) {
        sp.edit().putBoolean("auto_next", v).apply();
    }

    public boolean autoSkipIntro() {
        return sp.getBoolean("auto_skip_intro", false);
    }

    public void setAutoSkipIntro(boolean v) {
        sp.edit().putBoolean("auto_skip_intro", v).apply();
    }

    public String lastQuery() {
        return sp.getString("last_query", "");
    }

    public void setLastQuery(String q) {
        sp.edit().putString("last_query", q).apply();
    }

    /** 最近搜索（最多 8 条，最近的在前） */
    public java.util.List<String> history() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        String raw = sp.getString("history", "");
        if (raw.isEmpty()) return out;
        for (String s : raw.split("\u0001")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public void addHistory(String q) {
        if (q == null) return;
        String v = q.trim();
        if (v.isEmpty()) return;
        java.util.List<String> list = history();
        list.remove(v);
        list.add(0, v);
        while (list.size() > 8) list.remove(list.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append('\u0001');
            sb.append(s);
        }
        sp.edit().putString("history", sb.toString()).apply();
    }

    public void clearHistory() {
        sp.edit().remove("history").apply();
    }

    // ------------------------------------------------------------ 按剧记忆

    /** 用户手工校正后的片头结束点（秒）；-1 表示沿用站点预设 */
    public int introFor(String variantId) {
        return sp.getInt("intro_" + key(variantId), -1);
    }

    public void setIntroFor(String variantId, int seconds) {
        sp.edit().putInt("intro_" + key(variantId), seconds).apply();
    }

    /** 用户手工校正后的片尾起点距结尾秒数；-1 表示沿用站点预设 */
    public int outroFor(String variantId) {
        return sp.getInt("outro_" + key(variantId), -1);
    }

    public void setOutroFor(String variantId, int seconds) {
        sp.edit().putInt("outro_" + key(variantId), seconds).apply();
    }

    // ------------------------------------------------------------ 续播

    public long positionOf(String episodeToken) {
        return sp.getLong("pos_" + key(episodeToken), 0L);
    }

    public void setPosition(String episodeToken, long ms) {
        sp.edit().putLong("pos_" + key(episodeToken), ms).apply();
    }

    /**
     * 稳定 key：token 往往前 40 位相同（同一部剧共用前缀），
     * 直接截断会让不同集共用一条续播记录，这里改用哈希。
     */
    private static String key(String s) {
        if (s == null || s.isEmpty()) return "x";
        if (s.length() <= 24) return s;
        return Integer.toHexString(s.hashCode()) + "_" + s.length();
    }
}
