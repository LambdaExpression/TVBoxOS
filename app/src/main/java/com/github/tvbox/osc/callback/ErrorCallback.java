package com.github.tvbox.osc.callback;

import com.github.tvbox.osc.R;
import com.kingja.loadsir.callback.Callback;

/**
 * 加载失败占位页。文案由 {@link com.github.tvbox.osc.base.BaseLazyFragment#showError(String)} 注入，
 * 点击走 LoadSir 的 onReload，避免失败后一直停在转圈。
 */
public class ErrorCallback extends Callback {
    @Override
    protected int onCreateView() {
        return R.layout.loadsir_error_layout;
    }
}
