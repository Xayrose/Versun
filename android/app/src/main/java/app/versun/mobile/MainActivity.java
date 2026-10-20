package app.versun.mobile;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import java.util.Calendar;
import android.os.Bundle;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.view.View;
import android.graphics.Color;
import android.provider.MediaStore;
import android.widget.Toast;

public class MainActivity extends Activity {
    private WebView web;
    private PermissionRequest pendingWebPermission;
    private ValueCallback<Uri[]> fileCallback;
    private static final int REQ_CAMERA = 7101;
    private static final int REQ_FILE = 7102;
    private static final int REQ_NOTIFICATIONS = 7103;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(2,8,6));
        getWindow().setNavigationBarColor(Color.rgb(2,8,6));
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(5,15,10));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new AndroidBridge(this), "VersunNative");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (!"https://xayrose.github.io".equals(request.getOrigin().getScheme() + "://" + request.getOrigin().getHost())) {
                        request.deny(); return;
                    }
                    boolean wantsCamera = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) wantsCamera = true;
                    }
                    if (!wantsCamera) { request.deny(); return; }
                    pendingWebPermission = request;
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        grantPendingCamera();
                    } else {
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    }
                });
            }

            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingWebPermission == request) pendingWebPermission = null;
            }

            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("image/*");
                Intent chooser = Intent.createChooser(pick, "Выбрать или снять фото");
                if (camera.resolveActivity(getPackageManager()) != null) {
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
                }
                try { startActivityForResult(chooser, REQ_FILE); }
                catch (Exception e) { fileCallback = null; callback.onReceiveValue(null); return false; }
                return true;
            }
        });
        setContentView(web);
        if (state == null) web.loadUrl("https://xayrose.github.io/Versun/app.html");
        else web.restoreState(state);
    }

    public class AndroidBridge {
        private final Context context;
        AndroidBridge(Context context) { this.context = context.getApplicationContext(); }
        @android.webkit.JavascriptInterface
        public void setTrainingReminder(String time, boolean enabled) {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
                }
                Intent intent = new Intent(context, ReminderReceiver.class);
                PendingIntent pending = PendingIntent.getBroadcast(context, 8101, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                if (alarm == null) return;
                alarm.cancel(pending);
                if (!enabled || time == null || !time.matches("[0-2][0-9]:[0-5][0-9]")) return;
                try {
                    String[] parts = time.split(":");
                    Calendar when = Calendar.getInstance();
                    when.set(Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0]));
                    when.set(Calendar.MINUTE, Integer.parseInt(parts[1]));
                    when.set(Calendar.SECOND, 0);
                    when.set(Calendar.MILLISECOND, 0);
                    when.add(Calendar.HOUR_OF_DAY, -1);
                    if (when.getTimeInMillis() <= System.currentTimeMillis()) when.add(Calendar.DAY_OF_YEAR, 1);
                    alarm.setInexactRepeating(AlarmManager.RTC_WAKEUP, when.getTimeInMillis(), AlarmManager.INTERVAL_DAY, pending);
                } catch (Exception ignored) { }
            });
        }
    }

    private void grantPendingCamera() {
        if (pendingWebPermission == null) return;
        PermissionRequest request = pendingWebPermission;
        pendingWebPermission = null;
        try { request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE}); }
        catch (Exception ignored) { try { request.deny(); } catch (Exception ignoredAgain) {} }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_CAMERA) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) grantPendingCamera();
            else if (pendingWebPermission != null) { pendingWebPermission.deny(); pendingWebPermission = null; Toast.makeText(this, "Разреши Versun доступ к камере в настройках Android", Toast.LENGTH_LONG).show(); }
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK) {
                if (data != null && data.getData() != null) results = new Uri[]{data.getData()};
                else if (data != null && data.getClipData() != null && data.getClipData().getItemCount() > 0) results = new Uri[]{data.getClipData().getItemAt(0).getUri()};
            }
            fileCallback.onReceiveValue(results);
            fileCallback = null;
        }
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }
    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
