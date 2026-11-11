package com.github.tvbox.osc.util;

import static android.content.Context.UI_MODE_SERVICE;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

public class ScreenUtils {

    public static double getSqrt(Activity activity) {
        WindowManager wm = activity.getWindowManager();
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(dm);
        double x = Math.pow(dm.widthPixels / dm.xdpi, 2);
        double y = Math.pow(dm.heightPixels / dm.ydpi, 2);
        double screenInches = Math.sqrt(x + y);// 屏幕尺寸
        return screenInches;
    }

    private static boolean checkScreenLayoutIsTv(Context context) {
        return (context.getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) > Configuration.SCREENLAYOUT_SIZE_LARGE;
    }

    private static boolean checkIsPhone(Context context) {
        Object service = context.getSystemService(Context.TELEPHONY_SERVICE);
        if (!(service instanceof TelephonyManager)) {
            return false;
        }
        return ((TelephonyManager) service).getPhoneType() != TelephonyManager.PHONE_TYPE_NONE;
    }

    /**
     * 电视或盒子才显示局域网传输。系统服务拿不到时按非电视处理，避免首页直接崩溃。
     */
    public static boolean isTv(Context context) {
        if (context == null) {
            return false;
        }
        Object service = context.getSystemService(UI_MODE_SERVICE);
        if (service instanceof UiModeManager
                && ((UiModeManager) service).getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true;
        }
        return checkScreenLayoutIsTv(context) && !checkIsPhone(context);
    }


}
