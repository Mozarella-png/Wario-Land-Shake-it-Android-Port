package com.wario.shakeit;

import android.app.Application;

import java.io.File;
import java.io.FileOutputStream;
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

    @Override
    public void onCreate() {
        super.onCreate();
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

    private String processName() {
        try {
            return android.app.Application.getProcessName();
        } catch (RuntimeException e) {
            return "?";
        }
    }
}
