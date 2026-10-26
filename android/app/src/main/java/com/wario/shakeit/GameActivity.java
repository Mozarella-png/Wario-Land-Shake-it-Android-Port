package com.wario.shakeit;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.system.Os;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

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

    // Bump when the files under runtime/assets change, so installs unpack them again.
    private static final String RUNTIME_STAMP = "unpacked-1";

    // VideoAspect 2 = fill the screen: ultrawide phones draw wider; nothing is stretched.
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

        hideSystemBars();

        // The runtime reads these before the game thread starts (runtime/src/platform/host_platform.cpp).
        try {
            File runtimeDir = unpackRuntime();
            Os.setenv("WIICOMPILED_RUNTIME_DIR", runtimeDir.getAbsolutePath(), true);
            // Config.toml (with [paths] dvd_root, written by SetupActivity) lives here too.
            Os.setenv("WIICOMPILED_DATA_DIR", SetupActivity.dataDir(this).getAbsolutePath(), true);
        } catch (Exception e) {
            throw new RuntimeException("No se pudieron preparar los archivos del runtime", e);
        }

        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        }

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

    // Copies runtime/assets (from the APK) into internal storage once per RUNTIME_STAMP.
    private File unpackRuntime() throws IOException {
        File root = new File(getFilesDir(), "runtime");
        File stamp = new File(root, RUNTIME_STAMP);
        if (!stamp.exists()) {
            copyAssets("", root);
            if (!root.exists() && !root.mkdirs()) {
                throw new IOException("No se pudo crear " + root);
            }
            stamp.createNewFile();
        }
        return root;
    }

    private void copyAssets(String path, File dest) throws IOException {
        String[] children = getAssets().list(path);
        if (children != null && children.length > 0) {
            dest.mkdirs();
            for (String child : children) {
                copyAssets(path.isEmpty() ? child : path + "/" + child, new File(dest, child));
            }
            return;
        }
        File parent = dest.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (InputStream in = getAssets().open(path); OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
        }
    }

    /** Full-screen game: hides the status bar and the navigation buttons until the player swipes. */
    private void hideSystemBars() {
        android.view.Window window = getWindow();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(android.view.WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
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
