package ai.sol.hermes;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * Manages runtime backups, staging transactions, and tool installations.
 * Reconstructed from com.xermes.qroot.HermesRuntimeManager.
 */
public class HermesRuntimeManager {
    private static final String TAG = "HermesRuntimeManager";

    public static final String RUNTIME_BACKUP = "hermes_runtime.backup";
    public static final String RUNTIME_STAGING = "hermes_runtime.staging";
    public static final String RUNTIME_TRANSACTION = "hermes_runtime.transaction";

    private final Context context;

    public HermesRuntimeManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public File getStagingDir() {
        File dir = new File(context.getFilesDir(), RUNTIME_STAGING);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public void cleanTransactions() {
        try {
            File trans = new File(context.getFilesDir(), RUNTIME_TRANSACTION);
            if (trans.exists()) {
                InstallationManager.deleteRecursive(trans);
            }
        } catch (Exception e) {
            Log.w(TAG, "Transaction cleanup notice: " + e.getMessage());
        }
    }
}
