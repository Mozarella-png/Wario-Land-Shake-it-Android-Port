package com.wario.shakeit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Start menu of the Android port. Same pages and the same Config.toml keys as the PC launcher
 * (launcher/MainForm.cs): Home, Display, Graphics, Audio, Game and Advanced. Controls live in the
 * in-game touch remote (TouchOverlay), and the data import stays in SetupActivity.
 */
public class LauncherActivity extends Activity {

    private static final int BACK = 0xFF180E22;
    private static final int SIDE = 0xFF100918;
    private static final int SIDE_SELECTED = 0xFF26163A;
    private static final int PURPLE = 0xFF7634AA;
    private static final int GOLD = 0xFFF6C426;
    private static final int TEXT = 0xFFF0EAF8;
    private static final int MUTED = 0xFFA092B6;
    private static final int FIELD_BORDER = 0xFF543870;
    private static final int GOOD = 0xFF78D678;
    private static final int BAD = 0xFFF06060;

    private static final boolean ES = Locale.getDefault().getLanguage().equals("es");

    static String t(String es, String en) {
        return ES ? es : en;
    }

    private interface Pick {
        void on(int index);
    }

    private LinearLayout page;
    private ScrollView scroll;
    private final TextView[] navItems = new TextView[6];
    private int selected = 0;

    // Same order as the PC launcher's side menu.
    private String[] pageTitles() {
        return new String[] {
            t("Inicio", "Home"), t("Pantalla", "Display"), t("Gráficos", "Graphics"),
            t("Audio", "Audio"), t("Juego", "Game"), t("Avanzado", "Advanced"),
        };
    }

    // ---- Lifecycle ---------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SetupActivity.dataDir(this).mkdirs();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BACK);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setBackgroundColor(SIDE);
        nav.setPadding(dp(14), dp(20), dp(14), dp(20));

        TextView brand = text("WARIO LAND\nSHAKE IT!", 17, GOLD);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        nav.addView(brand, wrap(0, 0, 0, dp(22)));

        String[] titles = pageTitles();
        for (int i = 0; i < titles.length; i++) {
            final int index = i;
            TextView item = text(titles[i], 15, TEXT);
            item.setPadding(dp(14), dp(10), dp(10), dp(10));
            item.setOnClickListener(v -> show(index));
            navItems[i] = item;
            nav.addView(item, wrap(0, 0, 0, dp(4)));
        }

        View spacer = new View(this);
        nav.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        TextView play = text(t("JUGAR", "PLAY"), 18, 0xFF28103C);
        play.setTypeface(Typeface.DEFAULT_BOLD);
        play.setGravity(Gravity.CENTER);
        play.setBackgroundColor(GOLD);
        play.setPadding(dp(10), dp(14), dp(10), dp(14));
        play.setOnClickListener(v -> play());
        nav.addView(play, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(nav, new LinearLayout.LayoutParams(dp(230), LinearLayout.LayoutParams.MATCH_PARENT));

        scroll = new ScrollView(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(32), dp(28), dp(32), dp(28));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        setContentView(root);
        show(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        show(selected);  // the game and the data import can change the settings or the game folder
    }

    private void play() {
        Intent next = SetupActivity.dataReady(this)
                ? new Intent(this, GameActivity.class)
                : new Intent(this, SetupActivity.class);
        startActivity(next);
    }

    // ---- Pages -------------------------------------------------------------------------

    private void show(int index) {
        selected = index;
        for (int i = 0; i < navItems.length; i++) {
            navItems[i].setBackgroundColor(i == index ? SIDE_SELECTED : SIDE);
            navItems[i].setTextColor(i == index ? GOLD : TEXT);
        }
        page.removeAllViews();
        switch (index) {
            case 0: buildHome(); break;
            case 1: buildDisplay(); break;
            case 2: buildGraphics(); break;
            case 3: buildAudio(); break;
            case 4: buildGame(); break;
            default: buildAdvanced(); break;
        }
        scroll.scrollTo(0, 0);
    }

    private void buildHome() {
        ImageView banner = new ImageView(this);
        banner.setImageResource(R.drawable.launcher_banner);
        banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
        page.addView(banner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(200)));
        page.addView(text("RWLE01 · " + t("recompilación nativa para Android", "native Android recompilation")
                + "\nby Santiago109 · " + t("en pruebas", "in testing"), 12, MUTED), wrap(0, dp(8), 0, 0));

        section(t("Ajustes rápidos", "Quick settings"));
        row(t("Relación de aspecto", "Aspect ratio"), aspectPicker());
        row(t("Resolución interna", "Render resolution"), scalePicker());
        widescreenCheck();

        section(t("Estado", "Status"));
        status();
    }

    private void buildDisplay() {
        header(t("Pantalla", "Display"), t("Cómo se ve el juego en tu pantalla.", "How the game appears on your screen."));
        section(t("Imagen", "Picture"));
        row(t("Relación de aspecto", "Aspect ratio"), aspectPicker());
        widescreenCheck();
        check(t("Sincronización vertical (VSync)", "Vertical sync (VSync)"), "video", "vsync", true,
                t("Desactivada puede haber cortes en la imagen.", "Off can show tearing."));
        section(t("Superposición", "Overlay"));
        check(t("Mostrar FPS", "Show FPS"), "video", "show_fps", false, null);
        check(t("Gráfica de tiempo por cuadro", "Frame time graph"), "video", "frame_graph", false,
                t("Útil para ver tirones.", "Useful to spot stutter."));
    }

    private void buildGraphics() {
        header(t("Gráficos", "Graphics"), t("Resolución y compatibilidad.", "Resolution and compatibility."));
        section(t("Resolución", "Resolution"));
        row(t("Resolución interna", "Render resolution"), scalePicker());
        section(t("Compatibilidad", "Compatibility"));
        check(t("No esperar a los shaders nuevos", "Don't wait for new shaders"), "video", "skip_unready_pipelines", true,
                t("Menos tirones.", "Fewer stutters."));
        check(t("Filtro de copia original", "Original copy filter"), "video", "disable_copy_filter", false,
                t("Imagen más suave, como en la Wii.", "Softer picture, like on the Wii."));
        section(t("Texturas", "Textures"));
        check(t("Usar texturas de reemplazo", "Use replacement textures"), "video", "texture_replacements", true, null);
    }

    private void buildAudio() {
        header(t("Audio", "Audio"), t("Volumen y mezcla.", "Volume and mixing."));
        section(t("Volumen", "Volume"));
        int initial = (int) Math.round(getDouble("audio", "volume", 1.0) * 100);
        SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(Math.max(0, Math.min(100, initial)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) { }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) {
                put("audio", "volume", String.format(Locale.US, "%.2f", s.getProgress() / 100.0));
            }
        });
        row(t("Volumen general", "Master volume"), bar);
        check(t("Silenciar", "Mute"), "audio", "muted", false, null);
        section(t("Rendimiento", "Performance"));
        check(t("Mezclar el audio en su propio hilo", "Mix audio on its own thread"), "audio", "mix_worker", true,
                t("Aprovecha otro núcleo del procesador; apágalo solo si el sonido falla.",
                  "Uses another CPU core; turn off only if sound breaks."));
    }

    private void buildGame() {
        header(t("Juego", "Game"), t("Tu copia del juego e idioma.", "Your game copy and language."));
        section(t("Datos del juego", "Game data"));
        status();
        TextView change = flat(t("Cambiar datos del juego…", "Change game data…"));
        change.setOnClickListener(v -> {
            Intent setup = new Intent(this, SetupActivity.class);
            setup.putExtra(SetupActivity.EXTRA_CHOOSE, true);
            startActivity(setup);
        });
        page.addView(change, wrap(0, dp(10), 0, 0));

        section(t("Idioma", "Language"));
        final String[] codes = {"en", "es", "fr"};
        String[] labels = {"English", "Español", "Français"};
        String current = getString("game", "language", "en");
        row(t("Idioma del juego", "Game language"), picker(labels, indexOf(codes, current), i ->
                putString("game", "language", codes[i])));
    }

    private void buildAdvanced() {
        header(t("Avanzado", "Advanced"), t("Registros y datos de depuración.", "Logs and debugging data."));

        File crash = WarioApplication.crashFile(this);
        if (crash.isFile()) {
            section(t("Último error", "Last error"));
            page.addView(selectable(readText(crash)), wrap(0, dp(6), 0, dp(10)));
            TextView delete = flat(t("Borrar el error", "Clear the error"));
            delete.setOnClickListener(v -> {
                crash.delete();
                show(selected);
            });
            page.addView(delete);
        }

        section(t("Último registro del juego", "Latest game log"));
        File[] runs = new File(SetupActivity.dataDir(this), "Logs").listFiles();
        File newest = null;
        if (runs != null) {
            for (File f : runs) {
                if (f.isFile() && (newest == null || f.lastModified() > newest.lastModified())) {
                    newest = f;
                }
            }
        }
        if (newest == null) {
            page.addView(text(t("Todavía no hay registros.", "No logs yet."), 13, MUTED));
        } else {
            String log = readText(newest);
            if (log.length() > 6000) {
                log = "…\n" + log.substring(log.length() - 6000);
            }
            page.addView(selectable(log), wrap(0, dp(6), 0, 0));
        }

        section(t("Acerca de", "About"));
        page.addView(text("WarioRecomp Android · port de Santiago109 para Wario Land: Shake It!", 13, MUTED));
    }

    // ---- Shared controls ---------------------------------------------------------------

    private void widescreenCheck() {
        check(t("★ 16:9 real en los niveles (experimental)", "★ True 16:9 in levels (experimental)"),
                "video", "full_widescreen", false,
                t("Quita los paneles laterales y el nivel llena toda la pantalla, algo que el juego original nunca permitió. Se aplica al iniciar el juego.",
                  "Removes the side panels so the level fills the whole screen, something the original game never allowed. Applies when the game starts."));
    }

    private void status() {
        boolean ok = SetupActivity.dataReady(this);
        page.addView(text(ok
                ? "✔ " + t("Juego encontrado.", "Game found.")
                : "✖ " + t("No hay datos del juego. Elige el zip con el botón JUGAR o en Juego.",
                           "No game data. Choose the zip with PLAY or on the Game page."),
                14, ok ? GOOD : BAD), wrap(0, dp(6), 0, dp(6)));
    }

    private View aspectPicker() {
        // 0 = 4:3, 1 = 16:9, 2 = Fill (default on Android), 3 = Cinematic. Same as PC aspect_mode.
        String[] labels = {
            t("4:3 (original)", "4:3 (original)"), "16:9",
            t("Llenar la pantalla", "Fill the screen"), t("Cinemático 2.39:1", "Cinematic 2.39:1"),
        };
        return picker(labels, clampIndex(getInt("video", "aspect_mode", 2), labels.length), i -> {
            put("video", "aspect_mode", String.valueOf(i));
            put("video", "widescreen", String.valueOf(i != 0));
        });
    }

    private View scalePicker() {
        // 0 = match the screen (Android default), others are 480 lines per 1x like the PC launcher.
        final double[] values = {0.0, 1.0, 1.5, 2.25, 3.0};
        String[] labels = {
            t("Automática (según la pantalla)", "Automatic (follows the screen)"),
            "480p (" + t("original", "original") + ")", "720p", "1080p (Full HD)", "1440p (2K)",
        };
        double current = getDouble("video", "resolution_multiplier", 0.0);
        int index = 0;
        for (int i = 0; i < values.length; i++) {
            if (Math.abs(values[i] - current) < 0.001) {
                index = i;
            }
        }
        return picker(labels, index, i -> put("video", "resolution_multiplier", String.valueOf(values[i])));
    }

    private void section(String name) {
        TextView label = text(name.toUpperCase(Locale.ROOT), 13, GOLD);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(label, wrap(0, dp(22), 0, dp(4)));
        View line = new View(this);
        line.setBackgroundColor(FIELD_BORDER);
        page.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private void header(String title, String subtitle) {
        TextView t = text(title.toUpperCase(Locale.ROOT), 22, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(t, wrap(0, 0, 0, dp(2)));
        page.addView(text(subtitle, 14, MUTED), wrap(0, 0, 0, dp(8)));
    }

    private void row(String label, View control) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(6), 0, dp(6));
        TextView name = text(label, 15, TEXT);
        line.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        line.addView(control, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        page.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void check(String label, final String section, final String key, boolean def, String desc) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextColor(TEXT);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        box.setChecked(getBool(section, key, def));
        box.setOnCheckedChangeListener((b, checked) -> put(section, key, String.valueOf(checked)));
        page.addView(box, wrap(0, dp(6), 0, 0));
        if (desc != null) {
            page.addView(text(desc, 12, MUTED), wrap(dp(32), 0, 0, dp(4)));
        }
    }

    private TextView picker(final String[] labels, int current, final Pick pick) {
        final TextView button = flat(labels[clampIndex(current, labels.length)]);
        button.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setItems(labels, (dialog, which) -> {
                    button.setText(labels[which]);
                    pick.on(which);
                })
                .show());
        return button;
    }

    // ---- Config.toml (same file and keys as the PC launcher, edited through DataImporter) --

    private File config() {
        return SetupActivity.configFile(this);
    }

    private String raw(String section, String key) {
        try {
            return DataImporter.readSetting(config(), section, key);
        } catch (IOException e) {
            return null;
        }
    }

    private boolean getBool(String section, String key, boolean def) {
        String v = raw(section, key);
        return v == null ? def : v.equals("true");
    }

    private int getInt(String section, String key, int def) {
        try {
            return Integer.parseInt(raw(section, key));
        } catch (RuntimeException e) {
            return def;
        }
    }

    private double getDouble(String section, String key, double def) {
        try {
            return Double.parseDouble(raw(section, key));
        } catch (RuntimeException e) {
            return def;
        }
    }

    private String getString(String section, String key, String def) {
        String v = raw(section, key);
        if (v == null) {
            return def;
        }
        v = v.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    private void put(String section, String key, String rawValue) {
        try {
            SetupActivity.dataDir(this).mkdirs();
            DataImporter.writeSetting(config(), section, key, rawValue);
        } catch (IOException e) {
            android.widget.Toast.makeText(this,
                    t("No se pudo guardar Config.toml", "Could not save Config.toml"), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void putString(String section, String key, String value) {
        put(section, key, "\"" + value + "\"");
    }

    // ---- Small helpers -----------------------------------------------------------------

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }

    private static int clampIndex(int index, int count) {
        return Math.max(0, Math.min(count - 1, index));
    }

    private static String readText(File file) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return t("(no se pudo leer el archivo)", "(could not read the file)");
        }
    }

    private TextView text(String value, float sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    private TextView selectable(String value) {
        TextView view = text(value, 11, 0xFFFFAAAA);
        view.setTextIsSelectable(true);
        return view;
    }

    private TextView flat(String value) {
        TextView view = text(value, 15, TEXT);
        view.setPadding(dp(14), dp(8), dp(14), dp(8));
        view.setBackgroundColor(PURPLE);
        return view;
    }

    private LinearLayout.LayoutParams wrap(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(left, top, right, bottom);
        return lp;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
