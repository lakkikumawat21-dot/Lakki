package ai.sol.hermes;

import android.os.Build;
import android.system.Os;
import android.util.Log;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Robust, high-performance TAR.GZ archive unpacker for Android.
 * Correctly restores Linux symlinks and executable permissions inside rootfs.
 */
public class TarExtractor {
    private static final String TAG = "TarExtractor";

    public interface ProgressCallback {
        void onProgress(int count, String currentEntryName);
    }

    public static int extractTarGz(File archive, File destDir, ProgressCallback callback) throws Exception {
        if (!destDir.exists()) {
            destDir.mkdirs();
        }

        int entryCount = 0;
        long lastNotifyTime = System.currentTimeMillis();

        try (InputStream fis = new FileInputStream(archive);
             InputStream bis = new BufferedInputStream(fis, 65536);
             InputStream gzis = new GzipCompressorInputStream(bis);
             TarArchiveInputStream tais = new TarArchiveInputStream(gzis)) {

            TarArchiveEntry entry;
            byte[] buffer = new byte[32768];

            while ((entry = tais.getNextTarEntry()) != null) {
                entryCount++;
                String entryName = entry.getName();

                // Prevent zip slip vulnerability
                File destFile = new File(destDir, entryName);
                String destCanonical = destFile.getCanonicalPath();
                if (!destCanonical.startsWith(destDir.getCanonicalPath())) {
                    Log.w(TAG, "Skipping entry outside target: " + entryName);
                    continue;
                }

                if (entry.isDirectory()) {
                    destFile.mkdirs();
                } else if (entry.isSymbolicLink()) {
                    File parent = destFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                    if (destFile.exists()) {
                        destFile.delete();
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            Os.symlink(entry.getLinkName(), destFile.getAbsolutePath());
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed creating symlink: " + entryName + " -> " + entry.getLinkName());
                    }
                } else if (entry.isFile()) {
                    File parent = destFile.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }

                    try (FileOutputStream fos = new FileOutputStream(destFile)) {
                        int len;
                        while ((len = tais.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }

                    // Set executable permissions if octal mode has execute bits
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
                if (now - lastNotifyTime > 250 && callback != null) {
                    lastNotifyTime = now;
                    callback.onProgress(entryCount, entryName);
                }
            }
        }

        Log.i(TAG, "Extracted " + entryCount + " entries into " + destDir.getAbsolutePath());
        return entryCount;
    }
}
