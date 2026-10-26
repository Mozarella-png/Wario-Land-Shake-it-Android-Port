package com.wario.shakeit;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

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
