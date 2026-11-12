package com.github.tvbox.osc.ui.activity;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.BounceInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.bean.AbsSortXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.ui.adapter.HomePageAdapter;
import com.github.tvbox.osc.ui.adapter.SortAdapter;
import com.github.tvbox.osc.ui.fragment.GridFragment;
import com.github.tvbox.osc.ui.fragment.UserFragment;
import com.github.tvbox.osc.ui.tv.widget.NoScrollViewPager;
import com.github.tvbox.osc.util.DefaultConfig;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.List;

import me.jessyan.autosize.utils.AutoSizeUtils;

/**
 * 设置里的「网盘登录」独立页。只显示订阅里「我的云盘」站点的分类和内容，
 * 不改首页站源；登录完按返回键回到设置。
 */
public class DriveLoginActivity extends BaseActivity {
    /** 传入的站点 key。 */
    public static final String EXTRA_SOURCE_KEY = "sourceKey";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<BaseLazyFragment> fragments = new ArrayList<>();

    private SourceViewModel sourceViewModel;
    private SortAdapter sortAdapter;
    private HomePageAdapter pageAdapter;
    private TvRecyclerView tabView;
    private NoScrollViewPager pager;
    private TextView tvStatus;
    private String sourceKey;
    /** 当前画面对应的分类签名。网络结果和缓存预览一致时不重建。 */
    private String shownSign = "";
    private int currentSelected = -1;
    private int tabFocused = 0;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_drive_login;
    }

    @Override
    protected boolean shouldRefreshAutoSize() {
        return true;
    }

    @Override
    protected void init() {
        Bundle extras = getIntent() == null ? null : getIntent().getExtras();
        sourceKey = extras == null ? null : extras.getString(EXTRA_SOURCE_KEY);
        SourceBean source = TextUtils.isEmpty(sourceKey) ? null : ApiConfig.get().getSource(sourceKey);
        if (source == null) {
            Toast.makeText(mContext, "当前配置里没有我的云盘", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        TextView title = findViewById(R.id.tvDriveTitle);
        if (!TextUtils.isEmpty(source.getName())) {
            title.setText("网盘登录 · " + source.getName());
        }
        initTabs();
        tvStatus = findViewById(R.id.tvDriveStatus);
        tvStatus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.sortResult.observe(this, new Observer<AbsSortXml>() {
            @Override
            public void onChanged(AbsSortXml absXml) {
                onSortResult(absXml);
            }
        });
        load();
    }

    private void initTabs() {
        tabView = findViewById(R.id.mGridView);
        pager = findViewById(R.id.mViewPager);
        sortAdapter = new SortAdapter();
        tabView.setLayoutManager(new V7LinearLayoutManager(mContext, 0, false));
        tabView.setSpacingWithMargins(0, AutoSizeUtils.dp2px(mContext, 10.0f));
        tabView.setAdapter(sortAdapter);
        tabView.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView == null) {
                    return;
                }
                TextView text = itemView.findViewById(R.id.tvTitle);
                text.getPaint().setFakeBoldText(false);
                text.setTextColor(getResources().getColor(R.color.color_BBFFFFFF));
                itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).start();
                text.invalidate();
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView == null) {
                    return;
                }
                TextView text = itemView.findViewById(R.id.tvTitle);
                text.getPaint().setFakeBoldText(true);
                text.setTextColor(getResources().getColor(R.color.color_FFFFFF));
                itemView.animate().scaleX(1.1f).scaleY(1.1f).setInterpolator(new BounceInterpolator()).setDuration(300).start();
                text.invalidate();
                tabFocused = position;
                handler.removeCallbacks(switchPage);
                handler.postDelayed(switchPage, 200);
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                BaseLazyFragment current = currentFragment();
                if (current instanceof GridFragment && position == currentSelected
                        && !sortAdapter.getItem(position).filters.isEmpty()) {
                    ((GridFragment) current).showFilter();
                }
            }
        });
        tabView.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View focused) {
                if (direction != View.FOCUS_DOWN) {
                    return false;
                }
                BaseLazyFragment current = currentFragment();
                if (!(current instanceof GridFragment)) {
                    return false;
                }
                // 内容还没出来时不让焦点掉下去。
                return !((GridFragment) current).isLoad();
            }
        });
    }

    private final Runnable switchPage = new Runnable() {
        @Override
        public void run() {
            if (tabFocused < 0 || tabFocused >= fragments.size()) {
                return;
            }
            BaseLazyFragment target = fragments.get(tabFocused);
            if (tabFocused != currentSelected) {
                currentSelected = tabFocused;
                pager.setCurrentItem(tabFocused, false);
            }
            if (target instanceof GridFragment && ((GridFragment) target).shouldReloadOnSelect()) {
                ((GridFragment) target).forceRefresh();
            }
        }
    };

    private BaseLazyFragment currentFragment() {
        if (currentSelected < 0 || currentSelected >= fragments.size()) {
            return null;
        }
        return fragments.get(currentSelected);
    }

    /** 重新拉这个站点的分类。 */
    private void load() {
        showStatus("正在加载…", false);
        sourceViewModel.getSort(sourceKey);
    }

    private void onSortResult(AbsSortXml absXml) {
        if (absXml != null && absXml.keepCurrent) {
            return;
        }
        if (absXml != null && absXml.sourceKey != null && !absXml.sourceKey.equals(sourceKey)) {
            return;
        }
        List<MovieSort.SortData> sorts = new ArrayList<>();
        if (absXml != null && absXml.classes != null && absXml.classes.sortList != null) {
            sorts = DefaultConfig.adjustSort(sourceKey, absXml.classes.sortList, false);
        }
        List<Movie.Video> videos = absXml == null ? null : absXml.videoList;
        boolean hasVideos = videos != null && !videos.isEmpty();
        if (sorts.isEmpty() && !hasVideos) {
            // 缓存预览为空时再等网络结果，只有最终结果也为空才报失败。
            if (absXml == null || !absXml.fromCache) {
                clearPages();
                showStatus("没有加载到内容，点击重试", true);
            }
            return;
        }
        String sign = buildSign(sorts, videos);
        if (sign.equals(shownSign)) {
            return;
        }
        shownSign = sign;
        rebuild(sorts, hasVideos ? videos : null);
    }

    private String buildSign(List<MovieSort.SortData> sorts, List<Movie.Video> videos) {
        StringBuilder builder = new StringBuilder();
        for (MovieSort.SortData sort : sorts) {
            builder.append(sort.id).append('\u0001').append(sort.name).append('\n');
        }
        builder.append('|');
        if (videos != null) {
            for (Movie.Video video : videos) {
                if (video != null) {
                    builder.append(video.id).append('\u0001').append(video.name).append('\n');
                }
            }
        }
        return builder.toString();
    }

    private void rebuild(List<MovieSort.SortData> sorts, List<Movie.Video> videos) {
        clearPages();
        List<MovieSort.SortData> tabs = new ArrayList<>();
        if (videos != null) {
            tabs.add(new MovieSort.SortData("my0", "主页"));
        }
        tabs.addAll(sorts);
        for (MovieSort.SortData data : tabs) {
            if ("my0".equals(data.id)) {
                fragments.add(UserFragment.newInstance(videos));
            } else {
                fragments.add(GridFragment.newInstance(data).setFixedSourceKey(sourceKey));
            }
        }
        hideStatus();
        pageAdapter = new HomePageAdapter(getSupportFragmentManager(), fragments);
        pager.setAdapter(pageAdapter);
        pager.setCurrentItem(0, false);
        currentSelected = 0;
        tabFocused = 0;
        sortAdapter.setNewData(tabs);
        tabView.post(new Runnable() {
            @Override
            public void run() {
                View first = tabView.getLayoutManager() == null ? null : tabView.getLayoutManager().findViewByPosition(0);
                if (first != null) {
                    tabView.setSelectedPosition(0);
                    first.requestFocus();
                }
            }
        });
    }

    private void clearPages() {
        handler.removeCallbacks(switchPage);
        currentSelected = -1;
        tabFocused = 0;
        shownSign = "";
        if (pageAdapter != null) {
            pager.setAdapter(null);
            pageAdapter.removeAll();
            pageAdapter = null;
        } else {
            fragments.clear();
        }
        sortAdapter.setNewData(new ArrayList<MovieSort.SortData>());
    }

    private void showStatus(String text, boolean retry) {
        tvStatus.setText(text);
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setClickable(retry);
        tvStatus.setFocusable(retry);
        tvStatus.setFocusableInTouchMode(retry);
        if (retry) {
            tvStatus.requestFocus();
        }
    }

    private void hideStatus() {
        tvStatus.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        BaseLazyFragment current = currentFragment();
        // 在文件夹里时先退回上一层。
        if (current instanceof GridFragment && ((GridFragment) current).restoreView()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
