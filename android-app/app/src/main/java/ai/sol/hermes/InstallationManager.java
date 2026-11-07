package ai.sol.hermes;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * Manages the internal QROOT / PRoot Linux subsystem directories,
 * installation state, binary discovery, and diagnostic logs.
 */
public class InstallationManager {
    private static final String TAG = "InstallationManager";

    public static final String MARKER_FILE = ".installed";
    public static final String ROOTFS_DIR = "rootfs";
    public static final String WORKSPACE_DIR = "workspace";
    public static final String BIN_DIR = "bin";
    public static final String LOG_FILE = "hermes_engine.log";

    public static boolean isInstalled(Context context) {
        File filesDir = context.getFilesDir();
        File marker = new File(filesDir, MARKER_FILE);
        File rootfs = new File(filesDir, ROOTFS_DIR);
        File bash = new File(rootfs, "bin/bash");
        File sh = new File(rootfs, "bin/sh");

        return marker.exists() && rootfs.exists() && (bash.exists() || sh.exists());
    }

    public static void markInstalled(Context context, String profileName) {
        try {
            File marker = new File(context.getFilesDir(), MARKER_FILE);
            try (FileOutputStream fos = new FileOutputStream(marker)) {
                String info = "profile=" + profileName + "\ntimestamp=" + System.currentTimeMillis() + "\n";
                fos.write(info.getBytes());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to write install marker: " + e.getMessage());
        }
    }

    public static void resetEnvironment(Context context) {
        File filesDir = context.getFilesDir();
        new File(filesDir, MARKER_FILE).delete();
        deleteRecursive(new File(filesDir, ROOTFS_DIR));
        Log.i(TAG, "Environment reset complete.");
    }

    public static File getRootfsDir(Context context) {
        File dir = new File(context.getFilesDir(), ROOTFS_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File getWorkspaceDir(Context context) {
        File dir = new File(context.getFilesDir(), WORKSPACE_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File getLogFile(Context context) {
        return new File(context.getFilesDir(), LOG_FILE);
    }

    public static String findOrExtractProotBinary(Context context) {
        // 1. Try nativeLibraryDir (installed by Android OS from jniLibs with +x)
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        File libProot = new File(nativeDir, "libproot.so");
        if (libProot.exists() && libProot.canExecute()) {
            Log.i(TAG, "Found executable PRoot in nativeLibraryDir: " + libProot.getAbsolutePath());
            return libProot.getAbsolutePath();
        }

        File libQroot = new File(nativeDir, "libqroot.so");
        if (libQroot.exists() && libQroot.canExecute()) {
            Log.i(TAG, "Found executable QROOT in nativeLibraryDir: " + libQroot.getAbsolutePath());
            return libQroot.getAbsolutePath();
        }

        // 2. Fallback: Extract from APK assets into filesDir/bin/proot
        File binDir = new File(context.getFilesDir(), BIN_DIR);
        if (!binDir.exists()) binDir.mkdirs();
        File fallbackProot = new File(binDir, "proot");

        if (!fallbackProot.exists() || fallbackProot.length() == 0) {
            try {
                Log.i(TAG, "Extracting bundled PRoot binary from assets...");
                try (InputStream in = context.getAssets().open("bin/proot-arm64");
                     FileOutputStream out = new FileOutputStream(fallbackProot)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }
                setExecutable(fallbackProot);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unpack asset PRoot: " + e.getMessage());
            }
        }

        if (fallbackProot.exists()) {
            setExecutable(fallbackProot);
            return fallbackProot.getAbsolutePath();
        }

        // If native library existed but canExecute was false, try returning it anyway
        if (libProot.exists()) return libProot.getAbsolutePath();
        if (libQroot.exists()) return libQroot.getAbsolutePath();

        return fallbackProot.getAbsolutePath();
    }

    public static String findOrExtract7zaBinary(Context context) {
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        File lib7za = new File(nativeDir, "lib7za.so");
        if (lib7za.exists() && lib7za.canExecute()) {
            Log.i(TAG, "Found executable 7za in nativeLibraryDir: " + lib7za.getAbsolutePath());
            return lib7za.getAbsolutePath();
        }

        File lib7z = new File(nativeDir, "lib7z.so");
        if (lib7z.exists() && lib7z.canExecute()) {
            Log.i(TAG, "Found executable 7z in nativeLibraryDir: " + lib7z.getAbsolutePath());
            return lib7z.getAbsolutePath();
        }

        File binDir = new File(context.getFilesDir(), BIN_DIR);
        if (!binDir.exists()) binDir.mkdirs();
        File fallback7za = new File(binDir, "7za");

        if (!fallback7za.exists() || fallback7za.length() == 0) {
            try {
                Log.i(TAG, "Extracting bundled 7za binary from assets...");
                try (InputStream in = context.getAssets().open("bin/7za-arm64");
                     FileOutputStream out = new FileOutputStream(fallback7za)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }
                setExecutable(fallback7za);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unpack asset 7za: " + e.getMessage());
            }
        }

        if (fallback7za.exists()) {
            setExecutable(fallback7za);
            return fallback7za.getAbsolutePath();
        }

        if (lib7za.exists()) return lib7za.getAbsolutePath();
        if (lib7z.exists()) return lib7z.getAbsolutePath();

        return fallback7za.getAbsolutePath();
    }

    public static String findOrExtractBusyboxBinary(Context context) {
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        File libBusybox = new File(nativeDir, "libbusybox.so");
        if (libBusybox.exists() && libBusybox.canExecute()) {
            Log.i(TAG, "Found executable BusyBox in nativeLibraryDir: " + libBusybox.getAbsolutePath());
            return libBusybox.getAbsolutePath();
        }

        File binDir = new File(context.getFilesDir(), BIN_DIR);
        if (!binDir.exists()) binDir.mkdirs();
        File fallbackBusybox = new File(binDir, "busybox");

        if (!fallbackBusybox.exists() || fallbackBusybox.length() == 0) {
            try {
                Log.i(TAG, "Extracting bundled BusyBox binary from assets...");
                try (InputStream in = context.getAssets().open("bin/busybox-arm64");
                     FileOutputStream out = new FileOutputStream(fallbackBusybox)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }
                setExecutable(fallbackBusybox);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unpack asset BusyBox: " + e.getMessage());
            }
        }

        if (fallbackBusybox.exists()) {
            setExecutable(fallbackBusybox);
            return fallbackBusybox.getAbsolutePath();
        }

        if (libBusybox.exists()) return libBusybox.getAbsolutePath();
        return fallbackBusybox.getAbsolutePath();
    }

    public static void setExecutable(File file) {
        try {
            file.setExecutable(true, false);
            file.setReadable(true, false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Os.chmod(file.getAbsolutePath(), 0755);
            }
        } catch (Throwable t) {
            Log.w(TAG, "chmod failed on " + file.getName() + ": " + t.getMessage());
        }
    }

    public static boolean deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return fileOrDirectory.delete();
    }

    public static String readRecentLogs(Context context, int maxLines) {
        File logFile = getLogFile(context);
        if (!logFile.exists() || logFile.length() == 0) {
            return "No logs generated yet.";
        }

        try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
            long length = raf.length();
            long pos = Math.max(0, length - 32768); // Read last 32KB
            raf.seek(pos);
            byte[] bytes = new byte[(int) (length - pos)];
            raf.readFully(bytes);
            return new String(bytes);
        } catch (Exception e) {
            return "Error reading logs: " + e.getMessage();
        }
    }
}
