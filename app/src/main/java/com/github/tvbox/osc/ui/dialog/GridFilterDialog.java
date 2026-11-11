package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.MovieSort;
import com.github.tvbox.osc.ui.adapter.GridFilterKVAdapter;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;

public class GridFilterDialog extends BaseDialog {
    public LinearLayout filterRoot;

    public GridFilterDialog(@NonNull @NotNull Context context) {
        super(context);
        setCanceledOnTouchOutside(false);
        setCancelable(true);
        setContentView(R.layout.dialog_grid_filter);
        filterRoot = findViewById(R.id.filterRoot);

        bindOutsideTouchDismiss();
    }

    public interface Callback {
        void change();
    }

    public void setOnDismiss(Callback callback) {
        setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialogInterface) {
                if (selectChange) {
                    callback.change();
                }
            }
        });
    }

    public void setData(MovieSort.SortData sortData) {
        ArrayList<MovieSort.SortFilter> filters = sortData.filters;
        for (MovieSort.SortFilter filter : filters) {
            View line = LayoutInflater.from(getContext()).inflate(R.layout.item_grid_filter, null);
            ((TextView) line.findViewById(R.id.filterName)).setText(filter.name);
            TvRecyclerView gridView = line.findViewById(R.id.mFilterKv);
            gridView.setHasFixedSize(true);
            gridView.setLayoutManager(new V7LinearLayoutManager(getContext(), 0, false));
            final GridFilterKVAdapter filterKVAdapter = new GridFilterKVAdapter();
            gridView.setAdapter(filterKVAdapter);
            final String key = filter.key;
            final java.util.List<MovieSort.FilterOption> options = filter.options();
            filterKVAdapter.setSelectedValue(sortData.filterSelect == null ? null : sortData.filterSelect.get(key));
            filterKVAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
                @Override
                public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                    if (position < 0 || position >= options.size()) {
                        return;
                    }
                    selectChange = true;
                    MovieSort.FilterOption option = options.get(position);
                    sortData.selectFilter(key, option == null ? "" : option.value);
                    filterKVAdapter.setSelectedValue(sortData.filterSelect == null ? null : sortData.filterSelect.get(key));
                    filterKVAdapter.notifyDataSetChanged();
                }
            });
            filterKVAdapter.setNewData(options);
            filterRoot.addView(line);
        }
    }

    private boolean selectChange = false;

    public void show() {
        selectChange = false;
        super.show();
        WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
        layoutParams.gravity = Gravity.BOTTOM;
        layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
        layoutParams.dimAmount = 0f;
        getWindow().getDecorView().setPadding(0, 0, 0, 0);
        getWindow().setAttributes(layoutParams);
//        requestFirstFilterFocus();
    }

    private void bindOutsideTouchDismiss() {
        View rootView = findViewById(R.id.root);
        rootView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (isTouchInsideFilter(event)) {
                    return false;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    dismiss();
                    return true;
                }
                return event.getAction() == MotionEvent.ACTION_DOWN
                        || event.getAction() == MotionEvent.ACTION_MOVE
                        || event.getAction() == MotionEvent.ACTION_CANCEL;
            }
        });
    }

    private boolean isTouchInsideFilter(MotionEvent event) {
        return event.getX() >= filterRoot.getLeft()
                && event.getX() <= filterRoot.getRight()
                && event.getY() >= filterRoot.getTop()
                && event.getY() <= filterRoot.getBottom();
    }

    private void requestFirstFilterFocus() {
        filterRoot.postDelayed(new Runnable() {
            @Override
            public void run() {
                View target = findFocusableChild(filterRoot);
                if (target != null) {
                    target.requestFocus();
                }
            }
        }, 100);
    }

    private View findFocusableChild(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = findFocusableChild(group.getChildAt(i));
                if (child != null) {
                    return child;
                }
            }
        }
        return view.isFocusable() ? view : null;
    }

}
