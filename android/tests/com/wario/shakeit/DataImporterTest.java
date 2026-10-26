package com.wario.shakeit;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

public class DataImporterTest {
    static int failures = 0, passes = 0;

    static void check(boolean cond, String name) {
        if (cond) { passes++; System.out.println("  OK   " + name); }
        else { failures++; System.out.println("  FAIL " + name); }
    }

    static String sha(byte[] b) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte x : MessageDigest.getInstance("SHA-256").digest(b)) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    static byte[] boot(String id, int rev) {
        byte[] b = new byte[0x440];
        byte[] idb = id.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(idb, 0, b, 0, 6);
        b[7] = (byte) rev;
        return b;
    }

    /** Builds a zip from name->bytes entries. */
    static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    static Map<String, byte[]> gameEntries(String prefix, String id, int rev, byte[] dol) {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put(prefix + "files/", new byte[0]);
        m.put(prefix + "files/contents/a.arc", "archive".getBytes());
        m.put(prefix + "sys/fst.bin", new byte[64]);
        m.put(prefix + "sys/boot.bin", boot(id, rev));
        m.put(prefix + "sys/main.dol", dol);
        return m;
    }

    static File tmp(String name) throws IOException {
        Path p = Files.createTempDirectory("wtest-" + name);
        return p.toFile();
    }

    static String importErr(byte[] zipBytes, File dir, String sha) {
        try {
            DataImporter.importZip(new ByteArrayInputStream(zipBytes), zipBytes.length, dir, sha, null);
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    public static void main(String[] args) throws Exception {
        byte[] dol = "fake main dol contents".getBytes();
        String sha = sha(dol);

        System.out.println("[1] zip with DATA/ prefix imports");
        File d1 = tmp("prefix");
        File out1 = DataImporter.importZip(new ByteArrayInputStream(zip(gameEntries("DATA/", "RWLE01", 0, dol))),
                -1, d1, sha, null);
        check(new File(out1, "sys/main.dol").isFile(), "sys/main.dol landed at DATA/sys/main.dol");
        check(new File(out1, "files/contents/a.arc").isFile(), "files/ extracted");
        check(DataImporter.problem(out1, sha) == null, "problem() is null for imported folder");
        check(!new File(d1, DataImporter.STAGING_FOLDER).exists(), "staging folder cleaned up");
        check(out1.getName().equals("DATA"), "final folder is named DATA");

        System.out.println("[2] zip with files at its root imports");
        File d2 = tmp("root");
        File out2 = DataImporter.importZip(new ByteArrayInputStream(zip(gameEntries("", "RWLE01", 0, dol))),
                -1, d2, sha, null);
        check(new File(out2, "sys/boot.bin").isFile(), "root-level zip imports");

        System.out.println("[3] re-import replaces the old copy");
        Map<String, byte[]> second = gameEntries("", "RWLE01", 0, dol);
        second.put("files/new.txt", "new".getBytes());
        DataImporter.importZip(new ByteArrayInputStream(zip(second)), -1, d2, sha, null);
        check(new File(out2, "files/new.txt").isFile(), "new copy replaces old DATA");

        System.out.println("[4] rejects bad games, leaves nothing behind");
        String e;
        e = importErr(zip(gameEntries("DATA/", "RMCE01", 0, dol)), tmp("id"), sha);
        check(e != null && e.contains("RMCE01"), "wrong game id rejected: " + e);
        e = importErr(zip(gameEntries("DATA/", "RWLE01", 1, dol)), tmp("rev"), sha);
        check(e != null && e.contains("revisión 1"), "wrong revision rejected: " + e);
        e = importErr(zip(gameEntries("DATA/", "RWLE01", 0, "other".getBytes())), tmp("sha"), sha);
        check(e != null && e.contains("SHA-256"), "wrong main.dol rejected: " + e);
        Map<String, byte[]> nodol = gameEntries("DATA/", "RWLE01", 0, dol);
        nodol.remove("DATA/sys/main.dol");
        e = importErr(zip(nodol), tmp("nodol"), sha);
        check(e != null && e.contains("main.dol"), "missing main.dol rejected: " + e);
        Map<String, byte[]> nofiles = new LinkedHashMap<>(gameEntries("DATA/", "RWLE01", 0, dol));
        nofiles.keySet().removeIf(k -> k.startsWith("DATA/files/"));
        e = importErr(zip(nofiles), tmp("nofiles"), sha);
        check(e != null && e.contains("files/"), "missing files/ rejected: " + e);
        File dirBad = tmp("bad");
        importErr(zip(gameEntries("DATA/", "RMCE01", 0, dol)), dirBad, sha);
        check(!new File(dirBad, "DATA").exists() && !new File(dirBad, DataImporter.STAGING_FOLDER).exists(),
                "nothing left on disk after a rejected import");

        System.out.println("[5] zip-slip entries cannot escape");
        File d5 = tmp("slip");
        Map<String, byte[]> slip = gameEntries("DATA/", "RWLE01", 0, dol);
        slip.put("DATA/../../evil.txt", "x".getBytes());
        slip.put("/abs/evil2.txt", "y".getBytes());
        DataImporter.importZip(new ByteArrayInputStream(zip(slip)), -1, d5, sha, null);
        check(!new File(d5.getParentFile(), "evil.txt").exists() && !new File(d5, "../evil.txt").exists(),
                "../ entry not written outside the folder");
        check(!new File("/abs/evil2.txt").exists(), "absolute entry not written to the root");
        check(DataImporter.problem(new File(d5, "DATA"), sha) == null, "valid game still imports with junk entries");

        System.out.println("[6] Config.toml read/write");
        File cfgDir = tmp("cfg");
        File cfg = new File(cfgDir, "Config.toml");
        check(DataImporter.readDvdRoot(cfg) == null, "no config file -> no dvd_root");
        String weird = "/storage/emulated/0/Android/data/com.wario.shakeit/files/DATA";
        DataImporter.writeDvdRoot(cfg, weird);
        check(weird.equals(DataImporter.readDvdRoot(cfg)), "creates config with dvd_root");
        String text = new String(Files.readAllBytes(cfg.toPath()), StandardCharsets.UTF_8);
        check(text.contains("[paths]") && text.contains("dvd_root = \"" + weird + "\""), "file contents look right:\n" + text);

        String existing = "# comment\n[video]\nwindow_width = 800\n\n[paths]\n# dvd_root = \"D:\\\\old\"\nnand_root = \"x\"\n\n[audio]\nmuted = false\n";
        Files.write(cfg.toPath(), existing.getBytes(StandardCharsets.UTF_8));
        String tricky = "/data/a \"b\" \\c/DATA";
        DataImporter.writeDvdRoot(cfg, tricky);
        check(tricky.equals(DataImporter.readDvdRoot(cfg)), "quotes and backslashes round-trip");
        String after = new String(Files.readAllBytes(cfg.toPath()), StandardCharsets.UTF_8);
        check(after.contains("window_width = 800") && after.contains("nand_root = \"x\"") && after.contains("muted = false"),
                "other settings kept");
        check(after.contains("# dvd_root = \"D:"), "commented example line kept untouched");
        check(after.indexOf("[paths]") < after.indexOf("nand_root"), "dvd_root placed in [paths]");
        System.out.println("     file now:\n" + after.replaceAll("(?m)^", "       | "));

        File cfg2 = new File(tmp("cfg2"), "Config.toml");
        Files.write(cfg2.toPath(), "[video]\nx = 1\n".getBytes(StandardCharsets.UTF_8));
        DataImporter.writeDvdRoot(cfg2, "/a/DATA");
        check("/a/DATA".equals(DataImporter.readDvdRoot(cfg2)), "adds a [paths] section when missing");

        System.out.println();
        System.out.println(passes + " passed, " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }
}
