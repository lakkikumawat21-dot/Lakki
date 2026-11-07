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
