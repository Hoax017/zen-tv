package com.zen.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Écran d'accueil minimaliste : paysage qui suit l'heure du jour, horloge, dock de favoris, grille de toutes les apps. */
public class HomeActivity extends Activity {

    private static final long WALLPAPER_INTERVAL_MS = 60_000;
    private static final long FADE_MS = 2_000;
    private static final String PREFS = "zen";
    private static final String KEY_DOCK = "dock";
    /** Dock de départ : les premières de ces applis qui sont installées. */
    private static final String[] DEFAULT_DOCK = {
            "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "com.plexapp.android", "com.spotify.tv.android", "tv.twitch.android.app",
            "org.smarttube.beta", "org.smarttube.stable", "com.hbo.hbonow", "com.apple.atve.androidtv.appletv",
            "org.videolan.vlc", "org.xbmc.kodi"};
    private static final int DEFAULT_DOCK_SIZE = 5;
    private static final String ACTION_INPUTS = "com.android.tv.action.VIEW_INPUTS";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<AppEntry> apps = new ArrayList<>();
    private final DecelerateInterpolator ease = new DecelerateInterpolator(2.2f);

    private FrameLayout root;
    private ImageView bgFront, bgBack;
    private ObjectAnimator fadeAnim, zoomAnim;
    private LinearLayout dock;
    private View dockScroll;
    private TextView focusLabel;
    private FrameLayout allAppsOverlay;
    private GridLayout allAppsGrid;
    private final List<String> themes = new ArrayList<>();
    private int themeIndex;
    private String shownWallpaper;
    private float dp;

    // Mode « déplacer » du dock (appui long).
    private View movingTile;
    private ObjectAnimator wiggle;
    private boolean ignoreCenterUp;

    static class AppEntry {
        String pkg;
        CharSequence label;
        Drawable banner;
        boolean isBanner;
    }

    private final Runnable wallpaperTick = new Runnable() {
        @Override public void run() {
            themeIndex++;
            showWallpaper();
            handler.postDelayed(this, WALLPAPER_INTERVAL_MS);
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (movingTile != null) return;
            loadApps();
            buildDock(false);
            if (allAppsOverlay.getVisibility() == View.VISIBLE) buildAllApps(false);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        dp = getResources().getDisplayMetrics().density;
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        bgBack = newBackground();
        bgFront = newBackground();
        root.addView(bgBack, match());
        root.addView(bgFront, match());

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x55000000, 0x00000000, 0x00000000, 0x99000000}));
        root.addView(shade, match());

        root.addView(buildClock());

        focusLabel = new TextView(this);
        focusLabel.setTextColor(Color.WHITE);
        focusLabel.setTextSize(19);
        focusLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        focusLabel.setShadowLayer(8, 0, 2, 0x99000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = px(158);
        root.addView(focusLabel, lp);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(px(20), px(18), px(20), px(18));
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        GradientDrawable shelf = new GradientDrawable();
        shelf.setColor(0x38FFFFFF);
        shelf.setCornerRadius(px(30));
        shelf.setStroke(1, 0x2EFFFFFF);
        dock.setBackground(shelf);
        scroll.addView(dock);
        dockScroll = scroll;
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlp.bottomMargin = px(44);
        root.addView(scroll, dlp);

        allAppsOverlay = buildAllAppsOverlay();
        root.addView(allAppsOverlay, match());

        loadThemes();
        loadApps();
        buildDock(true);
        if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
        showWallpaper();

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(wallpaperTick);
        handler.postDelayed(wallpaperTick, WALLPAPER_INTERVAL_MS);
        showWallpaper(); // rattrape un changement de moment de la journée pendant l'absence
        if (allAppsOverlay.getVisibility() != View.VISIBLE && dock.getChildCount() > 0
                && dock.findFocus() == null) {
            dock.getChildAt(0).requestFocus();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(wallpaperTick);
        if (movingTile != null) endMove();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (movingTile != null) endMove();
        hideAllApps();
    }

    @Override
    public void onBackPressed() {
        if (movingTile != null) endMove();
        else if (allAppsOverlay.getVisibility() == View.VISIBLE) hideAllApps();
        // Écran d'accueil : retour ne quitte pas.
    }

    // ---------- Fond d'écran ----------

    /** Les fichiers sont nommés <thème>_<moment>.jpg ; on garde la liste des thèmes. */
    private void loadThemes() {
        TreeSet<String> set = new TreeSet<>();
        try {
            for (String f : getAssets().list("wallpapers")) {
                int us = f.lastIndexOf('_');
                if (us > 0) set.add(f.substring(0, us));
            }
        } catch (Exception ignored) {
        }
        themes.addAll(set);
        if (!themes.isEmpty()) themeIndex = (int) ((System.currentTimeMillis() / WALLPAPER_INTERVAL_MS) % themes.size());
    }

    private ImageView newBackground() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /** Thème courant, éclairé comme maintenant : mélange des deux moments de la journée qui encadrent l'heure. */
    private void showWallpaper() {
        if (themes.isEmpty()) return;
        final String theme = themes.get(themeIndex % themes.size());
        final DayPhase p = DayPhase.now();
        final String key = theme + p.from + p.to + Math.round(p.mix * 20);
        if (key.equals(shownWallpaper)) return;
        shownWallpaper = key;
        new Thread(() -> {
            Bitmap bmp = decode(theme + "_" + p.from + ".jpg", true);
            if (bmp != null && p.mix > 0.02f && !p.from.equals(p.to)) {
                Bitmap next = decode(theme + "_" + p.to + ".jpg", false);
                if (next != null) {
                    Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
                    paint.setAlpha(Math.round(p.mix * 255));
                    new Canvas(bmp).drawBitmap(next, 0, 0, paint);
                    next.recycle();
                }
            }
            final Bitmap result = bmp;
            if (result != null) handler.post(() -> crossfadeTo(result));
        }).start();
    }

    private Bitmap decode(String name, boolean mutable) {
        try (InputStream in = getAssets().open("wallpapers/" + name)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            o.inMutable = mutable;
            return BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            return null;
        }
    }

    /** bgBack garde l'image courante figée pendant que bgFront fait apparaître la nouvelle (avec léger zoom lent). */
    private void crossfadeTo(Bitmap bmp) {
        if (fadeAnim != null) fadeAnim.cancel();
        if (zoomAnim != null) zoomAnim.cancel();
        bgBack.setImageDrawable(bgFront.getDrawable());
        bgBack.setScaleX(bgFront.getScaleX());
        bgBack.setScaleY(bgFront.getScaleY());
        bgFront.setImageBitmap(bmp);
        bgFront.setAlpha(0f);
        fadeAnim = ObjectAnimator.ofFloat(bgFront, View.ALPHA, 0f, 1f).setDuration(FADE_MS);
        zoomAnim = ObjectAnimator.ofPropertyValuesHolder(bgFront,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.06f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.06f)).setDuration(WALLPAPER_INTERVAL_MS + FADE_MS);
        zoomAnim.setInterpolator(new LinearInterpolator());
        fadeAnim.start();
        zoomAnim.start();
    }

    // ---------- Horloge ----------

    private View buildClock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.END);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(44);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        time.setShadowLayer(10, 0, 2, 0x88000000);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        date.setTextColor(0xDDFFFFFF);
        date.setTextSize(16);
        date.setShadowLayer(8, 0, 2, 0x88000000);
        box.addView(time);
        box.addView(date);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lp.topMargin = px(36);
        lp.rightMargin = px(56);
        box.setLayoutParams(lp);
        box.setAlpha(0f);
        box.setTranslationY(-px(10));
        box.animate().alpha(1f).translationY(0).setDuration(600).setStartDelay(150).setInterpolator(ease).start();
        return box;
    }

    // ---------- Applications ----------

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        apps.clear();
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (ai.packageName.equals(getPackageName())) continue;
            AppEntry e = new AppEntry();
            e.pkg = ai.packageName;
            e.label = ri.loadLabel(pm);
            Drawable b = ai.loadBanner(pm);
            if (b == null) b = ai.applicationInfo.loadBanner(pm);
            e.isBanner = b != null;
            e.banner = b != null ? b : ri.loadIcon(pm);
            apps.add(e);
        }
        Collections.sort(apps, (a, b) -> a.label.toString().compareToIgnoreCase(b.label.toString()));
    }

    private AppEntry find(String pkg) {
        for (AppEntry e : apps) if (e.pkg.equals(pkg)) return e;
        return null;
    }

    private List<String> dockPackages() {
        String s = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_DOCK, null);
        List<String> out = new ArrayList<>();
        if (s == null) {
            for (String p : DEFAULT_DOCK) {
                if (out.size() < DEFAULT_DOCK_SIZE && find(p) != null) out.add(p);
            }
            return out;
        }
        for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    private void saveDock(List<String> pkgs) {
        SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        ed.putString(KEY_DOCK, TextUtils.join(",", pkgs)).apply();
    }

    private void launch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show();
        }
    }

    private void startSafe(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Dock ----------

    private void buildDock(boolean intro) {
        View focused = dock.findFocus();
        Object focusedTag = focused != null ? focused.getTag() : null;
        dock.removeAllViews();
        List<AppEntry> docked = new ArrayList<>();
        for (String pkg : dockPackages()) {
            AppEntry e = find(pkg);
            if (e != null) docked.add(e);
        }
        // Les tuiles rétrécissent pour que le dock tienne dans l'écran (au-delà de la taille mini, il défile).
        float screenDp = getResources().getDisplayMetrics().widthPixels / dp;
        float fixedDp = 40 + 25 + 3 * 72 + 2 * 48;
        int tileW = (int) Math.max(96, Math.min(136, (screenDp - fixedDp) / Math.max(1, docked.size()) - 18));
        int tileH = Math.round(tileW * 0.5625f);
        for (AppEntry e : docked) {
            String pkg = e.pkg;
            View tile = appTile(e);
            tile.setTag(pkg);
            tile.setOnClickListener(v -> launch(e.pkg));
            tile.setOnLongClickListener(v -> {
                if (movingTile == null) startMove(v);
                return true;
            });
            tile.setOnKeyListener(this::onDockTileKey);
            dock.addView(tile, tileParams(tileW, tileH));
        }
        View divider = new View(this);
        divider.setBackgroundColor(0x40FFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(px(1), px(44));
        dlp.leftMargin = px(12);
        dlp.rightMargin = px(12);
        dock.addView(divider, dlp);
        addDockAction(getString(R.string.all_apps), Icons.GRID, this::showAllApps);
        addDockAction(getString(R.string.inputs), Icons.INPUT, () -> startSafe(new Intent(ACTION_INPUTS)));
        addDockAction(getString(R.string.settings), Icons.GEAR, () -> startSafe(new Intent(Settings.ACTION_SETTINGS)));
        for (int i = 0; i < dock.getChildCount(); i++) {
            View c = dock.getChildAt(i);
            if (focusedTag != null && focusedTag.equals(c.getTag())) c.requestFocus();
            if (intro) {
                c.setAlpha(0f);
                c.setTranslationY(px(24));
                c.animate().alpha(1f).translationY(0).setStartDelay(120 + i * 45L)
                        .setDuration(450).setInterpolator(ease).start();
            }
        }
        if (intro) {
            dockScroll.setAlpha(0f);
            dockScroll.animate().alpha(1f).setDuration(400).start();
        }
    }

    private void addDockAction(String label, int icon, Runnable action) {
        FrameLayout b = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x30FFFFFF);
        b.setBackground(bg);
        b.setFocusable(true);
        b.setClickable(true);
        b.setContentDescription(label);
        b.setTag(label);
        ImageView img = new ImageView(this);
        img.setImageDrawable(new Icons(icon, Color.WHITE));
        b.addView(img, new FrameLayout.LayoutParams(px(26), px(26), Gravity.CENTER));
        b.setOnClickListener(v -> action.run());
        b.setOnFocusChangeListener((v, has) -> {
            animateFocus(v, has, 1.18f);
            int from = has ? 0x30FFFFFF : 0xF2FFFFFF, to = has ? 0xF2FFFFFF : 0x30FFFFFF;
            ValueAnimator a = ValueAnimator.ofObject(new ArgbEvaluator(), from, to).setDuration(200);
            a.addUpdateListener(an -> bg.setColor((int) an.getAnimatedValue()));
            a.start();
            if (has) img.setColorFilter(0xFF16181D, PorterDuff.Mode.SRC_IN);
            else img.clearColorFilter();
            if (has) showLabel(label);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(58), px(58));
        lp.leftMargin = px(7);
        lp.rightMargin = px(7);
        dock.addView(b, lp);
    }

    private LinearLayout.LayoutParams tileParams(int wDp, int hDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(wDp), px(hDp));
        lp.leftMargin = px(9);
        lp.rightMargin = px(9);
        return lp;
    }

    private void showLabel(CharSequence text) {
        if (TextUtils.equals(focusLabel.getText(), text)) return;
        focusLabel.setText(text);
        focusLabel.setAlpha(0f);
        focusLabel.setTranslationY(px(6));
        focusLabel.animate().alpha(1f).translationY(0).setDuration(220).setInterpolator(ease).start();
    }

    // ---------- Déplacer une appli du dock (appui long) ----------

    private int appTileCount() {
        int n = 0;
        while (n < dock.getChildCount() && dock.getChildAt(n).getTag() instanceof String
                && find((String) dock.getChildAt(n).getTag()) != null) n++;
        return n;
    }

    private void startMove(View tile) {
        movingTile = tile;
        ignoreCenterUp = true;
        tile.animate().scaleX(1.2f).scaleY(1.2f).translationY(-px(10)).translationZ(px(28))
                .setDuration(220).setInterpolator(new OvershootInterpolator(1.6f)).start();
        wiggle = ObjectAnimator.ofFloat(tile, View.ROTATION, -1.6f, 1.6f);
        wiggle.setDuration(130);
        wiggle.setRepeatMode(ValueAnimator.REVERSE);
        wiggle.setRepeatCount(ValueAnimator.INFINITE);
        wiggle.start();
        for (int i = 0; i < dock.getChildCount(); i++) {
            View c = dock.getChildAt(i);
            if (c != tile) c.animate().alpha(0.55f).setDuration(200).start();
        }
        showLabel(getString(R.string.move_hint));
    }

    private void endMove() {
        View tile = movingTile;
        movingTile = null;
        if (wiggle != null) wiggle.cancel();
        tile.setRotation(0);
        List<String> order = new ArrayList<>();
        for (int i = 0; i < appTileCount(); i++) order.add((String) dock.getChildAt(i).getTag());
        List<String> saved = dockPackages();
        for (String p : saved) if (!order.contains(p)) order.add(p); // apps désinstallées : on garde leur place en fin
        saveDock(order);
        for (int i = 0; i < dock.getChildCount(); i++) dock.getChildAt(i).animate().alpha(1f).setDuration(200).start();
        animateFocus(tile, tile.hasFocus(), 1.14f);
        AppEntry e = find((String) tile.getTag());
        if (e != null) showLabel(e.label);
    }

    private boolean onDockTileKey(View v, int code, KeyEvent ev) {
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        boolean center = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        // OK maintenu : les télécommandes envoient des répétitions, on entre en mode déplacement dès la première.
        if (movingTile == null && center && down && (ev.isLongPress() || ev.getRepeatCount() > 0)) {
            startMove(v);
            return true;
        }
        if (movingTile != v) return false;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) shift(v, code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) removeFromDock(v);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
                if (!down) {
                    if (ignoreCenterUp && code != KeyEvent.KEYCODE_BACK) ignoreCenterUp = false;
                    else endMove();
                }
                return true;
            default:
                return true;
        }
    }

    /** Échange la tuile avec sa voisine ; les tuiles glissent vers leur nouvelle place. */
    private void shift(View tile, int dir) {
        int from = dock.indexOfChild(tile), to = from + dir;
        if (to < 0 || to >= appTileCount()) return;
        final Map<View, Integer> before = new HashMap<>();
        for (int i = 0; i < dock.getChildCount(); i++) before.put(dock.getChildAt(i), dock.getChildAt(i).getLeft());
        ViewGroup.LayoutParams lp = tile.getLayoutParams();
        dock.removeViewAt(from);
        dock.addView(tile, to, lp);
        tile.requestFocus();
        dock.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                dock.getViewTreeObserver().removeOnPreDrawListener(this);
                for (Map.Entry<View, Integer> en : before.entrySet()) {
                    View c = en.getKey();
                    int delta = en.getValue() - c.getLeft();
                    if (delta == 0) continue;
                    c.setTranslationX(delta);
                    c.animate().translationX(0).setDuration(240).setInterpolator(ease).start();
                }
                return true;
            }
        });
    }

    private void removeFromDock(View tile) {
        if (wiggle != null) wiggle.cancel();
        movingTile = null;
        String pkg = (String) tile.getTag();
        tile.animate().scaleX(0.3f).scaleY(0.3f).alpha(0f).translationY(px(30)).setDuration(220)
                .withEndAction(() -> {
                    List<String> pkgs = dockPackages();
                    pkgs.remove(pkg);
                    saveDock(pkgs);
                    buildDock(false);
                    if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
                }).start();
        AppEntry e = find(pkg);
        Toast.makeText(this, getString(R.string.removed_from_dock, e != null ? e.label : pkg), Toast.LENGTH_SHORT).show();
    }

    private void toggleDock(AppEntry e) {
        List<String> pkgs = dockPackages();
        if (pkgs.remove(e.pkg)) {
            Toast.makeText(this, getString(R.string.removed_from_dock, e.label), Toast.LENGTH_SHORT).show();
        } else {
            pkgs.add(e.pkg);
            Toast.makeText(this, getString(R.string.added_to_dock, e.label), Toast.LENGTH_SHORT).show();
        }
        saveDock(pkgs);
        buildDock(false);
    }

    // ---------- Toutes les apps ----------

    private FrameLayout buildAllAppsOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xF00B0C10);
        overlay.setVisibility(View.GONE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(64), px(40), px(64), px(24));
        TextView title = new TextView(this);
        title.setText(R.string.all_apps);
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView hint = new TextView(this);
        hint.setText(R.string.all_apps_hint);
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(14);
        hint.setPadding(0, px(4), 0, px(20));
        col.addView(title);
        col.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, px(12), 0, px(12));
        allAppsGrid = new GridLayout(this);
        allAppsGrid.setColumnCount(5);
        allAppsGrid.setClipChildren(false);
        allAppsGrid.setClipToPadding(false);
        scroll.addView(allAppsGrid);
        col.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        overlay.addView(col, match());
        return overlay;
    }

    private void buildAllApps(boolean animate) {
        allAppsGrid.removeAllViews();
        List<String> docked = dockPackages();
        int i = 0;
        for (AppEntry e : apps) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setClipChildren(false);
            View tile = appTile(e);
            tile.setOnClickListener(v -> {
                hideAllApps();
                launch(e.pkg);
            });
            tile.setOnLongClickListener(v -> {
                toggleDock(e);
                buildAllApps(false);
                return true;
            });
            tile.setOnKeyListener((v, code, ev) -> {
                if (code == KeyEvent.KEYCODE_MENU && ev.getAction() == KeyEvent.ACTION_UP) {
                    toggleDock(e);
                    buildAllApps(false);
                    return true;
                }
                return false;
            });
            tile.setTag(e.pkg);
            TextView name = new TextView(this);
            name.setText((docked.contains(e.pkg) ? "★ " : "") + e.label);
            name.setTextColor(0xDDFFFFFF);
            name.setTextSize(13);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setMaxWidth(px(140));
            name.setPadding(0, px(8), 0, 0);
            cell.addView(tile, new LinearLayout.LayoutParams(px(140), px(79)));
            cell.addView(name);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(px(12), px(12), px(12), px(8));
            allAppsGrid.addView(cell, lp);
            if (animate) {
                cell.setAlpha(0f);
                cell.setTranslationY(px(16));
                cell.animate().alpha(1f).translationY(0).setStartDelay(Math.min(i * 18L, 360))
                        .setDuration(320).setInterpolator(ease).start();
            }
            i++;
        }
    }

    private void showAllApps() {
        loadApps();
        buildAllApps(true);
        dockScroll.animate().alpha(0f).translationY(px(20)).setDuration(180).start();
        focusLabel.animate().alpha(0f).setDuration(150).start();
        allAppsOverlay.setAlpha(0f);
        allAppsOverlay.setScaleX(1.03f);
        allAppsOverlay.setScaleY(1.03f);
        allAppsOverlay.setVisibility(View.VISIBLE);
        allAppsOverlay.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(ease).start();
        if (allAppsGrid.getChildCount() > 0) {
            ((ViewGroup) allAppsGrid.getChildAt(0)).getChildAt(0).requestFocus();
        }
    }

    private void hideAllApps() {
        if (allAppsOverlay.getVisibility() != View.VISIBLE) return;
        allAppsOverlay.animate().alpha(0f).scaleX(1.03f).scaleY(1.03f).setDuration(200)
                .withEndAction(() -> allAppsOverlay.setVisibility(View.GONE)).start();
        dockScroll.animate().alpha(1f).translationY(0).setDuration(300).setInterpolator(ease).start();
        focusLabel.animate().alpha(1f).setDuration(300).start();
        if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
    }

    // ---------- Tuiles ----------

    private View appTile(AppEntry e) {
        FrameLayout tile = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(14));
        bg.setColor(e.isBanner ? 0xFF1E1F24 : 0xFF2A2D35);
        tile.setBackground(bg);
        tile.setFocusable(true);
        tile.setClickable(true);
        tile.setContentDescription(e.label);
        final float radius = px(14);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        tile.setClipToOutline(true);

        ImageView img = new ImageView(this);
        img.setImageDrawable(e.banner);
        if (e.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(img, match());
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(img, new FrameLayout.LayoutParams(px(44), px(44), Gravity.CENTER));
        }

        // Reflet qui balaie la tuile quand elle prend le focus.
        View sheen = new View(this);
        sheen.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF}));
        sheen.setVisibility(View.INVISIBLE);
        tile.addView(sheen, new FrameLayout.LayoutParams(px(70), -1));

        tile.setOnFocusChangeListener((v, has) -> {
            if (movingTile != null) return; // pendant un déplacement, le focus saute brièvement d'une tuile à l'autre
            animateFocus(v, has, 1.14f);
            if (has) {
                sheen.setVisibility(View.VISIBLE);
                sheen.setTranslationX(-px(70));
                sheen.animate().translationX(v.getWidth()).setStartDelay(120).setDuration(650)
                        .setInterpolator(ease).withEndAction(() -> sheen.setVisibility(View.INVISIBLE)).start();
                if (v.getParent() == dock) showLabel(e.label);
            }
        });
        return tile;
    }

    private void animateFocus(View v, boolean has, float scale) {
        float s = has ? scale : 1f;
        v.animate().scaleX(s).scaleY(s)
                .translationY(has ? -px(5) : 0)
                .translationZ(has ? px(18) : 0)
                .setStartDelay(0)
                .setDuration(has ? 260 : 200)
                .setInterpolator(has ? new OvershootInterpolator(1.1f) : ease)
                .start();
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

    private int px(float dpValue) {
        return Math.round(dpValue * dp);
    }
}
