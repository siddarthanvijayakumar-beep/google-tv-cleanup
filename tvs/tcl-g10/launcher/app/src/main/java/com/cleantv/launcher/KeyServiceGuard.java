package com.cleantv.launcher;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

/**
 * Keeps {@link SettingsKeyService} running across reboots. TCL's TV player switches accessibility off
 * at every boot unless TalkBack is the service in use, which leaves our service listed but unbound.
 * If the owner turned the service on (it is in the enabled list), this turns accessibility back on and
 * rebinds it. If the owner turned it off, nothing happens.
 *
 * Needs WRITE_SECURE_SETTINGS, which only the owner can grant over adb; without it this does nothing.
 */
final class KeyServiceGuard {

    private static final String TAG = "CleanTvKeys";
    private static final String ENABLED = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
    private static final String MASTER = Settings.Secure.ACCESSIBILITY_ENABLED;
    /** TCL's boot-time check runs well after the launcher starts; look again at these points. */
    private static final long[] BOOT_CHECKS_MS = {5_000, 20_000, 45_000, 90_000, 180_000};
    /** Stop after this many repairs per launcher start, so we never fight another app in a loop. */
    private static final int MAX_REPAIRS = 6;

    private final Context ctx;
    private final Handler handler;
    private final ComponentName service;
    private final ContentObserver observer;
    private int repairs;

    KeyServiceGuard(Context ctx, Handler handler) {
        this.ctx = ctx;
        this.handler = handler;
        this.service = new ComponentName(ctx, SettingsKeyService.class);
        this.observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                handler.removeCallbacks(check);
                handler.postDelayed(check, 3_000);
            }
        };
    }

    private final Runnable check = this::ensure;

    void start() {
        Log.i(TAG, "guard start, permission granted=" + granted());
        if (!granted()) return;
        ContentResolver cr = ctx.getContentResolver();
        cr.registerContentObserver(Settings.Secure.getUriFor(MASTER), false, observer);
        cr.registerContentObserver(Settings.Secure.getUriFor(ENABLED), false, observer);
        for (long at : BOOT_CHECKS_MS) handler.postDelayed(check, at);
    }

    /** Also checked whenever the home screen comes to the front: TCL may freeze us in the background. */
    void checkSoon() {
        handler.removeCallbacks(check);
        handler.postDelayed(check, 1_500);
    }

    void stop() {
        ctx.getContentResolver().unregisterContentObserver(observer);
        handler.removeCallbacks(check);
    }

    private boolean granted() {
        return ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    private boolean listed(String list) {
        if (list == null) return false;
        for (String s : list.split(":")) {
            ComponentName c = ComponentName.unflattenFromString(s);
            if (service.equals(c)) return true;
        }
        return false;
    }

    private boolean bound() {
        AccessibilityManager am = ctx.getSystemService(AccessibilityManager.class);
        if (am == null) return false;
        for (android.accessibilityservice.AccessibilityServiceInfo i
                : am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK))
            if (service.flattenToString().equals(i.getId()) || service.flattenToShortString().equals(i.getId())) return true;
        return false;
    }

    private void ensure() {
        if (!granted()) return;
        ContentResolver cr = ctx.getContentResolver();
        String list = Settings.Secure.getString(cr, ENABLED);
        if (!listed(list)) return; // the owner has it off: respect that
        boolean master = Settings.Secure.getInt(cr, MASTER, 0) == 1;
        if (master && bound()) return;
        if (repairs >= MAX_REPAIRS) return;
        repairs++;
        Log.i(TAG, "re-enabling settings key service (master=" + master + ", try " + repairs + ")");
        // Writing the list without and then with our entry makes the system rebind it.
        StringBuilder others = new StringBuilder();
        for (String s : list.split(":")) {
            if (service.equals(ComponentName.unflattenFromString(s))) continue;
            if (others.length() > 0) others.append(':');
            others.append(s);
        }
        Settings.Secure.putString(cr, ENABLED, others.toString());
        Settings.Secure.putString(cr, ENABLED, list);
        Settings.Secure.putInt(cr, MASTER, 1);
    }
}
