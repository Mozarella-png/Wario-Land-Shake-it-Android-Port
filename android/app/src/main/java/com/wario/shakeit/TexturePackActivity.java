package com.wario.shakeit;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports an HD texture pack (Dolphin-style tex1_&lt;W&gt;x&lt;H&gt;_&lt;hash&gt;[_&lt;tlut&gt;]_&lt;format&gt;.png|.dds) into the
 * texture_replacements folder the runtime reads at startup. Only files whose names match that pattern
 * are kept; folders inside the zip are flattened. A new pack replaces the previous one.
 */
public class TexturePackActivity extends Activity {
    static final String TEXTURE_DIR = "texture_replacements";
    private static final int REQUEST_PICK_ZIP = 2;
    private static final Pattern TEXTURE_NAME = Pattern.compile(
            "tex1_\\d+x\\d+_[0-9a-f]+(_[0-9a-f]+)?_\\d+\\.(png|dds)", Pattern.CASE_INSENSITIVE);

    static File textureDir(Context context) {
        return new File(SetupActivity.dataDir(context), TEXTURE_DIR);
    }

    static int installedCount(Context context) {
        File[] files = textureDir(context).listFiles(f -> TEXTURE_NAME.matcher(f.getName()).matches());
        return files == null ? 0 : files.length;
    }

    static void remove(Context context) {
        File[] files = textureDir(context).listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_PICK_ZIP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_ZIP || resultCode != RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }
        String message;
        try {
            int count = importPack(data.getData());
            message = count == 0
                    ? t("El zip no tiene texturas tex1_ válidas.", "The zip has no valid tex1_ textures.")
                    : count + " " + t("texturas importadas.", "textures imported.");
        } catch (IOException e) {
            message = t("No se pudo importar el pack.", "Could not import the pack.");
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    private int importPack(Uri uri) throws IOException {
        File staging = new File(textureDir(this).getParentFile(), TEXTURE_DIR + ".tmp");
        deleteTree(staging);
        if (!staging.mkdirs()) {
            throw new IOException("cannot create " + staging);
        }
        int count = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream raw = getContentResolver().openInputStream(uri);
             ZipInputStream in = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                // Keep only the file name: no folders, so nothing can be written outside staging.
                String name = new File(entry.getName()).getName();
                if (!TEXTURE_NAME.matcher(name).matches()) {
                    continue;
                }
                try (OutputStream out = new FileOutputStream(new File(staging, name))) {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                }
                count++;
            }
        } catch (IOException e) {
            deleteTree(staging);
            throw e;
        }
        if (count == 0) {
            deleteTree(staging);
            return 0;
        }
        // Swap in the new pack only once it is complete, so a failed import keeps the old one.
        remove(this);
        File dir = textureDir(this);
        File[] files = staging.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.renameTo(new File(dir, f.getName()))) {
                    throw new IOException("cannot move " + f.getName());
                }
            }
        }
        deleteTree(staging);
        return count;
    }

    private static void deleteTree(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteTree(child);
                }
            }
        }
        file.delete();
    }

    private String t(String es, String en) {
        return Locale.getDefault().getLanguage().equals("es") ? es : en;
    }
}
