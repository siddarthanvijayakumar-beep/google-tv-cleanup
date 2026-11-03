package com.cleantv.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Brings back the remote's Settings (gear) button. On Google TV that button asks SystemUI for the
 * notification panel, which only the stock launcher provides; with it disabled the button does
 * nothing. This service sees the key first and opens Settings instead. It filters key events only.
 */
public class SettingsKeyService extends AccessibilityService {

    private static final String TAG = "CleanTvKeys";

    private boolean isSettingsKey(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_SETTINGS || code == KeyEvent.KEYCODE_NOTIFICATION) return true;
        for (int scan : getResources().getIntArray(R.array.settings_scan_codes))
            if (e.getScanCode() == scan) return true;
        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (!isSettingsKey(e)) return false;
        if (e.getAction() == KeyEvent.ACTION_UP) {
            Log.i(TAG, "settings key: code=" + e.getKeyCode() + " scan=" + e.getScanCode());
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ex) {
                Log.w(TAG, "no settings activity", ex);
            }
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
