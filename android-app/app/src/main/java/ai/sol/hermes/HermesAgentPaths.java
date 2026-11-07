package ai.sol.hermes;

/**
 * Standard paths and environment definitions for the Hermes Agent subsystem.
 * Ground-truth definitions matching BOOT_ARCHITECTURE.md and decompiled reference.
 */
public final class HermesAgentPaths {
    public static final String ROOT = "/usr/local/lib/hermes-agent";
    public static final String HERMES_BIN = "/usr/local/bin/hermes";
    public static final String BRIDGE_PYTHON = "/usr/local/lib/hermes-agent/venv/bin/python3";
    public static final String VENV_BIN = "/usr/local/lib/hermes-agent/venv/bin";
    public static final String HOME = "/root/.hermes";
    public static final String REAL_HOME = "/root";
    public static final String WORKSPACE = "/root/workspace";

    public static final String PATH = "/opt/qroot/bin:" +
            "/usr/local/lib/hermes-agent/venv/bin:" +
            "/usr/local/bin:" +
            "/root/.hermes/node/bin:" +
            "/root/.hermes/bin:" +
            "/usr/local/sbin:" +
            "/usr/local/bin:" +
            "/usr/sbin:" +
            "/usr/bin:" +
            "/sbin:" +
            "/bin";

    public static final String ACTIVATION_PREFIX = "source /usr/local/lib/hermes-agent/venv/bin/activate && ";
    public static final String STARTUP_COMMAND = "cd /root/workspace && exec /usr/local/bin/hermes dashboard --host 127.0.0.2 --port 39067 --no-open --skip-build";

    public static final int DASHBOARD_PORT = 39067;
    public static final int CAPABILITY_PORT = 43097;
    public static final String DASHBOARD_HOST = "127.0.0.2";
    public static final String FALLBACK_HOST = "127.0.0.1";

    private HermesAgentPaths() {}
}
