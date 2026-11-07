package ai.sol.hermes;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.util.Log;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Universal archive unpacker supporting both .7z (SevenZ) and .tar.gz (TarGz).
 * Restores POSIX symbolic links and file execution permissions for Linux rootfs.
 * Uses native lib7za.so / libbusybox.so for 100% fidelity symlink restoration with Java fallback.
 */
public class ArchiveExtractor {
    private static final String TAG = "ArchiveExtractor";

    public interface ProgressCallback {
        void onProgress(int count, String currentEntryName);
    }

    public static int extract(Context context, File archive, File destDir, ProgressCallback callback) throws Exception {
        String name = archive.getName().toLowerCase();
        if (name.endsWith(".7z")) {
            return extract7z(context, archive, destDir, callback);
        } else {
            return extractTarGz(context, archive, destDir, callback);
        }
    }

    public static int extract(File archive, File destDir, ProgressCallback callback) throws Exception {
        return extract(null, archive, destDir, callback);
    }

    public static int extract7z(Context context, File archive, File destDir, ProgressCallback callback) throws Exception {
        if (!destDir.exists()) destDir.mkdirs();

        // 1. Try Native 7za binary (restores true POSIX symlinks and file attributes)
        if (context != null) {
            try {
                String sevenZaPath = InstallationManager.findOrExtract7zaBinary(context);
                File sevenZaFile = new File(sevenZaPath);
                if (sevenZaFile.exists() && (sevenZaFile.canExecute() || sevenZaFile.setExecutable(true, false))) {
                    Log.i(TAG, "Executing native 7z extraction using: " + sevenZaPath);
                    return extract7zNative(sevenZaPath, archive, destDir, callback);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Native 7z extraction failed, falling back to Java: " + t.getMessage(), t);
            }
        }

        // 2. Java SevenZFile fallback
        return extract7zJava(archive, destDir, callback);
    }

    private static int extract7zNative(String binaryPath, File archive, File destDir, ProgressCallback callback) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(binaryPath);
        cmd.add("x");
        cmd.add("-y");
        cmd.add("-aoa"); // overwrite all
        cmd.add("-snl"); // store / restore symbolic links
        cmd.add("-snh"); // store / restore hard links
        cmd.add("-bsp1"); // output progress stream
        cmd.add(archive.getAbsolutePath());
        cmd.add("-o" + destDir.getAbsolutePath());

        Log.i(TAG, "Launching native 7za command: " + cmd);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        int count = 0;
        long lastNotify = System.currentTimeMillis();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                count++;
                long now = System.currentTimeMillis();
                if (now - lastNotify > 200 && callback != null) {
                    lastNotify = now;
                    callback.onProgress(count, line);
                }
            }
        }

        int exitCode = process.waitFor();
        Log.i(TAG, "Native 7za finished with exit code: " + exitCode);
        if (exitCode != 0 && exitCode != 1) { // 0 = ok, 1 = warning
            throw new Exception("Native 7za extraction failed with exit code: " + exitCode);
        }
        return count;
    }

    public static int extract7zJava(File archive, File destDir, ProgressCallback callback) throws Exception {
        if (!destDir.exists()) destDir.mkdirs();

        int count = 0;
        long lastNotify = System.currentTimeMillis();

        try (SevenZFile sevenZFile = new SevenZFile(archive)) {
            SevenZArchiveEntry entry;
            byte[] buffer = new byte[32768];

            while ((entry = sevenZFile.getNextEntry()) != null) {
                count++;
                String entryName = entry.getName();

                // Skip /dev nodes (PRoot dynamically binds host /dev)
                if (entryName.startsWith("dev/") || entryName.equals("dev")) {
                    continue;
                }

                File destFile = new File(destDir, entryName);

                // Path traversal protection
                String canonical = destFile.getCanonicalPath();
                if (!canonical.startsWith(destDir.getCanonicalPath())) {
                    continue;
                }

                if (entry.isDirectory()) {
                    destFile.mkdirs();
                } else {
                    File parent = destFile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();

                    try (FileOutputStream fos = new FileOutputStream(destFile)) {
                        int len;
                        while ((len = sevenZFile.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    } catch (Throwable writeError) {
                        Log.w(TAG, "Write skipped for " + entryName + ": " + writeError.getMessage());
                    }

                    // Set executable permissions for binaries / scripts
                    if (entryName.startsWith("bin/") || entryName.startsWith("usr/bin/") || entryName.endsWith(".sh")) {
                        destFile.setExecutable(true, false);
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                Os.chmod(destFile.getAbsolutePath(), 0755);
                            }
                        } catch (Throwable ignored) {}
                    }
                }

                long now = System.currentTimeMillis();
                if (now - lastNotify > 200 && callback != null) {
                    lastNotify = now;
                    callback.onProgress(count, entryName);
                }
            }
        }

        Log.i(TAG, "Extracted " + count + " entries from 7z into " + destDir.getAbsolutePath());
        return count;
    }

    public static int extractTarGz(Context context, File archive, File destDir, ProgressCallback callback) throws Exception {
        if (!destDir.exists()) destDir.mkdirs();

        // 1. Try Native Busybox tar
        if (context != null) {
            try {
                String busyboxPath = InstallationManager.findOrExtractBusyboxBinary(context);
                File bbFile = new File(busyboxPath);
                if (bbFile.exists() && (bbFile.canExecute() || bbFile.setExecutable(true, false))) {
                    Log.i(TAG, "Starting native tar extraction via BusyBox: " + busyboxPath);
                    List<String> cmd = new ArrayList<>();
                    cmd.add(busyboxPath);
                    cmd.add("tar");
                    cmd.add("-xvf");
                    cmd.add(archive.getAbsolutePath());
                    cmd.add("-C");
                    cmd.add(destDir.getAbsolutePath());

                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.redirectErrorStream(true);
                    Process process = pb.start();

                    int count = 0;
                    long lastNotify = System.currentTimeMillis();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            count++;
                            long now = System.currentTimeMillis();
                            if (now - lastNotify > 200 && callback != null) {
                                lastNotify = now;
                                callback.onProgress(count, line);
                            }
                        }
                    }

                    int exitCode = process.waitFor();
                    if (exitCode == 0) {
                        Log.i(TAG, "Native BusyBox tar completed successfully.");
                        return count;
                    }
                    Log.w(TAG, "BusyBox tar returned " + exitCode + ", falling back to Java tar");
                }
            } catch (Throwable t) {
                Log.w(TAG, "Native tar extraction failed, falling back to Java: " + t.getMessage());
            }
        }

        // 2. Java TarArchiveInputStream fallback
        return extractTarGzJava(archive, destDir, callback);
    }

    public static int extractTarGzJava(File archive, File destDir, ProgressCallback callback) throws Exception {
        if (!destDir.exists()) destDir.mkdirs();

        int count = 0;
        long lastNotify = System.currentTimeMillis();

        try (InputStream fis = new FileInputStream(archive);
             InputStream bis = new BufferedInputStream(fis, 65536);
             InputStream gzis = new GzipCompressorInputStream(bis);
             TarArchiveInputStream tais = new TarArchiveInputStream(gzis)) {

            TarArchiveEntry entry;
            byte[] buffer = new byte[32768];

            while ((entry = tais.getNextTarEntry()) != null) {
                count++;
                String entryName = entry.getName();

                // Skip /dev nodes (PRoot dynamically binds host /dev)
                if (entryName.startsWith("dev/") || entryName.equals("dev")) {
                    continue;
                }

                File destFile = new File(destDir, entryName);

                String canonical = destFile.getCanonicalPath();
                if (!canonical.startsWith(destDir.getCanonicalPath())) {
                    continue;
                }

                if (entry.isDirectory()) {
                    destFile.mkdirs();
                } else if (entry.isSymbolicLink()) {
                    File parent = destFile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    if (destFile.exists()) destFile.delete();

                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            Os.symlink(entry.getLinkName(), destFile.getAbsolutePath());
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed creating symlink: " + entryName + " -> " + entry.getLinkName());
                    }
                } else if (entry.isFile()) {
                    File parent = destFile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();

                    try (FileOutputStream fos = new FileOutputStream(destFile)) {
                        int len;
                        while ((len = tais.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    } catch (Throwable writeError) {
                        Log.w(TAG, "Write skipped for " + entryName + ": " + writeError.getMessage());
                    }

                    int mode = entry.getMode();
                    if ((mode & 0111) != 0 || entryName.startsWith("bin/") || entryName.startsWith("usr/bin/")) {
                        destFile.setExecutable(true, false);
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                Os.chmod(destFile.getAbsolutePath(), mode != 0 ? mode : 0755);
                            }
                        } catch (Throwable ignored) {}
                    }
                }

                long now = System.currentTimeMillis();
                if (now - lastNotify > 200 && callback != null) {
                    lastNotify = now;
                    callback.onProgress(count, entryName);
                }
            }
        }

        Log.i(TAG, "Extracted " + count + " entries from tar.gz into " + destDir.getAbsolutePath());
        return count;
    }
}
