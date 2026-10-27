package com.wario.shakeit;

import android.app.Application;
import android.os.Build;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;

/**
 * Saves the last uncaught Java exception to last_crash.txt, so the next time the app opens
 * SetupActivity can show it instead of closing silently. Runs in every process of the app
 * (the launcher and the game process). Native crashes in libmain.so are not caught here: use logcat.
 */
public class WarioApplication extends Application {

    static final String CRASH_FILE = "last_crash.txt";

    static File crashFile(android.content.Context context) {
        return new File(context.getFilesDir(), CRASH_FILE);
    }

    // Bump when the files under runtime/assets change, so installs unpack them again.
    private static final String RUNTIME_STAMP = "unpacked-2";

    @Override
    public void onCreate() {
        super.onCreate();
        // The game process must have these before libmain.so loads: native globals (for example
        // settings_overlay.cpp) read Config.toml while the library initializes, and that read is
        // cached for the rest of the process. GameActivity loads the library later, so setting them
        // there was too late and dvd_root was never seen.
        if (isGameProcess()) {
            try {
                File runtimeDir = unpackRuntime(this);
                Os.setenv("WIICOMPILED_RUNTIME_DIR", runtimeDir.getAbsolutePath(), true);
                Os.setenv("WIICOMPILED_DATA_DIR", SetupActivity.dataDir(this).getAbsolutePath(), true);
            } catch (Exception e) {
                // The game reports its own error if these are missing; keep the process alive.
            }
        }
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter text = new StringWriter();
                text.write("Proceso: " + processName() + "\nHilo: " + thread.getName() + "\n\n");
                error.printStackTrace(new PrintWriter(text));
                try (Writer out = new java.io.OutputStreamWriter(
                        new FileOutputStream(crashFile(this)), java.nio.charset.StandardCharsets.UTF_8)) {
                    out.write(text.toString());
                }
            } catch (Exception ignored) {
                // Nothing else we can do here; the default handler below still runs.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    private boolean isGameProcess() {
        String name = null;
        if (Build.VERSION.SDK_INT >= 28) {
            name = android.app.Application.getProcessName();
        } else {
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/cmdline"))) {
                String line = reader.readLine();
                name = line == null ? null : line.split("\0")[0];
            } catch (IOException e) {
                name = null;
            }
        }
        return name != null && name.endsWith(":game");
    }

    // Copies runtime/assets (from the APK) into internal storage once per RUNTIME_STAMP.
    static File unpackRuntime(android.content.Context context) throws IOException {
        File root = new File(context.getFilesDir(), "runtime");
        File stamp = new File(root, RUNTIME_STAMP);
        if (!stamp.exists()) {
            copyAssets(context, "", root);
            if (!root.exists() && !root.mkdirs()) {
                throw new IOException("No se pudo crear " + root);
            }
            // The runtime looks for its Wii bootstrap NAND in <runtime dir>/wii_bootstrap (the
            // desktop build copies it there). Without it, NAND init fails with "Unable to initialize
            // managed NAND", so place the assets in that folder too.
            copyAssets(context, "wii", new File(root, "wii_bootstrap"));
            stamp.createNewFile();
        }
        return root;
    }

    private static void copyAssets(android.content.Context context, String path, File dest) throws IOException {
        String[] children = context.getAssets().list(path);
        if (children != null && children.length > 0) {
            dest.mkdirs();
            for (String child : children) {
                copyAssets(context, path.isEmpty() ? child : path + "/" + child, new File(dest, child));
            }
            return;
        }
        File parent = dest.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (InputStream in = context.getAssets().open(path); OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
        }
    }

    private String processName() {
        try {
            return android.app.Application.getProcessName();
        } catch (RuntimeException e) {
            return "?";
        }
    }
}
