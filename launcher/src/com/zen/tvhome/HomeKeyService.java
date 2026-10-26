package com.zen.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Google TV garde son launcher prioritaire pour HOME ; ce service intercepte la touche Home
 * de la télécommande et ouvre Zen Home à la place. Si Zen Home ne peut pas s'ouvrir,
 * la touche est rendue au système (accueil Google TV).
 */
public class HomeKeyService extends AccessibilityService {

    private static final long BOOT_WINDOW_MS = 4 * 60_000;

    /** Juste après le démarrage, le service se connecte après l'accueil Google TV : on affiche Zen Home. */
    @Override
    protected void onServiceConnected() {
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) openHome();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) return true;
        if (event.isCanceled()) return true;
        return openHome();
    }

    private boolean openHome() {
        try {
            startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
