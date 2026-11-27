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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
        spotifyVuelve(getIntent());
    }

    // vuelve del login de Spotify (cubi://spotify?code=...): cambia el código por la sesión
    private void spotifyVuelve(Intent i) {
        Uri u = i == null ? null : i.getData();
        if (u == null || !"cubi".equals(u.getScheme()) || !"spotify".equals(u.getHost())) return;
        String code = u.getQueryParameter("code");
        if (code == null) { js("toast('Spotify no se conectó: " + (u.getQueryParameter("error") == null ? "" : u.getQueryParameter("error").replace("'", "")) + "')"); return; }
        new Thread(() -> {
            boolean ok = new SpotifyApi(this).recibirCodigo(code);
            js(ok ? "toast('Spotify conectado: ya puedo poner canciones');spotifyEstado()" : "toast('No pude conectar Spotify')");
        }).start();
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
        spotifyVuelve(i);
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
        if (code == 6) {  // GPS
            boolean si = r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED;
            if (si && CubiService.yo != null) CubiService.yo.gpsOn(true);
            else js("gpsEstado(false)");
            return;
        }
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

    // el número de cada parte en esa versión (versiones.json): la app, Cubi y Mapi. null si la versión no lo trae.
    private JSONObject versiones(JSONObject v) {
        try { return new JSONObject(new String(descargar(urlArchivo(v, "versiones.json")), "UTF-8")); } catch (Exception e) { return null; }
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

    // Android Auto solo muestra en el carro apps que figuren instaladas por Play Store. Se pasa por el instalador del
    // celular pidiéndole esa marca (lo mismo que hace AAAD); si no se puede, se instala como antes.
    private void instalarApk(byte[] apk) throws Exception {
        try {
            File d = new File(getCacheDir(), "apk");
            d.mkdirs();
            File f = new File(d, "cubi.apk");
            try (OutputStream o = new FileOutputStream(f)) { o.write(apk); }
            Uri u = androidx.core.content.FileProvider.getUriForFile(this, "com.cubi.robot.archivos", f);
            startActivityForResult(new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, "com.android.vending").putExtra(Intent.EXTRA_RETURN_RESULT, true), 8);
            return;
        } catch (Exception e) {
            // sigue con el modo de antes
        }
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
        if (i != null && "cubi.AUTO".equals(i.getAction())) {
            i.setAction("");   // una sola vez: sin esto la ventana de instalar salía repetida   // reinstala esta misma versión con la marca, para que salga en Android Auto
            new Thread(() -> {
                try (InputStream in = new FileInputStream(getApplicationInfo().sourceDir)) { instalarApk(leer(in)); } catch (Exception e) { js("toast('No pude reinstalar')"); }
            }).start();
            return;
        }
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
        public void abrirAsistente() {   // la ventanita del asistente (como dejar apretado el botón de inicio), no la app de Gemini
            runOnUiThread(() -> {
                boolean abrio = false;
                try { abrio = showAssist(new Bundle()); } catch (Throwable ignorado) {}
                if (CubiService.yo == null) return;
                if (abrio) CubiService.yo.asistenteAbierto(); else CubiService.yo.abrirAsistente();
            });
        }

        // los registros de la app: al portapapeles o a "Compartir" (WhatsApp, correo…)
        @JavascriptInterface
        public void copiar(String t) {
            runOnUiThread(() -> ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("Cubi", t)));
        }

        @JavascriptInterface
        public void compartir(String t) {
            Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t);
            runOnUiThread(() -> startActivity(Intent.createChooser(s, "Mandar")));
        }

        @JavascriptInterface
        public String caida() { return CubiService.yo == null ? "" : CubiService.yo.caida(); }

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
        public String estadoLetra() {
            return CubiService.yo == null ? "" : CubiService.yo.estadoLetra;
        }

        @JavascriptInterface
        public String capturaEstado() {  // {on, kb, lineas}
            if (CubiService.yo == null) return "{}";
            java.io.File f = CubiService.yo.archivoCaptura();
            int lineas = 0;
            if (f.exists()) try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) { while (r.readLine() != null) lineas++; } catch (Exception ignored) {}
            try {
                return new JSONObject().put("on", CubiService.yo.capturando()).put("kb", f.length() / 1024).put("lineas", Math.max(0, lineas - 1)).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void capturaOn(boolean si) { if (CubiService.yo != null) CubiService.yo.capturar(si); }

        @JavascriptInterface
        public void capturaBorrar() { if (CubiService.yo != null) CubiService.yo.archivoCaptura().delete(); }

        // copia la captura a Descargas y abre "Compartir" para mandarla (WhatsApp, correo, Drive…)
        @JavascriptInterface
        public void capturaCompartir() {
            if (CubiService.yo == null) return;
            java.io.File f = CubiService.yo.archivoCaptura();
            if (!f.exists() || f.length() == 0) { js("toast('Todavía no hay nada capturado')"); return; }
            if (Build.VERSION.SDK_INT < 29) { js("toast('Esto necesita Android 10 o más nuevo')"); return; }
            try {
                String nombre = "cubi_captura_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(new java.util.Date()) + ".csv";
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, nombre);
                v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/csv");
                Uri u = getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                try (java.io.OutputStream o = getContentResolver().openOutputStream(u); java.io.InputStream i = new java.io.FileInputStream(f)) {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = i.read(b)) > 0) o.write(b, 0, n);
                }
                Intent s = new Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, u)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(s, "Mandar la captura de Cubi")));
                js("toast(" + JSONObject.quote("Guardada en Descargas: " + nombre) + ")");
            } catch (Exception e) {
                js("toast(" + JSONObject.quote("No pude guardar la captura: " + e.getMessage()) + ")");
            }
        }

        @JavascriptInterface
        public String personasCubi() {  // {"lista": [{n: nombre, r: reglas}], "sel": índice}
            if (CubiService.yo == null) return "{}";
            try {
                return new JSONObject().put("lista", CubiService.yo.personas()).put("sel", CubiService.yo.personaElegida()).toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void guardarPersonasCubi(String json, int sel) {
            if (CubiService.yo != null) CubiService.yo.guardarPersonas(json, sel);
        }

        @JavascriptInterface
        public boolean spotifyConectado() {
            return new SpotifyApi(MainActivity.this).conectado();
        }

        @JavascriptInterface
        public void spotifyConectar() {
            SpotifyApi s = new SpotifyApi(MainActivity.this);
            if (s.conectado()) { s.desconectar(); js("toast('Spotify desconectado');spotifyEstado()"); return; }
            try { s.conectar(); } catch (Exception e) { js("toast('No pude abrir el login de Spotify')"); }
        }

        @JavascriptInterface
        public boolean puedeTocarMusica() {
            return CubiService.yo == null || CubiService.yo.puedeTocarMusica();
        }

        // "¿Dónde parqueé?": la última ubicación guardada ("lat,lon,hora" o vacío) y abrirla en Maps
        @JavascriptInterface
        public String parqueoDir() { return getSharedPreferences("cubi", 0).getString("parqueoDir", ""); }
        @JavascriptInterface
        public String parqueo() { return CubiService.yo == null ? getSharedPreferences("cubi", 0).getString("parqueo", "") : CubiService.yo.parqueo(); }

        @JavascriptInterface
        public void abrirParqueo() {
            String[] p = parqueo().split(",");
            if (p.length < 2) return;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:" + p[0] + "," + p[1] + "?q=" + p[0] + "," + p[1] + "(Mi carro)")));
            } catch (Exception e) {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/maps/search/?api=1&query=" + p[0] + "," + p[1])));
            }
        }

        @JavascriptInterface
        public void pedirTocarMusica() {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        }

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
        public void gpsCubi(boolean si) {
            if (CubiService.yo == null) return;
            if (si && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 6);
            else CubiService.yo.gpsOn(si);
        }

        @JavascriptInterface
        public boolean gpsActivo() {
            return CubiService.yo != null && CubiService.yo.gpsActivo();
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
        public boolean navActiva() {
            return CubiService.yo == null || CubiService.yo.navActiva();
        }

        @JavascriptInterface
        public void navCubi(boolean si) {
            if (CubiService.yo != null) CubiService.yo.navOn(si);
        }

        // abre Google Maps navegando al mismo punto del destino escrito en Cubi, para que la ruta de Cubi y la de Maps vayan al mismo sitio
        @JavascriptInterface
        public void irMaps() {
            double[] d = CubiService.yo == null ? null : CubiService.yo.destinoPunto();
            if (d == null) { js("toast('Primero escribe el destino y toca Trazar ruta')"); return; }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(String.format(java.util.Locale.US, "google.navigation:q=%.6f,%.6f", d[0], d[1]))).setPackage("com.google.android.apps.maps"));
            } catch (Exception e) { js("toast('No pude abrir Google Maps')"); }
        }

        // la mascota de la pantalla del carro (las lee CubiCarro en cada cuadro): personaje, fondo y vista
        @JavascriptInterface
        public int carroOpcion(String k) {
            android.content.SharedPreferences p = getSharedPreferences("cubi", 0);
            if (k.equals("carroZelda")) return p.getBoolean(k, true) ? 1 : 0;
            if (k.equals("carroZeldaTam")) return p.getInt(k, 0) % 3;
            if (k.equals("carroPila")) return p.getInt(k, 0) % 4;
            return k.equals("carroFondo") ? p.getInt(k, -1) : p.getInt("carroVista", p.getBoolean("carroSola", false) ? 1 : 0);
        }

        // lo último que anotó la caja negra del carro (CubiCarroDatos): se ve y se copia desde la pestaña, sin cable
        @JavascriptInterface
        public String carroRegistro() {
            try {
                java.io.File f = new java.io.File(getExternalFilesDir(null), "datos_carro.txt");
                if (!f.exists()) return "Todavía no hay nada: conecta el celular al carro y abre Cubi en la pantalla.";
                try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
                    long n = r.length(); byte[] b = new byte[(int) Math.min(n, 24000)];
                    r.seek(n - b.length); r.readFully(b);
                    String t = new String(b, "UTF-8"); int i = t.indexOf('\n');
                    return n > b.length && i >= 0 ? t.substring(i + 1) : t;   // si se cortó, desde el primer renglón completo
                }
            } catch (Exception e) { return "No pude leer el registro: " + e; }
        }

        @JavascriptInterface
        public void carroPon(String k, int v) {
            android.content.SharedPreferences.Editor e = getSharedPreferences("cubi", 0).edit();
            if (k.equals("carroZelda")) e.putBoolean(k, v != 0);
            else if (k.equals("carroFondo")) e.putInt(k, Math.max(-1, Math.min(4, v)));
            else if (k.equals("carroVista")) e.putInt(k, Math.max(0, Math.min(2, v)));
            else if (k.equals("carroZeldaTam")) e.putInt(k, Math.max(0, Math.min(2, v)));
            else if (k.equals("carroPila")) e.putInt(k, Math.max(0, Math.min(3, v)));
            e.apply();
        }

        @JavascriptInterface
        public boolean navMapa() {
            return CubiService.yo != null && CubiService.yo.navMapa();
        }

        @JavascriptInterface
        public void navMapaCubi(boolean si) {
            if (CubiService.yo != null) CubiService.yo.navMapaOn(si);
        }

        @JavascriptInterface
        public boolean navCompleto() {
            return CubiService.yo != null && CubiService.yo.navCompleto();
        }

        @JavascriptInterface
        public void navCompletoCubi(boolean si) {
            if (CubiService.yo != null) CubiService.yo.navCompletoOn(si);
        }

        @JavascriptInterface
        public String destinoTexto() {
            return CubiService.yo == null ? "" : CubiService.yo.destinoTexto();
        }

        @JavascriptInterface
        public void destinoCubi(String txt) {
            if (CubiService.yo != null) CubiService.yo.destino(txt);
        }

        // el mapa de Google (opcional): la llave la pega el usuario; no se le devuelve a la página, solo cómo termina
        @JavascriptInterface
        public String googleEstado() {
            String k = CubiGoogle.llave(MainActivity.this);
            if (k.isEmpty()) return "Sin llave: Cubi usa su mapa de siempre.";
            String cola = k.length() > 4 ? k.substring(k.length() - 4) : k;
            return (CubiGoogle.busca.isEmpty() ? "" : CubiGoogle.busca + " ") + "Llave guardada (termina en " + cola + "). " + (CubiGoogle.lista(MainActivity.this) ? (CubiGoogle.estado.isEmpty() ? "Lista: se usa al conectar el carro." : CubiGoogle.estado)
                    : CubiGoogle.estado.isEmpty() ? "Falta aceptar los términos de Google: toca Guardar otra vez." : CubiGoogle.estado);
        }
        @JavascriptInterface
        public void googleLlave(String k) {
            getSharedPreferences("cubi", 0).edit().putString("llaveGoogle", k == null ? "" : k.trim()).apply();
            if (k != null && !k.trim().isEmpty()) runOnUiThread(() -> CubiGoogle.aceptar(MainActivity.this));
        }
        @JavascriptInterface
        public void googleAceptar() { if (!CubiGoogle.llave(MainActivity.this).isEmpty()) runOnUiThread(() -> CubiGoogle.aceptar(MainActivity.this)); }
        @JavascriptInterface
        public String mapiEstado() { return CubiMapi.estado(); }
        @JavascriptInterface
        public boolean mapiActualizando() { return CubiMapi.actualizando(); }
        @JavascriptInterface
        public String mapiVersion() { return CubiMapi.version(); }
        @JavascriptInterface
        public boolean googleUsa() { return CubiGoogle.elegido(MainActivity.this); }
        @JavascriptInterface
        public void googleUsar(boolean si) { getSharedPreferences("cubi", 0).edit().putBoolean("mapaGoogle", si).apply(); }
        @JavascriptInterface
        public void googleAbrir() {
            try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://console.cloud.google.com/google/maps-apis/start"))); }
            catch (Exception e) { js("toast('No pude abrir el navegador')"); }
        }

        // los destinos favoritos de la pantalla del carro: renglones "nombre<tab>destino"
        @JavascriptInterface
        public String favLista() { return getSharedPreferences("cubi", 0).getString("destinosFav", ""); }
        @JavascriptInterface
        public void favPon(String nombre, String destino) {
            String n = nombre == null ? "" : nombre.replaceAll("[\\t\\n]", " ").trim(), d = destino == null ? "" : destino.replaceAll("[\\t\\n]", " ").trim();
            if (n.isEmpty() || d.isEmpty()) return;
            String antes = favLista();
            getSharedPreferences("cubi", 0).edit().putString("destinosFav", (antes.isEmpty() ? "" : antes + "\n") + n + "\t" + d).apply();
        }
        @JavascriptInterface
        public void favQuita(int cual) {
            java.util.ArrayList<String> l = new java.util.ArrayList<>(java.util.Arrays.asList(favLista().split("\n")));
            if (cual >= 0 && cual < l.size()) l.remove(cual);
            getSharedPreferences("cubi", 0).edit().putString("destinosFav", android.text.TextUtils.join("\n", l)).apply();
        }

        @JavascriptInterface
        public String paisEstado() { return CubiService.yo == null ? "" : CubiService.yo.estadoPais(); }
        @JavascriptInterface
        public void paisBajar() { if (CubiService.yo != null) CubiService.yo.bajarPais(); }
        @JavascriptInterface
        public void paisBorrar() { if (CubiService.yo != null) CubiService.yo.borrarPais(); }

        @JavascriptInterface
        public String estadoNav() {
            return CubiService.yo == null ? "" : CubiService.yo.estadoNav();
        }

        @JavascriptInterface
        public boolean holaActivo() {
            return CubiService.yo != null && CubiService.yo.holaActivo();
        }

        @JavascriptInterface
        public String estadoHola() {
            return CubiService.yo == null ? "" : CubiService.yo.estadoHola();
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
                    JSONObject v = ultimaVersion(), vs = versiones(v);
                    int ult = numero(v.getString("tag_name"));
                    js("versionGithub(" + ult + "," + miVersion() + ",''" + "," + (vs == null ? ult : vs.optInt("cubi", ult)) + "," + (vs == null ? ult : vs.optInt("mapi", ult)) + ")");
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

        // mapas sin internet: las ciudades y departamentos escogidos se guardan solo en este celular
        @JavascriptInterface public String mapasZonas() { return getSharedPreferences("cubi", 0).getString("mapasZonas", "[]"); }
        @JavascriptInterface public void mapasGuardar(String zonas) { getSharedPreferences("cubi", 0).edit().putString("mapasZonas", zonas).apply(); }
        @JavascriptInterface public int mapasCuenta(String zonas) { return CubiMapas.cuenta(zonas); }
        private String pais() {
            String pais = null;
            try { pais = ((android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE)).getNetworkCountryIso(); } catch (Exception ignored) { }
            return pais == null || pais.length() != 2 ? java.util.Locale.getDefault().getCountry() : pais;
        }
        @JavascriptInterface
        public void mapasBuscar(String q) {
            new Thread(() -> {
                String r;
                try { r = CubiMapas.buscar(q, pais()).toString(); } catch (Exception e) { r = "null"; }
                js("mapasOpciones(" + JSONObject.quote(q) + "," + JSONObject.quote(r) + ")");
            }).start();
        }
        @JavascriptInterface public float mapiAjuste(String k) { return getSharedPreferences("cubi", 0).getFloat(k, k.equals("mapiGiro") || k.equals("mapiEstilo") ? 0 : 1); }
        @JavascriptInterface public void mapiAjustar(String k, float v) { if (k.matches("mapi(Zoom|Flecha|Giro|Estilo)")) getSharedPreferences("cubi", 0).edit().putFloat(k, v).apply(); }
        @JavascriptInterface public String mapasEstado() { return CubiMapas.resumen(MainActivity.this); }
        @JavascriptInterface
        public void mapasBajar() { CubiMapas.descargar(getApplicationContext(), mapasZonas()); }

        @JavascriptInterface
        public void actualizarMapi() {
            new Thread(() -> {
                try {
                    js("toast('Descargando el firmware de Mapi…')");
                    if (!CubiMapi.listo()) { js("toast('Mapi no está conectado')"); return; }
                    JSONObject v = ultimaVersion(), vs = versiones(v);
                    String ult = vs != null && vs.has("mapi") ? "2.0." + vs.getInt("mapi") : v.getString("tag_name").replaceFirst("^v", "");
                    if (ult.equals(CubiMapi.version())) { js("toast(" + JSONObject.quote("Mapi ya tiene la última versión (" + ult + ")") + ")"); return; }
                    byte[] fw = descargar(urlArchivo(v, "mapi.ino.bin"));
                    if (CubiMapi.yo != null) CubiMapi.yo.ota(fw);
                } catch (Exception e) {
                    js("toast(" + JSONObject.quote("No pude descargar el firmware de Mapi: " + e.getMessage()) + ")");
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
