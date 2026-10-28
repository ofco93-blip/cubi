package com.cubi.robot;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

// La interfaz vive en assets/index.html y le habla al robot por HTTP en la red local.
public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> archivo;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF0D0F12);
        getWindow().setNavigationBarColor(0xFF0D0F12);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        web.addJavascriptInterface(new Puente(), "Android");
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                archivo = cb;
                try {
                    startActivityForResult(p.createIntent(), 1);
                } catch (Exception e) {
                    archivo = null;
                    return false;
                }
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 1 && archivo != null) {
            archivo.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            archivo = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    class Puente {
        // Primeros tres números de la IP del celular (ej. "192.168.1.") para buscar al robot en la WiFi de la casa.
        @JavascriptInterface
        public String subred() {
            WifiManager w = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int ip = w.getConnectionInfo().getIpAddress();
            if (ip == 0) return "";
            return (ip & 255) + "." + ((ip >> 8) & 255) + "." + ((ip >> 16) & 255) + ".";
        }
    }
}
