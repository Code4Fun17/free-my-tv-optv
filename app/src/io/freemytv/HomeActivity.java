package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    private HomeView home;
    private android.content.BroadcastReceiver remoteWoke;   // the remote connected (RemoteBattery)
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        bootCheckOnce();
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        home = new HomeView(this);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
        if (Config.TOP_INFO) {
            RemoteBattery.start(this, home::topInfoChanged);
            remoteWoke = RemoteBattery.listen(this);
        }
    }

    /**
     * The boot check (HomeMode.bootCheck), once per boot, from the home screen too: some TVs never hand
     * BOOT_COMPLETED to an app they don't know (HomeReceiver gets nothing), but the home screen starts after every boot.
     * Android's boot counter tells a new boot from the home screen opening again; when both run, the job is one.
     */
    private void bootCheckOnce() {
        int boots = android.provider.Settings.Global.getInt(getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
        android.content.SharedPreferences seen = getSharedPreferences("boot", MODE_PRIVATE);
        if (boots < 0 || seen.getInt("checked", -1) == boots) return;
        seen.edit().putInt("checked", boots).apply();
        HomeMode.bootCheck(this);
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) {
            home.covered(false);
            home.resume();
            if (Config.TOP_INFO) RemoteBattery.soon(1500);   // the Home button just woke the remote
        }
    }

    /** Back from another app: the home screen is drawn bright again before it shows. */
    @Override protected void onStart() {
        super.onStart();
        if (home != null) home.covered(false);
    }

    @Override protected void onPause() {
        if (home != null) {
            home.pause();
            home.covered(true);   // a panel over it (Android's Settings, the inputs…) reads on a dark, solid background
        }
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home != null) home.reset(true);
    }

    @Override protected void onDestroy() {
        if (remoteWoke != null) unregisterReceiver(remoteWoke);
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // The info pill's battery: the remote is connected for a little while after a key, so it's read then.
        if (Config.TOP_INFO && home != null && event.getAction() == KeyEvent.ACTION_UP) RemoteBattery.soon(2000);
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
