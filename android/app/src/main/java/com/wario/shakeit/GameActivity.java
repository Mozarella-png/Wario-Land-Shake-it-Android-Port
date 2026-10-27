package com.wario.shakeit;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
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

    private static final int ASPECT_FILL = 2;

    // Implemented in runtime/src/android/android_bridge.cpp.
    public static native void nativeSetTouchState(int buttons, boolean shake, float tilt);
    public static native void nativeApplyDisplaySettings(int aspect, float renderScale, boolean showFps);

    private TouchOverlay overlay;

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

        // The runtime environment (WIICOMPILED_*) is set in WarioApplication, before libmain.so loads.

        super.onCreate(savedInstanceState);

        // Must run after super.onCreate(): the window's decor view is only attached (and has its
        // WindowInsetsController) once SDLActivity has set up the content view.
        hideSystemBars();

        // Applies at the next frame; the game reads the aspect again only at boot.
        nativeApplyDisplaySettings(ASPECT_FILL, 0f, false);

        overlay = new TouchOverlay(this);
        addContentView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        addContentView(overlay.createPanel(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    }

    @Override
    protected void onPause() {
        // Never leave a button held down while the game is in the background.
        if (overlay != null) {
            overlay.releaseAll();
        }
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
