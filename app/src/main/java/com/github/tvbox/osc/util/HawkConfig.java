package com.github.tvbox.osc.util;

/**
 * @author pj567
 * @date :2020/12/23
 * @description:
 */
public class HawkConfig {
    public static final String API_URL = "api_url";
    /** 未手动保存过配置地址时使用的订阅地址。用户一旦保存（含清空），不再回写。 */
    public static final String DEFAULT_API_URL = "http://www.饭太硬.cc/tv";
    /** 内置爬虫包对应的下载地址。只在本机还没有这个包时，用 assets 里的文件。 */
    public static final String DEFAULT_SPIDER_URL = "https://xiuxiu-pro-new.meitudata.com/posters/937f5abf1c7c4ff2f170d835c5c46c21.jpg";
    /** 配置后台更新的结果文案，设置页直接展示。 */
    public static final String CONFIG_UPDATE_STATUS = "config_update_status";
    public static final String EPG_URL = "epg_url";
    public static final String SHOW_PREVIEW = "show_preview";
    public static final String API_HISTORY = "api_history";
    public static final String API_LINE_LIST = "api_line_list";
    public static final String API_LINE_SOURCE = "api_line_source";
    public static final String LIVE_API_HISTORY = "live_api_history";
    public static final String EPG_HISTORY = "epg_history";
    public static final String HOME_API = "home_api";
    public static final String DEFAULT_PARSE = "parse_default";
    public static final String DEBUG_OPEN = "debug_open";
    public static final String PARSE_WEBVIEW = "parse_webview"; // true 系统 false xwalk
    public static final String IJK_CODEC = "ijk_codec";
    public static final String PLAY_TYPE = "play_type";//1 ijk 2 exo 10 MXPlayer
    public static final String LIVE_PLAY_TYPE = "live_play_type";//1 ijk 2 exo 10 MXPlayer
    public static final String PLAY_RENDER = "play_render"; //0 texture 2
    public static final String PLAY_SCALE = "play_scale"; //0 texture 2
    public static final String LIVE_PLAY_SCALE = "live_play_scale";
    public static final String PLAY_TIME_STEP = "play_time_step"; //0 texture 2
    public static final String DOH_URL = "doh_url";
    public static final int DEFAULT_HOME_REC = 1;
    public static final String HOME_REC = "home_rec"; // 0 豆瓣热播 1 数据源推荐 2 历史
    /** 豆瓣热播上次成功的原始 JSON。键名保持不变，避免丢掉已经缓存的数据。 */
    public static final String HOME_HOT_CACHE = "home_hot";
    /** 豆瓣热播缓存写入日期，仅作记录，不再用来跳过刷新。 */
    public static final String HOME_HOT_DAY = "home_hot_day";
    /** 站点推荐磁盘缓存的 sourceKey 列表，最多留 5 个。 */
    public static final String HOME_SORT_CACHE_INDEX = "home_sort_cache_index";
    public static final String HISTORY_NUM = "history_num";
    public static final String HISTORY_MERGE = "history_merge";
    public static final String SEARCH_VIEW = "search_view"; // 0 列表 1 缩略图
    public static final String LIVE_CHANNEL = "last_live_channel_name";
    public static final String LIVE_CHANNEL_REVERSE = "live_channel_reverse";
    public static final String LIVE_CROSS_GROUP = "live_cross_group";
    public static final String LIVE_CONNECT_TIMEOUT = "live_connect_timeout";
    public static final String LIVE_SHOW_NET_SPEED = "live_show_net_speed";
    public static final String LIVE_SHOW_RESOLUTION = "live_show_resolution";
    public static final String LIVE_SHOW_TIME = "live_show_time";
    public static final String FAST_SEARCH_MODE = "fast_search_mode";
    public static final String SUBTITLE_TEXT_SIZE = "subtitle_text_size";
    public static final String SUBTITLE_TIME_DELAY = "subtitle_time_delay";
    public static final String SUBTITLE_EXO_SCALE = "subtitle_exo_scale";
    public static final String SUBTITLE_EXO_POSITION = "subtitle_exo_position";
    public static final String SOURCES_FOR_SEARCH = "checked_sources_for_search";
    public static final String HOME_REC_STYLE = "home_rec_style";
    public static final String NOW_DATE = "now_date"; //当前日期
    public static final String REMOTE_TVBOX = "remote_tvbox_host";
    public static final String IJK_CACHE_PLAY = "ijk_cache_play";
    /** Exo 点播是否把已下载片段写入磁盘，供回拉和再次播放命中。直播不使用。 */
    public static final String EXO_CACHE_PLAY = "exo_cache_play";
    /** Exo 磁盘缓存上限，单位 MB。只允许 512、1024、2048、4096。 */
    public static final String EXO_CACHE_SIZE_MB = "exo_cache_size_mb";
    /** 未改过容量时使用 512MB。 */
    public static final int DEFAULT_EXO_CACHE_SIZE_MB = 512;
    public static final String PLAYER_IS_LIVE = "player_is_live";
    public static final String DOH_JSON = "doh_json";
    public static final String LIVE_GROUP_INDEX = "live_group_index";
    public static final String LIVE_GROUP_LIST = "live_group_list";
    public static final String LIVE_API_URL = "live_api_url";
    public static final String M3U8_PURIFY = "m3u8_purify";
    public static final String AUTO_SWITCH_LINE = "auto_switch_line";
    public static final String SCREEN_DISPLAY = "screen_display";
    public static final String LIVE_WEB_HEADER = "live_web_header";
    public static final String DEFAULT_LOAD_LIVE = "DEFAULT_LOAD_LIVE";
    public static final String SEARCH_HISTORY = "search_history";
    public static final String DANMU_OPEN = "danmu_open";
    public static final String DANMU_MAX_LINE = "danmu_max_line";
    public static final String DANMU_SPEED = "danmu_speed";
    public static final String DANMU_ALPHA = "danmu_alpha";
    public static final String DANMU_SIZE_SCALE = "danmu_size_scale";
    public static final String DANMU_RANDOM_COLOR = "danmu_random_color";
    public static final String DANMU_API = "danmu_api";
    public static boolean hotVodDelete;
}
