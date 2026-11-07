package ai.sol.hermes;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * Manages hermes_data migration and /root/.hermes persistence.
 * Reconstructed from com.xermes.qroot.HermesDataManager.
 */
public class HermesDataManager {
    private static final String TAG = "HermesDataManager";

    public static final String DATA_DIR = "hermes_data";
    public static final String MIGRATING_DIR = "hermes_data.migrating";

    private final Context context;

    public HermesDataManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public File getDataDirectory() {
        File dataDir = new File(context.getFilesDir(), DATA_DIR);
        if (!dataDir.exists()) dataDir.mkdirs();
        return dataDir;
    }

    public void ensurePersistenceLinked() {
        try {
            File rootfsDir = InstallationManager.getRootfsDir(context);
            File rootHome = new File(rootfsDir, "root/.hermes");
            if (!rootHome.exists()) {
                rootHome.mkdirs();
            }
            Log.d(TAG, "Hermes persistence directory verified: " + rootHome.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "Persistence link notice: " + e.getMessage());
        }
    }
}
