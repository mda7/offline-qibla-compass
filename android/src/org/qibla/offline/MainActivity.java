package org.qibla.offline;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;

public final class MainActivity extends Activity implements SensorEventListener {
    private static final int LOCATION_REQUEST = 1;
    private static final String PAGE = "file:///android_asset/index.html";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private SensorManager sensorManager;
    private Sensor compassSensor;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private Runnable locationTimeout;
    private boolean freshLocationRequested;
    private boolean compassWanted;
    private float declination;
    private long lastHeadingAt;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        compassSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (compassSensor == null) {
            compassSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
        }
        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setAllowContentAccess(false);
        webView.getSettings().setBlockNetworkLoads(true);
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !PAGE.equals(request.getUrl().toString());
            }
        });
        setContentView(webView);
        webView.loadUrl(PAGE);
    }

    private void js(String expression) {
        if (webView != null) webView.evaluateJavascript(expression, null);
    }

    private void compassError(String message) {
        js("window.qiblaNativeCompassError(" + JSONObject.quote(message) + ")");
    }

    private void locationError(String message) {
        js("window.qiblaNativeLocationError(" + JSONObject.quote(message) + ")");
    }

    private void startCompass() {
        if (compassSensor == null) {
            compassError("No compass sensor is available. Use the bearing above.");
            return;
        }
        compassWanted = true;
        sensorManager.registerListener(this, compassSensor, SensorManager.SENSOR_DELAY_UI);
    }

    private void stopCompass() {
        compassWanted = false;
        sensorManager.unregisterListener(this);
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!compassWanted || event.sensor != compassSensor) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastHeadingAt < 80) return;
        lastHeadingAt = now;
        float[] matrix = new float[9];
        float[] angles = new float[3];
        SensorManager.getRotationMatrixFromVector(matrix, event.values);
        SensorManager.getOrientation(matrix, angles);
        double heading = (Math.toDegrees(angles[0]) + declination + 360) % 360;
        js("window.qiblaNativeHeading(" + heading + ")");
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void getLocation(boolean fresh) {
        freshLocationRequested = fresh;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCATION_REQUEST);
            return;
        }
        try {
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationError("GPS is off. Enable device Location and tap Show Qibla again.");
                return;
            }
            Location cached = fresh ? null : locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (cached != null && System.currentTimeMillis() - cached.getTime() < 300000) {
                deliverLocation(cached);
                return;
            }
            stopLocation();
            locationListener = location -> {
                deliverLocation(location);
                stopLocation();
            };
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListener, Looper.getMainLooper());
            locationTimeout = () -> {
                stopLocation();
                locationError("GPS timed out. Try outdoors with a clear view of the sky, then tap Show Qibla again.");
            };
            handler.postDelayed(locationTimeout, 60000);
        } catch (SecurityException exception) {
            locationError("Location permission is unavailable. Enable it in Settings and try again.");
        }
    }

    private void deliverLocation(Location location) {
        js("window.qiblaNativeLocation(" + location.getLatitude() + "," + location.getLongitude() + "," +
            (location.hasAccuracy() ? location.getAccuracy() : "null") + ")");
    }

    private void stopLocation() {
        if (locationListener != null) locationManager.removeUpdates(locationListener);
        locationListener = null;
        if (locationTimeout != null) handler.removeCallbacks(locationTimeout);
        locationTimeout = null;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != LOCATION_REQUEST) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) getLocation(freshLocationRequested);
        else locationError("Location permission denied. Enable it in Settings and try again.");
    }

    @Override protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override protected void onResume() {
        super.onResume();
        if (compassWanted && compassSensor != null) sensorManager.registerListener(this, compassSensor, SensorManager.SENSOR_DELAY_UI);
    }

    @Override protected void onDestroy() {
        stopLocation();
        stopCompass();
        webView.destroy();
        webView = null;
        super.onDestroy();
    }

    private final class Bridge {
        @JavascriptInterface public void startCompass() {
            runOnUiThread(MainActivity.this::startCompass);
        }
        @JavascriptInterface public void stopCompass() {
            runOnUiThread(MainActivity.this::stopCompass);
        }
        @JavascriptInterface public void getLocation(boolean fresh) {
            runOnUiThread(() -> MainActivity.this.getLocation(fresh));
        }
        @JavascriptInterface public void setDeclination(double latitude, double longitude) {
            runOnUiThread(() -> {
                GeomagneticField field = new GeomagneticField((float) latitude, (float) longitude, 0, System.currentTimeMillis());
                declination = field.getDeclination();
            });
        }
    }
}
