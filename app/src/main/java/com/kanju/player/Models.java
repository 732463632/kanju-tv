package com.kanju.player;

import java.util.ArrayList;
import java.util.List;

/** 站点接口对应的数据模型，字段与 /v1 返回一一对应，只保留界面需要的部分。 */
public final class Models {

    private Models() {}

    /** 搜索结果卡片 */
    public static final class Movie {
        public String variantId = "";     // 播放/详情用 id
        public String title = "";
        public int year;
        public String kind = "";          // series / movie
        public String area = "";
        public String poster = "";
        public String remarks = "";
        public int episodeCount;
        public final List<String> genres = new ArrayList<>();
        public final List<String> actors = new ArrayList<>();

        public String subtitle() {
            StringBuilder sb = new StringBuilder();
            if (year > 0) sb.append(year);
            if (!area.isEmpty()) { if (sb.length() > 0) sb.append(" · "); sb.append(area); }
            for (int i = 0; i < genres.size() && i < 2; i++) {
                String g = genres.get(i);
                if (g == null || g.isEmpty() || g.contains("·")) continue;
                if (sb.length() > 0) sb.append(" · ");
                sb.append(g);
            }
            return sb.toString();
        }

        public String castLine() {
            if (actors.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < actors.size() && i < 4; i++) {
                if (i > 0) sb.append(" / ");
                sb.append(actors.get(i));
            }
            return sb.toString();
        }
    }

    /** 搜索一页的结果 */
    public static final class Page {
        public final List<Movie> items = new ArrayList<>();
        public boolean hasMore;
        public int nextPage = 1;
    }

    /** 影片详情 */
    public static final class Detail {
        public String variantId = "";
        public String title = "";
        public String description = "";
        public String remarks = "";
        public String poster = "";
        public int year;
        public String area = "";
        public String statusText = "";        // 更新至18集 / 共30集
        public int totalEpisodes;
        public int availableEpisodes;
        public int introEndSeconds = -1;      // -1 表示站点未提供
        public int outroFromEndSeconds = -1;
        public int skipSampleCount;
        public final List<String> genres = new ArrayList<>();
    }

    /** 单集 */
    public static final class Episode {
        public String token = "";   // 播放解析用 token
        public String title = "";
        public int number;
    }

    /** 一条播放线路（换源项） */
    public static final class Line {
        public String label = "";         // 1080P-官方V / 极速资源
        public String provider = "";
        public String ticket = "";        // resolve:// 后的票根
        public String urlKind = "";       // mp4 / m3u8 / unknown
        public int weight;
        public boolean resolveRequired = true;

        /** 最终解析出的播放地址 */
        public String url = "";
        public String finalKind = "";
    }

    /** 某集的播放信息：包含 33 条可选线路 */
    public static final class Playback {
        public final List<Line> lines = new ArrayList<>();
        public String episodeTitle = "";
        public int episodeNumber;
        public String nextToken = "";
        public String prevToken = "";
    }
}
