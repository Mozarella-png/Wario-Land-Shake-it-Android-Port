package com.wario.shakeit;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * The launcher screen. If the game's data is already in place it starts the game at once; otherwise
 * (or when the player asks to change it from the game menu) it asks for the zip of the DATA folder
 * and imports it. The game itself runs in its own process (GameActivity, android:process=":game"),
 * so this screen never has to share the runtime with it.
 */
public class SetupActivity extends Activity {

    /** Set by the game menu's "Change game data": show the picker even when the data is valid. */
    static final String EXTRA_CHOOSE = "choose";

    private static final int REQUEST_PICK_ZIP = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private ProgressBar progress;
    private Button pick;
    private Button play;
    private boolean importing;

    /** The folder the game reads from. Same place for the launcher and the game process. */
    static File dataDir(android.content.Context context) {
        File external = context.getExternalFilesDir(null);
        return external != null ? external : new File(context.getFilesDir(), "data");
    }

    static File configFile(android.content.Context context) {
        return new File(dataDir(context), DataImporter.CONFIG_FILE);
    }

    /** Written each time the game process starts; failures older than this are already seen. */
    static File startMarker(android.content.Context context) {
        return new File(dataDir(context), "game_started");
    }

    static void touchStartMarker(android.content.Context context) {
        File marker = startMarker(context);
        try {
            dataDir(context).mkdirs();
            if (!marker.exists()) {
                marker.createNewFile();
            }
        } catch (IOException ignored) {
            // Only used to hide old errors; a missing marker just shows them again.
        }
        marker.setLastModified(System.currentTimeMillis());
    }

    /** True when Config.toml points at a valid game folder. */
    static boolean dataReady(android.content.Context context) {
        try {
            String root = DataImporter.readDvdRoot(configFile(context));
            if (root == null || root.isEmpty()) {
                return false;
            }
            return DataImporter.problem(new File(root)) == null;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean choose = getIntent() != null && getIntent().getBooleanExtra(EXTRA_CHOOSE, false);
        File crash = WarioApplication.crashFile(this);
        if (crash.isFile()) {
            // A report older than the last game start belongs to a build or run that is already gone.
            File marker = startMarker(this);
            if (!marker.isFile() || crash.lastModified() > marker.lastModified()) {
                showReport(readText(crash), crash);
                return;
            }
            crash.delete();
        }
        if (!choose) {
            String failure = lastGameFailure();
            if (failure != null) {
                showReport(failure, null);
                return;
            }
        }
        if (!choose && dataReady(this)) {
            startGame();
            return;
        }
        buildUi();
        if (!dataReady(this)) {
            status.setText("Aún no hay datos del juego. Elige el zip con la carpeta DATA de tu disco.");
        } else {
            status.setText("Datos actuales: correctos. Elige otro zip para cambiarlos, o vuelve al juego.");
            play.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Shows an error report so the player can copy it. crashToDelete (the Java crash file) is removed on retry.
     */
    private void showReport(String text, File crashToDelete) {
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("La última vez el juego se cerró con un error. Copia este texto y envíalo para poder arreglarlo.");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        root.addView(title);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        TextView body = new TextView(this);
        body.setText(text);
        body.setTextColor(0xFFFFAAAA);
        body.setTextSize(11);
        body.setTextIsSelectable(true);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button retry = new Button(this);
        retry.setText("Borrar el error y reintentar");
        retry.setOnClickListener(v -> {
            if (crashToDelete != null) {
                crashToDelete.delete();
            }
            touchStartMarker(this);
            recreate();
        });
        root.addView(retry);
        setContentView(root);
    }

    private static String readText(File file) {
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "No se pudo leer el registro del error: " + e.getMessage();
        }
    }

    /**
     * Why the last game run stopped, or null if it did not fail. Covers fatal errors the runtime wrote to
     * Logs/<run>/crash_*.txt (which the runtime otherwise only shows as a popup on Windows), and on
     * Android 11+ native crashes and exits with an error code, which otherwise close the app silently.
     */
    private String lastGameFailure() {
        File marker = startMarker(this);
        long since = marker.isFile() ? marker.lastModified() : 0L;

        File newest = null;
        File[] runs = new File(dataDir(this), "Logs").listFiles();
        if (runs != null) {
            for (File run : runs) {
                File[] reports = run.listFiles((dir, name) -> name.startsWith("crash_") && name.endsWith(".txt"));
                if (reports == null) {
                    continue;
                }
                for (File report : reports) {
                    if (report.lastModified() > since
                            && (newest == null || report.lastModified() > newest.lastModified())) {
                        newest = report;
                    }
                }
            }
        }
        if (newest != null) {
            return readText(newest);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            return exitReasonOfGame(since);
        }
        return null;
    }

    private String exitReasonOfGame(long since) {
        if (Build.VERSION.SDK_INT < 30) {
            return null;
        }
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) {
            return null;
        }
        // Most recent first.
        List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(getPackageName(), 0, 8);
        for (ApplicationExitInfo info : exits) {
            String process = info.getProcessName();
            if (process == null || !process.endsWith(":game")) {
                continue;
            }
            if (info.getTimestamp() < since) {
                break;
            }
            int reason = info.getReason();
            boolean failed = reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                    || reason == ApplicationExitInfo.REASON_CRASH
                    || (reason == ApplicationExitInfo.REASON_EXIT_SELF && info.getStatus() != 0);
            if (!failed) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            text.append("El juego se cerró por un error del sistema.\n")
                    .append("Motivo: ").append(String.valueOf(info.getDescription()))
                    .append("\nCódigo: ").append(info.getStatus()).append("\n");
            try (InputStream trace = info.getTraceInputStream()) {
                if (trace != null) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int n;
                    while (out.size() < 32 * 1024 && (n = trace.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                    }
                    text.append("\n").append(out.toString("UTF-8"));
                }
            } catch (IOException ignored) {
                // The trace is optional; the reason above is still useful.
            }
            return text.toString();
        }
        return null;
    }

    private void buildUi() {
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("Wario Land: Shake it!");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView help = new TextView(this);
        help.setText("Este port necesita tu propia copia del juego (US, RWLE01, revisión 0). Pon en un .zip "
                + "la carpeta DATA de tu disco (con sys/ y files/) y elígelo aquí. Se descomprime una vez "
                + "en el teléfono.");
        help.setTextColor(0xFFCCCCCC);
        help.setTextSize(14);
        help.setGravity(Gravity.CENTER);
        help.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(help);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        root.addView(status);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(12 * density));
        barParams.setMargins(0, pad / 2, 0, pad / 2);
        root.addView(progress, barParams);

        pick = new Button(this);
        pick.setText("Elegir zip de datos");
        pick.setOnClickListener(v -> openPicker());
        root.addView(pick);

        play = new Button(this);
        play.setText("Volver al juego");
        play.setVisibility(View.GONE);
        play.setOnClickListener(v -> startGame());
        root.addView(play);

        setContentView(root);
    }

    private void openPicker() {
        if (importing) {
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed",
                "application/octet-stream"});
        startActivityForResult(intent, REQUEST_PICK_ZIP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_ZIP || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        importZip(data.getData());
    }

    private void importZip(Uri uri) {
        importing = true;
        pick.setEnabled(false);
        play.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        status.setText("Leyendo el zip…");

        final long size = sizeOf(uri);
        final File dataDir = dataDir(this);
        new Thread(() -> {
            String result;
            boolean ok = false;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    throw new IOException("No se pudo abrir el archivo elegido.");
                }
                DataImporter.importZip(in, size, dataDir, (fraction, message) -> ui.post(() -> {
                    progress.setProgress(Math.round(fraction * 100));
                    status.setText(message);
                }));
                DataImporter.writeDvdRoot(configFile(this), new File(dataDir, DataImporter.DATA_FOLDER).getAbsolutePath());
                result = null;
                ok = true;
            } catch (IOException e) {
                result = e.getMessage() != null ? e.getMessage() : "No se pudo importar el zip.";
            }
            final String message = result;
            final boolean success = ok;
            ui.post(() -> finishImport(success, message));
        }, "wario-import").start();
    }

    private void finishImport(boolean ok, String message) {
        importing = false;
        pick.setEnabled(true);
        progress.setVisibility(View.GONE);
        if (ok) {
            status.setText("Datos importados. Arrancando el juego…");
            startGame();
        } else {
            status.setText(message + "\n\nElige otro zip para intentarlo de nuevo.");
        }
    }

    private long sizeOf(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (RuntimeException ignored) {
            // Some providers do not report a size; the progress bar then stays at 0 until done.
        }
        return -1;
    }

    private void startGame() {
        Intent game = new Intent(this, GameActivity.class);
        startActivity(game);
        finish();
    }
}
