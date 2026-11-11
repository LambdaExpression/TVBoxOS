package com.github.tvbox.osc.ui.adapter;

import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.MovieSort;

import java.util.ArrayList;

/**
 * 筛选弹层里的一行可选值。选中状态放在数据上，列表回收后不会串到别的项。
 */
public class GridFilterKVAdapter extends BaseQuickAdapter<MovieSort.FilterOption, BaseViewHolder> {

    /** 当前这一维选中的 v。空表示没选，全部也不会被标成选中。 */
    private String selectedValue;

    public GridFilterKVAdapter() {
        super(R.layout.item_grid_filter_value, new ArrayList<MovieSort.FilterOption>());
    }

    /**
     * @param selectedValue 当前选中的 v，没有选中就传 null
     */
    public void setSelectedValue(String selectedValue) {
        this.selectedValue = selectedValue;
    }

    @Override
    protected void convert(BaseViewHolder helper, MovieSort.FilterOption item) {
        String name = item == null || item.name == null ? "" : item.name;
        helper.setText(R.id.filterValue, name);
        boolean selected = item != null && item.value != null && item.value.length() > 0 && item.value.equals(selectedValue);
        TextView text = helper.getView(R.id.filterValue);
        text.getPaint().setFakeBoldText(selected);
        text.setTextColor(helper.itemView.getResources().getColor(selected ? R.color.color_02F8E1 : R.color.color_FFFFFF));
    }
}
