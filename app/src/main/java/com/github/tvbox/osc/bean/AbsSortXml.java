package com.github.tvbox.osc.bean;

import com.thoughtworks.xstream.annotations.XStreamAlias;

import java.io.Serializable;
import java.util.List;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
@XStreamAlias("rss")
public class AbsSortXml implements Serializable {
    public String sourceKey;

    /** 这次结果来自本地缓存，分类和推荐先显示，网络结果回来后再替换。 */
    public transient boolean fromCache;

    /** 网络刷新失败或内容和当前一致时，首页保持当前列表，不重建页面。 */
    public transient boolean keepCurrent;

    @XStreamAlias("class")
    public MovieSort classes;

    @XStreamAlias("list")
    public Movie list;

    public List<Movie.Video> videoList;
}