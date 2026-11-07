package ai.sol.hermes;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Executes the native PRoot subsystem process to host the Hermes Agent dashboard & gateway.
 * Directly implements the blueprint documented in BOOT_ARCHITECTURE.md.
 */
public class PRootRunner {
    private static final String TAG = "PRootRunner";

    public interface OutputListener {
        void onOutputLine(String line);
    }

    private static PRootRunner instance;
    private final Context context;
    private Process engineProcess;
    private volatile boolean isRunning = false;
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool();
    private final List<OutputListener> outputListeners = new CopyOnWriteArrayList<>();

    public static synchronized PRootRunner getInstance(Context context) {
        if (instance == null) {
            instance = new PRootRunner(context);
        }
        return instance;
    }

    private PRootRunner(Context context) {
        this.context = context.getApplicationContext();
    }

    public void addOutputListener(OutputListener listener) {
        if (!outputListeners.contains(listener)) {
            outputListeners.add(listener);
        }
    }

    public void removeOutputListener(OutputListener listener) {
        outputListeners.remove(listener);
    }

    public synchronized boolean start() {
        if (isAlive()) {
            Log.i(TAG, "PRoot engine is already running.");
            return true;
        }

        try {
            String prootBinary = InstallationManager.findOrExtractProotBinary(context);
            File rootfsDir = InstallationManager.getRootfsDir(context);
            File workspaceDir = InstallationManager.getWorkspaceDir(context);

            File bashFile = new File(rootfsDir, "bin/bash");
            String shellPath = bashFile.exists() ? "/bin/bash" : "/bin/sh";

            // Boot command directly matching BOOT_ARCHITECTURE.md
            String startupCommand = "cd /root/workspace && exec /usr/local/bin/hermes dashboard --host 127.0.0.2 --port 39067 --no-open --skip-build";

            List<String> cmd = new ArrayList<>();
            cmd.add(prootBinary);
            cmd.add("-r");
            cmd.add(rootfsDir.getAbsolutePath());
            cmd.add("-0"); // root emulation
            cmd.add("-b");
            cmd.add("/dev");
            cmd.add("-b");
            cmd.add("/proc");
            cmd.add("-b");
            cmd.add("/sys");
            cmd.add("-b");
            cmd.add(workspaceDir.getAbsolutePath() + ":/root/workspace");
            cmd.add("-w");
            cmd.add("/root/workspace");
            cmd.add(shellPath);
            cmd.add("-lc");
            cmd.add(startupCommand);

            Log.i(TAG, "Launching PRoot command: " + cmd);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workspaceDir);
            pb.redirectErrorStream(true);

            // Configure full Hermes environment per BOOT_ARCHITECTURE.md
            Map<String, String> env = pb.environment();
            env.put("HERMES_HOME", "/root/.hermes");
            env.put("HERMES_AGENT_ROOT", "/usr/local/lib/hermes-agent");
            env.put("HERMES_BIN", "/usr/local/bin/hermes");
            env.put("HERMES_ANDROID_CAPABILITY_PORT", "43097");
            env.put("HERMES_REAL_HOME", "/root");
            env.put("HERMES_GATEWAY_DETACHED", "1");
            env.put("HERMES_DISABLE_LAZY_INSTALLS", "1");
            env.put("HERMES_EXEC_ASK", "1");
            env.put("HERMES_QUIET", "1");
            env.put("HERMES_STARTUP_WATCHDOG_TIMEOUT_S", "300");
            env.put("PORT", "39067");
            env.put("QROOT_ANDROID_PACKAGE", "ai.sol.hermes");
            env.put("QROOT_GPU", "llvmpipe");
            env.put("QROOT_IN_STUB", "1");
            env.put("QROOT_LOOPBACK_NAMESPACE", "hermes-agent");
            env.put("QROOT_LOOPBACK_UNIX", "1");
            env.put("QROOT_STUB_BLOCK_TARGET_SECCOMP", "1");
            env.put("HOME", "/root");
            env.put("USER", "root");
            env.put("TERM", "xterm-256color");
            env.put("TMPDIR", "/tmp");
            env.put("PATH", "/usr/local/lib/hermes-agent/.hermes/bin:/root/.local/bin:/usr/local/bin:/root/.hermes/bin:/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin");

            engineProcess = pb.start();
            isRunning = true;

            // Stream logs to hermes_engine.log and UI listeners
            ioExecutor.execute(this::pipeOutput);

            Log.i(TAG, "PRoot engine process spawned successfully.");
            return true;

        } catch (Throwable t) {
            Log.e(TAG, "Failed to start PRoot engine: " + t.getMessage(), t);
            isRunning = false;
            return false;
        }
    }

    private void pipeOutput() {
        File logFile = InstallationManager.getLogFile(context);
        try (InputStream in = engineProcess.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
             FileOutputStream fos = new FileOutputStream(logFile, true)) {

            String line;
            while (isRunning && (line = reader.readLine()) != null) {
                String logLine = "[Hermes] " + line;
                Log.d(TAG, logLine);

                // Write to log file
                fos.write((logLine + "\n").getBytes(StandardCharsets.UTF_8));
                fos.flush();

                // Notify live listeners
                for (OutputListener listener : outputListeners) {
                    try {
                        listener.onOutputLine(logLine);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Log piping ended: " + e.getMessage());
        } finally {
            isRunning = false;
        }
    }

    public synchronized void stop() {
        isRunning = false;
        if (engineProcess != null) {
            try {
                engineProcess.destroy();
                Log.i(TAG, "PRoot engine process destroyed.");
            } catch (Exception ignored) {}
            engineProcess = null;
        }
    }

    public boolean isAlive() {
        if (engineProcess == null) return false;
        try {
            engineProcess.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true; // Still running
        }
    }
}
