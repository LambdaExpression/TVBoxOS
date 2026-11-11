package com.github.tvbox.osc.bean;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamAsAttribute;
import com.thoughtworks.xstream.annotations.XStreamConverter;
import com.thoughtworks.xstream.annotations.XStreamImplicit;
import com.thoughtworks.xstream.converters.extended.ToAttributedValueConverter;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author pj567
 * @date :2020/12/18
 * @description:
 */
@XStreamAlias("class")
public class MovieSort implements Serializable {
    @XStreamImplicit(itemFieldName = "ty")
    public List<SortData> sortList;

    @XStreamAlias("ty")
    @XStreamConverter(value = ToAttributedValueConverter.class, strings = {"name"})
    public static class SortData implements Serializable, Comparable<SortData> {
        @XStreamAsAttribute
        public String id;
        public String name;
        public int sort = -1;
        public boolean select = false;
        /** 这一分类的筛选项。没有就是空列表，不要用 null。 */
        public ArrayList<SortFilter> filters = new ArrayList<>();
        /** 当前选中的筛选。键是筛选项 key，值是要传给爬虫的 v。空字符串表示全部，不放进来。 */
        public HashMap<String, String> filterSelect = new HashMap<>();
        public String flag; // 类型

        public SortData() {
        }

        public SortData(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public int filterSelectCount() {
            if (filterSelect == null) {
                return 0;
            }
            int count = 0;
            for (String filter : filterSelect.values()) {
                if (filter != null && !filter.isEmpty()) {
                    count++;
                }
            }
            return count;
        }

        /**
         * 选中一项。再点同一项，或点到空值（全部），就取消这一维。
         *
         * @param key 筛选项 key
         * @param value 源里的 v。空表示全部，不传给爬虫
         */
        public void selectFilter(String key, String value) {
            if (filterSelect == null) {
                filterSelect = new HashMap<>();
            }
            if (key == null || key.length() == 0 || value == null || value.length() == 0) {
                filterSelect.remove(key);
                return;
            }
            if (value.equals(filterSelect.get(key))) {
                filterSelect.remove(key);
                return;
            }
            filterSelect.put(key, value);
        }

        @Override
        public int compareTo(SortData o) {
            return this.sort - o.sort;
        }

        @Override
        public String toString() {
            return "SortData{" +
                    "id='" + id + '\'' +
                    ", name='" + name + '\'' +
                    ", sort=" + sort +
                    ", select=" + select +
                    ", filters=" + filters +
                    ", filterSelect=" + filterSelect +
                    ", flag='" + flag + '\'' +
                    '}';
        }
    }

    /**
     * 一个筛选项，例如年份、地区。选项按源里的顺序放，允许显示名重复。
     */
    public static class SortFilter {
        /** 传给爬虫的键。 */
        public String key;
        /** 这一行的标题。 */
        public String name;
        /** 显示名到提交值。同名只留第一个，界面以 options 为准。 */
        public LinkedHashMap<String, String> values = new LinkedHashMap<>();
        /** 源里的每一项，顺序和重复都保留。 */
        public ArrayList<FilterOption> options = new ArrayList<>();
        /** 源声明的默认值，对应 init，按 v 匹配。空表示没有默认。 */
        public String init = "";

        /**
         * 界面要用的选项。没有单独的 options 时，从 values 补出来。
         *
         * @return 不为 null
         */
        public List<FilterOption> options() {
            if (options != null && !options.isEmpty()) {
                return options;
            }
            ArrayList<FilterOption> built = new ArrayList<>();
            if (values == null) {
                return built;
            }
            for (Map.Entry<String, String> entry : values.entrySet()) {
                FilterOption option = new FilterOption();
                option.name = entry.getKey();
                option.value = entry.getValue();
                built.add(option);
            }
            return built;
        }

        @Override
        public String toString() {
            return "SortFilter{" +
                    "key='" + key + '\'' +
                    ", name='" + name + '\'' +
                    ", values=" + values +
                    '}';
        }
    }

    /**
     * 筛选项里的一个可选值。n 给界面看，v 才是请求参数。
     */
    public static class FilterOption {
        /** 界面文字，对应 n。 */
        public String name;
        /** 交给爬虫的值，对应 v。空字符串表示全部。 */
        public String value;
    }

}