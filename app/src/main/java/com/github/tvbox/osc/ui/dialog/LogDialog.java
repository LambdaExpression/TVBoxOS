package com.github.tvbox.osc.ui.dialog;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.util.LOG;

import org.jetbrains.annotations.NotNull;

/**
 * 展示应用运行日志。最多 500 条，可以复制或清空。
 */
public class LogDialog extends BaseDialog {
    /** 日志正文。 */
    private TextView logText;

    public LogDialog(@NonNull @NotNull Context context) {
        super(context);
        setContentView(R.layout.dialog_log);
        logText = findViewById(R.id.logText);
        showLogs();
        findViewById(R.id.logCopy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyLogs();
            }
        });
        findViewById(R.id.logClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                LOG.clear();
                showLogs();
            }
        });
    }

    /** 把当前保留的日志填进对话框。没有内容时给出说明。 */
    private void showLogs() {
        String text = LOG.getText();
        if (text == null || text.length() == 0) {
            logText.setText("还没有日志");
            return;
        }
        logText.setText(text);
    }

    /** 复制全部日志。剪贴板不可用时提示，不把失败当成已经复制。 */
    private void copyLogs() {
        String text = LOG.getText();
        if (text == null || text.length() == 0) {
            Toast.makeText(getContext(), "没有可复制的日志", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) {
            Toast.makeText(getContext(), "无法复制", Toast.LENGTH_SHORT).show();
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("tvbox-log", text));
        Toast.makeText(getContext(), "已复制", Toast.LENGTH_SHORT).show();
    }
}
