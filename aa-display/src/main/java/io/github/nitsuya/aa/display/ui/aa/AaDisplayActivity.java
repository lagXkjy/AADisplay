package io.github.nitsuya.aa.display.ui.aa;

import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import com.google.android.apps.auto.sdk.CarActivity;
import io.github.nitsuya.aa.display.CoreApiKt;
import io.github.nitsuya.aa.display.R;
import io.github.nitsuya.aa.display.databinding.ActivityAaDisplayBinding;

public class AaDisplayActivity extends CarActivity {
    private static final String TAG = "AADisplay_AaActivity";
    /** Keep rotation/locale/etc but allow HU display-size updates after Coolwalk reconnect. */
    private static final int IGNORE_CONFIG_EXCEPT_DISPLAY_SIZE =
        0xFFFF & ~(0x0800 | 0x2000 | 0x0400); // screen size, smallest screen size, screen layout
    /** Shell cursor + locked-peel preview pull while keyguard may pause Choreographer. */
    private static final long SHELL_STATE_POLL_MS = 16L;

    private ActivityAaDisplayBinding mBinding;
    private boolean mHidCursorPolling;
    private float mLastHidCursorGen = Float.NaN;
    private boolean mLastLockedPeelActive;
    private float mLastLockedPeelRatio = Float.NaN;
    private float mLastLockedFlipSeq = Float.NaN;
    private final Handler mShellPollHandler = new Handler(Looper.getMainLooper());
    private final Runnable mShellPoll = new Runnable() {
        @Override
        public void run() {
            if (!mHidCursorPolling || mBinding == null) return;
            try {
                float[] s = CoreApiKt.getCoreApi().getHidCursorOverlay();
                if (s != null && s.length >= 4) {
                    if (s[3] != mLastHidCursorGen) {
                        mLastHidCursorGen = s[3];
                        if (s[0] > 0.5f) {
                            mBinding.hidCursorOverlay.setCursorPosition(s[1], s[2]);
                        } else {
                            mBinding.hidCursorOverlay.hideCursor();
                        }
                    }
                    // Locked peel: system_server writes ratio; pull here (AMS broadcasts wait for PIN).
                    if (s.length >= 6) {
                        boolean peelActive = s[4] > 0.5f;
                        float peelRatio = s[5];
                        if (peelActive != mLastLockedPeelActive
                                || (peelActive && peelRatio != mLastLockedPeelRatio)) {
                            mLastLockedPeelActive = peelActive;
                            mLastLockedPeelRatio = peelRatio;
                            AaDisplayActivityKt.INSTANCE.applyLockedPeelPreview(
                                    getSupportFragmentManager(), peelActive, peelRatio);
                        }
                    }
                    // Locked peel tap-flip: same pull path — do not wait for delayed broadcast.
                    if (s.length >= 8 && s[6] != mLastLockedFlipSeq) {
                        mLastLockedFlipSeq = s[6];
                        AaDisplayActivityKt.INSTANCE.applyLockedFullscreenFlip(
                                getSupportFragmentManager(), (int) s[7]);
                    }
                }
            } catch (Throwable ignored) {
            }
            if (mHidCursorPolling) {
                mShellPollHandler.postDelayed(this, SHELL_STATE_POLL_MS);
            }
        }
    };

    protected Window getWindow() {
        return this.c();
    }

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        Log.d(TAG, "onCreate");
        setIgnoreConfigChanges(IGNORE_CONFIG_EXCEPT_DISPLAY_SIZE);
        this.setTheme(R.style.Theme_AADisplay);
        this.mBinding = ActivityAaDisplayBinding.inflate(getLayoutInflater());
        this.addGenericView(this.mBinding.getRoot());
        this.setContentView(this.mBinding.getRoot());
        Window window = this.getWindow();
        WindowInsetsController insetsController = window.getInsetsController();
        if (insetsController != null) {
            insetsController.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            insetsController.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        window.setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        AaDisplayActivityKt.INSTANCE.showMain(getSupportFragmentManager());
        // Keep pull alive across keyguard lock (onPause) — locked peel preview needs it.
        startHidCursorPolling();
    }

    private void startHidCursorPolling() {
        if (mHidCursorPolling) return;
        mHidCursorPolling = true;
        mLastHidCursorGen = Float.NaN;
        mLastLockedPeelActive = false;
        mLastLockedPeelRatio = Float.NaN;
        mLastLockedFlipSeq = Float.NaN;
        mShellPollHandler.post(mShellPoll);
    }

    private void stopHidCursorPolling() {
        if (!mHidCursorPolling) return;
        mHidCursorPolling = false;
        mShellPollHandler.removeCallbacks(mShellPoll);
        if (mBinding != null) {
            mBinding.hidCursorOverlay.hideCursor();
        }
        mLastLockedPeelActive = false;
        mLastLockedPeelRatio = Float.NaN;
        mLastLockedFlipSeq = Float.NaN;
    }

    @Override
    public void onStart() {
        super.onStart();
        Log.d(TAG, "onStart");
        startHidCursorPolling();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(TAG, "onConfigurationChanged: " + newConfig.screenWidthDp + "x" + newConfig.screenHeightDp);
        if (mBinding != null) {
            mBinding.getRoot().requestLayout();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        startHidCursorPolling();
        try {
            // Keep in sync with AABroadcastConst.ACTION_AA_DISPLAY_SHOWN
            sendBroadcast(new android.content.Intent("aa.display.action.AA_DISPLAY_SHOWN"));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause");
        // Do not stop shell-state polling here: keyguard lock pauses the activity but
        // locked-fullscreen peel still needs getHidCursorOverlay pull for GPU preview.
        if (mBinding != null) {
            mBinding.hidCursorOverlay.hideCursor();
        }
        super.onPause();
    }

    @Override
    public void onStop() {
        Log.d(TAG, "onStop");
        super.onStop();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent keyEvent) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (AaDisplayActivityKt.INSTANCE.consumeShellBackKey(getSupportFragmentManager())) {
                return true;
            }
        }
        switch (keyCode){
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                AaDisplayActivityKt.INSTANCE.pressKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                return true;
            case KeyEvent.KEYCODE_BACK:
                AaDisplayActivityKt.INSTANCE.pressKey(keyCode);
                return true;
            default:
                return super.onKeyDown(keyCode, keyEvent);
        }
    }

    private boolean onGenericMotionEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_SCROLL: {
                float vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (vScroll < 0) {
                    AaDisplayActivityKt.INSTANCE.pressKey(KeyEvent.KEYCODE_MEDIA_REWIND);
                } else if (vScroll > 0) {
                    AaDisplayActivityKt.INSTANCE.pressKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
                }
            }
        }
        return true;
    }
    private void addGenericView(ViewGroup viewGroup){
        View view = new View(this){
            @Override
            public boolean onGenericMotionEvent(MotionEvent event) {
                return AaDisplayActivity.this.onGenericMotionEvent(event);
            }
        };
        view.setLayoutParams(new ViewGroup.LayoutParams(1, 1));
        view.setAlpha(0);
        viewGroup.addView(view, 0);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        stopHidCursorPolling();
        super.onDestroy();
    }


}
