package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.DefaultConfig;

import org.jetbrains.annotations.NotNull;

public class AboutDialog extends BaseDialog {

    public AboutDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_about);
        appendVersion(context);
    }

    /**
     * 在原文案上面（第一行）加“版本号：xxx”。
     * 版本号来自 versionName，GitHub 打包时就是 Release 的标题，本地打包是 1.0.0。
     * 样式照着布局里已有的文案复制，这样不用改 dialog_about.xml。
     */
    private void appendVersion(Context context) {
        View content = findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup) || ((ViewGroup) content).getChildCount() == 0) {
            return;
        }
        View root = ((ViewGroup) content).getChildAt(0);
        if (!(root instanceof LinearLayout) || ((LinearLayout) root).getChildCount() == 0) {
            return;
        }
        LinearLayout box = (LinearLayout) root;
        View first = box.getChildAt(0);
        if (!(first instanceof TextView)) {
            return;
        }
        TextView base = (TextView) first;

        TextView version = new TextView(context);
        version.setText("版本号：" + DefaultConfig.getAppVersionName(context));
        version.setTextColor(base.getTextColors());
        version.setTextSize(TypedValue.COMPLEX_UNIT_PX, base.getTextSize());
        version.setLineSpacing(base.getLineSpacingExtra(), base.getLineSpacingMultiplier());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                base.getLayoutParams().width, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) base.getTextSize();
        box.addView(version, 0, lp);
    }
}
