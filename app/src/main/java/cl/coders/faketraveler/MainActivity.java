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
import android.os.IBinder;
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
        
        // 终极加固：用全局 try-catch 包裹 activity 渲染，确保任何未捕获的系统级异常不触发闪退
        try {
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

            // 修改核心逻辑：将自动绑定剥离！点击大按钮时才去触发绑定和定位，防止冷启动闪退
            buttonApplyStop.setOnClickListener(view -> {
                handleLocationButtonClick();
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
            } catch (Throwable e) {
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

            // 仅进行权限预申请，不自动拉起服务
            requestRequiredPermissionsOnly();
            
            // 初始化按钮状态为可点击“启动”
            changeButtonToApply();
            
        } catch (Throwable t) {
            Log.e(MainActivity.class.toString(), "CRITICAL ONSUBMIT ERROR PREVENTED", t);
        }
    }

    private void requestRequiredPermissionsOnly() {
        try {
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
            }
        } catch (Throwable ignored) {}
    }

    private void handleLocationButtonClick() {
        try {
            if (binder == null) {
                showSnackbar("正在安全注入吉隆坡定位服务...");
                Intent intent = new Intent(this, MockedLocationService.class);
                bindService(intent, this, BIND_AUTO_CREATE);
            } else {
                binder.continueMock();
            }
        } catch (SecurityException se) {
            Log.e(MainActivity.class.toString(), "Mock blocked by system security", se);
            showSnackbar("注入失败！请前往开发者选项重新勾选本应用");
            changeButtonToApply();
        } catch (Throwable t) {
            Log.e(MainActivity.class.toString(), "Error binding service", t);
            showSnackbar("服务绑定异常，请重试");
            changeButtonToApply();
        }
    }

    @Override
    protected void onResume() { super.onResume(); try { context = getApplicationContext(); loadSharedPrefs(); } catch (Throwable ignored) {} }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); try { loadSharedPrefs(); applyIntentOrDefault(intent); } catch (Throwable ignored) {} }
    @Override public void onDestroy() { super.onDestroy(); }

    private void loadSharedPrefs() {
        migrateOldPreferences(context);
        SharedPreferences sharedPref = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE);
        version = sharedPref.getInt("version", 0);
        lat = getDouble(sharedPref, "lat", 3.1390); // 默认吉隆坡
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
        try {
            Editor editor = context.getSharedPreferences(sharedPrefKey, Context.MODE_PRIVATE).edit();
            editor.putInt("version", version); putDouble(editor, "lat", lat); putDouble(editor, "lng", lng); putDouble(editor, "zoom", zoom);
            editor.putInt("mockCount", mockCount); editor.putInt("mockFrequency", mockFrequency); putDouble(editor, "dLat", dLat); putDouble(editor, "dLng", dLng);
            editor.putBoolean("mockSpeed", mockSpeed); editor.putLong("endTime", endTime); editor.putString("mapProvider", mapProvider); editor.apply();
        } catch (Throwable ignored) {}
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
        try {
            webView.loadUrl(Uri.parse("file:///android_asset/map.html").buildUpon().appendQueryParameter("lat", "" + lat).appendQueryParameter("lng", "" + lng).appendQueryParameter("zoom", "" + zoom).appendQueryParameter("provider", mapProvider).build().toString());
        } catch (Throwable ignored) {}
    }

    protected void applyLocation() {
        try {
            if (latIsEmpty() || lngIsEmpty()) { showSnackbar(context.getResources().getString(R.string.MainActivity_NoLatLong)); return; }
            lat = Double.parseDouble(editTextLat.getText().toString()); lng = Double.parseDouble(editTextLng.getText().toString());
            if (binder != null) {
                float[] speed = {0};
                if (mockSpeed) { Location.distanceBetween(lat, lng, lat + dLat / 1000000, lng + dLng / 1000000, speed); speed[0] /= mockFrequency * 1000L; }
                binder.startMock(lng, lat, dLng / 1000000, dLat / 1000000, mockFrequency * 1000L, mockCount, speed[0]);
                endTime = System.currentTimeMillis() + (mockCount - 1L) * mockFrequency * 1000L; saveSettings();
            }
        } catch (Throwable ignored) {}
    }

    void showSnackbar(String str) { try { Snackbar.make(findViewById(R.id.main_layout), str, Snackbar.LENGTH_SHORT).show(); } catch (Throwable ignored) {} }
    void showSnackbar(@StringRes int strRes) { try { Snackbar.make(findViewById(R.id.main_layout), strRes, Snackbar.LENGTH_SHORT).show(); } catch (Throwable ignored) {} }
    boolean latIsEmpty() { return editTextLat.getText().toString().isBlank(); }
    boolean lngIsEmpty() { return editTextLng.getText().toString().isBlank(); }
    protected void setMapMarker(double lat, double lng) { try { if (webView == null || webView.getUrl() == null) return; webView.loadUrl("javascript:setOnMap(" + lat + "," + lng + ");"); } catch (Throwable ignored) {} }

    void changeButtonToApply() {
        try {
            buttonApplyStop.setText("启动吉隆坡模拟定位");
            buttonApplyStop.setOnClickListener(view -> handleLocationButtonClick());
        } catch (Throwable ignored) {}
    }

    void changeButtonToStop() {
        try {
            buttonApplyStop.setText("关闭模拟定位并恢复");
            buttonApplyStop.setOnClickListener(view -> { 
                try { unbindService(this); } catch (Exception e) {} 
                disconnectService(); 
            });
        } catch (Throwable ignored) {}
    }

    public void setZoom(double zoom) { this.zoom = zoom; saveSettings(); }
    void setLatLng(double mLat, double mLng, SourceChange srcChange) {
        lat = mLat; lng = mLng;
        if (srcChange == CHANGE_FROM_EDITTEXT || srcChange == LOAD) { setMapMarker(lat, lng); }
        if (srcChange == CHANGE_FROM_MAP || srcChange == LOAD) { this.srcChange = CHANGE_FROM_MAP; editTextLat.setText(DECIMAL_FORMAT.format(lat)); editTextLng.setText(DECIMAL_FORMAT.format(lng)); this.srcChange = NONE; }
        saveSettings();
    }

    @Override public void onServiceConnected(ComponentName name, IBinder service) { try { binder = (MockedLocationService.MockedBinder) service; binder.mockState.observe(this, this::onMockedStateChange); binder.mockedLocation.observe(this, this::onMockedLocationChange); } catch (Throwable ignored) {} }
    @Override public void onServiceDisconnected(ComponentName name) { disconnectService(); }

    private void disconnectService() { if (binder == null) return; try { binder.mockState.removeObservers(this); binder.mockedLocation.removeObservers(this); } catch (Throwable ignored) {} binder = null; indicateMockStop(); }

    private void onMockedStateChange(MockState state) {
        switch (state) {
            case NOT_MOCKED -> indicateMockStop();
            case SERVICE_BOUND -> applyLocation();
            case MOCKED -> { changeButtonToStop(); showSnackbar("吉隆坡定位成功锁定！"); }
            case MOCK_ERROR -> showSnackbar("定位注入失败，请检查开发者选项绑定");
        }
    }

    private void indicateMockStop() { showSnackbar("定位模拟已停止"); changeButtonToApply(); }
    private void onMockedLocationChange(Location location) { setMapMarker(location.getLatitude(), location.getLongitude()); }
    public enum SourceChange { NONE, LOAD, CHANGE_FROM_EDITTEXT, CHANGE_FROM_MAP }
}
