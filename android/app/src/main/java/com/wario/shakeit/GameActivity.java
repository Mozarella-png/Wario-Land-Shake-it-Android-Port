package com.wario.shakeit;

import android.hardware.display.DisplayManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.view.Choreographer;
import android.view.Display;
import android.view.WindowManager;

import java.io.IOException;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;

import org.libsdl.app.SDLActivity;


/**
 * Hosts libmain.so (the WarioRecomp runtime) through SDL's Android activity and draws the touch
 * remote on top of it.
 */
public class GameActivity extends SDLActivity {

    // libmain.so holds the runtime and the JNI functions below. Load it explicitly, before any native
    // call: if it fails, the error (missing library or unresolved symbol) is reported by dlopen.
    static {
        System.loadLibrary("main");
    }

    /**
     * SDLActivity loads only "SDL3" by default. The runtime's JNI functions (android_bridge.cpp) live
     * in libmain.so, so it has to be loaded too, or every native call fails with UnsatisfiedLinkError.
     */
    @Override
    protected String[] getLibraries() {
        // SDL3 is linked statically into libmain.so (SDL_SHARED OFF), so there is no libSDL3.so to load.
        return new String[] {"main"};
    }

    private static final int ASPECT_16X9 = 1;
    private static final int ASPECT_FILL = 2;

    // Implemented in runtime/src/android/android_bridge.cpp.
    public static native void nativeSetTouchState(int buttons, boolean shake, float tilt);
    public static native void nativeApplyDisplaySettings(int aspect, float renderScale, boolean showFps);
    public static native void nativeSetDisplayClock(long vblankNanos, double hz);

    private TouchOverlay overlay;
    private Choreographer.FrameCallback displayClock;
    private boolean displayClockOn;
    // True while a frame callback is queued with Choreographer. Keeps onPause/onResume from queuing a
    // second callback chain (which would call nativeSetDisplayClock twice per frame, forever).
    private boolean displayClockPending;

    /**
     * Opens the picker for the game data (SetupActivity). The runtime reads the game folder once per
     * process, so the game process is ended; SetupActivity runs in the app's main process and starts
     * a fresh game once the new data is in place.
     */
    public void chooseGameData() {
        Intent setup = new Intent(this, SetupActivity.class);
        setup.putExtra(SetupActivity.EXTRA_CHOOSE, true);
        setup.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(setup);
        finish();
        Process.killProcess(Process.myPid());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Without a valid game folder the runtime would fail at boot: go back to the picker instead.
        if (!SetupActivity.dataReady(this)) {
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }

        // Lets the launcher tell failures of this run from older ones.
        SetupActivity.touchStartMarker(this);

        // Before the runtime starts (it reads Config.toml once): ask for no more frame interpolation
        // than this screen can show. 180 FPS on a 120 Hz panel leaves the interpolated frames
        // unpresentable, which the runtime reads as overrun and drops to 60 Hz judder.
        matchInterpolationToDisplay();

        // The runtime environment (WIICOMPILED_*) is set in WarioApplication, before libmain.so loads.

        super.onCreate(savedInstanceState);

        // Must run after super.onCreate(): the window's decor view is only attached (and has its
        // WindowInsetsController) once SDLActivity has set up the content view.
        hideSystemBars();

        // The launcher's choices (Config.toml), so the in-game picture matches the start menu.
        nativeApplyDisplaySettings(configAspect(), configRenderScale(), configBool("video", "show_fps", false));

        // 120 or 180 FPS frame interpolation needs a display that runs that fast: ask for its mode.
        requestDisplayRate(configInt("video", "frame_interpolation_fps", 0));

        overlay = new TouchOverlay(this);
        addContentView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        addContentView(overlay.createPanel(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    }

    private int configAspect() {
        try {
            String v = DataImporter.readSetting(SetupActivity.configFile(this), "video", "aspect_mode");
            return v == null ? ASPECT_16X9 : Integer.parseInt(v.trim());
        } catch (IOException | RuntimeException e) {
            return ASPECT_16X9;
        }
    }

    private float configRenderScale() {
        try {
            String v = DataImporter.readSetting(SetupActivity.configFile(this), "video", "resolution_multiplier");
            return v == null ? 0f : Float.parseFloat(v.trim());
        } catch (IOException | RuntimeException e) {
            return 0f;
        }
    }

    /**
     * Lowers [video] frame_interpolation_fps to the largest multiple of 60 the screen can run
     * (same resolution as now). Leaves the setting alone when the screen can already do it.
     */
    private void matchInterpolationToDisplay() {
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        try {
            int target = configInt("video", "frame_interpolation_fps", 0);
            if (target <= 60) {
                return;
            }
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) {
                return;
            }
            Display.Mode current = display.getMode();
            float top = 0f;
            for (Display.Mode mode : display.getSupportedModes()) {
                if (mode.getPhysicalWidth() == current.getPhysicalWidth()
                        && mode.getPhysicalHeight() == current.getPhysicalHeight()) {
                    top = Math.max(top, mode.getRefreshRate());
                }
            }
            // +2 Hz tolerance: panels report 119.9 Hz for a 120 Hz mode.
            int achievable = ((int) (top + 2f) / 60) * 60;
            int effective = Math.min(target, achievable);
            if (effective != target) {
                DataImporter.writeSetting(SetupActivity.configFile(this), "video",
                        "frame_interpolation_fps", String.valueOf(effective));
            }
        } catch (IOException | RuntimeException e) {
            // Keep the configured value if the screen or the file cannot be read.
        }
    }

    private int configInt(String section, String key, int def) {
        try {
            String v = DataImporter.readSetting(SetupActivity.configFile(this), section, key);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (IOException | NumberFormatException e) {
            return def;
        }
    }

    /**
     * Switches to the screen mode closest to the target refresh rate (same resolution as now). A
     * 60 FPS target keeps the system's choice. Falls back to the fastest mode the screen has.
     */
    private void requestDisplayRate(int targetFps) {
        if (targetFps <= 60 || Build.VERSION.SDK_INT < 23) {
            return;
        }
        try {
            Display display = getWindowManager().getDefaultDisplay();
            Display.Mode current = display.getMode();
            Display.Mode best = null;
            for (Display.Mode mode : display.getSupportedModes()) {
                if (mode.getPhysicalWidth() != current.getPhysicalWidth()
                        || mode.getPhysicalHeight() != current.getPhysicalHeight()) {
                    continue;
                }
                if (best == null) {
                    best = mode;
                    continue;
                }
                boolean modeFast = mode.getRefreshRate() >= targetFps - 2f;
                boolean bestFast = best.getRefreshRate() >= targetFps - 2f;
                if (modeFast && !bestFast) {
                    best = mode;
                } else if (modeFast == bestFast && (modeFast
                        ? Math.abs(mode.getRefreshRate() - targetFps) < Math.abs(best.getRefreshRate() - targetFps)
                        : mode.getRefreshRate() > best.getRefreshRate())) {
                    best = mode;
                }
            }
            if (best == null || best.getModeId() == current.getModeId()) {
                return;
            }
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.preferredDisplayModeId = best.getModeId();
            params.preferredRefreshRate = best.getRefreshRate();
            getWindow().setAttributes(params);
        } catch (RuntimeException e) {
            // Keep the system's mode if the screen refuses the request.
        }
    }

    /**
     * Sends every display frame's vblank time to the runtime (Choreographer), so the game clock can
     * follow the screen instead of running on its own 60 Hz timer.
     */
    private void startDisplayClock() {
        if (displayClockOn) {
            return;
        }
        if (displayClock == null) {
            displayClock = new Choreographer.FrameCallback() {
                @Override
                public void doFrame(long frameTimeNanos) {
                    displayClockPending = false;
                    if (!displayClockOn) {
                        return;
                    }
                    double hz = getWindowManager().getDefaultDisplay().getRefreshRate();
                    nativeSetDisplayClock(frameTimeNanos, hz);
                    displayClockPending = true;
                    Choreographer.getInstance().postFrameCallback(this);
                }
            };
        }
        displayClockOn = true;
        if (!displayClockPending) {
            displayClockPending = true;
            Choreographer.getInstance().postFrameCallback(displayClock);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startDisplayClock();
    }

    private boolean configBool(String section, String key, boolean def) {
        try {
            String v = DataImporter.readSetting(SetupActivity.configFile(this), section, key);
            return v == null ? def : v.trim().equals("true");
        } catch (IOException e) {
            return def;
        }
    }

    @Override
    protected void onPause() {
        // Never leave a button held down while the game is in the background.
        if (overlay != null) {
            overlay.releaseAll();
        }
        displayClockOn = false;
        super.onPause();
    }

    /** Full-screen game: hides the status bar and the navigation buttons until the player swipes. */
    private void hideSystemBars() {
        android.view.View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            // View#getWindowInsetsController() returns null (instead of throwing) while the view is not
            // attached. onWindowFocusChanged calls this again once the window has focus.
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_SWIPE);
            }
        } else {
            decor.setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                            | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
        }
    }
}
