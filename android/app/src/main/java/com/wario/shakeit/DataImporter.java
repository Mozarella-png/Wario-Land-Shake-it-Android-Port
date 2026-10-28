package com.wario.shakeit;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports the player's own copy of Wario Land: Shake It! (a .zip of the DATA folder) for the
 * Android port. Plain Java on purpose: no Android classes, so it can be tested on a desktop JDK.
 *
 * What it does:
 *  - unpacks the zip into {dataDir}/DATA (the folder that holds files/ and sys/), accepting the zip
 *    either at its root or inside one folder (such as DATA/);
 *  - checks the game the same way the CI does (sys/main.dol SHA-256) plus the boot.bin header
 *    (RWLE01, revision 0), since the APK's code is translated from that exact main.dol;
 *  - writes [paths] dvd_root into Config.toml, which is where the runtime looks for the game.
 */
public final class DataImporter {

    public static final String GAME_ID = "RWLE01";
    public static final int GAME_REVISION = 0;
    public static final String MAIN_DOL_SHA256 =
            "053be60dde84c8042f45ed2f4ba80c17efb2d20fedf95a414c2c7e37d336912a";

    public static final String DATA_FOLDER = "DATA";
    public static final String CONFIG_FILE = "Config.toml";
    static final String STAGING_FOLDER = "DATA.importing";

    /** Receives progress from 0 to 1 (of the zip file) and a short message. */
    public interface Listener {
        void onProgress(float fraction, String message);
    }

    private DataImporter() {
    }

    // ---- Validation ---------------------------------------------------------------------

    /** The game that the port was built for. Null when {@code root} holds it; otherwise why not. */
    public static String problem(File root) {
        return problem(root, MAIN_DOL_SHA256);
    }

    /** As {@link #problem(File)}, with another main.dol checksum (tests use a synthetic one). */
    static String problem(File root, String dolSha256) {
        if (!new File(root, "files").isDirectory()) {
            return "The files/ folder is missing from the zip.";
        }
        if (!new File(root, "sys/fst.bin").isFile()) {
            return "sys/fst.bin is missing from the zip.";
        }
        File boot = new File(root, "sys/boot.bin");
        if (!boot.isFile()) {
            return "sys/boot.bin is missing from the zip.";
        }
        byte[] header;
        try {
            header = readFirst(boot, 8);
        } catch (IOException e) {
            return "Could not read sys/boot.bin.";
        }
        if (header.length < 8) {
            return "sys/boot.bin is incomplete.";
        }
        String id = new String(header, 0, 6, StandardCharsets.US_ASCII);
        int revision = header[7] & 0xff;
        if (!GAME_ID.equals(id)) {
            return "This disc is " + id + ", but this port only works with " + GAME_ID + ".";
        }
        if (revision != GAME_REVISION) {
            return "This is revision " + revision + " of the game; the port only works with revision "
                    + GAME_REVISION + ".";
        }
        File dol = new File(root, "sys/main.dol");
        if (!dol.isFile()) {
            return "sys/main.dol is missing from the zip.";
        }
        try {
            if (!sha256(dol).equalsIgnoreCase(dolSha256)) {
                return "sys/main.dol is not the one for this port (the SHA-256 does not match).";
            }
        } catch (IOException e) {
            return "Could not read sys/main.dol.";
        }
        return null;
    }

    // ---- Import -------------------------------------------------------------------------

    /**
     * Unpacks {@code zip} into {@code dataDir/DATA}, replacing any previous copy only after the new
     * one checks out. Returns the new DATA folder, or throws IOException with a message for the
     * player.
     */
    public static File importZip(InputStream zip, long zipSize, File dataDir, Listener listener)
            throws IOException {
        return importZip(zip, zipSize, dataDir, MAIN_DOL_SHA256, listener);
    }

    static File importZip(InputStream zip, long zipSize, File dataDir, String dolSha256,
                          Listener listener) throws IOException {
        if (!dataDir.isDirectory() && !dataDir.mkdirs()) {
            throw new IOException("Could not create " + dataDir);
        }
        File staging = new File(dataDir, STAGING_FOLDER);
        deleteRecursively(staging);
        if (!staging.mkdirs()) {
            throw new IOException("Could not create " + staging);
        }

        CountingInputStream counted = new CountingInputStream(zip, zipSize, listener);
        try (ZipInputStream in = new ZipInputStream(counted)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                File target = safeTarget(staging, entry.getName());
                if (target == null) {
                    continue; // directory entry with an odd name, or one trying to escape
                }
                if (entry.isDirectory()) {
                    if (!target.isDirectory() && !target.mkdirs()) {
                        throw new IOException("Could not create " + target.getName());
                    }
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("Could not create " + parent.getName());
                }
                try (OutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                    }
                }
            }
        } catch (IOException e) {
            deleteRecursively(staging);
            throw new IOException("Could not unpack the zip: " + e.getMessage(), e);
        }

        report(listener, 1f, "Checking the game…");
        File root = findRoot(staging);
        if (root == null) {
            deleteRecursively(staging);
            throw new IOException("sys/main.dol was not found in the zip. It must contain the "
                    + "DATA folder of your disc (sys/ and files/).");
        }
        String problem = problem(root, dolSha256);
        if (problem != null) {
            deleteRecursively(staging);
            throw new IOException(problem);
        }

        File finalDir = new File(dataDir, DATA_FOLDER);
        deleteRecursively(finalDir);
        if (!root.renameTo(finalDir)) {
            deleteRecursively(staging);
            throw new IOException("Could not move the data to " + finalDir);
        }
        deleteRecursively(staging);
        return finalDir;
    }

    /** The folder that holds sys/main.dol: the staging folder itself or one or two levels down. */
    static File findRoot(File staging) {
        List<File> level = new ArrayList<>();
        level.add(staging);
        for (int depth = 0; depth < 3 && !level.isEmpty(); depth++) {
            List<File> next = new ArrayList<>();
            for (File dir : level) {
                if (new File(dir, "sys/main.dol").isFile()) {
                    return dir;
                }
                File[] children = dir.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (child.isDirectory()) {
                            next.add(child);
                        }
                    }
                }
            }
            level = next;
        }
        return null;
    }

    /** Where a zip entry goes inside {@code base}; null for entries that would escape it. */
    static File safeTarget(File base, String name) throws IOException {
        String cleaned = name.replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isEmpty()) {
            return null;
        }
        for (String part : cleaned.split("/")) {
            if (part.equals("..")) {
                return null;
            }
        }
        File target = new File(base, cleaned);
        String basePath = base.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(basePath)) {
            return null;
        }
        return target;
    }

    // ---- Config.toml --------------------------------------------------------------------

    /** The dvd_root stored in Config.toml, or null when there is none. */
    public static String readDvdRoot(File config) throws IOException {
        if (!config.isFile()) {
            return null;
        }
        boolean inPaths = false;
        for (String line : readLines(config)) {
            String t = line.trim();
            if (t.startsWith("[") && !t.startsWith("#")) {
                inPaths = t.equals("[paths]");
                continue;
            }
            if (inPaths && isKeyLine(t, "dvd_root")) {
                String value = t.substring(t.indexOf('=') + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    return value.substring(1, value.length() - 1)
                            .replace("\\\"", "\"").replace("\\\\", "\\");
                }
                return value;
            }
        }
        return null;
    }

    /** Sets [paths] dvd_root, keeping every other line of Config.toml as it is. */
    public static void writeDvdRoot(File config, String path) throws IOException {
        List<String> lines = config.isFile() ? readLines(config) : new ArrayList<String>();
        String keyLine = "dvd_root = \"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";

        int header = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals("[paths]")) {
                header = i;
                break;
            }
        }
        if (header < 0) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).trim().isEmpty()) {
                lines.add("");
            }
            lines.add("[paths]");
            lines.add(keyLine);
        } else {
            int key = -1;
            for (int i = header + 1; i < lines.size(); i++) {
                String t = lines.get(i).trim();
                if (t.startsWith("[")) {
                    break;
                }
                if (isKeyLine(t, "dvd_root")) {
                    key = i;
                    break;
                }
            }
            if (key >= 0) {
                lines.set(key, keyLine);
            } else {
                lines.add(header + 1, keyLine);
            }
        }

        File tmp = new File(config.getParentFile(), config.getName() + ".tmp");
        try (OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                out.write(line);
                out.write("\n");
            }
        }
        if (config.exists() && !config.delete()) {
            throw new IOException("Could not replace " + config.getName());
        }
        if (!tmp.renameTo(config)) {
            throw new IOException("Could not save " + config.getName());
        }
    }

    /** Reads [section] key from Config.toml as a raw value (e.g. "true"), or null when it is not set. */
    public static String readSetting(File config, String section, String key) throws IOException {
        if (!config.isFile()) {
            return null;
        }
        boolean inSection = false;
        for (String line : readLines(config)) {
            String t = line.trim();
            if (t.startsWith("[")) {
                inSection = t.equals("[" + section + "]");
                continue;
            }
            if (inSection && isKeyLine(t, key)) {
                String value = t.substring(t.indexOf('=') + 1).trim();
                int hash = value.indexOf('#');
                return (hash >= 0 ? value.substring(0, hash) : value).trim();
            }
        }
        return null;
    }

    /** Sets [section] key = rawValue in Config.toml, keeping every other line as it is. */
    public static void writeSetting(File config, String section, String key, String rawValue) throws IOException {
        List<String> lines = config.isFile() ? readLines(config) : new ArrayList<String>();
        String keyLine = key + " = " + rawValue;

        int header = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals("[" + section + "]")) {
                header = i;
                break;
            }
        }
        if (header < 0) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).trim().isEmpty()) {
                lines.add("");
            }
            lines.add("[" + section + "]");
            lines.add(keyLine);
        } else {
            int found = -1;
            for (int i = header + 1; i < lines.size(); i++) {
                String t = lines.get(i).trim();
                if (t.startsWith("[")) {
                    break;
                }
                if (isKeyLine(t, key)) {
                    found = i;
                    break;
                }
            }
            if (found >= 0) {
                lines.set(found, keyLine);
            } else {
                lines.add(header + 1, keyLine);
            }
        }

        File tmp = new File(config.getParentFile(), config.getName() + ".tmp");
        try (OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                out.write(line);
                out.write("\n");
            }
        }
        if (config.exists() && !config.delete()) {
            throw new IOException("Could not replace " + config.getName());
        }
        if (!tmp.renameTo(config)) {
            throw new IOException("Could not save " + config.getName());
        }
    }

    // ---- Helpers ------------------------------------------------------------------------

    private static boolean isKeyLine(String trimmed, String key) {
        if (trimmed.startsWith("#") || !trimmed.startsWith(key)) {
            return false;
        }
        String rest = trimmed.substring(key.length()).trim();
        return rest.startsWith("=");
    }

    private static List<String> readLines(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static byte[] readFirst(File file, int count) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(count);
            int b;
            while (out.size() < count && (b = in.read()) != -1) {
                out.write(b);
            }
            return out.toByteArray();
        }
    }

    static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, n);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static void deleteRecursively(File file) {
        if (!file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    private static void report(Listener listener, float fraction, String message) {
        if (listener != null) {
            listener.onProgress(fraction, message);
        }
    }

    /** Counts the compressed bytes read so the UI can show how far the zip has got. */
    private static final class CountingInputStream extends InputStream {
        private final InputStream in;
        private final long total;
        private final Listener listener;
        private long read;
        private long lastReport;

        CountingInputStream(InputStream in, long total, Listener listener) {
            this.in = in;
            this.total = total;
            this.listener = listener;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b != -1) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) {
            read += n;
            if (total > 0 && read - lastReport >= 1024 * 1024) {
                lastReport = read;
                report(listener, Math.min(1f, read / (float) total) * 0.99f,
                        "Unpacking… " + (int) (Math.min(1f, read / (float) total) * 100) + " %");
            }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
