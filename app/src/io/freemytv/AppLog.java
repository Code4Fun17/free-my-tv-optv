package io.freemytv;

import android.util.Log;

/**
 * The app's log (adb logcat -s FreeMyTV). Never logs keys. It is only Android's log in memory, never a file: it starts
 * empty at every boot. The news logs only what went wrong.
 */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("FreeMyTV", message);
    }
}
