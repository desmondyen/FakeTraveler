package cl.coders.faketraveler;

import static cl.coders.faketraveler.MainActivity.SourceChange.CHANGE_FROM_EDITTEXT;
import static cl.coders.faketraveler.MainActivity.SourceChange.CHANGE_FROM_MAP;
import static cl.coders.faketraveler.MainActivity.SourceChange.LOAD;
import static cl.coders.faketraveler.MainActivity.SourceChange.NONE;
import static cl.coders.faketraveler.SharedPrefsUtil.getDouble;
import static cl.coders.faketraveler.SharedPrefsUtil.migrateOldPreferences;
import static cl.coders.faketraveler.SharedPrefsUtil.putDouble;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;


public class MainActivity extends AppCompatActivity implements ServiceConnection {

    @NonNull
    public static final String sharedPrefKey = "cl.coders.faketraveler.sharedprefs";
    @NonNull
    public static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("0.######", DecimalFormatSymbols.getInstance(Locale.ROOT));

    private MaterialButton buttonApplyStop;
    private WebView webView;
    private EditText editTextLat;
    private EditText editTextLng;
    private Context context;
    private int currentVersion;

    @NonNull
    private SourceChange srcChange = NONE;

    @Nullable
    private MockedLocationService.MockedBinder binder = null;

    private int version;
    private double lat;
    private double lng;
    private double zoom;
    private int mockCount;
    private int mockFrequency;
    private double dLat;
    private double dLng;
    private boolean mockSpeed;
    private long endTime;
    private String mapProvider;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_layout), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        context = getApplicationContext();
        webView = findViewById(R.id.webView0);
        WebAppInterface webAppInterface = new WebAppInterface(this);

        buttonApplyStop = findViewById(R.id.button_applyStop);
        MaterialButton buttonSettings = findViewById(R.id.button_settings);
        editTextLat = findViewById(R.id.editTextLat);
        editTextLng = findViewById(R.id.editTextLng);

        buttonApplyStop.setOnClickListener(view -> {
            Intent intent = new Intent(this, MockedLocationService.class);
            bindService(intent, this, BIND_AUTO_CREATE);
        });
        buttonSettings.setOnClickListener(view -> {
            Intent myIntent = new Intent(getBaseContext(), MoreActivity.class);
            startActivity(myIntent);
        });

        webView.getSettings().setJavaScriptEnabled(true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
        webView.addJavascriptInterface(webAppInterface, "Android");

        try {
            PackageInfo pInfo = this.getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                currentVersion = (int) (pInfo.getLongVersionCode() >> 32);
            } else {
                currentVersion = pInfo.versionCode;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(MainActivity.class.toString(), "Could not read version info!", e);
        }

        loadSharedPrefs();
        applyIntentOrDefault(getIntent());

        editTextLat.addTextChangedListener(new TextWatcher() {
            @Override public void afterTextChanged(Editable s) {
                if (!editTextLat.getText().toString().isEmpty() && !editTextLat.getText().toString().equals("-")) {
                    if (srcChange != CHANGE_FROM_MAP) {
                        try { lat = Double.parseDouble(editTextLat.getText().toString()); setLatLng(lat, lng, CHANGE_FROM_EDITTEXT); } catch (Throwable t) {}
                    }
                }
            }
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        });

        editTextLng.addTextChangedListener(new TextWatcher() {
            @Override public void afterTextChanged(Editable s) {
                if (!editTextLng.getText().toString().isEmpty() && !editTextLng.getText().toString().equals("-")) {
                    if (srcChange != CHANGE_FROM_MAP) {
                        try { lng = Double.parseDouble(editTextLng.getText().toString()); setLatLng(lat, lng, CHANGE_FROM_EDITTEXT); } catch (Throwable t) {}
                    }
                }
            }
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        });

        // 🛡️ 安全加固：只有在用户同时勾选了“定位权限”以及“开发者选项模拟器绑定”后，才允许后台服务初始化，彻底杜绝闪退
        requestRequiredPermissions();
    }

    private void requestRequiredPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS};
        } else {
            permissions = new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
        }

        boolean needRequest = false;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
                break;
            }
        }

        if (needRequest) {
            ActivityCompat.requestPermissions(this, permissions, 102);
        } else {
            startAutoMockService();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // 无论用户有没有完全允许通知，我们都尝试启动（定位权限是核心）
        startAutoMockService();
    }

    private void startAutoMockService() {
        // 使用 2 秒延迟，等待 Android 16 沙箱生命周期以及系统 Mock 标志位就位后再进行拉起
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                changeButtonToStop();
                Intent autoIntent = new Intent(this, MockedLocationService.class);
                bindService(autoIntent, this, BIND_AUTO_CREATE);
            } catch (SecurityException se) {
                Log.e(MainActivity.class.toString(), "安全拒绝：请先去开发者选项中选择本应用为模拟位置应用！", se);
                changeButtonToApply();
                showSnackbar("请先前往开发者选项勾选本应用！");
            } catch (Exception e) {
                Log.e(MainActivity.class.toString(), "绑定服务失败", e);
            }
        }, 2000);
    }

    @Override
    protected void onResume() { super.onResume(); context = getApplicationContext(); loadSharedPrefs(); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); loadSharedPrefs(); applyIntentOrDefault(intent); }
    @Override public void onDestroy() { super.onDestroy(); }

    private void loadSharedPrefs() {
        migrateOldPreferences(context);
        SharedPreferences sharedPref = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE);
        version = sharedPref.getInt("version", 0);
        lat = getDouble(sharedPref, "lat", 3.1390); // 锁定吉隆坡
        lng = getDouble(sharedPref, "lng", 101.6869);
        zoom = getDouble(sharedPref, "zoom", 12);
        mockCount = sharedPref.getInt("mockCount", 0);
        if (mockCount <= 0) mockCount = 999999;
        mockFrequency = sharedPref.getInt("mockFrequency", 10);
        if (mockFrequency <= 0) mockFrequency = 1;
        dLat = getDouble(sharedPref, "dLat", 0);
        dLng = getDouble(sharedPref, "dLng", 0);
        mockSpeed = sharedPref.getBoolean("mockSpeed", true);
        endTime = sharedPref.getLong("endTime", 0);
        mapProvider = sharedPref.getString("mapProvider", MapProviderUtil.getDefaultMapProvider(Locale.getDefault()));
        if (version != currentVersion) { version = currentVersion; saveSettings(); }
    }

    private void saveSettings() {
        Editor editor = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE).edit();
        editor.putInt("version", version); putDouble(editor, "lat", lat); putDouble(editor, "lng", lng); putDouble(editor, "zoom", zoom);
        editor.putInt("mockCount", mockCount); editor.putInt("mockFrequency", mockFrequency); putDouble(editor, "dLat", dLat); putDouble(editor, "dLng", dLng);
        editor.putBoolean("mockSpeed", mockSpeed); editor.putLong("endTime", endTime); editor.putString("mapProvider", mapProvider); editor.apply();
    }

    private void applyIntentOrDefault(Intent intent) {
        String intentData = intent.getDataString();
        if (intentData != null) {
            try {
                GeoUri uri = GeoUri.parse(intentData);
                if (uri != null) { lat = uri.lat(); lng = uri.lng(); Double zoomTmp = uri.zoom(); if (zoomTmp != null) zoom = zoomTmp; }
            } catch (Throwable t) {}
        }
        setLatLng(lat, lng, LOAD);
        webView.loadUrl(Uri.parse("file:///android_asset/map.html").buildUpon().appendQueryParameter("lat", "" + lat).appendQueryParameter("lng", "" + lng).appendQueryParameter("zoom", "" + zoom).appendQueryParameter("provider", mapProvider).build().toString());
    }

    protected void applyLocation() {
        if (latIsEmpty() || lngIsEmpty()) { showSnackbar(context.getResources().getString(R.string.MainActivity_NoLatLong)); return; }
        lat = Double.parseDouble(editTextLat.getText().toString()); lng = Double.parseDouble(editTextLng.getText().toString());
        if (binder != null) {
            float[] speed = {0};
            if (mockSpeed) { Location.distanceBetween(lat, lng, lat + dLat / 1000000, lng + dLng / 1000000, speed); speed[0] /= mockFrequency * 1000L; }
            binder.startMock(lng, lat, dLng / 1000000, dLat / 1000000, mockFrequency * 1000L, mockCount, speed[0]);
            endTime = System.currentTimeMillis() + (mockCount - 1L) * mockFrequency * 1000L; saveSettings();
        }
    }

    void showSnackbar(String str) { Snackbar.make(findViewById(R.id.main_layout), str, Snackbar.LENGTH_SHORT).show(); }
    void showSnackbar(@StringRes int strRes) { Snackbar.make(findViewById(R.id.main_layout), strRes, Snackbar.LENGTH_SHORT).show(); }
    boolean latIsEmpty() { return editTextLat.getText().toString().isBlank(); }
    boolean lngIsEmpty() { return editTextLng.getText().toString().isBlank(); }
    protected void setMapMarker(double lat, double lng) { if (webView == null || webView.getUrl() == null) return; webView.loadUrl("javascript:setOnMap(" + lat + "," + lng + ");"); }

    void changeButtonToApply() {
        buttonApplyStop.setText(context.getResources().getString(R.string.ActivityMain_Apply));
        buttonApplyStop.setOnClickListener(view -> { if (binder == null) { Intent intent = new Intent(this, MockedLocationService.class); bindService(intent, this, BIND_AUTO_CREATE); } else { binder.continueMock(); } });
    }

    void changeButtonToStop() {
        buttonApplyStop.setText(context.getResources().getString(R.string.ActivityMain_Stop));
        buttonApplyStop.setOnClickListener(view -> { try { unbindService(this); } catch (Exception e) {} disconnectService(); });
    }

    public void setZoom(double zoom) { this.zoom = zoom; saveSettings(); }
    void setLatLng(double mLat, double mLng, SourceChange srcChange) {
        lat = mLat; lng = mLng;
        if (srcChange == CHANGE_FROM_EDITTEXT || srcChange == LOAD) { setMapMarker(lat, lng); }
        if (srcChange == CHANGE_FROM_MAP || srcChange == LOAD) { this.srcChange = CHANGE_FROM_MAP; editTextLat.setText(DECIMAL_FORMAT.format(lat)); editTextLng.setText(DECIMAL_FORMAT.format(lng)); this.srcChange = NONE; }
        saveSettings();
    }

    @Override public void onServiceConnected(ComponentName name, IBinder service) { binder = (MockedLocationService.MockedBinder) service; binder.mockState.observe(this, this::onMockedStateChange); binder.mockedLocation.observe(this, this::onMockedLocationChange); }
    @Override public void onServiceDisconnected(ComponentName name) { disconnectService(); }

    private void disconnectService() { if (binder == null) return; binder.mockState.removeObservers(this); binder.mockedLocation.removeObservers(this); binder = null; indicateMockStop(); }

    private void onMockedStateChange(MockState state) {
        switch (state) {
            case NOT_MOCKED -> indicateMockStop();
            case SERVICE_BOUND -> applyLocation();
            case MOCKED -> { changeButtonToStop(); showSnackbar(R.string.MainActivity_MockApplied); }
            case MOCK_ERROR -> showSnackbar(R.string.MainActivity_MockNotApplied);
        }
    }

    private void indicateMockStop() { showSnackbar(R.string.MainActivity_MockStopped); changeButtonToApply(); }
    private void onMockedLocationChange(Location location) { setMapMarker(location.getLatitude(), location.getLongitude()); }
    public enum SourceChange { NONE, LOAD, CHANGE_FROM_EDITTEXT, CHANGE_FROM_MAP }
}
