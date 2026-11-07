package ai.sol.hermes;

import android.os.Build;
import android.system.Os;
import android.util.Log;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Universal archive unpacker supporting both .7z (SevenZ) and .tar.gz (TarGz).
 * Restores POSIX symbolic links and file execution permissions for Linux rootfs.
 */
public class ArchiveExtractor {
    private static final String TAG = "ArchiveExtractor";

    public interface ProgressCallback {
        void onProgress(int count, String currentEntryName);
    }

    public static int extract(File archive, File destDir, ProgressCallback callback) throws Exception {
        String name = archive.getName().toLowerCase();
        if (name.endsWith(".7z")) {
            return extract7z(archive, destDir, callback);
        } else {
            return extractTarGz(archive, destDir, callback);
        }
    }

    public static int extract7z(File archive, File destDir, ProgressCallback callback) throws Exception {
        if (!destDir.exists()) destDir.mkdirs();

        int count = 0;
        long lastNotify = System.currentTimeMillis();

        try (SevenZFile sevenZFile = new SevenZFile(archive)) {
            SevenZArchiveEntry entry;
            byte[] buffer = new byte[32768];

            while ((entry = sevenZFile.getNextEntry()) != null) {
                count++;
                String entryName = entry.getName();
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

    public static int extractTarGz(File archive, File destDir, ProgressCallback callback) throws Exception {
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
