package com.github.tvbox.osc.player.controller;

import android.animation.ObjectAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;

import xyz.doikki.videoplayer.controller.BaseVideoController;
import xyz.doikki.videoplayer.controller.IControlComponent;
import xyz.doikki.videoplayer.controller.IGestureComponent;
import xyz.doikki.videoplayer.controller.MediaPlayerControl;
import xyz.doikki.videoplayer.player.VideoView;
import xyz.doikki.videoplayer.util.PlayerUtils;

public abstract class BaseController extends BaseVideoController implements GestureDetector.OnGestureListener, GestureDetector.OnDoubleTapListener, View.OnTouchListener {
    private GestureDetector mGestureDetector;
    private AudioManager mAudioManager;
    private boolean mIsGestureEnabled = true;
    private int mStreamVolume;
    private float mBrightness;
    private int mSeekPosition = -1;
    private boolean mFirstTouch;
    private boolean mChangePosition;
    private boolean mChangeBrightness;
    private boolean mChangeVolume;
    private boolean mCanChangePosition = true;
    private boolean mEnableInNormal;
    private boolean mCanSlide;
    private int mCurPlayState;

    protected Handler mHandler;

    protected HandlerCallback mHandlerCallback;

    protected interface HandlerCallback {
        void callback(Message msg);
    }

    private boolean mIsDoubleTapTogglePlayEnabled = true;


    public BaseController(@NonNull Context context) {
        super(context);
        mHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(@NonNull Message msg) {
                int what = msg.what;
                switch (what) {
                    case 100: { // 亮度+音量调整
                        mSlideInfo.setVisibility(VISIBLE);
                        mSlideInfo.setText(msg.obj.toString());
                        break;
                    }

                    case 101: { // 亮度+音量调整 关闭
                        mSlideInfo.setVisibility(GONE);
                        break;
                    }
                    default: {
                        if (mHandlerCallback != null)
                            mHandlerCallback.callback(msg);
                        break;
                    }
                }
                return false;
            }
        });
    }

    public BaseController(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public BaseController(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    private TextView mSlideInfo;
    private ProgressBar mLoading;
    private ViewGroup mPauseRoot;
    private TextView mPauseTime;
    /** 暂停条上的播放图标，恢复时慢慢转。 */
    private ImageView mPauseIcon;
    /** 图标旋转。声音没好之前一直转。 */
    private ObjectAnimator mPauseIconSpin;
    /** 正在等声音恢复，这段时间画面停在暂停帧。 */
    private boolean mResumeWaiting;
    /** 放弃等待或已经开播后，迟到的回调不再继续。 */
    private int mResumeToken;
    /** 绑定的播放器，用来等声音和结束过渡后开播。 */
    private VideoView<?> mBoundVideoView;
    /** 图标至少转这么久，声音很快就绪时过渡也不会一闪而过。 */
    private static final long RESUME_SPIN_MIN_MS = 400L;
    /** 暂停条淡出时间。淡出结束后才开播。 */
    private static final long RESUME_FADE_MS = 220L;

    @Override
    protected void initView() {
        super.initView();
        mAudioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        mGestureDetector = new GestureDetector(getContext(), this);
        setOnTouchListener(this);
        mSlideInfo = findViewWithTag("vod_control_slide_info");
        mLoading = findViewWithTag("vod_control_loading");
        mPauseRoot = findViewWithTag("vod_control_pause");
        mPauseTime = findViewWithTag("vod_control_pause_t");
        mPauseIcon = findViewWithTag("vod_control_pause_icon");
    }

    /**
     * 记下播放器，暂停后恢复时先等声音，再让中间那条提示淡出。
     */
    @Override
    public void setMediaPlayer(MediaPlayerControl mediaPlayer) {
        super.setMediaPlayer(mediaPlayer);
        if (mediaPlayer instanceof VideoView) {
            mBoundVideoView = (VideoView<?>) mediaPlayer;
            mBoundVideoView.setResumeGate(new VideoView.ResumeGate() {
                @Override
                public boolean onResumeFromPause() {
                    return beginResumeWhenAudioReady();
                }
            });
        } else {
            mBoundVideoView = null;
        }
    }

    @Override
    protected void setProgress(int duration, int position) {
        super.setProgress(duration, position);
        mPauseTime.setText(PlayerUtils.stringForTime(position) + " / " + PlayerUtils.stringForTime(duration));
    }

    @Override
    protected void onPlayStateChanged(int playState) {
        super.onPlayStateChanged(playState);
        if (playState != VideoView.STATE_PAUSED && mResumeWaiting) {
            abortResumeWait(false);
        }
        switch (playState) {
            case VideoView.STATE_IDLE:
                mPauseRoot.setVisibility(GONE);
                mLoading.setVisibility(GONE);
                break;
            case VideoView.STATE_PLAYING:
                mPauseRoot.setVisibility(GONE);
                mLoading.setVisibility(GONE);
                break;
            case VideoView.STATE_PAUSED:
                if (mPauseRoot != null) {
                    mPauseRoot.animate().cancel();
                    mPauseRoot.setAlpha(1f);
                    mPauseRoot.setVisibility(VISIBLE);
                }
                if (mPauseIcon != null) {
                    mPauseIcon.setRotation(0f);
                }
                mLoading.setVisibility(GONE);
                break;
            case VideoView.STATE_PREPARED:
            case VideoView.STATE_ERROR:
            case VideoView.STATE_BUFFERED:
                mPauseRoot.setVisibility(GONE);
                mLoading.setVisibility(GONE);
                break;
            case VideoView.STATE_PREPARING:
            case VideoView.STATE_BUFFERING:
                mPauseRoot.setVisibility(GONE);
                mLoading.setVisibility(VISIBLE);
                break;
            case VideoView.STATE_PLAYBACK_COMPLETED:
                mLoading.setVisibility(GONE);
                mPauseRoot.setVisibility(GONE);
                break;
        }
    }

    /**
     * 设置是否可以滑动调节进度，默认可以
     */
    public void setCanChangePosition(boolean canChangePosition) {
        mCanChangePosition = canChangePosition;
    }

    /**
     * 是否在竖屏模式下开始手势控制，默认关闭
     */
    public void setEnableInNormal(boolean enableInNormal) {
        mEnableInNormal = enableInNormal;
    }

    /**
     * 是否开启手势控制，默认开启，关闭之后，手势调节进度，音量，亮度功能将关闭
     */
    public void setGestureEnabled(boolean gestureEnabled) {
        mIsGestureEnabled = gestureEnabled;
    }

    /**
     * 是否开启双击播放/暂停，默认开启
     */
    public void setDoubleTapTogglePlayEnabled(boolean enabled) {
        mIsDoubleTapTogglePlayEnabled = enabled;
    }

    /**
     * 暂停后继续：播放图标先转，声音通道恢复后这条提示淡出，再开始播放。
     * 再按一次则取消等待，画面继续停着。
     *
     * @return true 这次先不开播
     */
    private boolean beginResumeWhenAudioReady() {
        if (mPauseRoot == null || mBoundVideoView == null) {
            return false;
        }
        if (mResumeWaiting) {
            abortResumeWait(true);
            return true;
        }
        mResumeWaiting = true;
        final int token = ++mResumeToken;
        startPauseIconSpin();
        final long begin = SystemClock.uptimeMillis();
        mBoundVideoView.warmUpAudio(new Runnable() {
            @Override
            public void run() {
                if (token != mResumeToken || !isAttachedToWindow()) {
                    return;
                }
                long remain = RESUME_SPIN_MIN_MS - (SystemClock.uptimeMillis() - begin);
                if (remain > 0) {
                    postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            fadePauseChipThenStart(token);
                        }
                    }, remain);
                } else {
                    fadePauseChipThenStart(token);
                }
            }
        });
        return true;
    }

    /**
     * 声音已经恢复。淡出中间的暂停条，淡出完再开播。
     *
     * @param token 发起这次等待时的代号
     */
    private void fadePauseChipThenStart(final int token) {
        if (token != mResumeToken || mBoundVideoView == null) {
            return;
        }
        if (mPauseRoot == null) {
            mResumeWaiting = false;
            stopPauseIconSpin();
            mBoundVideoView.cancelAudioWarmup();
            mBoundVideoView.startSkippingResumeGate();
            return;
        }
        mPauseRoot.animate().alpha(0f).setDuration(RESUME_FADE_MS).setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (token != mResumeToken || mBoundVideoView == null) {
                    return;
                }
                mPauseRoot.animate().setListener(null);
                stopPauseIconSpin();
                mResumeWaiting = false;
                mBoundVideoView.cancelAudioWarmup();
                mBoundVideoView.startSkippingResumeGate();
            }
        });
    }

    /**
     * 停止等待。恢复暂停条时把透明度设回 1，避免下次暂停看不见。
     *
     * @param restoreChip true 时把暂停条留在画面上
     */
    private void abortResumeWait(boolean restoreChip) {
        mResumeToken++;
        mResumeWaiting = false;
        if (mBoundVideoView != null) {
            mBoundVideoView.cancelAudioWarmup();
        }
        stopPauseIconSpin();
        if (mPauseRoot == null) {
            return;
        }
        mPauseRoot.animate().setListener(null);
        mPauseRoot.animate().cancel();
        mPauseRoot.setAlpha(1f);
        if (restoreChip) {
            mPauseRoot.setVisibility(VISIBLE);
        }
    }

    /**
     * 播放图标绕中心慢慢转一圈，重复到声音恢复。
     */
    private void startPauseIconSpin() {
        if (mPauseIcon == null) {
            return;
        }
        stopPauseIconSpin();
        mPauseIcon.setPivotX(mPauseIcon.getWidth() / 2f);
        mPauseIcon.setPivotY(mPauseIcon.getHeight() / 2f);
        mPauseIconSpin = ObjectAnimator.ofFloat(mPauseIcon, View.ROTATION, 0f, 360f);
        mPauseIconSpin.setDuration(1400L);
        mPauseIconSpin.setRepeatCount(ObjectAnimator.INFINITE);
        mPauseIconSpin.setInterpolator(new LinearInterpolator());
        mPauseIconSpin.start();
    }

    /**
     * 停掉旋转，图标角度回到朝上。
     */
    private void stopPauseIconSpin() {
        if (mPauseIconSpin != null) {
            mPauseIconSpin.cancel();
            mPauseIconSpin = null;
        }
        if (mPauseIcon != null) {
            mPauseIcon.setRotation(0f);
        }
    }

    public void hidePauseRoot() {
        if (mPauseRoot != null) {
            mPauseRoot.setVisibility(GONE);
        }
    }

    public void showPauseRoot() {
        if (mPauseRoot != null) {
            mPauseRoot.setVisibility(VISIBLE);
        }
    }

    @Override
    public void setPlayerState(int playerState) {
        super.setPlayerState(playerState);
        if (playerState == VideoView.PLAYER_NORMAL) {
            mCanSlide = mEnableInNormal;
        } else if (playerState == VideoView.PLAYER_FULL_SCREEN) {
            mCanSlide = true;
        }
    }

    @Override
    public void setPlayState(int playState) {
        super.setPlayState(playState);
        mCurPlayState = playState;
    }

    protected boolean isInPlaybackState() {
        return mControlWrapper != null
                && mCurPlayState != VideoView.STATE_ERROR
                && mCurPlayState != VideoView.STATE_IDLE
                && mCurPlayState != VideoView.STATE_PREPARING
                && mCurPlayState != VideoView.STATE_PREPARED
                && mCurPlayState != VideoView.STATE_START_ABORT
                && mCurPlayState != VideoView.STATE_PLAYBACK_COMPLETED;
    }

    protected boolean canHandleGesture(MotionEvent event) {
        return isInPlaybackState()
                && mIsGestureEnabled
                && mCanSlide
                && !isLocked()
                && !PlayerUtils.isEdge(getContext(), event);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        return mGestureDetector.onTouchEvent(event);
    }

    /**
     * 手指按下的瞬间
     */
    @Override
    public boolean onDown(MotionEvent e) {
        if (!isInPlaybackState() //不处于播放状态
                || !mIsGestureEnabled //关闭了手势
                || PlayerUtils.isEdge(getContext(), e)) //处于屏幕边沿
            return true;
        mStreamVolume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        Activity activity = PlayerUtils.scanForActivity(getContext());
        if (activity == null) {
            mBrightness = 0;
        } else {
            mBrightness = activity.getWindow().getAttributes().screenBrightness;
        }
        mFirstTouch = true;
        mChangePosition = false;
        mChangeBrightness = false;
        mChangeVolume = false;
        return true;
    }

    /**
     * 单击
     */
    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
        if (isInPlaybackState()) {
            mControlWrapper.toggleShowState();
        }
        return true;
    }

    /**
     * 双击
     */
    @Override
    public boolean onDoubleTap(MotionEvent e) {
        if (mIsDoubleTapTogglePlayEnabled && !isLocked() && isInPlaybackState()) togglePlay();
        return true;
    }

    /**
     * 在屏幕上滑动
     */
    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
        if (!canHandleGesture(e1)) return true;
        float deltaX = e1.getX() - e2.getX();
        float deltaY = e1.getY() - e2.getY();
        if (mFirstTouch) {
            mChangePosition = Math.abs(distanceX) >= Math.abs(distanceY);
            if (!mChangePosition) {
                //半屏宽度
                int halfScreen = PlayerUtils.getScreenWidth(getContext(), true) / 2;
                if (e2.getX() > halfScreen) {
                    mChangeVolume = true;
                } else {
                    mChangeBrightness = true;
                }
            }

            if (mChangePosition) {
                //根据用户设置是否可以滑动调节进度来决定最终是否可以滑动调节进度
                mChangePosition = mCanChangePosition;
            }

            if (mChangePosition || mChangeBrightness || mChangeVolume) {
                for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
                    IControlComponent component = next.getKey();
                    if (component instanceof IGestureComponent) {
                        ((IGestureComponent) component).onStartSlide();
                    }
                }
            }
            mFirstTouch = false;
        }
        if (mChangePosition) {
            slideToChangePosition(deltaX);
        } else if (mChangeBrightness) {
            slideToChangeBrightness(deltaY);
        } else if (mChangeVolume) {
            slideToChangeVolume(deltaY);
        }
        return true;
    }

    protected void slideToChangePosition(float deltaX) {
        deltaX = -deltaX;
        int width = getMeasuredWidth();
        int duration = PlayerUtils.safeTimeMs(mControlWrapper.getDuration());
        int currentPosition = PlayerUtils.safeTimeMs(mControlWrapper.getCurrentPosition());
        int position = (int) (deltaX / width * 120000 + currentPosition);
        if (position > duration) position = duration;
        if (position < 0) position = 0;
        for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
            IControlComponent component = next.getKey();
            if (component instanceof IGestureComponent) {
                ((IGestureComponent) component).onPositionChange(position, currentPosition, duration);
            }
        }
        updateSeekUI(currentPosition, position, duration);
        mSeekPosition = position;
    }

    protected void updateSeekUI(int curr, int seekTo, int duration) {

    }

    protected void slideToChangeBrightness(float deltaY) {
        Activity activity = PlayerUtils.scanForActivity(getContext());
        if (activity == null) return;
        Window window = activity.getWindow();
        WindowManager.LayoutParams attributes = window.getAttributes();
        int height = getMeasuredHeight();
        if (mBrightness == -1.0f) mBrightness = 0.5f;
        float brightness = deltaY * 2 / height * 1.0f + mBrightness;
        if (brightness < 0) {
            brightness = 0f;
        }
        if (brightness > 1.0f) brightness = 1.0f;
        int percent = (int) (brightness * 100);
        attributes.screenBrightness = brightness;
        window.setAttributes(attributes);
        for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
            IControlComponent component = next.getKey();
            if (component instanceof IGestureComponent) {
                ((IGestureComponent) component).onBrightnessChange(percent);
            }
        }
        Message msg = Message.obtain();
        msg.what = 100;
        msg.obj = "亮度" + percent + "%";
        mHandler.sendMessage(msg);
        mHandler.removeMessages(101);
        mHandler.sendEmptyMessageDelayed(101, 1000);
    }

    protected void slideToChangeVolume(float deltaY) {
        int streamMaxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int height = getMeasuredHeight();
        float deltaV = deltaY * 2 / height * streamMaxVolume;
        float index = mStreamVolume + deltaV;
        if (index > streamMaxVolume) index = streamMaxVolume;
        if (index < 0) index = 0;
        int percent = (int) (index / streamMaxVolume * 100);
        mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (int) index, 0);
        for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
            IControlComponent component = next.getKey();
            if (component instanceof IGestureComponent) {
                ((IGestureComponent) component).onVolumeChange(percent);
            }
        }
        Message msg = Message.obtain();
        msg.what = 100;
        msg.obj = "音量" + percent + "%";
        mHandler.sendMessage(msg);
        mHandler.removeMessages(101);
        mHandler.sendEmptyMessageDelayed(101, 1000);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        //滑动结束时事件处理
        if (!mGestureDetector.onTouchEvent(event)) {
            int action = event.getAction();
            switch (action) {
                case MotionEvent.ACTION_UP:
                    stopSlide();
                    if (mSeekPosition >= 0) {
                        mControlWrapper.seekTo(mSeekPosition);
                        mSeekPosition = -1;
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    stopSlide();
                    mSeekPosition = -1;
                    break;
            }
        }
        return super.onTouchEvent(event);
    }

    private void stopSlide() {
        for (Map.Entry<IControlComponent, Boolean> next : mControlComponents.entrySet()) {
            IControlComponent component = next.getKey();
            if (component instanceof IGestureComponent) {
                ((IGestureComponent) component).onStopSlide();
            }
        }
    }

    @Override
    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
        return false;
    }

    @Override
    public void onLongPress(MotionEvent e) {

    }

    @Override
    public void onShowPress(MotionEvent e) {

    }

    @Override
    public boolean onDoubleTapEvent(MotionEvent e) {
        return false;
    }


    @Override
    public boolean onSingleTapUp(MotionEvent e) {
        return false;
    }

    public boolean onKeyEvent(KeyEvent event) {
        return false;
    }
}
