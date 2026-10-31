package com.wario.shakeit;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
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

    private static final String RUNTIME_DIR = "runtime";
    private static final String STAMP_FILE = "unpacked_stamp.txt";
    // Bump when the layout produced by unpackRuntime() changes. Every APK update also changes the
    // stamp (lastUpdateTime), so updated assets are copied again without touching this number.
    private static final String RUNTIME_LAYOUT = "3";

    /**
     * True in the game process once the runtime files are in place. GameActivity checks it before
     * starting the runtime, so a failed unpack goes back to SetupActivity (which shows the error).
     */
    static volatile boolean runtimeReady;

    static File crashFile(Context context) {
        return new File(context.getFilesDir(), CRASH_FILE);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Installed first, so that errors in the unpacking below are recorded as well.
        installCrashHandler();
        // The game process must have these before libmain.so loads: native globals (for example
        // settings_overlay.cpp) read Config.toml while the library initializes, and that read is
        // cached for the rest of the process. GameActivity loads the library later, so setting them
        // there was too late and dvd_root was never seen.
        if (isGameProcess()) {
            try {
                File runtimeDir = unpackRuntime(this);
                Os.setenv("WIICOMPILED_RUNTIME_DIR", runtimeDir.getAbsolutePath(), true);
                Os.setenv("WIICOMPILED_DATA_DIR", SetupActivity.dataDir(this).getAbsolutePath(), true);
                runtimeReady = true;
            } catch (Exception e) {
                writeCrash("No se pudieron preparar los archivos del juego al iniciar.\n"
                        + "Could not prepare the game files at start-up.\n\n", e);
            }
        }
    }

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            writeCrash("Proceso: " + processName() + "\nHilo: " + thread.getName() + "\n\n", error);
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    private void writeCrash(String header, Throwable error) {
        try {
            StringWriter text = new StringWriter();
            text.write(header);
            error.printStackTrace(new PrintWriter(text));
            try (Writer out = new java.io.OutputStreamWriter(
                    new FileOutputStream(crashFile(this)), java.nio.charset.StandardCharsets.UTF_8)) {
                out.write(text.toString());
            }
        } catch (Exception ignored) {
            // Nothing else we can do here; the default handler still runs for uncaught exceptions.
        }
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

    /**
     * Copies runtime/assets (from the APK) into internal storage. It runs again whenever the app is
     * installed or updated, because the stamp contains the APK's version and install time.
     */
    static File unpackRuntime(Context context) throws IOException {
        File root = new File(context.getFilesDir(), RUNTIME_DIR);
        File stampFile = new File(root, STAMP_FILE);
        String stamp = runtimeStamp(context);
        if (stamp.equals(readStamp(stampFile))) {
            return root;
        }
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("No se pudo crear " + root);
        }
        copyAssets(context, "", root);
        // The runtime looks for its Wii bootstrap NAND in <runtime dir>/wii_bootstrap (the
        // desktop build copies it there). Without it, NAND init fails with "Unable to initialize
        // managed NAND", so place the assets in that folder too.
        copyAssets(context, "wii", new File(root, "wii_bootstrap"));
        // The runtime finds these two beside libmain (the executable directory), not in the
        // subfolders they come from in the APK: the TLS root store and Aurora's shader cache.
        copyAsset(context, "certs/cacert.pem", new File(root, "cacert.pem"));
        copyAsset(context, "pipeline/initial_pipeline_cache.db", new File(root, "initial_pipeline_cache.db"));
        writeStamp(stampFile, stamp);
        return root;
    }

    private static String runtimeStamp(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return RUNTIME_LAYOUT + ":" + info.versionCode + ":" + info.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return RUNTIME_LAYOUT + ":unknown:" + System.currentTimeMillis();
        }
    }

    private static String readStamp(File stampFile) {
        if (!stampFile.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(stampFile)) {
            byte[] bytes = new byte[256];
            int n = in.read(bytes);
            return n <= 0 ? null : new String(bytes, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeStamp(File stampFile, String stamp) throws IOException {
        try (OutputStream out = new FileOutputStream(stampFile)) {
            out.write(stamp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static void copyAssets(Context context, String path, File dest) throws IOException {
        String[] children = context.getAssets().list(path);
        if (children != null && children.length > 0) {
            if (!dest.isDirectory() && !dest.mkdirs()) {
                throw new IOException("No se pudo crear " + dest);
            }
            for (String child : children) {
                copyAssets(context, path.isEmpty() ? child : path + "/" + child, new File(dest, child));
            }
            return;
        }
        copyAsset(context, path, dest);
    }

    private static void copyAsset(Context context, String assetPath, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("No se pudo crear " + parent);
        }
        try (InputStream in = context.getAssets().open(assetPath); OutputStream out = new FileOutputStream(dest)) {
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
