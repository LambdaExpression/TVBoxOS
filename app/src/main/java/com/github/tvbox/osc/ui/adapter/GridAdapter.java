package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.util.ImgUtil;

import java.util.ArrayList;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 首页/分类网格海报适配器。
 * sizeScale 只改格子显示大小，不改变列数和比例。
 */
public class GridAdapter extends BaseQuickAdapter<Movie.Video, BaseViewHolder> {
    /** 是否列表模式。 */
    private boolean mShowList;
    /** style 下用于算图宽的基准宽度（已乘 sizeScale）。 */
    private int defaultWidth;
    /** 站点配置的展示样式，可空。 */
    public ImgUtil.Style style;
    /** 宽高等比缩放，1 为默认大小。 */
    private final float sizeScale;

    /**
     * @param showList 是否列表模式
     * @param style    展示样式，可空
     */
    public GridAdapter(boolean showList, ImgUtil.Style style) {
        this(showList, style, 1f);
    }

    /**
     * @param showList  是否列表模式
     * @param style     展示样式，可空
     * @param sizeScale 宽高等比缩放，小于等于 0 时按 1 处理
     */
    public GridAdapter(boolean showList, ImgUtil.Style style, float sizeScale) {
        super(showList ? R.layout.item_list : R.layout.item_grid, new ArrayList<>());
        this.mShowList = showList;
        this.sizeScale = sizeScale > 0f ? sizeScale : 1f;
        if (style != null) {
            if (style.type.equals("list")) this.mShowList = true;
            this.defaultWidth = Math.max(1, Math.round(ImgUtil.getStyleDefaultWidth(style) * this.sizeScale));
        }
        this.style = style;
    }

    @Override
    protected void convert(BaseViewHolder helper, Movie.Video item) {
        if (this.mShowList) {
            helper.setText(R.id.tvNote, item.note);
            helper.setText(R.id.tvName, item.name);
            ImageView ivThumb = helper.getView(R.id.ivThumb);
            String pic = item.pic == null ? "" : item.pic.trim();
            if (!TextUtils.isEmpty(pic)) {
                if (ImgUtil.isBase64Image(pic)) {
                    ivThumb.setImageBitmap(ImgUtil.decodeBase64ToBitmap(pic));
                } else {
                    int roundingRadius = AutoSizeUtils.mm2px(mContext, 10);
                    int imageWidth = AutoSizeUtils.mm2px(mContext, 240);
                    int imageHeight = AutoSizeUtils.mm2px(mContext, 336);
                    if (style != null) {
                        ImgUtil.load(pic, ivThumb, roundingRadius, imageWidth, imageHeight, item.name, ImageView.ScaleType.FIT_XY);
                    } else {
                        ImgUtil.load(pic, ivThumb, roundingRadius, imageWidth, imageHeight, item.name);
                    }
                }
            } else {
                ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name));
            }
            return;
        }

        TextView tvYear = helper.getView(R.id.tvYear);
        if (item.year <= 0) {
            tvYear.setVisibility(View.GONE);
        } else {
            tvYear.setText(String.valueOf(item.year));
            tvYear.setVisibility(View.VISIBLE);
        }
        TextView tvLang = helper.getView(R.id.tvLang);
        tvLang.setVisibility(View.GONE);
        TextView tvArea = helper.getView(R.id.tvArea);
        tvArea.setVisibility(View.GONE);
        if (TextUtils.isEmpty(item.note)) {
            helper.setVisible(R.id.tvNote, false);
        } else {
            helper.setVisible(R.id.tvNote, true);
            helper.setText(R.id.tvNote, item.note);
        }
        helper.setText(R.id.tvName, item.name);
        helper.setText(R.id.tvActor, item.actor);
        ImageView ivThumb = helper.getView(R.id.ivThumb);
        int newWidth = Math.max(1, Math.round(ImgUtil.defaultWidth * sizeScale));
        int newHeight = Math.max(1, Math.round(ImgUtil.defaultHeight * sizeScale));
        if (style != null) {
            newWidth = defaultWidth;
            newHeight = Math.max(1, Math.round(newWidth / style.ratio));
        }
        String pic = item.pic == null ? "" : item.pic.trim();
        if (!TextUtils.isEmpty(pic)) {
            if (ImgUtil.isBase64Image(pic)) {
                ivThumb.setImageBitmap(ImgUtil.decodeBase64ToBitmap(pic));
            } else {
                int roundingRadius = AutoSizeUtils.mm2px(mContext, 10);
                int imageWidth = AutoSizeUtils.mm2px(mContext, newWidth);
                int imageHeight = AutoSizeUtils.mm2px(mContext, newHeight);
                if (style != null) {
                    ImgUtil.load(pic, ivThumb, roundingRadius, imageWidth, imageHeight, item.name, ImageView.ScaleType.FIT_XY);
                } else {
                    ImgUtil.load(pic, ivThumb, roundingRadius, imageWidth, imageHeight, item.name);
                }
            }
        } else {
            ivThumb.setImageDrawable(ImgUtil.createTextDrawable(item.name));
        }
        applyStyleToImage(ivThumb, newWidth, newHeight);
    }

    /**
     * 把缩略图容器改成目标宽高。有 style 或需要缩放时才改布局。
     *
     * @param ivThumb   海报图
     * @param widthMm   目标宽度（mm 设计单位）
     * @param heightMm  目标高度（mm 设计单位）
     */
    private void applyStyleToImage(final ImageView ivThumb, int widthMm, int heightMm) {
        if (style == null && sizeScale == 1f) {
            return;
        }
        ViewGroup container = (ViewGroup) ivThumb.getParent();
        ViewGroup.LayoutParams containerParams = container.getLayoutParams();
        containerParams.height = AutoSizeUtils.mm2px(mContext, heightMm);
        containerParams.width = AutoSizeUtils.mm2px(mContext, widthMm);
        container.setLayoutParams(containerParams);
    }
}
