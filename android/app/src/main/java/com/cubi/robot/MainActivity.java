package com.cubi.robot;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

// La interfaz vive en assets/index.html. La conexión con Cubi la lleva CubiService (sigue con la pantalla apagada).
public class MainActivity extends Activity {
    // Versiones publicadas por GitHub Actions (Cubi.apk y cubi.ino.bin en cada versión)
    static final String VERSIONES = "https://api.github.com/repos/ofco93-blip/cubi/releases/latest";

    private WebView web;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF0D0F12);
        getWindow().setNavigationBarColor(0xFF0D0F12);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Puente(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                if (CubiService.yo != null) js("bleEstado(" + JSONObject.quote(CubiService.yo.ultimoEstado) + ")");
            }
        });
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
        if (tienePermisos()) arrancar();
        instalacion(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (CubiService.yo != null) {
            CubiService.yo.oyente = this::js;
            if (CubiService.yo.holaActivo()) CubiService.yo.primerPlano();  // con la app a la vista recupera el micrófono
            if (CubiService.yo.climaActivo()) CubiService.yo.guardarUbicacion();
        }
    }

    @Override
    protected void onPause() {
        if (CubiService.yo != null) CubiService.yo.oyente = null;
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        instalacion(i);
    }

    @Override
    public void onBackPressed() {
        if (CubiService.yo != null && CubiService.yo.otaActiva) return;  // durante la actualización no se sale
        super.onBackPressed();
    }

    void js(String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    private void arrancar() {
        Intent i = new Intent(this, CubiService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        web.postDelayed(() -> { if (CubiService.yo != null) CubiService.yo.oyente = this::js; }, 300);
    }

    // ---------- permisos ----------
    private String[] permisos() {
        if (Build.VERSION.SDK_INT >= 33)
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS};
        if (Build.VERSION.SDK_INT >= 31)
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean tienePermisos() {
        for (String p : permisos())
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(permisos(), 2);
                return false;
            }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (code == 5) {  // ubicación aproximada para el clima
            boolean si = r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED;
            if (si && CubiService.yo != null) CubiService.yo.climaOn(true);
            else js("climaEstado(false)");
            return;
        }
        if (code == 4) {  // micrófono para "Hola Cubi"
            boolean si = r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED;
            if (si && CubiService.yo != null) CubiService.yo.hola(true);
            else js("holaEstado(false)");
            return;
        }
        if (code != 2) return;
        boolean ok = true;
        for (int i = 0; i < p.length; i++)
            if (!p[i].equals(Manifest.permission.POST_NOTIFICATIONS) && r[i] != PackageManager.PERMISSION_GRANTED) ok = false;
        if (ok) arrancar();
        else js("bleEstado('sin-permiso')");
    }

    // ---------- actualizar el firmware de Cubi desde un archivo ----------
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != 3) return;
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        try (InputStream in = getContentResolver().openInputStream(data.getData())) {
            mandarFirmware(leer(in));
        } catch (Exception e) {
            js("otaFin(false," + JSONObject.quote("No pude leer el archivo") + ")");
        }
    }

    private void mandarFirmware(byte[] fw) {
        // Un firmware del ESP32 empieza con 0xE9 y pesa más de 100 KB: así no se sube cualquier archivo por error
        if (fw.length < 100000 || (fw[0] & 0xFF) != 0xE9) {
            js("otaFin(false," + JSONObject.quote("Ese archivo no es un firmware de Cubi.") + ")");
            return;
        }
        if (CubiService.yo == null) return;
        runOnUiThread(() -> getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        CubiService.yo.ota(fw);
    }

    static byte[] leer(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }

    // ---------- actualizaciones desde GitHub: la app y el firmware de Cubi ----------
    private JSONObject ultimaVersion() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(VERSIONES).openConnection();
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setUseCaches(false);  // que siempre pregunte de nuevo
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        if (c.getResponseCode() == 404) throw new Exception("No veo las versiones en GitHub (¿el proyecto es privado?)");
        try (InputStream in = c.getInputStream()) {
            return new JSONObject(new String(leer(in), "UTF-8"));
        }
    }

    private String urlArchivo(JSONObject v, String nombre) throws Exception {
        JSONArray a = v.getJSONArray("assets");
        for (int i = 0; i < a.length(); i++)
            if (a.getJSONObject(i).getString("name").equals(nombre)) return a.getJSONObject(i).getString("browser_download_url");
        throw new Exception("La versión no trae " + nombre);
    }

    private byte[] descargar(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream()) {
            return leer(in);
        }
    }

    // número de compilación: el tag es v2.0.N y N es el versionCode de esa app
    private static int numero(String tag) {
        try { return Integer.parseInt(tag.substring(tag.lastIndexOf('.') + 1)); } catch (Exception e) { return 0; }
    }

    private int miVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }

    private void instalarApk(byte[] apk) throws Exception {
        PackageInstaller pi = getPackageManager().getPackageInstaller();
        int id = pi.createSession(new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL));
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream o = s.openWrite("cubi.apk", 0, apk.length)) {
                o.write(apk);
                s.fsync(o);
            }
            Intent i = new Intent(this, MainActivity.class).setAction("cubi.INSTALADA");
            int f = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getActivity(this, 7, i, f).getIntentSender());
        }
    }

    // Android pide confirmar la instalación: se muestra su ventana
    private void instalacion(Intent i) {
        if (i == null || !"cubi.INSTALADA".equals(i.getAction())) return;
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmar = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmar != null) startActivity(confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else if (st != PackageInstaller.STATUS_SUCCESS && st != -999) {
            js("toast(" + JSONObject.quote("No se instaló: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)) + ")");
        }
    }

    class Puente {
        @JavascriptInterface
        public void bleBuscar() {
            if (!tienePermisos()) return;
            if (CubiService.yo == null) arrancar();
            else CubiService.yo.buscar();
        }

        @JavascriptInterface
        public void bleEnviar(String orden) {
            if (CubiService.yo != null) CubiService.yo.enviar(orden);
        }

        @JavascriptInterface
        public void otaElegir() {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            runOnUiThread(() -> startActivityForResult(i, 3));
        }

        @JavascriptInterface
        public void otaListo() {
            runOnUiThread(() -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        }

        @JavascriptInterface
        public void abrirAsistente() {
            if (CubiService.yo != null) CubiService.yo.abrirAsistente();
        }

        @JavascriptInterface
        public boolean claudeListo() {
            return CubiService.yo != null && !CubiService.yo.claveClaude().isEmpty();
        }

        @JavascriptInterface
        public void claudeClave(String k) {
            if (CubiService.yo != null) CubiService.yo.guardarClaveClaude(k);
        }

        @JavascriptInterface
        public String vocesCubi() {  // [{n: nombre, q: calidad, red: usa internet}], la elegida en "sel"
            JSONArray a = new JSONArray();
            if (CubiService.yo == null) return "{}";
            try {
                for (android.speech.tts.Voice v : CubiService.yo.vocesEs())
                    a.put(new JSONObject().put("n", v.getName()).put("q", v.getQuality()).put("red", v.isNetworkConnectionRequired()));
                return new JSONObject().put("voces", a).put("sel", getSharedPreferences("cubi", 0).getString("voz", ""))
                        .put("tono", getSharedPreferences("cubi", 0).getFloat("tono", 1.4f)).toString();
            } catch (Exception e) { return "{}"; }
        }

        // abre los ajustes de texto a voz del celular (motor y voces); si no existen, el instalador de voces
        @JavascriptInterface
        public void abrirVoces() {
            try {
                startActivity(new Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception e2) {
                    js("toast('Busca “texto a voz” en los Ajustes del celular')");
                }
            }
        }

        @JavascriptInterface
        public void recargarVoces() {
            if (CubiService.yo != null) runOnUiThread(() -> CubiService.yo.reiniciarVoz());
        }

        @JavascriptInterface
        public void vozCubi(String nombre, float tono) {
            if (CubiService.yo != null) runOnUiThread(() -> { CubiService.yo.elegirVoz(nombre, tono); CubiService.yo.probarVoz(); });
        }

        @JavascriptInterface
        public void hablarCubi() {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 6);
            else if (CubiService.yo != null) runOnUiThread(() -> CubiService.yo.charla());
        }

        @JavascriptInterface
        public boolean puedeAbrirSolo() {
            return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(MainActivity.this);
        }

        @JavascriptInterface
        public void pedirAbrirSolo() {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        }

        @JavascriptInterface
        public void holaCubi(boolean si) {
            if (CubiService.yo == null) return;
            if (si && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 4);
            else CubiService.yo.hola(si);
        }

        @JavascriptInterface
        public void climaCubi(boolean si) {
            if (CubiService.yo == null) return;
            if (si && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 5);
            else CubiService.yo.climaOn(si);
        }

        @JavascriptInterface
        public boolean climaActivo() {
            return CubiService.yo != null && CubiService.yo.climaActivo();
        }

        @JavascriptInterface
        public String climaTexto() {
            return CubiService.yo == null ? "" : CubiService.yo.climaTexto;
        }

        @JavascriptInterface
        public boolean holaActivo() {
            return CubiService.yo != null && CubiService.yo.holaActivo();
        }

        @JavascriptInterface
        public int version() {
            return miVersion();
        }

        // Revisa GitHub y le cuenta a la interfaz: versión de la app y si hay nueva
        @JavascriptInterface
        public void buscarVersion() {
            new Thread(() -> {
                try {
                    JSONObject v = ultimaVersion();
                    js("versionGithub(" + numero(v.getString("tag_name")) + "," + miVersion() + ")");
                } catch (Exception e) {
                    js("versionGithub(-1,0," + JSONObject.quote(e.getMessage()) + ")");
                }
            }).start();
        }

        @JavascriptInterface
        public void actualizarApp() {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                js("toast('Activa “Permitir de esta fuente” y vuelve a tocar Actualizar la app')");
                return;
            }
            new Thread(() -> {
                try {
                    js("toast('Descargando la app nueva…')");
                    instalarApk(descargar(urlArchivo(ultimaVersion(), "Cubi.apk")));
                } catch (Exception e) {
                    js("toast(" + JSONObject.quote("No pude actualizar la app: " + e.getMessage()) + ")");
                }
            }).start();
        }

        @JavascriptInterface
        public void actualizarCubi() {
            new Thread(() -> {
                try {
                    js("toast('Descargando el firmware de Cubi…')");
                    mandarFirmware(descargar(urlArchivo(ultimaVersion(), "cubi.ino.bin")));
                } catch (Exception e) {
                    js("otaFin(false," + JSONObject.quote("No pude descargar el firmware: " + e.getMessage()) + ")");
                }
            }).start();
        }
    }
}
