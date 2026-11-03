package com.cleantv.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int TOP = 0, DOCK = 1, GRID = 2, MENU = 3, INPUTS = 4;
    private static final float LIFT = 1.08f;

    private static final class Input {
        String id;
        CharSequence label;
        /** Name the connected device reports over HDMI-CEC, e.g. "PlayStation 5". */
        CharSequence device;
        boolean tuner, hdmi, connected, airplay;
    }

    private AppRepository repo = new AppRepository();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<Input> inputs = new ArrayList<>();
    /** The Inputs row under the dock: HDMI ports and Live TV, one tile each. */
    private final List<Input> rowInputs = new ArrayList<>();
    private final List<AppRepository.App> rowTiles = new ArrayList<>();
    private TvInputManager tim;

    private FrameLayout root;
    private ScrollView scroll;
    private View hero, dockBand, glow, bottomSpace;
    /** Glow behind the focused All apps tile; it sits outside the scroll view and follows the focus. */
    private View gridGlow;
    private ValueAnimator gridGlowAnim;
    private int gridGlowColor = Color.TRANSPARENT;
    private RecyclerView dockRv, gridRv, inputsRv;
    private TextView greeting, clock, date, dockTitle, gridTitle, inputsTitle;
    private View weather;
    private TextView weatherTemp, weatherCond, weatherInfo;
    private long weatherFetchedAt;
    private static final long WEATHER_EVERY = 30 * 60_000L;
    private TextView[] pills;
    private LinearLayout inputsMenu;
    private GlassHighlight highlight;
    private ImageView wallpaper;

    private float dp, radius;
    private int columns, tileW, cardH, gap;
    /** Inputs are slim full-width bars, so the dock and the Inputs row both fit on the first screen. */
    private int inputCardH;
    private Typeface thin, light, regular;

    private int zone = DOCK, topIdx, dockIdx, gridIdx, menuIdx, inputIdx;
    private View lifted;
    private ObjectAnimator scrollAnim;
    private ValueAnimator glowAnim;
    private int glowColor = Color.TRANSPARENT;
    private boolean resumed, dpiLaunching;
    /** On screen (between onResume and onStop). A Home press pauses us before onNewIntent, so resumed alone misses it. */
    private boolean shown;
    /** OK held on an app opens its options; a short press still opens the app (on key up). */
    private boolean okDown, okLong;
    /** The options menu reuses the inputs menu view; these say which one is open and where to return. */
    private boolean appMenu;
    /** The Picture & Sound pill's menu (Picture, Sound), also shown in the inputs menu view. */
    private boolean avMenu;
    private static final int PILL_INPUTS = 0, PILL_AV = 1, PILL_WIFI = 2, PILL_SETTINGS = 3;
    private int menuReturn;
    private final List<Integer> appMenuActions = new ArrayList<>();
    private static final int ACT_MOVE = 0, ACT_ADD_DOCK = 1, ACT_REMOVE_DOCK = 2;
    /** Move mode: the arrows slide the focused app through its list. */
    private boolean moving;
    private SkeletonAdapter.Shimmer shimmer;
    private List<String> dpiApps;
    private SharedPreferences prefs;
    private ConnectivityManager cm;
    private KeyServiceGuard keyGuard;

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
            maybeFetchWeather();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            reloadApps();
        }
    };

    private final ConnectivityManager.NetworkCallback netCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network n, NetworkCapabilities caps) {
            main.post(() -> updateNetwork(caps));
        }

        @Override
        public void onLost(Network n) {
            main.post(() -> updateNetwork(null));
        }
    };

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String id, int state) {
            reloadInputRow();
        }

        @Override
        public void onInputAdded(String id) {
            reloadInputRow();
        }

        @Override
        public void onInputRemoved(String id) {
            reloadInputRow();
        }

        @Override
        public void onInputUpdated(String id) {
            reloadInputRow();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        dp = getResources().getDisplayMetrics().density;
        columns = getResources().getInteger(R.integer.columns);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        dpiApps = Arrays.asList(getResources().getStringArray(R.array.dpi_apps));

        root = findViewById(R.id.root);
        scroll = findViewById(R.id.scroll);
        hero = findViewById(R.id.hero);
        dockBand = findViewById(R.id.dockBand);
        glow = findViewById(R.id.glow);
        gridGlow = findViewById(R.id.gridGlow);
        bottomSpace = findViewById(R.id.bottomSpace);
        dockRv = findViewById(R.id.dock);
        gridRv = findViewById(R.id.grid);
        inputsRv = findViewById(R.id.inputsRow);
        inputsTitle = findViewById(R.id.inputsTitle);
        weather = findViewById(R.id.weather);
        weatherTemp = findViewById(R.id.weatherTemp);
        weatherCond = findViewById(R.id.weatherCond);
        weatherInfo = findViewById(R.id.weatherInfo);
        greeting = findViewById(R.id.greeting);
        clock = findViewById(R.id.clock);
        date = findViewById(R.id.date);
        dockTitle = findViewById(R.id.dockTitle);
        gridTitle = findViewById(R.id.gridTitle);
        inputsMenu = findViewById(R.id.inputsMenu);
        highlight = findViewById(R.id.highlight);
        wallpaper = findViewById(R.id.wallpaper);
        pills = new TextView[]{findViewById(R.id.pillInputs), findViewById(R.id.pillAv), findViewById(R.id.pillWifi), findViewById(R.id.pillSettings)};

        thin = font(R.integer.clock_weight);
        light = font(R.integer.title_weight);
        regular = font(R.integer.body_weight);
        clock.setTypeface(thin);
        weatherTemp.setTypeface(thin);
        weatherCond.setTypeface(regular);
        weatherInfo.setTypeface(regular);
        // Last known weather at once after power-on; a fresh copy follows when the network is up.
        String cached = prefs.getString("weather", null);
        if (cached != null) showWeather(cached);
        weatherFetchedAt = prefs.getLong("weather_at", 0);
        dockTitle.setTypeface(light);
        dockTitle.setVisibility(View.GONE);
        for (TextView t : new TextView[]{greeting, date, gridTitle, inputsTitle}) t.setTypeface(regular);

        layoutGeometry();
        styleChrome();
        loadWallpaper();
        showSkeletons();
        reloadApps();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packageReceiver, pf);

        keyGuard = new KeyServiceGuard(this, main);
        keyGuard.start();

        tim = getSystemService(TvInputManager.class);
        if (tim != null) tim.registerCallback(inputCallback, main);
        reloadInputRow();

        cm = getSystemService(ConnectivityManager.class);
        cm.registerDefaultNetworkCallback(netCallback);
        updateNetwork(cm.getNetworkCapabilities(cm.getActiveNetwork()));
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        keyGuard.stop();
        cm.unregisterNetworkCallback(netCallback);
        if (tim != null) tim.unregisterCallback(inputCallback);
        if (shimmer != null) shimmer.stop();
        io.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        shown = true;
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, tf);
        updateClock();
        reloadInputRow();
        maybeFetchWeather();
        if (keyGuard != null) keyGuard.checkSoon();
        // Back home from an app that needed the DPI bypass: switch it off. The flag lives in prefs
        // so it survives the launcher being killed while the app was open.
        if (!dpiLaunching && prefs.getBoolean("dpi_on", false)) {
            prefs.edit().putBoolean("dpi_on", false).apply();
            dpi("only_stop");
        }
    }

    @Override
    protected void onStop() {
        shown = false;
        super.onStop();
    }

    @Override
    protected void onPause() {
        resumed = false;
        if (moving) finishMove();
        unregisterReceiver(timeReceiver);
        rememberFocus();
        super.onPause();
    }

    /** Home pressed while already home: close menus and return to the dock. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!shown) return;
        if (moving) finishMove();
        if (zone == MENU) hideMenu();
        appMenu = false;
        avMenu = false;
        if (!repo.dock.isEmpty()) zone = DOCK;
        applyFocus(true);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to.
    }

    // ---- layout ----

    private Typeface font(int weightRes) {
        try {
            return new Typeface.Builder(getAssets(), getString(R.string.font_file))
                    .setFontVariationSettings("'wght' " + getResources().getInteger(weightRes))
                    .build();
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }

    private void layoutGeometry() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int side = px(64), bandMargin = px(40);
        gap = px(28);
        radius = px(14);
        tileW = (screenW - 2 * side - (columns - 1) * gap) / columns;
        cardH = tileW * 9 / 16;

        inputCardH = px(46);
        int dockPad = side - bandMargin - gap / 2;
        dockRv.setPadding(dockPad, 0, dockPad, 0);
        inputsRv.setPadding(side - gap / 2, 0, side - gap / 2, 0);
        gridRv.setPadding(side - gap / 2, 0, side - gap / 2, 0);
        for (RecyclerView rv : new RecyclerView[]{dockRv, gridRv, inputsRv}) {
            rv.setLayoutManager(new GridLayoutManager(this, columns));
            rv.setNestedScrollingEnabled(false);
            rv.setItemAnimator(null);
            rv.setFocusable(false);
            rv.setDescendantFocusability(RecyclerView.FOCUS_BLOCK_DESCENDANTS);
        }

        int labelSpace = px(12) + px(26);
        int bandH = dockBand.getPaddingTop() + dockBand.getPaddingBottom() + cardH + labelSpace;
        ViewGroup.MarginLayoutParams t = (ViewGroup.MarginLayoutParams) inputsTitle.getLayoutParams();
        int inputsH = t.topMargin + inputsTitle.getLineHeight() + t.bottomMargin + inputCardH + labelSpace;
        hero.getLayoutParams().height = screenH - bandH - inputsH - px(8);
        bottomSpace.getLayoutParams().height = screenH;
    }

    private void styleChrome() {
        GradientDrawable band = new GradientDrawable();
        band.setColor(0xD9161619);
        band.setCornerRadius(px(30));
        band.setStroke(Math.max(1, px(1) / 2), 0x14FFFFFF);
        dockBand.setBackground(band);

        for (TextView p : pills) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x24FFFFFF);
            bg.setCornerRadius(px(24));
            p.setBackground(bg);
            p.setTypeface(regular);
        }

        GradientDrawable menu = new GradientDrawable();
        menu.setColor(0xFA1C1C1F);
        menu.setCornerRadius(px(22));
        menu.setStroke(px(1), 0x26FFFFFF);
        inputsMenu.setBackground(menu);

        // A circular radial gradient stretched horizontally reads as a soft ambient glow.
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        // glow_scale grows the glow around the same centre (270dp in, 40dp above the dock).
        float half = 210 * getResources().getInteger(R.integer.glow_scale) / 100f;
        g.setGradientRadius(px(half));
        g.setColors(new int[]{Color.TRANSPARENT, Color.TRANSPARENT});
        glow.setBackground(g);
        glow.getLayoutParams().width = px(2 * half);
        glow.getLayoutParams().height = px(2 * half);
        ((FrameLayout.LayoutParams) glow.getLayoutParams()).setMarginStart(px(270 - half));
        ((FrameLayout.LayoutParams) glow.getLayoutParams()).bottomMargin = -px(half - 40);
        glow.setScaleX(2.3f);

        GradientDrawable gg = new GradientDrawable();
        gg.setShape(GradientDrawable.OVAL);
        gg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        gg.setGradientRadius(px(half));
        gg.setColors(new int[]{Color.TRANSPARENT, Color.TRANSPARENT});
        gridGlow.setBackground(gg);
        gridGlow.getLayoutParams().width = px(2 * half);
        gridGlow.getLayoutParams().height = px(2 * half);
        gridGlow.setScaleX(1.8f);
    }

    private void loadWallpaper() {
        File f = new File(getExternalFilesDir(null), getString(R.string.wallpaper_file));
        if (!f.isFile()) return;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int target = getResources().getDisplayMetrics().widthPixels;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= target) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
        if (b == null) return;
        wallpaper.setImageBitmap(b);
        wallpaper.setColorFilter(Color.argb(getResources().getInteger(R.integer.wallpaper_dim), 0, 0, 0),
                PorterDuff.Mode.SRC_ATOP);
        wallpaper.setVisibility(View.VISIBLE);
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    // ---- data ----

    private void reloadApps() {
        io.execute(() -> {
            AppRepository r = new AppRepository();
            r.load(this);
            main.post(() -> showApps(r));
        });
    }

    /** Cold start: placeholder plates in the last known counts, so the first frame is never an empty band. */
    private void showSkeletons() {
        int dockN = prefs.getInt("dock_n", columns), gridN = prefs.getInt("grid_n", columns);
        shimmer = new SkeletonAdapter.Shimmer();
        dockRv.setAdapter(new SkeletonAdapter(shimmer, dockN, cardH, gap, radius));
        gridRv.setAdapter(new SkeletonAdapter(shimmer, gridN, cardH, gap, radius));
        gridTitle.setVisibility(gridN == 0 ? View.GONE : View.VISIBLE);
        shimmer.start();
    }

    private void showApps(AppRepository r) {
        boolean first = shimmer != null;
        if (first) {
            shimmer.stop();
            shimmer = null;
        }
        repo = r;
        lifted = null;
        dockRv.setAdapter(new TileAdapter(repo.dock, cardH, gap, radius, regular, first));
        gridRv.setAdapter(new TileAdapter(repo.grid, cardH, gap, radius, regular, first));
        gridTitle.setVisibility(repo.grid.isEmpty() ? View.GONE : View.VISIBLE);
        prefs.edit().putInt("dock_n", repo.dock.size()).putInt("grid_n", repo.grid.size()).apply();
        // After power-on start on the dock; later reloads keep the focus on the last opened app.
        if (first && !repo.dock.isEmpty()) zone = DOCK;
        else if (zone != MENU && zone != TOP) restoreFocus();
        afterLayout(() -> applyFocus(false));
    }

    private void restoreFocus() {
        String pkg = prefs.getString("pkg", null);
        zone = repo.dock.isEmpty() ? (repo.grid.isEmpty() ? TOP : GRID) : DOCK;
        dockIdx = Math.min(dockIdx, Math.max(0, repo.dock.size() - 1));
        gridIdx = Math.min(gridIdx, Math.max(0, repo.grid.size() - 1));
        if (pkg == null) return;
        for (int i = 0; i < repo.dock.size(); i++)
            if (repo.dock.get(i).pkg.equals(pkg)) {
                zone = DOCK;
                dockIdx = i;
                return;
            }
        for (int i = 0; i < repo.grid.size(); i++)
            if (repo.grid.get(i).pkg.equals(pkg)) {
                zone = GRID;
                gridIdx = i;
                return;
            }
    }

    private void rememberFocus() {
        AppRepository.App a = focusedApp();
        if (a != null) prefs.edit().putString("pkg", a.pkg).apply();
    }

    private AppRepository.App focusedApp() {
        if (zone == DOCK && dockIdx < repo.dock.size()) return repo.dock.get(dockIdx);
        if (zone == GRID && gridIdx < repo.grid.size()) return repo.grid.get(gridIdx);
        return null;
    }

    private void afterLayout(Runnable r) {
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                r.run();
                return true;
            }
        });
        root.invalidate();
    }

    // ---- keys and focus ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean ours;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                ours = true;
                break;
            default:
                ours = false;
        }
        if (!ours) return super.dispatchKeyEvent(e);
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
        if (ok) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    okDown = true;
                    okLong = false;
                } else if (okDown && !okLong) {
                    okLong = true;
                    showAppMenu();
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                if (okDown && !okLong) activate();
                okDown = false;
            }
            return true;
        }
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (code == KeyEvent.KEYCODE_MENU) {
            if (e.getRepeatCount() == 0) showAppMenu();
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: move(-1, 0); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0); break;
            case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1); break;
            case KeyEvent.KEYCODE_BACK: back(); break;
        }
        return true;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private int rowLength(int row) {
        return Math.min(columns, repo.grid.size() - row * columns);
    }

    private void move(int dx, int dy) {
        if (moving) {
            moveApp(dx, dy);
            return;
        }
        switch (zone) {
            case TOP:
                if (dx != 0) topIdx = clamp(topIdx + dx, 0, pills.length - 1);
                else if (dy > 0 && !repo.dock.isEmpty()) zone = DOCK;
                else if (dy > 0 && !repo.grid.isEmpty()) zone = GRID;
                break;
            case DOCK:
                if (dx != 0) dockIdx = clamp(dockIdx + dx, 0, repo.dock.size() - 1);
                else if (dy < 0) zone = TOP;
                else if (!rowInputs.isEmpty()) {
                    zone = INPUTS;
                    inputIdx = Math.min(dockIdx, rowInputs.size() - 1);
                } else if (!repo.grid.isEmpty()) {
                    zone = GRID;
                    gridIdx = Math.min(dockIdx % columns, rowLength(0) - 1);
                }
                break;
            case GRID: {
                int row = gridIdx / columns, col = gridIdx % columns;
                int rows = (repo.grid.size() + columns - 1) / columns;
                if (dx != 0) {
                    gridIdx = row * columns + clamp(col + dx, 0, rowLength(row) - 1);
                } else if (dy < 0) {
                    if (row > 0) gridIdx = (row - 1) * columns + col;
                    else if (!rowInputs.isEmpty()) {
                        zone = INPUTS;
                        inputIdx = Math.min(col, rowInputs.size() - 1);
                    } else if (!repo.dock.isEmpty()) {
                        zone = DOCK;
                        dockIdx = Math.min(col, repo.dock.size() - 1);
                    } else zone = TOP;
                } else if (row + 1 < rows) {
                    gridIdx = (row + 1) * columns + Math.min(col, rowLength(row + 1) - 1);
                }
                break;
            }
            case INPUTS:
                if (dx != 0) inputIdx = clamp(inputIdx + dx, 0, rowInputs.size() - 1);
                else if (dy < 0) {
                    if (!repo.dock.isEmpty()) {
                        zone = DOCK;
                        dockIdx = Math.min(inputIdx, repo.dock.size() - 1);
                    } else zone = TOP;
                } else if (!repo.grid.isEmpty()) {
                    zone = GRID;
                    gridIdx = Math.min(inputIdx % columns, rowLength(0) - 1);
                }
                break;
            case MENU:
                if (dy != 0) menuIdx = clamp(menuIdx + dy, 0, inputsMenu.getChildCount() - 1);
                break;
        }
        applyFocus(true);
    }

    private void back() {
        if (moving) {
            finishMove();
            return;
        }
        if (zone == MENU) {
            hideMenu();
            zone = appMenu ? menuReturn : TOP;
            appMenu = false;
            avMenu = false;
        } else if (zone == GRID || zone == TOP || zone == INPUTS) {
            zone = repo.dock.isEmpty() ? zone : DOCK;
        }
        applyFocus(true);
    }

    private void activate() {
        if (moving) {
            finishMove();
            return;
        }
        if (zone == MENU && appMenu) {
            int action = menuIdx < appMenuActions.size() ? appMenuActions.get(menuIdx) : -1;
            hideMenu();
            zone = menuReturn;
            appMenu = false;
            runAppAction(action);
            return;
        }
        if (zone == MENU && avMenu) {
            int pick = menuIdx;
            hideMenu();
            zone = TOP;
            avMenu = false;
            applyFocus(false);
            // TCL's own Picture and Sound side panels; the generic actions are the fallback elsewhere.
            if (pick == 0) start(new Intent(Settings.ACTION_DISPLAY_SETTINGS));
            else startFirst(new Intent("com.android.tv.action.VIEW_SOUND_SETTINGS"), new Intent(Settings.ACTION_SOUND_SETTINGS));
            return;
        }
        switch (zone) {
            case TOP:
                if (topIdx == PILL_INPUTS) showInputs();
                else if (topIdx == PILL_AV) showAvMenu();
                else if (topIdx == PILL_WIFI) start(new Intent(Settings.ACTION_WIFI_SETTINGS));
                else start(new Intent(Settings.ACTION_SETTINGS));
                break;
            case DOCK:
            case GRID:
                AppRepository.App a = focusedApp();
                if (a != null) {
                    rememberFocus();
                    if (dpiApps.contains(a.pkg)) startWithDpi(a.intent);
                    else start(a.intent);
                }
                break;
            case INPUTS:
                if (inputIdx < rowInputs.size()) switchInput(rowInputs.get(inputIdx));
                break;
            case MENU:
                if (menuIdx < inputs.size()) switchInput(inputs.get(menuIdx));
                hideMenu();
                zone = TOP;
                applyFocus(false);
                break;
        }
    }

    private void start(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, e.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
        }
    }

    /** Start the DPI bypass, give its VPN a moment to come up, then open the app. */
    private void startWithDpi(Intent app) {
        // The toggle activity finishes at once and resumes us; dpiLaunching keeps onResume from stopping it.
        dpiLaunching = true;
        dpi("only_start");
        main.postDelayed(() -> {
            prefs.edit().putBoolean("dpi_on", true).apply();
            dpiLaunching = false;
            start(app);
        }, getResources().getInteger(R.integer.dpi_delay_ms));
    }

    /** ByeByeDPI's exported ToggleActivity takes only_start / only_stop extras. */
    private void dpi(String action) {
        ComponentName c = ComponentName.unflattenFromString(getString(R.string.dpi_toggle));
        start(new Intent().setComponent(c).putExtra(action, true));
    }

    /** The view the glass plate should cover for the current focus. */
    private View focusTarget() {
        switch (zone) {
            case TOP: return pills[topIdx];
            case DOCK: return card(dockRv, dockIdx);
            case GRID: return card(gridRv, gridIdx);
            case INPUTS: return card(inputsRv, inputIdx);
            case MENU: return inputsMenu.getChildAt(menuIdx);
        }
        return null;
    }

    private static View card(RecyclerView rv, int pos) {
        RecyclerView.LayoutManager lm = rv.getLayoutManager();
        View item = lm == null ? null : lm.findViewByPosition(pos);
        return item == null ? null : item.findViewById(R.id.card);
    }

    private void applyFocus(boolean animate) {
        // Placeholders take no focus; the top bar does, it is real from the first frame.
        if (shimmer != null && zone != TOP && zone != MENU) return;
        View target = focusTarget();
        if (target == null) return;
        if (lifted != null && lifted != target) lift(lifted, false);
        lift(target, true);
        lifted = target;

        int scrollTo = scrollTarget();
        if (scrollAnim != null) scrollAnim.cancel();
        if (scroll.getScrollY() != scrollTo) {
            if (animate) {
                scrollAnim = ObjectAnimator.ofInt(scroll, "scrollY", scrollTo);
                scrollAnim.setDuration(380);
                scrollAnim.setInterpolator(new DecelerateInterpolator(2f));
                scrollAnim.start();
            } else {
                scroll.scrollTo(0, scrollTo);
            }
        }

        Rect r = new Rect(0, 0, target.getWidth(), target.getHeight());
        root.offsetDescendantRectToMyCoords(target, r);
        if (zone != MENU) r.offset(0, scroll.getScrollY() - scrollTo);
        RectF plate = new RectF(r);
        float corner;
        if (zone == DOCK || zone == GRID || zone == INPUTS) {
            plate.inset(-(r.width() * (LIFT - 1) / 2 + px(6)), -(r.height() * (LIFT - 1) / 2 + px(6)));
            corner = radius * LIFT + px(6);
        } else if (zone == MENU) {
            plate.inset(px(8), px(1));
            corner = px(14);
        } else {
            corner = r.height() / 2f;
        }
        highlight.moveTo(plate, corner, animate);
        updateDockTitle();
        updateGridGlow(r.centerX(), r.centerY(), animate);
    }

    private void lift(View v, boolean on) {
        if (v.getId() == R.id.card) {
            v.animate().scaleX(on ? LIFT : 1).scaleY(on ? LIFT : 1).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator()).start();
            View label = ((View) v.getParent()).findViewById(R.id.label);
            label.animate().alpha(on ? 1 : 0).setDuration(on ? 220 : 120).start();
        } else if (v instanceof TextView) {
            ((TextView) v).setTextColor(on ? 0xFFFFFFFF : 0xCCFFFFFF);
        }
    }

    private int scrollTarget() {
        // Inputs: scroll just far enough that the row clears the bottom edge, the dock stays in view.
        if (zone == MENU) return scroll.getScrollY();
        if (zone == INPUTS) return Math.max(0, inputsRv.getBottom() + px(4) - scroll.getHeight());
        if (zone != GRID) return 0;
        RecyclerView.LayoutManager lm = gridRv.getLayoutManager();
        View item = lm == null ? null : lm.findViewByPosition(gridIdx);
        if (item == null) return scroll.getScrollY();
        return Math.max(0, gridRv.getTop() + item.getTop() - px(150));
    }

    private void updateDockTitle() {
        boolean show = zone == DOCK && dockIdx < repo.dock.size();
        if (show) {
            AppRepository.App a = repo.dock.get(dockIdx);
            if (!a.label.equals(dockTitle.getText())) {
                dockTitle.setText(a.label);
                dockTitle.setAlpha(0.3f);
                dockTitle.setTranslationY(px(6));
            }
            animateGlow(a.color);
        }
        dockTitle.animate().alpha(show ? 1 : 0).translationY(0).setDuration(show ? 260 : 160).start();
        glow.animate().alpha(show ? 1 : 0).setDuration(show ? 420 : 200).start();
    }

    private void animateGlow(int color) {
        if (color == glowColor) return;
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = fadeGlow(glow, glowColor, color);
        glowColor = color;
    }

    /** The grid's glow: the focused tile's colour, centred on it, hidden outside All apps. */
    private void updateGridGlow(float cx, float cy, boolean animate) {
        boolean show = zone == GRID && gridIdx < repo.grid.size();
        if (show) {
            int color = repo.grid.get(gridIdx).color;
            if (color != gridGlowColor) {
                if (gridGlowAnim != null) gridGlowAnim.cancel();
                gridGlowAnim = fadeGlow(gridGlow, gridGlowColor, color);
                gridGlowColor = color;
            }
            float tx = cx - gridGlow.getLayoutParams().width / 2f, ty = cy - gridGlow.getLayoutParams().height / 2f;
            boolean wasHidden = gridGlow.getAlpha() == 0;
            if (animate && !wasHidden) {
                gridGlow.animate().translationX(tx).translationY(ty).alpha(1).setDuration(380)
                        .setInterpolator(new DecelerateInterpolator(2f)).start();
            } else {
                gridGlow.setTranslationX(tx);
                gridGlow.setTranslationY(ty);
                gridGlow.animate().alpha(1).setDuration(420).start();
            }
        } else {
            gridGlow.animate().alpha(0).setDuration(200).start();
        }
    }

    private ValueAnimator fadeGlow(View v, int from, int to) {
        GradientDrawable g = (GradientDrawable) v.getBackground();
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from == Color.TRANSPARENT ? to : from, to);
        anim.setDuration(420);
        anim.addUpdateListener(a -> {
            int c = (int) a.getAnimatedValue();
            g.setColors(new int[]{(c & 0x00FFFFFF) | 0x59000000, (c & 0x00FFFFFF) | 0x1A000000, Color.TRANSPARENT});
        });
        anim.start();
        return anim;
    }

    // ---- top bar ----

    private void updateClock() {
        Date now = new Date();
        boolean h24 = DateFormat.is24HourFormat(this);
        clock.setText(DateFormat.format(h24 ? "HH:mm" : "h:mm", now));
        String d = new java.text.SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now);
        date.setText(d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1));
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h >= 5 && h < 12 ? R.string.good_morning
                : h >= 12 && h < 18 ? R.string.good_day
                : h >= 18 && h < 23 ? R.string.good_evening : R.string.good_night);
    }

    private void updateNetwork(NetworkCapabilities caps) {
        TextView p = pills[PILL_WIFI];
        p.setSingleLine(false);
        p.setMaxLines(2);
        p.setLineSpacing(0, 0.9f);
        if (caps == null) {
            p.setText(R.string.offline);
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            p.setText(R.string.ethernet);
        } else {
            WifiInfo info = getSystemService(WifiManager.class).getConnectionInfo();
            String ssid = info == null ? null : info.getSSID();
            boolean known = ssid != null && !ssid.isEmpty() && !ssid.equals(WifiManager.UNKNOWN_SSID);
            String name = known ? ssid.replaceAll("^\"|\"$", "") : getString(R.string.wifi);
            // "Connected" small above the network name keeps the pill narrow enough for four pills.
            String status = getString(R.string.connected);
            android.text.SpannableString s = new android.text.SpannableString(status + "\n" + name);
            s.setSpan(new android.text.style.RelativeSizeSpan(0.68f), 0, status.length(), 0);
            s.setSpan(new android.text.style.ForegroundColorSpan(0x99FFFFFF), 0, status.length(), 0);
            p.setText(s);
        }
        if (zone == TOP) afterLayout(() -> applyFocus(false));
    }

    // ---- weather (Open-Meteo: free, no account; only the configured coordinates are sent) ----

    private void maybeFetchWeather() {
        if (System.currentTimeMillis() - weatherFetchedAt >= WEATHER_EVERY) fetchWeather();
    }

    private void fetchWeather() {
        weatherFetchedAt = System.currentTimeMillis();
        String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                        + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min"
                        + "&forecast_days=1&timezone=auto&temperature_unit=%s",
                getString(R.string.weather_lat), getString(R.string.weather_lon), getString(R.string.weather_unit));
        io.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[4096];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                }
                String body = out.toString(StandardCharsets.UTF_8.name());
                prefs.edit().putString("weather", body).putLong("weather_at", weatherFetchedAt).apply();
                main.post(() -> showWeather(body));
            } catch (Exception e) {
                // Offline or the service is down: keep what is shown, retry in a few minutes.
                weatherFetchedAt = System.currentTimeMillis() - WEATHER_EVERY + 5 * 60_000L;
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    private void showWeather(String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            org.json.JSONObject now = o.getJSONObject("current");
            org.json.JSONObject day = o.getJSONObject("daily");
            long hi = Math.round(day.getJSONArray("temperature_2m_max").getDouble(0));
            long lo = Math.round(day.getJSONArray("temperature_2m_min").getDouble(0));
            weatherTemp.setText(Math.round(now.getDouble("temperature_2m")) + "°");
            int code = now.getInt("weather_code");
            boolean daytime = now.optInt("is_day", 1) == 1;
            weatherCond.setText(describeWeather(code));
            Drawable icon = getDrawable(weatherIcon(code, daytime));
            int s = px(26);
            if (icon != null) icon.setBounds(0, 0, s, s);
            weatherCond.setCompoundDrawablesRelative(icon, null, null, null);
            weatherCond.setCompoundDrawablePadding(px(10));
            weatherInfo.setText(getString(R.string.weather_info, hi, lo, getString(R.string.weather_city)));
            weather.setVisibility(View.VISIBLE);
        } catch (org.json.JSONException e) {
            weather.setVisibility(View.GONE);
        }
    }

    private static int weatherIcon(int code, boolean day) {
        if (code <= 1) return day ? R.drawable.ic_wx_sun : R.drawable.ic_wx_moon;
        if (code == 2) return day ? R.drawable.ic_wx_partly : R.drawable.ic_wx_cloud;
        if (code == 3) return R.drawable.ic_wx_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_wx_fog;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_wx_snow;
        if (code >= 95) return R.drawable.ic_wx_storm;
        return R.drawable.ic_wx_rain; // drizzle, rain, freezing rain, showers
    }

    /** WMO weather codes, as Open-Meteo reports them. */
    private static String describeWeather(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mainly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code == 66 || code == 67) return "Freezing rain";
        if (code >= 61 && code <= 65) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "";
    }

    // ---- inputs ----

    private void loadInputs() {
        inputs.clear();
        TvInputManager tim = getSystemService(TvInputManager.class);
        if (tim == null) return;
        Input tuner = null;
        // HDMI-CEC devices show up as child inputs of their port, labelled with the device's name.
        java.util.Map<String, CharSequence> devices = new java.util.HashMap<>();
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.getParentId() == null) continue;
            CharSequence name = info.loadCustomLabel(this);
            if (TextUtils.isEmpty(name)) name = info.loadLabel(this);
            if (!TextUtils.isEmpty(name)) devices.put(info.getParentId(), name);
        }
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.getParentId() != null || info.isHidden(this)) continue;
            Input in = new Input();
            in.id = info.getId();
            in.connected = tim.getInputState(in.id) != TvInputManager.INPUT_STATE_DISCONNECTED;
            if (info.getType() == TvInputInfo.TYPE_TUNER) {
                // One Live TV entry, from the built-in tuner only (apps may register tuner inputs too).
                boolean system = (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                if (tuner != null || !system) continue;
                in.tuner = true;
                in.connected = true;
                in.label = getString(R.string.live_tv);
                tuner = in;
                continue;
            }
            if (!info.isPassthroughInput()) continue;
            in.hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
            CharSequence custom = info.loadCustomLabel(this);
            in.label = custom != null ? custom
                    : info.getType() == TvInputInfo.TYPE_COMPOSITE ? getString(R.string.av) : info.loadLabel(this);
            in.device = devices.get(in.id);
            if (in.device != null) in.connected = true;
            inputs.add(in);
        }
        Collator col = Collator.getInstance();
        // HDMI ports first, then AV and the rest, Live TV last.
        inputs.sort((a, b) -> a.hdmi != b.hdmi ? (a.hdmi ? -1 : 1) : col.compare(a.label.toString(), b.label.toString()));
        if (tuner != null) inputs.add(tuner);
    }

    private void showInputs() {
        loadInputs();
        if (inputs.isEmpty()) return;
        inputsMenu.removeAllViews();
        for (Input in : inputs) {
            TextView t = new TextView(this);
            t.setText(in.device == null ? in.label : in.label + "  ·  " + in.device);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setTypeface(regular);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            t.setTextColor(0xCCFFFFFF);
            t.setAlpha(in.connected ? 1f : 0.45f);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(px(24), 0, px(24), 0);
            inputsMenu.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(44)));
        }
        Rect pill = new Rect(0, 0, pills[0].getWidth(), pills[0].getHeight());
        root.offsetDescendantRectToMyCoords(pills[0], pill);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) inputsMenu.getLayoutParams();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = Math.min(pill.left, root.getWidth() - px(24) - lp.width);
        lp.topMargin = pill.bottom + px(12);
        inputsMenu.setLayoutParams(lp);
        inputsMenu.setVisibility(View.VISIBLE);
        inputsMenu.setAlpha(0);
        inputsMenu.setTranslationY(-px(8));
        inputsMenu.animate().alpha(1).translationY(0).setDuration(200).start();
        zone = MENU;
        menuIdx = 0;
        for (int i = 0; i < inputs.size(); i++)
            if (inputs.get(i).connected) {
                menuIdx = i;
                break;
            }
        lifted = null;
        lift(pills[0], false);
        afterLayout(() -> applyFocus(false));
    }

    // ---- arranging apps ----

    /** Options for the focused dock or grid app, opened by holding OK or pressing Menu. */
    private void showAppMenu() {
        if (moving || (zone != DOCK && zone != GRID) || focusedApp() == null) return;
        View card = focusTarget();
        if (card == null) return;
        appMenuActions.clear();
        appMenuActions.add(ACT_MOVE);
        appMenuActions.add(zone == DOCK ? ACT_REMOVE_DOCK : ACT_ADD_DOCK);
        inputsMenu.removeAllViews();
        for (int action : appMenuActions) {
            TextView t = new TextView(this);
            t.setText(action == ACT_MOVE ? R.string.move : action == ACT_ADD_DOCK ? R.string.add_to_dock : R.string.remove_from_dock);
            t.setTypeface(regular);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            t.setTextColor(0xCCFFFFFF);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(px(24), 0, px(24), 0);
            inputsMenu.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(44)));
        }
        // Under the tile, or above it when there is no room below.
        Rect r = new Rect(0, 0, card.getWidth(), card.getHeight());
        root.offsetDescendantRectToMyCoords(card, r);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) inputsMenu.getLayoutParams();
        int menuH = appMenuActions.size() * px(44) + inputsMenu.getPaddingTop() + inputsMenu.getPaddingBottom();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = clamp(r.left, px(24), root.getWidth() - px(24) - lp.width);
        int below = r.bottom + px(56); // clear of the name shown under the lifted tile
        lp.topMargin = below + menuH <= root.getHeight() - px(24) ? below : r.top - px(24) - menuH;
        inputsMenu.setLayoutParams(lp);
        inputsMenu.setVisibility(View.VISIBLE);
        inputsMenu.setAlpha(0);
        inputsMenu.animate().alpha(1).setDuration(160).start();
        menuReturn = zone;
        appMenu = true;
        zone = MENU;
        menuIdx = 0;
        lifted = null; // the app's tile stays lifted under its menu
        afterLayout(() -> applyFocus(false));
    }

    private void runAppAction(int action) {
        if (action == ACT_MOVE) {
            moving = true;
            Toast.makeText(this, R.string.move_hint, Toast.LENGTH_SHORT).show();
            applyFocus(false);
        } else if (action == ACT_ADD_DOCK) {
            if (repo.dock.size() >= columns) {
                Toast.makeText(this, R.string.dock_full, Toast.LENGTH_LONG).show();
                applyFocus(false);
                return;
            }
            repo.dock.add(repo.grid.remove(gridIdx));
            zone = DOCK;
            dockIdx = repo.dock.size() - 1;
            gridIdx = Math.min(gridIdx, Math.max(0, repo.grid.size() - 1));
            saveOrder();
            rebindApps();
        } else if (action == ACT_REMOVE_DOCK) {
            // Back to the top of All apps, where it is easy to find again.
            repo.grid.add(0, repo.dock.remove(dockIdx));
            dockIdx = Math.min(dockIdx, Math.max(0, repo.dock.size() - 1));
            if (repo.dock.isEmpty()) {
                zone = GRID;
                gridIdx = 0;
            }
            saveOrder();
            rebindApps();
        } else {
            applyFocus(false);
        }
    }

    /** One step of move mode: swap the focused app with its neighbour in that direction. */
    private void moveApp(int dx, int dy) {
        if (zone != DOCK && zone != GRID) return;
        boolean dock = zone == DOCK;
        List<AppRepository.App> list = dock ? repo.dock : repo.grid;
        int from = dock ? dockIdx : gridIdx, to = from;
        if (dx != 0) {
            to = clamp(from + dx, 0, list.size() - 1);
        } else if (!dock) {
            int rows = (list.size() + columns - 1) / columns;
            int row = from / columns + dy;
            if (row >= 0 && row < rows) to = Math.min(from + dy * columns, list.size() - 1);
        }
        if (to == from) return;
        list.add(to, list.remove(from));
        (dock ? dockRv : gridRv).getAdapter().notifyItemMoved(from, to);
        if (dock) dockIdx = to;
        else gridIdx = to;
        afterLayout(() -> applyFocus(true));
    }

    private void finishMove() {
        moving = false;
        saveOrder();
        applyFocus(false);
    }

    private void saveOrder() {
        StringBuilder d = new StringBuilder(), g = new StringBuilder();
        for (AppRepository.App a : repo.dock) d.append(d.length() == 0 ? "" : ",").append(a.pkg);
        for (AppRepository.App a : repo.grid) g.append(g.length() == 0 ? "" : ",").append(a.pkg);
        prefs.edit().putString("dock_order", d.toString()).putString("grid_order", g.toString())
                .putInt("dock_n", repo.dock.size()).putInt("grid_n", repo.grid.size()).apply();
    }

    /** Fresh tiles after an app changed lists; the focus follows the moved app. */
    private void rebindApps() {
        lifted = null;
        dockRv.setAdapter(new TileAdapter(repo.dock, cardH, gap, radius, regular, false));
        gridRv.setAdapter(new TileAdapter(repo.grid, cardH, gap, radius, regular, false));
        gridTitle.setVisibility(repo.grid.isEmpty() ? View.GONE : View.VISIBLE);
        afterLayout(() -> applyFocus(true));
    }

    /** Rebuild the Inputs row: one tile per HDMI port named after its device, then Live TV. */
    private void reloadInputRow() {
        if (inputsRv == null || cardH == 0) return;
        loadInputs();
        rowInputs.clear();
        rowTiles.clear();
        List<Input> row = new ArrayList<>();
        for (Input in : inputs) if (in.hdmi) row.add(in);
        // AirPlay is not a TV input; TCL's input bar opens its receiver screen with a broadcast.
        String airplayPkg = getString(R.string.airplay_package);
        try {
            getPackageManager().getPackageInfo(airplayPkg, 0);
            Input ap = new Input();
            ap.id = "airplay";
            ap.label = getString(R.string.airplay);
            ap.airplay = true;
            ap.connected = true;
            row.add(ap);
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
        }
        for (Input in : row) {
            rowInputs.add(in);
            AppRepository.App t = new AppRepository.App();
            t.pkg = in.id;
            t.label = in.label;
            t.isBanner = true;
            t.image = inputTile(in);
            rowTiles.add(t);
        }
        inputsTitle.setVisibility(rowInputs.isEmpty() ? View.GONE : View.VISIBLE);
        inputsRv.setAdapter(new TileAdapter(rowTiles, inputCardH, gap, radius, regular, false));
        if (zone == INPUTS) {
            if (rowInputs.isEmpty()) zone = repo.dock.isEmpty() ? TOP : DOCK;
            else inputIdx = Math.min(inputIdx, rowInputs.size() - 1);
        }
        lifted = null;
        afterLayout(() -> applyFocus(false));
    }

    /** Bar art: input icon on the left, the device name with the port under it. Ports with nothing reported read dimmer. */
    private Drawable inputTile(Input in) {
        int w = tileW, h = inputCardH;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(in.device != null ? 0xFF26262A : 0xFF1C1C1E);
        boolean lit = in.device != null || in.tuner || in.airplay;
        int pad = Math.round(h * 0.3f), s = Math.round(h * 0.42f);

        Drawable icon = getDrawable(in.airplay ? R.drawable.ic_airplay : in.tuner ? R.drawable.ic_wifi : R.drawable.ic_input);
        if (icon != null) {
            icon.setBounds(pad, (h - s) / 2, pad + s, (h + s) / 2);
            icon.setAlpha(lit ? 230 : 120);
            icon.draw(c);
        }

        float x = pad + s + pad * 0.7f;
        String title = in.device != null ? in.device.toString() : in.label.toString();
        String sub = in.device != null ? in.label.toString() : in.tuner || in.airplay ? "" : getString(R.string.no_device);
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setTypeface(regular);
        tp.setTextSize(h * 0.32f);
        tp.setColor(lit ? 0xFFFFFFFF : 0x99FFFFFF);
        CharSequence fit = TextUtils.ellipsize(title, new android.text.TextPaint(tp), w - x - pad, TextUtils.TruncateAt.END);
        c.drawText(fit.toString(), x, sub.isEmpty() ? h * 0.62f : h * 0.48f, tp);
        if (!sub.isEmpty()) {
            Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
            sp.setTypeface(regular);
            sp.setTextSize(h * 0.22f);
            sp.setColor(0x8CFFFFFF);
            c.drawText(sub, x, h * 0.8f, sp);
        }
        return new BitmapDrawable(getResources(), b);
    }

    private void startFirst(Intent preferred, Intent fallback) {
        if (preferred.resolveActivity(getPackageManager()) != null) start(preferred);
        else start(fallback);
    }

    /** Picture / Sound, dropped down under their pill like the inputs menu. */
    private void showAvMenu() {
        inputsMenu.removeAllViews();
        for (int label : new int[]{R.string.picture, R.string.sound}) {
            TextView t = new TextView(this);
            t.setText(label);
            t.setTypeface(regular);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            t.setTextColor(0xCCFFFFFF);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(px(24), 0, px(24), 0);
            inputsMenu.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(44)));
        }
        View pill = pills[PILL_AV];
        Rect r = new Rect(0, 0, pill.getWidth(), pill.getHeight());
        root.offsetDescendantRectToMyCoords(pill, r);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) inputsMenu.getLayoutParams();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = Math.min(r.left, root.getWidth() - px(24) - lp.width);
        lp.topMargin = r.bottom + px(12);
        inputsMenu.setLayoutParams(lp);
        inputsMenu.setVisibility(View.VISIBLE);
        inputsMenu.setAlpha(0);
        inputsMenu.setTranslationY(-px(8));
        inputsMenu.animate().alpha(1).translationY(0).setDuration(200).start();
        avMenu = true;
        zone = MENU;
        menuIdx = 0;
        lifted = null;
        lift(pill, false);
        afterLayout(() -> applyFocus(false));
    }

    private void hideMenu() {
        inputsMenu.animate().alpha(0).setDuration(140).withEndAction(() -> inputsMenu.setVisibility(View.GONE)).start();
        lifted = null;
    }

    private void switchInput(Input in) {
        if (in.airplay) {
            sendBroadcast(new Intent(getString(R.string.airplay_action)).setPackage(getString(R.string.airplay_package)));
            return;
        }
        if (in.tuner) {
            Intent tv = getPackageManager().getLeanbackLaunchIntentForPackage(getString(R.string.live_tv_package));
            if (tv != null) start(tv);
            return;
        }
        start(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(in.id)));
    }
}
