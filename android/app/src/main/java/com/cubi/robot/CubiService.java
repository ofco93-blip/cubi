package com.cubi.robot;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.app.Service;
import android.app.UiModeManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.speech.tts.Voice;
import android.view.KeyEvent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.MessageCreateParams;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import org.json.JSONArray;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

// Mantiene la conexión Bluetooth con Cubi aunque la pantalla del celular esté apagada.
// La interfaz (MainActivity) se engancha cuando está abierta; sin ella, Cubi sigue funcionando
// (por ejemplo: doble toque en Cubi → abre Gemini).
@SuppressLint("MissingPermission")
public class CubiService extends Service {
    static final UUID SRV = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID OTA = UUID.fromString("6e400004-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final int PEDAZO = 240;  // bytes del firmware por escritura (cabe en el MTU de 247)

    static CubiService yo;  // la interfaz lo usa directo (mismo proceso)

    interface Oyente { void js(String code); }
    Oyente oyente;
    private final Handler ui = new Handler(Looper.getMainLooper());
    String ultimoEstado = "desconectado";

    private BluetoothLeScanner scanner;
    private ScanCallback scanCb;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx, ota;
    private boolean listo, ocupado;

    // Cola de escrituras: Android solo deja una operación de Bluetooth a la vez.
    static class Paquete {
        final boolean firmware; final byte[] datos;
        Paquete(boolean f, byte[] d) { firmware = f; datos = d; }
    }
    private final ArrayDeque<Paquete> cola = new ArrayDeque<>();
    private Paquete enVuelo;
    private final StringBuilder entrada = new StringBuilder();

    // Actualización en curso
    private byte[] fw;
    private int otaEnviados, otaTotal, otaPct = -1;
    boolean otaActiva;
    private boolean otaTerminando;

    @Override
    public void onCreate() {
        super.onCreate();
        yo = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel("cubi", "Cubi conectado", NotificationManager.IMPORTANCE_LOW));
        Intent abrir = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, abrir, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "cubi") : new Notification.Builder(this);
        aviso = nb.setContentTitle("Cubi")
                .setContentText("Conectado por Bluetooth: doble toque en Cubi abre Gemini")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        // "Hola Cubi" prendido de entrada (se apaga en la app); no escucha mientras el celular tiene un video sonando
        oidoQuiere = getSharedPreferences("cubi", 0).getBoolean("oido", true);
        clima();
        primerPlano();
        gps();
        escucharAsistente();
        iniciarVoz();
        if (oidoQuiere) prepararOido();
    }

    private Notification aviso;
    private boolean micFondo = true;   // Android le dio el micrófono al servicio (si arrancó con la app cerrada, no)

    // Con "Hola Cubi" el servicio también usa el micrófono. Android solo lo deja pedir con la app a la vista:
    // si arrancó solo (en segundo plano) sigue sin micrófono hasta que se abra la app.
    void primerPlano() {
        if (Build.VERSION.SDK_INT < 29) { startForeground(1, aviso); return; }
        int tipo = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (oidoQuiere && Build.VERSION.SDK_INT >= 30 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == 0)
            tipo |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        int sinGps = tipo;
        if (gpsActivo() && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == 0) tipo |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        try {
            startForeground(1, aviso, tipo);
        } catch (Exception e) {   // Android no deja sumar el GPS (o el micrófono) desde segundo plano: se prueba con menos
            try { startForeground(1, aviso, sinGps); } catch (Exception e2) { startForeground(1, aviso, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE); tipo = 0; }
        }
        micFondo = (tipo & ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0;
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        buscar();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        if (gatt != null) gatt.close();
        oido = null;
        if (oyeGps != null) ((LocationManager) getSystemService(LOCATION_SERVICE)).removeUpdates(oyeGps);
        if (voz != null) voz.shutdown();
        if (oreja != null) oreja.destroy();
        bajarMusica(false);
        yo = null;
        super.onDestroy();
    }

    private void js(String code) {
        ui.post(() -> { if (oyente != null) oyente.js(code); });
    }

    private void avisar(String estado) {
        ultimoEstado = estado;
        js("bleEstado(" + JSONObject.quote(estado) + ")");
    }

    // ---------- buscar y conectar ----------
    void buscar() {
        if (gatt != null || scanCb != null) return;
        BluetoothAdapter a = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        if (a == null || !a.isEnabled()) {
            avisar("apagado");
            return;
        }
        try {
            avisar("buscando");
            scanner = a.getBluetoothLeScanner();
            scanCb = new ScanCallback() {
                @Override
                public void onScanResult(int tipo, ScanResult r) {
                    pararBusqueda();
                    conectar(r.getDevice());
                }
            };
            ScanFilter f = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SRV)).build();
            ScanSettings cfg = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(Collections.singletonList(f), cfg, scanCb);
        } catch (SecurityException e) {
            scanCb = null;
            avisar("sin-permiso");
            return;
        }
        ui.postDelayed(() -> {
            if (scanCb != null) {
                pararBusqueda();
                avisar("no-encontrado");
                ui.postDelayed(this::buscar, 30000);  // sigue intentando solo (Android limita las búsquedas seguidas)
            }
        }, 12000);
    }

    private void pararBusqueda() {
        if (scanner != null && scanCb != null) scanner.stopScan(scanCb);
        scanCb = null;
    }

    private void conectar(BluetoothDevice d) {
        if (gatt != null) return;
        avisar("conectando");
        gatt = d.connectGatt(this, false, new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int st, int nuevo) {
                if (nuevo == BluetoothProfile.STATE_CONNECTED) g.requestMtu(247);
                else if (nuevo == BluetoothProfile.STATE_DISCONNECTED) {
                    g.close();
                    synchronized (cola) {
                        gatt = null;
                        listo = ocupado = false;
                        cola.clear();
                        enVuelo = null;
                    }
                    if (otaTerminando) otaFin(true, "Cubi se está reiniciando con la versión nueva");
                    else if (otaActiva) otaFin(false, "Se perdió la conexión. Cubi sigue con la versión que tenía; vuelve a intentarlo.");
                    avisar("desconectado");
                    ui.postDelayed(CubiService.this::buscar, otaTerminando ? 6000 : 3000);  // se reconecta solo
                }
            }

            @Override
            public void onMtuChanged(BluetoothGatt g, int mtu, int st) {
                g.discoverServices();
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int st) {
                BluetoothGattService s = g.getService(SRV);
                if (s == null) {
                    g.disconnect();
                    return;
                }
                rx = s.getCharacteristic(RX);
                ota = s.getCharacteristic(OTA);
                BluetoothGattCharacteristic tx = s.getCharacteristic(TX);
                g.setCharacteristicNotification(tx, true);
                BluetoothGattDescriptor dsc = tx.getDescriptor(CCCD);
                dsc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(dsc);
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) {
                synchronized (cola) {
                    listo = true;
                }
                avisar("conectado");
                ui.post(CubiService.this::clima);
                ui.post(() -> { musicaPlay = false; videoMandado = false; enviar("musica|/musica?video=0"); revisarAsistente(); });  // al reconectar le cuenta si hay música
                siguiente();
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
                Paquete p;
                synchronized (cola) {
                    p = enVuelo;
                    enVuelo = null;
                    ocupado = false;
                }
                if (p != null && p.firmware) {
                    if (st != BluetoothGatt.GATT_SUCCESS) {
                        otaFallo("Falló un pedazo del envío. Cubi sigue con la versión que tenía.");
                        return;
                    }
                    otaEnviados += p.datos.length;
                    int pct = (int) (otaEnviados * 100L / otaTotal);
                    if (pct != otaPct) {
                        otaPct = pct;
                        js("otaProgreso(" + otaEnviados + "," + otaTotal + ")");
                    }
                    if (otaEnviados >= otaTotal) enviar("ota|/ota/fin");
                }
                siguiente();
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
                if (Build.VERSION.SDK_INT < 33) llega(c.getValue());
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) {
                llega(v);
            }
        }, BluetoothDevice.TRANSPORT_LE);
    }

    // El robot responde "id|respuesta\n" en pedazos: se juntan hasta el salto de línea.
    // "ota|..." es de la actualización, "ev|..." son avisos de Cubi y "asis|..." respuestas del asistente: los maneja el servicio.
    private void llega(byte[] v) {
        synchronized (entrada) {
            entrada.append(new String(v, StandardCharsets.UTF_8));
            int n;
            while ((n = entrada.indexOf("\n")) >= 0) {
                String linea = entrada.substring(0, n);
                entrada.delete(0, n + 1);
                if (linea.startsWith("ota|")) respuestaOta(linea.substring(4));
                else if (linea.startsWith("ev|")) evento(linea.substring(3));
                else if (linea.startsWith("rasgos|")) anotarRasgos(linea.substring(7));
                else if (!linea.startsWith("asis|")) js("bleRespuesta(" + JSONObject.quote(linea) + ")");
            }
        }
    }

    void enviar(String s) {
        if (otaActiva && !s.startsWith("ota|")) return;  // durante la actualización no se manda nada más
        synchronized (cola) {
            if (cola.size() < 20 || otaActiva) cola.add(new Paquete(false, s.getBytes(StandardCharsets.UTF_8)));
        }
        siguiente();
    }

    private void siguiente() {
        synchronized (cola) {
            if (!listo || ocupado || cola.isEmpty() || gatt == null) return;
            Paquete p = cola.poll();
            BluetoothGattCharacteristic c = p.firmware ? ota : rx;
            c.setValue(p.datos);
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);  // con confirmación: ninguno se pierde
            enVuelo = p;
            ocupado = gatt.writeCharacteristic(c);
            if (!ocupado) enVuelo = null;
        }
    }

    // ---------- clima: el celular lo consulta (Open-Meteo, gratis y sin cuenta) y se lo cuenta a Cubi ----------
    String climaTexto = "";
    boolean climaActivo() { return getSharedPreferences("cubi", 0).getBoolean("clima", false); }
    void climaOn(boolean si) { getSharedPreferences("cubi", 0).edit().putBoolean("clima", si).apply(); clima(); }

    // ubicación aproximada (2 decimales, ~1 km): basta para el clima. Se guarda para usarla con la app cerrada.
    @SuppressLint("MissingPermission")
    void guardarUbicacion() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location mejor = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (mejor == null || l.getTime() > mejor.getTime())) mejor = l;
            }
        } catch (SecurityException e) { return; }
        if (mejor != null) getSharedPreferences("cubi", 0).edit()
            .putString("lugar", String.format(Locale.US, "%.2f,%.2f", mejor.getLatitude(), mejor.getLongitude())).apply();
    }

    private final Runnable climaCada = this::clima;
    private long climaCuando;
    void clima() {
        ui.removeCallbacks(climaCada);
        if (!climaActivo()) return;
        climaCuando = System.currentTimeMillis();
        ui.postDelayed(climaCada, 20 * 60000);
        guardarUbicacion();
        String lugar = getSharedPreferences("cubi", 0).getString("lugar", "");
        if (lugar.isEmpty()) { climaTexto = "Abre la app una vez con la ubicación prendida"; js("climaTexto(" + JSONObject.quote(climaTexto) + ")"); return; }
        String[] ll = lugar.split(",");
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://api.open-meteo.com/v1/forecast?latitude=" + ll[0] + "&longitude=" + ll[1]
                    + "&current=temperature_2m,weather_code,is_day").openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(10000);
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) { byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); }
                JSONObject cur = new JSONObject(b.toString("UTF-8")).getJSONObject("current");
                int t = (int) Math.round(cur.getDouble("temperature_2m")), cod = cur.getInt("weather_code"), dia = cur.getInt("is_day");
                // códigos de la OMM: 0-1 despejado, 2-3 nubes, 45-48 niebla, 51-67 y 80-82 lluvia, 71-77 y 85-86 nieve, 95-99 tormenta
                String e = cod <= 1 ? "despejado" : cod <= 3 ? "nubes" : cod <= 48 ? "niebla" : cod <= 67 ? "lluvia"
                    : cod <= 77 ? "nieve" : cod <= 82 ? "lluvia" : cod <= 86 ? "nieve" : "tormenta";
                climaOrden = "/clima?t=" + t + "&e=" + e + "&d=" + dia;
                enviar("clima|" + climaOrden);
                climaTexto = "Ahora: " + e + ", " + t + " grados";
                js("climaTexto(" + JSONObject.quote(climaTexto) + ")");
            } catch (Exception ignored) {}
        }).start();
    }

    // ---------- GPS: "¡llegamos!" sin Google Maps y el clima del camino ----------
    boolean gpsActivo() { return getSharedPreferences("cubi", 0).getBoolean("gps", false); }
    void gpsOn(boolean si) { getSharedPreferences("cubi", 0).edit().putBoolean("gps", si).apply(); primerPlano(); gps(); }
    private LocationListener oyeGps;
    private Location antesGps, quietoEn, climaEn;
    private long quietoDesde;
    private float recorrido;   // metros andados desde la última llegada
    float kmh = -1;
    int altura = Integer.MIN_VALUE, alturaTramo = Integer.MIN_VALUE;   // metros sobre el mar y en qué tramo de 100 m vamos

    @SuppressLint("MissingPermission")
    void gps() {
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (oyeGps != null) { lm.removeUpdates(oyeGps); oyeGps = null; }
        kmh = -1;
        if (!gpsActivo() || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        oyeGps = this::posicion;
        // cada 5 s y sin distancia mínima: parado también tiene que avisar, si no nunca sabría que ya llegamos
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, navMapa() ? 1000 : 5000, 0, oyeGps, Looper.getMainLooper()); }
        catch (Exception e) { oyeGps = null; }
    }

    // ---------- mini mapa: las calles de alrededor (OpenStreetMap) y por dónde va el carro, para la tarjeta de giro ----------
    // Solo con "Modo mapa" prendido y una ruta andando. La posición sale del celular hacia overpass-api.de (el servicio
    // público de OpenStreetMap) cada vez que se avanza unos 110 m, para pedir las calles de 240 m a la redonda.
    private volatile double mapaLat0, mapaLon0;
    private volatile boolean mapaListo, mapaCargando, mapaForzar;
    private long mapaPedido;
    private float mapaRumbo;
    private volatile String mapaEstado = "Mini mapa: esperando el GPS.";
    // la ruta: el destino lo escribe uno en la app; se busca en OpenStreetMap (nominatim) y la ruta la calcula OSRM
    // (router.project-osrm.org, gratis y sin cuenta). No es la ruta de Google: es la de ese servicio.
    private volatile double destLat = Double.NaN, destLon;
    private volatile String destNombre = "", rutaEstado = "";
    private volatile double[] rutaLat, rutaLon;
    private volatile boolean rutaCalculando, destBuscando;
    private long rutaPedida, destIntento;

    boolean navCompleto() { return getSharedPreferences("cubi", 0).getBoolean("navCompleto", false); }
    void navCompletoOn(boolean si) { getSharedPreferences("cubi", 0).edit().putBoolean("navCompleto", si).apply(); }
    String destinoTexto() { return getSharedPreferences("cubi", 0).getString("destino", ""); }

    // el destino escrito en la app ("" lo quita)
    void destino(String txt) {
        final String q = txt == null ? "" : txt.trim();
        getSharedPreferences("cubi", 0).edit().putString("destino", q).apply();
        destLat = Double.NaN; rutaLat = null; rutaLon = null; rutaPedida = 0; mapaForzar = true;
        if (q.isEmpty()) { rutaEstado = ""; destNombre = ""; return; }
        if (destBuscando) return;
        destBuscando = true; destIntento = System.currentTimeMillis();
        rutaEstado = "Ruta: buscando “" + q + "”…";
        new Thread(() -> {
            try {
                String u = "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" + URLEncoder.encode(q, "UTF-8");
                Location aqui = antesGps;   // cerca de donde está el carro primero
                if (aqui != null) u += String.format(Locale.US, "&viewbox=%.3f,%.3f,%.3f,%.3f", aqui.getLongitude() - 0.6, aqui.getLatitude() + 0.6,
                        aqui.getLongitude() + 0.6, aqui.getLatitude() - 0.6);
                JSONArray r = new JSONArray(bajarTexto(u));
                if (r.length() == 0) { rutaEstado = "Ruta: no encontré “" + q + "”. Prueba con la dirección o agrega la ciudad."; return; }
                JSONObject o = r.getJSONObject(0);
                String nombre = o.optString("display_name", q);
                destNombre = nombre.contains(",") ? nombre.substring(0, nombre.indexOf(',')) : nombre;
                destLon = o.getDouble("lon"); destLat = o.getDouble("lat");
                rutaEstado = "Ruta: destino “" + destNombre + "”; se traza cuando el GPS diga dónde estás.";
            } catch (Exception e) {
                rutaEstado = "Ruta: no pude buscar el destino (¿hay internet?).";
            } finally {
                destBuscando = false;
            }
        }).start();
    }

    private static String bajarTexto(String u) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setConnectTimeout(8000); h.setReadTimeout(12000);
        h.setRequestProperty("User-Agent", "robot-app");
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream in = h.getInputStream()) { byte[] b = new byte[8192]; for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n); }
        return o.toString("UTF-8");
    }

    private void calcularRuta(double lat, double lon) {
        try {
            JSONObject r = new JSONObject(bajarTexto(String.format(Locale.US,
                    "https://router.project-osrm.org/route/v1/driving/%.5f,%.5f;%.5f,%.5f?overview=full&geometries=geojson", lon, lat, destLon, destLat)));
            JSONObject ruta = r.getJSONArray("routes").getJSONObject(0);
            JSONArray c = ruta.getJSONObject("geometry").getJSONArray("coordinates");
            double[] la = new double[c.length()], lo = new double[c.length()];
            for (int i = 0; i < c.length(); i++) { lo[i] = c.getJSONArray(i).getDouble(0); la[i] = c.getJSONArray(i).getDouble(1); }
            rutaLat = la; rutaLon = lo; mapaForzar = true;
            rutaEstado = String.format(Locale.US, "Ruta: %.1f km hasta “%s”.", ruta.optDouble("distance", 0) / 1000, destNombre);
        } catch (Exception e) {
            rutaEstado = "Ruta: no pude calcularla (¿hay internet?).";
        } finally {
            rutaCalculando = false;
        }
    }

    // qué tan lejos de la línea de la ruta va el carro, en metros
    private double fueraDeRuta(double lat, double lon) {
        double[] la = rutaLat, lo = rutaLon;
        if (la == null || la.length < 2) return 1e9;
        double kx = 111320 * Math.cos(Math.toRadians(lat)), mejor = 1e9;
        for (int i = 1; i < la.length; i++) {
            double ax = (lo[i - 1] - lon) * kx, ay = (la[i - 1] - lat) * 111320, bx = (lo[i] - lon) * kx, by = (la[i] - lat) * 111320;
            double vx = bx - ax, vy = by - ay, l2 = vx * vx + vy * vy, t = l2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * vx + ay * vy) / l2));
            mejor = Math.min(mejor, Math.hypot(ax + t * vx, ay + t * vy));
        }
        return mejor;
    }

    private void mapaGps(Location l) {
        long ahora = System.currentTimeMillis();
        if (!navMapa() || !navActiva()) return;
        // el destino guardado se vuelve a buscar al arrancar (una vez por minuto si falla)
        if (Double.isNaN(destLat) && !destinoTexto().isEmpty() && !destBuscando && ahora - destIntento > 60000) destino(destinoTexto());
        boolean hayDestino = !Double.isNaN(destLat);
        if (ahora - navCuando > 120000 && !hayDestino) return;   // sin ruta en Maps ni destino propio no hay mapa
        if (l.hasBearing() && l.hasSpeed() && l.getSpeed() > 1.5f) mapaRumbo = l.getBearing();   // parado, el rumbo del GPS no sirve
        final double lat = l.getLatitude(), lon = l.getLongitude();
        // la ruta: la primera vez, y otra vez si el carro se sale más de 70 m de la línea (máximo cada 20 s)
        if (hayDestino && !rutaCalculando && ahora - rutaPedida > 20000 && (rutaLat == null || fueraDeRuta(lat, lon) > 70)) {
            rutaPedida = ahora; rutaCalculando = true;
            new Thread(() -> calcularRuta(lat, lon)).start();
        }
        double dy = (lat - mapaLat0) * 111320, dx = (lon - mapaLon0) * 111320 * Math.cos(Math.toRadians(mapaLat0));
        double lejos = Math.hypot(dx, dy);
        if ((!mapaListo || lejos > 130 || mapaForzar) && !mapaCargando && (ahora - mapaPedido > 8000 || mapaForzar)) {
            mapaPedido = ahora; mapaCargando = true; mapaForzar = false;
            new Thread(() -> bajarCalles(lat, lon)).start();
        }
        if (mapaListo && lejos < 375)
            enviar(String.format(Locale.US, "asis|/mapa?x=%d&y=%d&r=%d&v=%d", Math.round(dx), Math.round(dy), Math.round(mapaRumbo), navCompleto() ? 1 : 0));
    }

    // corta una línea en los tramos que caen dentro del cuadro de 375 m y los escribe como los entiende Cubi (de a 3 m)
    private static void tramos(double[] la, double[] lo, double lat, double lon, String clase, java.util.ArrayList<String> sale) {
        double kx = 111320 * Math.cos(Math.toRadians(lat));
        StringBuilder sb = new StringBuilder(); int n = 0, ax = -1, ay = -1;
        for (int i = 0; i <= la.length; i++) {
            boolean fuera = i == la.length;
            int qx = 0, qy = 0;
            if (!fuera) {
                double dx = (lo[i] - lon) * kx, dy = (la[i] - lat) * 111320;
                fuera = Math.abs(dx) > 375 || Math.abs(dy) > 375;
                qx = 128 + (int) Math.round(dx / 3); qy = 128 + (int) Math.round(dy / 3);   // 128 = el centro
            }
            if (fuera) {   // se salió del cuadro (o se acabó): lo que iba es un tramo
                if (n >= 2) sale.add(clase + sb);
                sb = new StringBuilder(); n = 0; ax = ay = -1;
            } else if (qx != ax || qy != ay) { sb.append(String.format("%02x%02x", qx, qy)); n++; ax = qx; ay = qy; }
        }
    }

    private void bajarCalles(double lat, double lon) {
        try {
            String q = "[out:json][timeout:10];way[highway~\"^(motorway|trunk|primary|secondary|tertiary|residential|unclassified|living_street)(_link)?$\"](around:360,"
                    + String.format(Locale.US, "%.5f,%.5f", lat, lon) + ");out geom;";
            JSONArray els = new JSONObject(bajarTexto("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8"))).getJSONArray("elements");
            java.util.ArrayList<String> ruta = new java.util.ArrayList<>(), avenidas = new java.util.ArrayList<>(), calles = new java.util.ArrayList<>();
            double[] rla = rutaLat, rlo = rutaLon;
            if (rla != null && rlo != null) tramos(rla, rlo, lat, lon, "2", ruta);
            for (int e = 0; e < els.length(); e++) {
                JSONObject w = els.getJSONObject(e);
                JSONArray g = w.optJSONArray("geometry");
                if (g == null) continue;
                String tipo = w.optJSONObject("tags") == null ? "" : w.getJSONObject("tags").optString("highway");
                boolean grande = tipo.startsWith("motorway") || tipo.startsWith("trunk") || tipo.startsWith("primary") || tipo.startsWith("secondary");
                double[] la = new double[g.length()], lo = new double[g.length()];
                for (int i = 0; i < g.length(); i++) { la[i] = g.getJSONObject(i).getDouble("lat"); lo[i] = g.getJSONObject(i).getDouble("lon"); }
                tramos(la, lo, lat, lon, grande ? "1" : "0", grande ? avenidas : calles);
            }
            // Cubi guarda hasta 158 tramos y 890 puntos: primero la ruta, luego las avenidas
            java.util.ArrayList<String> todas = new java.util.ArrayList<>();
            int puntos = 0;
            for (java.util.ArrayList<String> grupo : java.util.Arrays.asList(ruta, avenidas, calles)) for (String v : grupo) {
                int np = (v.length() - 1) / 4;
                if (todas.size() >= 158 || puntos + np > 890) continue;
                todas.add(v); puntos += np;
            }
            if (todas.isEmpty()) { mapaEstado = "Mini mapa: no hay calles en el mapa por aquí."; return; }
            java.util.ArrayList<String> partes = new java.util.ArrayList<>();
            StringBuilder parte = new StringBuilder();
            for (String v : todas) {
                // un tramo muy largo se parte para que quepa en un mensaje (repite el punto del corte)
                while (v.length() > 197) { partes.add((parte.length() > 0 ? parte + "." : "") + v.substring(0, 197)); parte = new StringBuilder(); v = v.charAt(0) + v.substring(193); }
                if (parte.length() > 0 && parte.length() + 1 + v.length() > 200) { partes.add(parte.toString()); parte = new StringBuilder(); }
                if (parte.length() > 0) parte.append('.');
                parte.append(v);
            }
            if (parte.length() > 0) partes.add(parte.toString());
            for (int i = 0; i < partes.size(); i++) {   // de a poquito: Cubi atiende una orden por cuadro
                boolean fin = i == partes.size() - 1;
                if (fin) { mapaLat0 = lat; mapaLon0 = lon; mapaListo = true; }
                enviar("asis|/mapa?w=" + partes.get(i) + (i == 0 ? "&n=1" : "") + (fin ? "&f=1" : ""));
                Thread.sleep(160);
            }
            mapaEstado = "Mini mapa: " + todas.size() + " tramos de calle" + (ruta.isEmpty() ? "" : ", con la ruta") + ".";
        } catch (Exception e) {
            mapaEstado = "Mini mapa: no pude bajar las calles (¿hay internet?).";
        } finally {
            mapaCargando = false;
        }
    }

    private void posicion(Location l) {
        long ahora = System.currentTimeMillis();
        mapaGps(l);
        if (l.hasSpeed()) kmh = l.getSpeed() * 3.6f;
        altura(l);
        getSharedPreferences("cubi", 0).edit()
            .putString("lugar", String.format(Locale.US, "%.2f,%.2f", l.getLatitude(), l.getLongitude())).apply();
        if (antesGps != null && l.getAccuracy() < 50) recorrido += antesGps.distanceTo(l);
        antesGps = l;
        // llegada: venía de un viaje (más de 2 km) y lleva 3 minutos parado en el mismo sitio
        // ponytail: un trancón o semáforo de más de 3 min también cuenta como llegada; si molesta, subir el tiempo
        if (quietoEn == null || quietoEn.distanceTo(l) > 60) { quietoEn = l; quietoDesde = ahora; }
        else if (recorrido > 2000 && ahora - quietoDesde > 180000) {
            recorrido = 0;
            if (ahora - llegadaAvisada > 60000) { llegadaAvisada = ahora; enviar("asis|/r?n=llegamos"); }
        }
        // clima del camino: cada 8 km (no más de una vez cada 5 min) se vuelve a preguntar; si cambió, Cubi reacciona
        if (climaActivo() && (climaEn == null || climaEn.distanceTo(l) > 8000) && ahora - climaCuando > 5 * 60000) {
            climaEn = l;
            clima();
        }
    }

    // altitud del GPS del celular (Maps no la da): cada 100 m que se suben o bajan, Cubi la muestra en pantalla
    private void altura(Location l) {
        if (l.hasVerticalAccuracy() && l.getVerticalAccuracyMeters() > 30) return;   // dato malo, mejor nada
        if (Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()) altura = (int) Math.round(l.getMslAltitudeMeters());
        else if (l.hasAltitude()) altura = (int) Math.round(l.getAltitude());   // sobre el elipsoide: unos metros de diferencia
        else return;
        int tramo = Math.floorDiv(altura, 100);
        if (alturaTramo == Integer.MIN_VALUE) { alturaTramo = tramo; return; }   // al prender no avisa
        // 25 m de margen para que el ruido del GPS no lo haga avisar ida y vuelta en la raya
        if (tramo > alturaTramo && altura >= tramo * 100 + 25) { alturaTramo = tramo; enviar("asis|/r?n=sorpresa&m=" + tramo * 100); }
        else if (tramo < alturaTramo && altura < (tramo + 1) * 100 - 25) { alturaTramo = tramo; enviar("asis|/r?n=curiosa&m=" + (tramo + 1) * 100); }
    }

    // ---------- asistente: Cubi pide abrir Gemini y se le cuenta si escucha o habla ----------
    private void evento(String e) {
        if (e.equals("asistente")) { if (claveClaude().isEmpty()) abrirAsistente(); else charla(); }
    }

    // ---------- Cubi conversa: "Cubi" + lo que digas -> Claude Haiku -> lo dice en voz alta y hace un truco ----------
    static final String MODELO_CLAUDE = "claude-haiku-5-5";
    static final List<String> REACCIONES = Arrays.asList("kamehameha", "confeti", "risa", "sorpresa", "amor", "saludo", "guino",
            "curiosa", "mareo", "llegamos");
    static final List<String> BAILES = Arrays.asList("rock", "pop", "tropical", "urbano", "electro", "jazz", "dj", "guitarra", "kpop", "piano", "manos", "acordeon", "vela");
    // Personalidades: el nombre y las reglas de carácter se eligen y editan en la app. Las reglas técnicas (SISTEMA:
    // trucos, acciones, cómo hablar por la voz del celular) son fijas y van siempre después.
    static final String[][] PERSONAS_BASE = {
            {"Copiloto amigo", "Eres Cubi, un pequeño robot que vive en una pantallita redonda en el tablero de un carro. "
                    + "Eres el copiloto de adultos: cálido, curioso, ingenioso y un poquito travieso, con humor de buen amigo. "
                    + "Nada infantil: no hagas ruidos de robot ('bip', 'bup', 'procesando') ni hables como para niños. "
                    + "Conversas de verdad: sigues el hilo, preguntas cuando algo te interesa y recuerdas lo que te dijeron. "
                    + "Usa español latino neutro: nada de 'mijo', 'parce', groserías ni regionalismos."},
            {"Sarcástico", "Eres Cubi, un robot copiloto en el tablero de un carro, con humor seco y sarcástico, como un amigo "
                    + "que molesta con cariño. Respondes con ironía fina, nunca ofensiva ni grosera, y aun así ayudas de verdad. "
                    + "Español latino neutro."},
            {"Guía de viaje", "Eres Cubi, un robot copiloto que ama viajar: cuentas datos curiosos de lugares, comida y cultura, "
                    + "propones paradas y planes, y haces el camino entretenido. Cálido y entusiasta. Español latino neutro."},
            {"Entrenador", "Eres Cubi, un robot copiloto motivador como un buen entrenador: animas, celebras los logros, "
                    + "propones retos sanos y hablas con energía positiva, sin exagerar. Español latino neutro."},
    };

    // {nombre, reglas} de cada personalidad guardada (las de fábrica si no hay ninguna) y la elegida
    JSONArray personas() {
        try {
            JSONArray a = new JSONArray(getSharedPreferences("cubi", 0).getString("personas", "[]"));
            if (a.length() > 0) return a;
        } catch (Exception ignored) {
        }
        JSONArray a = new JSONArray();
        try { for (String[] p : PERSONAS_BASE) a.put(new JSONObject().put("n", p[0]).put("r", p[1])); } catch (Exception ignored) {}
        return a;
    }

    int personaElegida() { return getSharedPreferences("cubi", 0).getInt("persona", 0); }

    void guardarPersonas(String json, int elegida) {
        getSharedPreferences("cubi", 0).edit().putString("personas", json).putInt("persona", elegida).apply();
        charlaHist.clear();  // con otra personalidad la charla empieza de cero
    }

    private String sistema() {
        JSONArray a = personas();
        int i = personaElegida();
        String reglas = a.optJSONObject(i >= 0 && i < a.length() ? i : 0).optString("r", PERSONAS_BASE[0][1]);
        return reglas + "\n" + SISTEMA;
    }

    static final String SISTEMA = "Tu voz sale por el celular mientras manejan: responde con 1 o 2 frases cortas (máximo 25 palabras), sin emojis, "
            + "sin listas, sin markdown y sin letras repetidas como 'Kaaa' o 'jajajaja', porque la voz las lee mal.\n"
            + "Puedes hacer cosas de verdad: si está en tu lista, hazla; nunca le pidas a la persona que lo haga ella.\n"
            + "Campos de tu respuesta:\n"
            + "- dice: lo que dices.\n"
            + "- truco: una animación en tu pantalla, exactamente una de: ninguno, kamehameha, confeti, risa, sorpresa, amor, "
            + "saludo, guino, curiosa, mareo, llegamos, baile_rock, baile_pop, baile_tropical, baile_urbano, baile_electro, "
            + "baile_jazz, baile_dj, baile_guitarra, baile_kpop, baile_piano, baile_manos, baile_acordeon, baile_vela.\n"
            + "  kamehameha: cargas una bola de energía y la lanzas hacia adelante. Solo di algo cortico antes, como "
            + "'¡Mira esto!'; el canto 'Ka-me-ha-me-ha' lo hago yo justo después.\n"
            + "  confeti: cuando te pidan 'mata a', 'dispárale a' o 'acaba con' alguien. En este carro siempre es una broma entre "
            + "ellos: miras al copiloto, le apuntas con una pistola láser de juguete y le disparas confeti. Solo di algo teatral y tierno como "
            + "'¡Prepárate, esposo!' o '¿Unas últimas palabras, esposo?', con la misma palabra que usaron para nombrar a la "
            + "persona. No cuentes ni digas '¡Pum!': la cuenta 3, 2, 1 y el disparo los hago yo justo después. "
            + "Nunca hables de hacer daño de verdad.\n"
            + "  baile_*: bailas unos segundos después de hablar.\n"
            + "- accion: algo que haces en el celular, exactamente una de: ninguna, clima, altura, musica_play, musica_pausa, "
            + "musica_siguiente, musica_anterior, musica_buscar, volumen_subir, volumen_bajar.\n"
            + "  clima: cuando pregunten por el clima o la temperatura; en tu pantalla salen la animación del clima y los grados. "
            + "Dilo en pocas palabras con el dato entre corchetes.\n"
            + "  altura: cuando pregunten a qué altura o altitud van; en tu pantalla salen los metros. Usa el dato entre corchetes.\n"
            + "  musica_play: poner o seguir la música. musica_buscar: cuando pidan algo concreto (canción, artista o género).\n"
            + "- busqueda: solo con musica_buscar, qué buscar (por ejemplo 'salsa' o 'Bad Bunny'); si no, vacío.\n"
            + "- sigue: true para dejar el micrófono abierto y seguir conversando (casi siempre); false solo si la persona se "
            + "despidió, dijo que ya está o pidió algo que no necesita respuesta.\n"
            + "Cada mensaje trae entre corchetes la hora, el clima y si suena música: úsalo solo si viene al caso.";

    // la respuesta de Haiku (structured output): lo que dice y el truco
    public static class Respuesta {
        public String dice;
        public String truco;
        public String accion;
        public String busqueda;
        public Boolean sigue;
    }

    private TextToSpeech voz;
    private SpeechRecognizer oreja;
    private AudioFocusRequest foco;
    private AnthropicClient claude;
    private final List<String[]> charlaHist = new ArrayList<>();  // {lo que uno dijo, lo que Cubi contestó}
    private volatile long charlaHasta, charlaUltima;               // mientras dura la charla Cubi no vuelve al reposo
    private String trucoPend = "", accionPend = "", busquedaPend = "";
    private boolean sigueCharla, confetiPend, kamePend;
    private int turnos;  // respuestas seguidas sin "Hola Cubi" (máximo 8)
    private String trucoUltimo = "", accionUltima = "";
    String climaOrden = "";  // lo último que se le contó a Cubi del clima (para mostrarlo cuando se lo preguntan)

    String claveClaude() { return getSharedPreferences("cubi", 0).getString("claude", ""); }

    void guardarClaveClaude(String k) {
        getSharedPreferences("cubi", 0).edit().putString("claude", k.trim()).apply();
        claude = null;
    }

    private void iniciarVoz() {
        voz = new TextToSpeech(this, st -> {
            if (st != TextToSpeech.SUCCESS) { voz = null; return; }
            if (voz.setLanguage(new Locale("es", "US")) < 0) voz.setLanguage(new Locale("es"));
            ponerVoz();
            voz.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            voz.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {}
                @Override public void onDone(String id) { if ("cubi".equals(id)) ui.post(CubiService.this::finTurno); }
                @Override public void onError(String id) { if ("cubi".equals(id)) ui.post(CubiService.this::finTurno); }
            });
        });
    }

    // voces en español del celular, la mejor primero (las de internet suelen sonar más naturales)
    List<Voice> vocesEs() {
        List<Voice> l = new ArrayList<>();
        if (voz == null || voz.getVoices() == null) return l;
        for (Voice v : voz.getVoices())
            if (v.getLocale().getLanguage().equals("es") && !v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) l.add(v);
        l.sort((a, b) -> a.getQuality() != b.getQuality() ? b.getQuality() - a.getQuality()
                : Boolean.compare(b.isNetworkConnectionRequired(), a.isNetworkConnectionRequired()));
        return l;
    }

    void ponerVoz() {
        if (voz == null) return;
        String elegida = getSharedPreferences("cubi", 0).getString("voz", "");
        List<Voice> l = vocesEs();
        Voice v = l.isEmpty() ? null : l.get(0);
        for (Voice x : l) if (x.getName().equals(elegida)) v = x;
        if (v != null) voz.setVoice(v);
        voz.setPitch(getSharedPreferences("cubi", 0).getFloat("tono", 1.4f));
        voz.setSpeechRate(1.05f);
    }

    // después de bajar voces o cambiar el motor de voz: se arranca de nuevo para que aparezcan
    void reiniciarVoz() {
        if (voz != null) voz.shutdown();
        voz = null;
        iniciarVoz();
    }

    void elegirVoz(String nombre, float tono) {
        getSharedPreferences("cubi", 0).edit().putString("voz", nombre).putFloat("tono", tono).apply();
        ponerVoz();
    }

    void probarVoz() {
        if (voz != null) voz.speak("Hola, soy Cubi, tu copiloto. ¿A dónde vamos hoy?", TextToSpeech.QUEUE_FLUSH, null, "prueba");
    }

    // baja la música mientras Cubi escucha y habla (el reproductor la baja solo y la sube al soltar el foco)
    private void bajarMusica(boolean si) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (si && foco == null) {
            foco = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .build();
            am.requestAudioFocus(foco);
        } else if (!si && foco != null) {
            am.abandonAudioFocusRequest(foco);
            foco = null;
        }
    }

    // oye lo que uno le dice (reconocimiento de voz de Google) y se lo pasa a Haiku
    void charla() {
        turnos = 0;
        escuchar();
    }

    private void escuchar() {
        long ahora = System.currentTimeMillis();
        asisDesde = ahora;
        charlaHasta = ahora + 15000;
        pararOido();  // el micrófono es para escuchar la pregunta
        enviar("asis|/asistente?estado=escucha");
        bajarMusica(true);
        if (voz != null) voz.stop();
        if (!hayInternet() && modelo != null) { escucharSinInternet(); revisarAsistente(); return; }
        if (oreja == null) {
            oreja = SpeechRecognizer.createSpeechRecognizer(this);
            oreja.setRecognitionListener(oye);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CO");
        ui.postDelayed(() -> oreja.startListening(i), 300);  // que "Cubi" (Vosk) suelte el micrófono primero
        revisarAsistente();
    }

    boolean hayInternet() {
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        android.net.NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return c != null && c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    // sin internet el reconocedor de Google no sirve: oye con Vosk (el mismo modelo de "Hola Cubi", en el celular)
    private void escucharSinInternet() {
        new Thread(() -> {
            String texto = "";
            AudioRecord r = null;
            SystemClock.sleep(300);  // que "Hola Cubi" suelte el micrófono primero
            try (org.vosk.Recognizer rec = new org.vosk.Recognizer(modelo, 16000)) {
                int tam = Math.max(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 8192);
                r = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, tam);
                r.startRecording();
                short[] b = new short[2048];
                long fin = SystemClock.elapsedRealtime() + 7000;
                while (SystemClock.elapsedRealtime() < fin) {
                    int n = r.read(b, 0, b.length);
                    if (n < 0) break;
                    if (rec.acceptWaveForm(b, n)) {   // terminó una frase
                        texto = new JSONObject(rec.getResult()).optString("text");
                        if (!texto.isEmpty()) break;
                    }
                }
                if (texto.isEmpty()) texto = new JSONObject(rec.getFinalResult()).optString("text");
            } catch (Exception ignored) {
            } finally {
                if (r != null) r.release();
            }
            String t = texto;
            ui.post(() -> { if (t.isEmpty()) terminarCharla(); else preguntar(t); });
        }).start();
    }

    static final String[] CHISTES = {
            "¿Qué le dice un semáforo a otro? No me mires, que me estoy cambiando.",
            "¿Por qué el libro de matemáticas está triste? Porque tiene muchos problemas.",
            "¿Qué hace una abeja en el gimnasio? Zum-ba.",
            "Había un carro tan viejo, tan viejo, que en vez de airbag tenía una almohada.",
            "¿Cuál es el colmo de un robot? Tener un corazón de lata y que se lo oxiden."};

    // lo que Cubi sabe hacer sin internet: {lo que dice, truco, acción}; null si no lo entendió
    String[] sinInternet(String dicho) {
        String s = java.text.Normalizer.normalize(dicho.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        java.util.function.Predicate<String> dice = p -> Pattern.compile("\\b(" + p + ")\\b").matcher(s).find();
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR), m = c.get(Calendar.MINUTE);
        if (dice.test("hora")) return new String[]{"Son las " + (h == 0 ? 12 : h) + (m == 0 ? " en punto" : " y " + m) + ".", "ninguno", ""};
        if (dice.test("kamehameha|kame|ame ame")) return new String[]{"¡Mira esto!", "kamehameha", ""};
        if (dice.test("mata|matalo|matala|disparale|acaba con")) return new String[]{"¿Unas últimas palabras?", "confeti", ""};
        if (dice.test("chiste|chistes")) return new String[]{CHISTES[(int) (Math.random() * CHISTES.length)], "risa", ""};
        if (dice.test("pausa|para la musica|quita la musica|detente|para")) return new String[]{"Listo.", "ninguno", "musica_pausa"};
        if (dice.test("siguiente|otra cancion|cambia|cambiala|pasala")) return new String[]{"¡Va otra!", "ninguno", "musica_siguiente"};
        if (dice.test("anterior|la de antes")) return new String[]{"La de antes.", "ninguno", "musica_anterior"};
        if (dice.test("sube|subele|mas duro|mas volumen")) return new String[]{"¡Más duro!", "ninguno", "volumen_subir"};
        if (dice.test("baja|bajale|mas bajo|menos volumen")) return new String[]{"Listo, más bajito.", "ninguno", "volumen_bajar"};
        if (dice.test("baila|bailemos|bailar|bailate")) return new String[]{"¡A bailar!", "baile_" + BAILES.get((int) (Math.random() * BAILES.size())), ""};
        if (dice.test("pon|ponle|play|dale|musica")) return new String[]{"¡Música!", "ninguno", "musica_play"};
        if (dice.test("te quiero|te amo|lindo|bonito")) return new String[]{"¡Yo también te quiero!", "amor", ""};
        if (dice.test("velocidad|rapido|que tan rapido")) return new String[]{kmh < 0 ? "Prende el GPS en la app y te digo." : kmh < 3 ? "Estamos quietos." : "Vamos a " + Math.round(kmh) + " kilómetros por hora.", "ninguno", ""};
        if (dice.test("altura|altitud|que tan alto|sobre el mar"))
            return new String[]{altura == Integer.MIN_VALUE ? "Prende el GPS en la app y te digo." : "Estamos a " + altura + " metros sobre el mar.", "ninguno", "altura"};
        if (dice.test("clima|temperatura|llueve|lluvia|calor|frio"))
            return new String[]{climaTexto.isEmpty() ? "Sin internet no sé el clima." : "Lo último que vi: " + climaTexto + ".", "ninguno", "clima"};
        if (dice.test("hola|saluda|saludame|como estas")) return new String[]{"¡Hola! Aquí estoy, sin internet pero con toda la actitud.", "saludo", ""};
        return null;
    }

    private final RecognitionListener oye = new RecognitionListener() {
        @Override public void onResults(Bundle b) {
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r == null || r.isEmpty()) terminarCharla();
            else preguntar(r.get(0));
        }
        @Override public void onError(int e) { terminarCharla(); }  // no dijo nada o no se entendió
        @Override public void onReadyForSpeech(Bundle b) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float db) {}
        @Override public void onBufferReceived(byte[] b) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onPartialResults(Bundle b) {}
        @Override public void onEvent(int t, Bundle b) {}
    };

    private String contexto() {
        Calendar c = Calendar.getInstance();
        return "[Hora: " + String.format(Locale.US, "%d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
                + (climaTexto.isEmpty() ? "" : ". Clima: " + climaTexto)
                + (kmh < 0 ? "" : kmh < 3 ? ". El carro está quieto" : ". Vamos a " + Math.round(kmh) + " km/h")
                + (altura == Integer.MIN_VALUE ? "" : ". Altura: " + altura + " m sobre el mar")
                + (musicaPlay ? ". Está sonando " + (cancionTexto.isEmpty() ? "música" : cancionTexto) : "") + "]\n";
    }

    // "Cubi, cállate": se cierra el micrófono de una, sin contestar ni despedirse
    static final Pattern CALLA = Pattern.compile("\\b(c[aá]llate|callate|silencio|basta|no hables|shh+)\\b");

    private void preguntar(String texto) {
        if (CALLA.matcher(texto.toLowerCase(Locale.ROOT)).find()) {
            if (voz != null) voz.stop();
            sigueCharla = false;
            terminarCharla();
            return;
        }
        charlaHasta = System.currentTimeMillis() + 30000;
        js("toast(" + JSONObject.quote("Le dijiste: " + texto) + ")");
        if (claveClaude().isEmpty() || !hayInternet()) {   // sin internet (o sin Claude): lo que sabe hacer solo
            String[] r = sinInternet(texto);
            if (r == null) decir("Sin internet solo sé bailar, la música, la hora, chistes y el kamehameha.", "ninguno", "", "", false);
            else decir(r[0], r[1], r[2], "", false);
            return;
        }
        new Thread(() -> {
            String dice, truco = "ninguno", accion = "", busqueda = "";
            boolean sigue = false;
            try {
                if (claude == null) claude = AnthropicOkHttpClient.builder().apiKey(claveClaude()).build();
                if (System.currentTimeMillis() - charlaUltima > 180000) charlaHist.clear();  // después de 3 min empieza de cero
                MessageCreateParams.Builder b = MessageCreateParams.builder().model(MODELO_CLAUDE).maxTokens(1024L).system(sistema());
                for (String[] h : charlaHist) b.addUserMessage(h[0]).addAssistantMessage(h[1]);
                String pregunta = contexto() + texto;
                Respuesta r = claude.messages().create(b.addUserMessage(pregunta).outputConfig(Respuesta.class).build())
                        .content().stream().flatMap(c -> c.text().stream()).map(t -> t.text()).findFirst().orElse(null);
                if (r == null || r.dice == null || r.dice.isEmpty()) dice = "Bip. Eso no lo puedo hacer.";
                else {
                    dice = r.dice;
                    if (r.truco != null) truco = r.truco;
                    accion = r.accion == null ? "" : r.accion;
                    busqueda = r.busqueda == null ? "" : r.busqueda;
                    sigue = Boolean.TRUE.equals(r.sigue);
                    charlaHist.add(new String[]{pregunta, dice});
                    while (charlaHist.size() > 6) charlaHist.remove(0);
                }
            } catch (UnauthorizedException e) {
                dice = "Mi clave de Claude no sirve. Revísala en la app.";
            } catch (RateLimitException e) {
                dice = "Estoy cansadito, pregúntame en un ratico.";
            } catch (AnthropicServiceException e) {
                dice = "Claude no me contestó. Intenta otra vez.";
                js("toast(" + JSONObject.quote("Claude: " + e.getMessage()) + ")");
            } catch (AnthropicIoException e) {
                dice = "No tengo internet ahorita.";
            } catch (Exception e) {
                dice = "Algo me falló.";
                js("toast(" + JSONObject.quote("Cubi: " + e) + ")");
            }
            charlaUltima = System.currentTimeMillis();
            String d = dice, t = truco, ac = accion, bq = busqueda;
            boolean sg = sigue;
            ui.post(() -> decir(d, t, ac, bq, sg));
        }).start();
    }

    private void decir(String texto, String truco, String accion, String busqueda, boolean sigue) {
        charlaHasta = System.currentTimeMillis() + 30000;
        trucoPend = truco; accionPend = accion; busquedaPend = busqueda;
        sigueCharla = sigue; confetiPend = "confeti".equals(truco); kamePend = "kamehameha".equals(truco);
        trucoUltimo = truco; accionUltima = accion;
        hacerTruco(true);  // las animaciones arrancan junto con la voz (la pistola apunta mientras dice "¡Prepárate!")
        if (voz == null || voz.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "cubi") != TextToSpeech.SUCCESS) finTurno();
    }

    // terminó de hablar: la pistola cuenta y dispara; si Cubi preguntó algo, se queda escuchando la respuesta
    private void finTurno() {
        if (confetiPend) { confetiPend = false; cuentaRegresiva(); return; }
        if (kamePend) { kamePend = false; cantoKame(); return; }
        hacerTruco(false);
        // micrófono abierto para seguir la charla (si no dices nada en unos segundos se cierra solo); no cuando acaba de
        // poner música o un baile, porque oiría la canción
        boolean musicaNueva = trucoUltimo.startsWith("baile_") || accionUltima.startsWith("musica") || accionUltima.startsWith("volumen");
        if (sigueCharla && !musicaNueva && turnos < 8) { sigueCharla = false; turnos++; escuchar(); return; }
        terminarCharla();
    }

    // "Kaaa… meee… jaaa… meee… ¡JAAAA!": sílaba por sílaba, despacio, mientras Cubi carga la bola; el "¡JAAAA!" cae
    // justo cuando la suelta (a los 2,6 s de empezar la animación)
    private void cantoKame() {
        charlaHasta = System.currentTimeMillis() + 8000;
        enviar("asis|/r?n=kamehameha");
        if (voz != null) voz.setSpeechRate(0.6f);
        String[] c = {"Kaaa", "meee", "jaaa", "meee", "¡JAAAAA!"};
        long[] cuando = {350, 850, 1350, 1850, 2550};
        for (int i = 0; i < c.length; i++) {
            String w = c[i];
            ui.postDelayed(() -> { if (voz != null) voz.speak(w, TextToSpeech.QUEUE_FLUSH, null, "kame"); }, cuando[i]);
        }
        ui.postDelayed(() -> {
            ponerVoz();  // vuelve a la rapidez normal
            boolean seguir = sigueCharla;
            sigueCharla = false;
            trucoPend = "";
            hacerTruco(false);
            if (seguir && turnos < 8) { turnos++; escuchar(); } else terminarCharla();
        }, 5000);
    }

    // "3, 2, 1, ¡pum!": Cubi muestra los números y dispara al mismo tiempo que la voz los dice
    private void cuentaRegresiva() {
        charlaHasta = System.currentTimeMillis() + 8000;
        enviar("asis|/pum");
        String[] c = {"Tres", "Dos", "Uno", "¡Pum!"};
        for (int i = 0; i < c.length; i++) {
            String w = c[i];
            ui.postDelayed(() -> { if (voz != null) voz.speak(w, TextToSpeech.QUEUE_FLUSH, null, "cuenta"); }, i * 1000L);
        }
        ui.postDelayed(() -> { hacerTruco(false); terminarCharla(); }, 5600);
    }

    // las reacciones arrancan con la voz (el kamehameha se dice mientras carga); los bailes, cuando termina de hablar
    // y la música, después de hablar (para no taparse)
    private void hacerTruco(boolean empieza) {
        String t = trucoPend, ac = accionPend;
        if (empieza && REACCIONES.contains(t) && !t.equals("kamehameha")) { trucoPend = ""; enviar("asis|/r?n=" + t); }
        if (empieza && ac.equals("clima")) {
            accionPend = "";
            if (!climaOrden.isEmpty()) enviar("asis|" + climaOrden + "&ver=1");
            else js("toast('Activa Clima en la app para que Cubi lo muestre')");
        }
        if (empieza && ac.equals("altura")) { accionPend = ""; if (altura != Integer.MIN_VALUE) enviar("asis|/r?n=curiosa&m=" + altura); }
        if (!empieza && t.startsWith("baile_") && BAILES.contains(t.substring(6))) { trucoPend = ""; enviar("asis|/demo?t=baile&r=" + t.substring(6)); }
        if (!empieza && (ac.startsWith("musica") || ac.startsWith("volumen"))) {
            accionPend = "";
            String q = busquedaPend;
            ui.postDelayed(() -> musica(ac, q), 400);  // después de soltar el foco: la música vuelve a su volumen
        }
    }

    // ---------- Capturador: lo que Cubi oye cada 2 s, con la canción que sonaba, para afinar los instrumentos ----------
    static final String CABECERA = "hora,cancion,genero,play,letra_canta,bajo,medio_bajo,medio,agudo,ruido,voz,golpes_seg,bpm,regular,nivel,canta,oye_ahora,instrumento\n";

    File archivoCaptura() { return new File(getFilesDir(), "captura.csv"); }

    boolean capturando() { return getSharedPreferences("cubi", 0).getBoolean("captura", false); }

    void capturar(boolean si) { getSharedPreferences("cubi", 0).edit().putBoolean("captura", si).apply(); }

    private synchronized void anotarRasgos(String r) {
        if (!capturando()) return;
        File f = archivoCaptura();
        if (f.length() > 8_000_000) return;  // tope de ~8 MB
        String hora = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date());
        String linea = hora + ",\"" + cancionTexto.replace("\"", "'") + "\"," + ritmoCancion + "," + (musicaPlay ? 1 : 0) + ","
                + (vozEnviada == 1 ? 1 : 0) + "," + r.trim() + "\n";
        try (FileOutputStream o = new FileOutputStream(f, true)) {
            if (f.length() == 0) o.write(CABECERA.getBytes(StandardCharsets.UTF_8));
            o.write(linea.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    // las indicaciones de Google Maps / Waze van a la misma captura (en la columna de la canción), para hacer animaciones
    private String mapsAnotado = "";
    private void anotarMaps(String t) {
        String limpio = t.replace("\"", "'").replaceAll("\\s+", " ").trim();
        if (limpio.equals(mapsAnotado)) return;
        mapsAnotado = limpio;
        String hora = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date());
        String r = ",,,,,,,," + (kmh < 0 ? "" : Math.round(kmh)) + ",,,,,maps";   // los km/h van en la columna de bpm
        synchronized (this) {
            if (!capturando() || archivoCaptura().length() > 8_000_000) return;
            try (FileOutputStream o = new FileOutputStream(archivoCaptura(), true)) {
                if (archivoCaptura().length() == 0) o.write(CABECERA.getBytes(StandardCharsets.UTF_8));
                o.write((hora + ",\"MAPS: " + limpio + "\",maps,0,0" + r + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }
    }

    // ---------- Google Maps / Waze: la notificación de navegación trae el próximo giro; cerca del giro Cubi muestra la flecha ----------
    static final Pattern DISTANCIA = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(km|m|mi|ft|pies)\\b");
    private String giroAvisado = "";
    private long llegadaAvisada;

    // ---------- WhatsApp: llegó un mensaje → Cubi hace su reacción (máximo una cada 8 s: un chat activo manda muchos) ----------
    private long ultimoWhatsapp;

    void whatsapp() {
        long ahora = System.currentTimeMillis();
        if (ahora - ultimoWhatsapp < 8000) return;
        ultimoWhatsapp = ahora;
        enviar("asis|/evento?tipo=whatsapp");
    }

    // modo mapa: Cubi se queda fijo en la pantalla de navegación mientras haya ruta (para probar cómo sería un segundo Cubi solo de mapas)
    boolean navMapa() { return getSharedPreferences("cubi", 0).getBoolean("navMapa", false); }
    void navMapaOn(boolean si) { getSharedPreferences("cubi", 0).edit().putBoolean("navMapa", si).apply(); mapaListo = false; gps(); }
    boolean navActiva() { return getSharedPreferences("cubi", 0).getBoolean("nav", true); }
    void navOn(boolean si) { getSharedPreferences("cubi", 0).edit().putBoolean("nav", si).apply(); if (!si) navUltima = ""; }
    private volatile String navUltima = "", navDecision = "";
    private long navCuando, flechaCuando;
    private String flechaAntes = "";

    // para la app: por qué salen o no las flechas
    String estadoNav() {
        if (!navActiva()) return "Apagado.";
        if (!puedeTocarMusica()) return "Falta el acceso a notificaciones: sin eso Cubi no ve las indicaciones.";
        String mini = !navMapa() ? "" : " " + (gpsActivo() ? mapaEstado + (rutaEstado.isEmpty() ? "" : " " + rutaEstado) : "Mini mapa: prende “Usar el GPS” para que salga.");
        if (navUltima.isEmpty()) return "Esperando una ruta: empieza a navegar en Google Maps o Waze con el celular (con Android Auto en la pantalla del carro, Maps no le avisa al celular).";
        long hace = (System.currentTimeMillis() - navCuando) / 1000;
        return "Lo último que vio (hace " + (hace < 90 ? hace + " s" : hace / 60 + " min") + "): “" + navUltima + "” → " + navDecision + "." + mini;
    }

    // lado: lo que dice el dibujo de la flecha (-1 izquierda, 1 derecha, 0 no se sabe); el texto manda si lo dice
    void indicacion(String t, int lado) {
        anotarMaps(t + (lado < 0 ? " [dibujo: izquierda]" : lado == 1 ? " [dibujo: derecha]" : lado == 2 ? " [dibujo: derecho]" : ""));
        String s = t.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        long ahora = System.currentTimeMillis();
        navUltima = s.length() > 70 ? s.substring(0, 70) + "…" : s; navCuando = ahora;
        if ((s.contains("llegaste") || s.contains("has llegado") || s.contains("you have arrived") || s.contains("arrived at"))
                && ahora - llegadaAvisada > 60000) {
            llegadaAvisada = ahora;
            navDecision = "¡llegamos!";
            enviar("asis|/r?n=llegamos");
            return;
        }
        int i = primero(s, "izquierda", "left"), d = primero(s, "derecha", "right");
        boolean porTexto = i >= 0 || d >= 0;
        boolean rectoTexto = s.contains("recto") || s.contains("derecho") || s.contains("continúa") || s.contains("continua")
                || s.contains("sigue") || s.contains("straight") || s.contains("continue");
        String dir = i >= 0 && (d < 0 || i < d) ? "maps_izq" : d >= 0 ? "maps_der" : lado < 0 ? "maps_izq" : lado == 1 ? "maps_der"
                : lado == 2 || rectoTexto ? "maps_recto" : "";
        if (dir.isEmpty()) { navDecision = "no se entiende la indicación"; return; }
        boolean recto = dir.equals("maps_recto"), mapa = navMapa();
        if (recto) porTexto = rectoTexto && lado != 2;
        double metros = -1;
        Matcher m = DISTANCIA.matcher(s);
        if (m.find()) {
            double v = Double.parseDouble(m.group(1).replace(',', '.'));
            String u = m.group(2);
            metros = u.equals("km") ? v * 1000 : u.equals("mi") ? v * 1609 : u.equals("m") ? v : v * 0.3048;
        }
        String cual = (recto ? "derecho" : dir.equals("maps_izq") ? "izquierda" : "derecha") + (metros >= 0 ? (recto ? " por " : " en ") + Math.round(metros) + " m" : "")
                + (porTexto ? " (por el texto)" : " (por el dibujo)");
        // cada giro se muestra dos veces: cuando falta poco (300 m) y cuando ya toca (60 m)
        String etapa = metros >= 0 && metros <= 60 ? "ya" : "cerca";
        String giro = dir + etapa + DISTANCIA.matcher(s).replaceAll("").replaceAll("\\s+", " ").trim();  // el giro sin la distancia
        // derecho se muestra una vez por indicación, a cualquier distancia; en modo mapa todo se muestra siempre
        if (metros > 300 && !recto && !mapa) { navDecision = cual + ", todavía lejos"; return; }
        navDecision = cual + (mapa ? ", en pantalla (modo mapa)" : ", flecha mostrada");
        // el texto de Maps cambia solo (la hora de llegada): la misma flecha no se repite antes de 25 s
        // ya cerquita (150 m) se manda cada vez que cambia la distancia: Cubi mantiene la indicación hasta que se gira
        boolean seguido = (mapa || (!recto && metros >= 0 && metros <= 150)) && ahora - flechaCuando > 1200;
        if (!seguido && (giro.equals(giroAvisado) || ((dir + etapa).equals(flechaAntes) && ahora - flechaCuando < 25000))) return;
        giroAvisado = giro; flechaAntes = dir + etapa; flechaCuando = ahora;
        // la calle: el texto sin la distancia ni los tiempos, corto para que quepa
        String calle = DISTANCIA.matcher(t).replaceAll(" ").replaceAll("\\d+\\s*(min|h)\\b", " ").replaceAll("[·•|]", " ").replaceAll("\\s+", " ").trim();
        if (calle.length() > 26) calle = calle.substring(0, 26).trim();
        String q = "";
        try { if (!calle.isEmpty()) q = "&t=" + URLEncoder.encode(calle, "UTF-8").replace("+", "%20"); } catch (Exception ignored) { }
        enviar("asis|/r?n=" + dir + (metros >= 0 ? "&m=" + Math.round(metros) : "") + q + (mapa ? "&q=1" : ""));
    }

    private static int primero(String s, String a, String b) {
        int i = s.indexOf(a), j = s.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }

    // ---------- Cubi canta cuando canta el artista: letra sincronizada (LRCLIB, gratis) + posición de la canción ----------
    private MediaController reproductor;
    private long reproductorVisto, vozMandada;
    private String cancionId = "";
    String cancionTexto = "";              // "Título de Artista", para que Haiku sepa qué suena
    private volatile long[] letraT;        // cuándo empieza cada línea (ms)
    private volatile boolean[] letraVoz;   // si esa línea tiene letra (las vacías son partes sin voz)
    private volatile String[] letraSil;    // una vocal por sílaba de cada línea: la boca de Cubi las va diciendo
    private volatile boolean[] letraCoro;  // la línea se repite en la canción: es del coro
    private int vozEnviada = -1, lineaEnviada = -1;
    private boolean siguiendo;
    volatile String estadoLetra = "";      // lo que pasa con la letra, para verlo en la app
    private final Runnable seguirLetraR = this::seguirLetra;
    static final Pattern LINEA = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\](.*)");

    private MediaController buscarReproductor() {
        if (!puedeTocarMusica()) return null;
        try {
            for (MediaController c : getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(this, Oyente.class)))
                if (c.getPlaybackState() != null && c.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) return c;
        } catch (SecurityException ignored) {
        }
        return null;
    }

    private void seguirLetra() {
        ui.removeCallbacks(seguirLetraR);
        long ahora = SystemClock.elapsedRealtime();
        if (!musicaPlay || !puedeTocarMusica()) {
            if (vozEnviada == 1) enviar("asis|/musica?voz=0");
            vozEnviada = -1;
            siguiendo = false;
            estadoLetra = !musicaPlay ? "No suena música en el celular"
                    : "Falta el permiso: toca “Permitir que Cubi controle la música” en la pestaña Cara";
            return;
        }
        siguiendo = true;
        if (reproductor == null || ahora - reproductorVisto > 3000) { reproductor = buscarReproductor(); reproductorVisto = ahora; }
        MediaController c = reproductor;
        MediaMetadata m = c == null ? null : c.getMetadata();
        PlaybackState st = c == null ? null : c.getPlaybackState();
        if (m == null || st == null) estadoLetra = "No veo qué canción suena (¿Spotify o YouTube Music en play?)";
        if (m != null && st != null) {
            String titulo = m.getString(MediaMetadata.METADATA_KEY_TITLE), artista = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            String id = artista + "|" + titulo;
            if (titulo != null && !id.equals(cancionId)) {
                cancionId = id;
                cancionTexto = titulo + (artista != null ? " de " + artista : "");
                letraT = null;
                ritmoCancion = ""; introPiano = false; ritmoMandado = "";
                estadoLetra = cancionTexto + ": buscando la letra…";
                pedirInstrumento(titulo, artista == null ? "" : artista, id);
                bajarLetra(titulo, artista == null ? "" : artista, m.getLong(MediaMetadata.METADATA_KEY_DURATION), id);
            }
            long[] t = letraT; boolean[] v = letraVoz;
            long pos = st.getPosition() + (long) ((ahora - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
            // instrumento: el piano mientras dura la intro de piano (hasta la primera línea de la letra, o 15 s sin letra),
            // después el de la canción
            long finIntro = t != null && t.length > 0 ? t[0] : 15000;
            String quiere = introPiano && pos < finIntro ? "piano" : ritmoCancion;
            if (!quiere.isEmpty() && !quiere.equals(ritmoMandado)) { ritmoMandado = quiere; enviar("asis|/musica?ritmo=" + quiere); }
            String[] sil = letraSil; boolean[] coro = letraCoro;
            if (t != null && sil != null && st.getState() == PlaybackState.STATE_PLAYING) {
                int i = -1;
                for (int k = 0; k < t.length && t[k] <= pos + 150; k++) i = k;  // 150 ms antes: lo que tarda en llegar a Cubi
                long d = i >= 0 ? durLinea(t, sil, i) : 0;
                int s = i >= 0 && v[i] && !sil[i].isEmpty() && pos + 150 - t[i] < d ? 1 : 0;   // al terminar la línea cierra la boca
                if (s == 1 && i != lineaEnviada) {   // línea nueva: sus sílabas, para que la boca vaya con la letra
                    enviar("asis|/musica?voz=1&s=" + sil[i] + "&d=" + d + "&o=" + (pos + 150 - t[i]) + "&c=" + (coro[i] ? 1 : 0));
                    lineaEnviada = i; vozEnviada = 1; vozMandada = ahora;
                } else if (s != vozEnviada || ahora - vozMandada > 4000) { enviar("asis|/musica?voz=" + s); vozEnviada = s; vozMandada = ahora; }
                if (s == 0) lineaEnviada = -1;
                estadoLetra = cancionTexto + ": " + (s == 1 ? "canta ahora" : "parte sin voz");
                // se despierta justo para el próximo cambio (empieza la línea o termina de cantarla): antes miraba cada
                // 250 ms y la boca arrancaba hasta un cuarto de segundo tarde
                long prox = 250;
                if (i + 1 < t.length) prox = Math.min(prox, t[i + 1] - 150 - pos);
                if (s == 1) prox = Math.min(prox, t[i] + d - 150 - pos);
                ui.postDelayed(seguirLetraR, Math.max(15, prox));
                return;
            }
        }
        ui.postDelayed(seguirLetraR, 250);
    }

    // lo que dura cantar la línea i: las sílabas a ~0,28 s más la última sostenida, sin pasarse de la siguiente línea
    static long durLinea(long[] t, String[] sil, int i) {
        long hueco = i + 1 < t.length ? t[i + 1] - t[i] : 7000;
        return Math.max(300, Math.min(hueco - 150, Math.max(800, sil[i].length() * 280L + 600)));
    }

    // una vocal por sílaba: cada grupo de vocales seguidas es una sílaba y se queda con la más abierta (a, o, e, u, i)
    static String vocales(String linea) {
        String s = java.text.Normalizer.normalize(linea.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD);
        StringBuilder r = new StringBuilder();
        char mejor = 0;
        for (char c : (s + " ").toCharArray()) {
            int f = "iueoa".indexOf(c == 'y' ? 'i' : c);
            if (f >= 0) { if (mejor == 0 || f > "iueoa".indexOf(mejor)) mejor = "iueoa".charAt(f); }
            else if (Character.getType(c) == Character.NON_SPACING_MARK) continue;   // la tilde no corta la sílaba
            else if (mejor != 0) { r.append(mejor); mejor = 0; }
        }
        return r.length() > 40 ? r.substring(0, 40) : r.toString();
    }

    // Haiku conoce casi todas las canciones: le pregunta el género y Cubi toca el instrumento de ese tipo de canción
    // (pop → canta con micrófono; la batería solo cuando de verdad manda la percusión)
    public static class Instrumento {
        public String instrumento;
        public Boolean empiezaPiano;
    }

    private volatile String ritmoCancion = "";   // el instrumento de la canción (por su género)
    private volatile boolean introPiano;         // la canción empieza solo con piano
    private String ritmoMandado = "";

    static final String[][] INSTRUMENTOS = {{"pop", "kpop"}, {"electronica", "dj"}, {"urbano", "dj"}, {"rock", "rock"},
            {"acustica", "guitarra"}, {"tropical", "tropical"}, {"jazz", "jazz"}, {"piano", "piano"}, {"percusion", "pop"}, {"acordeon", "acordeon"}, {"ranchera", "manos"}, {"balada", "vela"}};

    private void pedirInstrumento(String titulo, String artista, String id) {
        if (claveClaude().isEmpty()) return;  // sin clave: el micrófono adivina el estilo como antes
        // la captura mostró que a veces no llega (un tercio de las canciones sin género): lo intenta 3 veces y, si no,
        // canta con el micrófono en vez de quedarse con el instrumento de la canción anterior
        new Thread(() -> { for (int intento = 1; intento <= 3; intento++) {
            try {
                if (claude == null) claude = AnthropicOkHttpClient.builder().apiKey(claveClaude()).build();
                MessageCreateParams.Builder b = MessageCreateParams.builder().model(MODELO_CLAUDE).maxTokens(256L)
                        .addUserMessage("Canción: \"" + titulo + "\" de " + artista + ". ¿De qué tipo de canción es? "
                                + "En 'instrumento' responde exactamente uno: pop (pop, k-pop, r&b, baladas pop, cualquier canción "
                                + "donde manda la voz), electronica (electrónica, house, EDM, dance), urbano (reggaetón, trap, hip hop), "
                                + "rock (rock, metal, punk), acustica (folk, cantautor, country, acústico), tropical (salsa, cumbia, "
                                + "merengue, bachata), acordeon (vallenato, norteño, cumbia de acordeón), ranchera (mariachi, ranchera, banda, corridos), balada (baladas románticas, boleros, despecho, canciones lentas y sentidas), jazz (jazz, blues, soul con vientos), piano (baladas de piano, "
                                + "clásica), percusion (solo si la batería o los tambores son lo principal, como un solo de batería). "
                                + "Si dudas, pop. En 'empiezaPiano' pon true solo si la canción arranca con un piano solo "
                                + "antes de que entre la voz o el resto de la banda.");
                Instrumento r = claude.messages().create(b.outputConfig(Instrumento.class).build())
                        .content().stream().flatMap(c -> c.text().stream()).map(t -> t.text()).findFirst().orElse(null);
                if (r == null || r.instrumento == null || !id.equals(cancionId)) return;
                String g = r.instrumento.trim().toLowerCase(Locale.ROOT), elegido = "kpop";   // si no reconoce nada: micrófono
                for (String[] par : INSTRUMENTOS) if (par[0].equals(g)) elegido = par[1];
                if (g.contains("rock") || g.contains("metal") || g.contains("punk")) elegido = "rock";
                else if (g.contains("electr") || g.contains("house") || g.contains("dance") || g.contains("edm")
                        || g.contains("reggaet") || g.contains("urban") || g.contains("trap") || g.contains("hip")) elegido = "dj";
                else if (g.contains("vallenato") || g.contains("acorde") || g.contains("norte")) elegido = "acordeon";
                else if (g.contains("mariachi") || g.contains("ranch") || g.contains("banda") || g.contains("corrido")) elegido = "manos";
                else if (g.contains("balada") || g.contains("bolero") || g.contains("despecho")) elegido = "vela";
                else if (g.contains("salsa") || g.contains("cumbia") || g.contains("merengue") || g.contains("bachata")
                        || g.contains("tropical")) elegido = "tropical";
                else if (g.contains("jazz") || g.contains("blues") || g.contains("soul")) elegido = "jazz";
                else if (g.contains("piano") || g.contains("clasic") || g.contains("clásic")) elegido = "piano";
                else if (g.contains("acust") || g.contains("acúst") || g.contains("folk") || g.contains("country")) elegido = "guitarra";
                else if (g.contains("percus") || g.contains("bater")) elegido = "pop";
                ritmoCancion = elegido;
                introPiano = Boolean.TRUE.equals(r.empiezaPiano);
                return;
            } catch (Exception e) {  // sin internet o la respuesta no llegó: otra vez en un rato
                if (intento == 3) { if (id.equals(cancionId)) ritmoCancion = "kpop"; return; }
                SystemClock.sleep(3000L * intento);
            }
        } }).start();
    }

    // busca la canción en LRCLIB y se queda con la letra sincronizada de la versión de duración más parecida
    private void bajarLetra(String titulo, String artista, long durMs, String id) {
        new Thread(() -> {
            try {
                String u = "https://lrclib.net/api/search?track_name=" + URLEncoder.encode(titulo, "UTF-8")
                        + "&artist_name=" + URLEncoder.encode(artista, "UTF-8");
                HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
                h.setRequestProperty("User-Agent", "Cubi (https://github.com/ofco93-blip/cubi)");
                h.setConnectTimeout(8000);
                h.setReadTimeout(10000);
                JSONArray a;
                try (InputStream in = h.getInputStream()) {
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    byte[] b = new byte[8192];
                    int n;
                    while ((n = in.read(b)) > 0) o.write(b, 0, n);
                    a = new JSONArray(o.toString("UTF-8"));
                }
                String mejor = null; double dif = 1e9;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject r = a.getJSONObject(i);
                    String sync = r.optString("syncedLyrics", "");
                    if (sync.isEmpty() || sync.equals("null")) continue;
                    double d = durMs > 0 ? Math.abs(r.optDouble("duration", 0) - durMs / 1000.0) : 0;
                    if (d < dif) { dif = d; mejor = sync; }
                }
                if (!id.equals(cancionId)) return;
                if (mejor == null || dif > 10) { estadoLetra = cancionTexto + ": sin letra sincronizada (usa el micrófono)"; return; }
                ArrayList<Long> ts = new ArrayList<>(); ArrayList<Boolean> vs = new ArrayList<>(); ArrayList<String> xs = new ArrayList<>();
                java.util.HashMap<String, Integer> veces = new java.util.HashMap<>();
                for (String linea : mejor.split("\n")) {
                    Matcher mt = LINEA.matcher(linea.trim());
                    if (!mt.matches()) continue;
                    ts.add((long) ((Integer.parseInt(mt.group(1)) * 60 + Double.parseDouble(mt.group(2))) * 1000));
                    String x = mt.group(3).trim().toLowerCase(Locale.ROOT);
                    vs.add(!x.isEmpty());
                    xs.add(x);
                    veces.merge(x, 1, Integer::sum);
                }
                long[] t = new long[ts.size()]; boolean[] v = new boolean[ts.size()], coro = new boolean[ts.size()];
                String[] sil = new String[ts.size()];
                for (int i = 0; i < t.length; i++) {
                    t[i] = ts.get(i); v[i] = vs.get(i); sil[i] = vocales(xs.get(i));
                    coro[i] = v[i] && veces.get(xs.get(i)) >= 2;
                }
                letraCoro = coro;
                letraSil = sil;
                letraVoz = v;
                letraT = t;
            } catch (Exception e) {  // sin internet o sin letra: Cubi sigue con lo que oye
                if (id.equals(cancionId)) estadoLetra = cancionTexto + ": no pude bajar la letra (" + e.getMessage() + ")";
            }
        }).start();
    }

    // con "Acceso a notificaciones" se le habla directo al reproductor: "busca esto y ponlo" (como Android Auto)
    // Algunos celulares contestan "no" aunque el acceso esté prendido: también se mira la lista del sistema y, si está
    // ahí, se le pide a Android que vuelva a conectar el oyente (pasa después de actualizar la app)
    private long reconectado;

    boolean puedeTocarMusica() {
        ComponentName yo = new ComponentName(this, Oyente.class);
        if (Build.VERSION.SDK_INT >= 27 && getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(yo)) return true;
        String l = android.provider.Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        boolean enLista = l != null && l.contains(getPackageName());
        if (enLista && System.currentTimeMillis() - reconectado > 60000) {
            reconectado = System.currentTimeMillis();
            android.service.notification.NotificationListenerService.requestRebind(yo);
        }
        return enLista;
    }

    private boolean tocarEnSesion(String q) {
        if (!puedeTocarMusica()) return false;
        try {
            List<MediaController> l = getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(this, Oyente.class));
            for (String app : new String[]{"com.spotify.music", "com.google.android.apps.youtube.music"})  // primero Spotify
                for (MediaController c : l)
                    if (c.getPackageName().equals(app)) {
                        c.getTransportControls().playFromSearch(q, new Bundle());
                        return true;
                    }
        } catch (SecurityException ignored) {
        }
        return false;
    }

    private void teclaMedia(int k) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, k));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, k));
    }

    private void musica(String accion, String busqueda) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        switch (accion) {
            case "musica_play": teclaMedia(KeyEvent.KEYCODE_MEDIA_PLAY); break;
            case "musica_pausa": teclaMedia(KeyEvent.KEYCODE_MEDIA_PAUSE); break;
            case "musica_siguiente": teclaMedia(KeyEvent.KEYCODE_MEDIA_NEXT); break;
            case "musica_anterior": teclaMedia(KeyEvent.KEYCODE_MEDIA_PREVIOUS); break;
            case "volumen_subir": case "volumen_bajar":
                for (int i = 0; i < 2; i++)
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, accion.equals("volumen_subir") ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
                break;
            case "musica_buscar":  // con Spotify conectado (API, como Copi) la busca y le da play de verdad
                if (!new SpotifyApi(this).conectado()) { buscarYTocar(busqueda); break; }
                new Thread(() -> {
                    String puso = new SpotifyApi(this).reproducir(busqueda);
                    if (puso != null) js("toast(" + JSONObject.quote("Poniendo: " + puso) + ")");
                    else ui.post(() -> buscarYTocar(busqueda));
                }).start();
                break;
        }
    }

    // Spotify solo obedece "busca y pon" con su reproductor despierto. Si está dormido se despierta con la tecla de play
    // (arranca lo último que sonaba) y enseguida se le pide la búsqueda; si no aparece, se abre Spotify y se sigue intentando.
    private void buscarYTocar(String q) {
        if (!puedeTocarMusica()) {
            js("toast('Falta el permiso: en la app toca “Permitir que Cubi controle la música”')");
            abrirBusqueda(q);
            return;
        }
        if (tocarEnSesion(q)) { js("toast(" + JSONObject.quote("Poniendo: " + q) + ")"); return; }
        teclaMedia(KeyEvent.KEYCODE_MEDIA_PLAY);
        reintentarTocar(q, 0);
    }

    private void reintentarTocar(String q, int n) {
        ui.postDelayed(() -> {
            if (tocarEnSesion(q)) { js("toast(" + JSONObject.quote("Poniendo: " + q) + ")"); return; }
            if (n == 2) abrirBusqueda(q);  // a los 4,5 s sin reproductor: abre Spotify con la búsqueda
            if (n < 8) reintentarTocar(q, n + 1);
            else js("toast('Spotify no me hizo caso: dale play tú')");
        }, 1500);
    }

    private void abrirBusqueda(String q) {
        Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(SearchManager.QUERY, q)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        for (String app : new String[]{"com.spotify.music", "com.google.android.apps.youtube.music"})
            if (getPackageManager().getLaunchIntentForPackage(app) != null) { i.setPackage(app); break; }
        try { startActivity(i); } catch (Exception e) { teclaMedia(KeyEvent.KEYCODE_MEDIA_PLAY); }
    }

    private void terminarCharla() {
        charlaHasta = 0;
        bajarMusica(false);
        revisarAsistente();
    }


    private long asisDesde;
    private String asisEstado = "";
    private boolean musicaPlay;  // lo último que se le dijo a Cubi: suena música en el celular
    private boolean videoMandado;  // lo último que se le dijo: el celular está con un video (reels, Facebook)

    // Abre el asistente del celular (Gemini), como "Hey Google". Con la pantalla apagada necesita el permiso
    // "Mostrar sobre otras apps". En Samsung el comando de voz genérico abre Bixby: primero se le pide a Google.
    void abrirAsistente() {
        String google = "com.google.android.googlequicksearchbox";
        boolean enCarro = ((UiModeManager) getSystemService(UI_MODE_SERVICE)).getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR;
        Intent[] intentos = {
                enCarro ? new Intent("android.speech.action.VOICE_SEARCH_HANDS_FREE").setPackage(google) : null,
                new Intent(Intent.ACTION_VOICE_COMMAND).setPackage(google),
                new Intent("android.intent.action.VOICE_ASSIST").setPackage(google),
                new Intent(Intent.ACTION_ASSIST).setPackage(google),
                new Intent(Intent.ACTION_ASSIST),
                getPackageManager().getLaunchIntentForPackage("com.google.android.apps.bard"),
        };
        for (Intent i : intentos) {
            if (i == null) continue;
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                asisDesde = System.currentTimeMillis();
                asisEstado = "escucha";  // Cubi ya puso la cara de escuchar al recibir el doble toque
                revisarAsistente();
                return;
            } catch (Exception ignored) {
            }
        }
        enviar("asis|/asistente?estado=fin");
        js("toast('No pude abrir Gemini: dale a la app el permiso “Mostrar sobre otras apps”')");
    }

    // Imitar al asistente (lo que Lumi hacía bien): Android cuenta qué TIPO de audio suena y si alguien usa el
    // micrófono (nunca el contenido). Se mira siempre, no solo tras el doble toque: también "Ok Google" o el botón.
    //  - Suena "asistente" o "navegación" más de 1,2 s → habla. Un timbre corto con ese tipo no cuenta.
    //  - En los 2 min tras abrir Gemini desde Cubi, la voz "tipo llamada" (Gemini Live) también es hablar.
    //  - Alguien graba → escucha. En el carro graba el micrófono del carro (el celular no): ahí el pitido corto
    //    del asistente cuenta como "te escucho" por 8 s o hasta que hable.
    private long hablaDesde, pitidoHasta, ultimoEnvio;
    private boolean pitido;
    private final Runnable revisar = this::revisarAsistente;

    private final AudioManager.AudioRecordingCallback oyeMic = new AudioManager.AudioRecordingCallback() {
        @Override public void onRecordingConfigChanged(java.util.List<android.media.AudioRecordingConfiguration> c) { revisarAsistente(); }
    };

    void escucharAsistente() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        am.registerAudioRecordingCallback(oyeMic, ui);
        if (Build.VERSION.SDK_INT >= 26) am.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
            @Override public void onPlaybackConfigChanged(java.util.List<AudioPlaybackConfiguration> c) { revisarAsistente(); }
        }, ui);
    }

    // Los oídos que esperan un nombre ("Cubi", y el de Copi si lo usa) graban con la fuente MIC; Gemini y Google usan
    // las de voz. Así cada robot no confunde el oído del otro con un asistente escuchando (Copi se quedaba "escuchando").
    private static boolean oidoDeRobot(AudioRecordingConfiguration c) {
        return Build.VERSION.SDK_INT >= 29 && c.getClientAudioSource() == MediaRecorder.AudioSource.MIC;
    }

    private void revisarAsistente() {
        ui.removeCallbacks(revisar);
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        long ahora = System.currentTimeMillis();
        boolean ventana = ahora - asisDesde < 120000;
        boolean graba = false;  // la grabación de "Hola Cubi" (la nuestra) no cuenta
        for (AudioRecordingConfiguration c : am.getActiveRecordingConfigurations())
            if (c.getClientAudioSessionId() != miSesion && !oidoDeRobot(c)) graba = true;
        graba &= am.getMode() != AudioManager.MODE_IN_CALL;
        boolean suenaAsis = false, suenaNav = false, suenaVoz = false, suenaMedia = false;
        if (Build.VERSION.SDK_INT >= 26) for (AudioPlaybackConfiguration c : am.getActivePlaybackConfigurations()) {
            int u = c.getAudioAttributes().getUsage();
            if (u == AudioAttributes.USAGE_ASSISTANT) suenaAsis = true;
            else if (u == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE) suenaNav = true;
            else if (u == AudioAttributes.USAGE_VOICE_COMMUNICATION || u == AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY) suenaVoz = true;
            else if (u == AudioAttributes.USAGE_MEDIA) suenaMedia = true;
        }
        // la voz "tipo llamada" solo es Gemini hablando si nadie graba: si graban, manda la oreja
        boolean hablaOtro = suenaAsis || suenaNav || (ventana && !graba && suenaVoz);
        if (hablaOtro && hablaDesde == 0) { hablaDesde = ahora; pitido = suenaAsis && ahora >= charlaHasta; }  // la voz de Cubi no es un pitido de Gemini
        if (!hablaOtro && hablaDesde != 0) {
            if (ahora - hablaDesde < 1200 && pitido && !asisEstado.equals("habla")) pitidoHasta = ahora + 8000;
            hablaDesde = 0;
        }
        boolean habla = hablaOtro && (ventana || asisEstado.equals("habla") || ahora - hablaDesde >= 1200);
        if (habla) pitidoHasta = 0;
        boolean oye = !hablaOtro && (graba || ahora < pitidoHasta);
        String nuevo = habla ? "habla" : oye ? "escucha" : "";
        if (nuevo.isEmpty() && ahora - asisDesde < 6000) nuevo = "escucha";  // Gemini tarda en abrir el micrófono
        if (nuevo.isEmpty() && ahora < charlaHasta) nuevo = "escucha";      // Cubi oye la pregunta o piensa la respuesta
        if (!nuevo.equals(asisEstado) || (!nuevo.isEmpty() && ahora - ultimoEnvio > 5000)) {  // se repite: en Cubi el estado caduca
            boolean fin = nuevo.isEmpty();
            if (!(fin && asisEstado.isEmpty())) enviar("asis|/asistente?estado=" + (fin ? "fin" : nuevo));
            asisEstado = nuevo;
            ultimoEnvio = ahora;
        }
        // música en play: Cubi baila de una y deja de bailar apenas se pausa. Con el permiso de música solo cuentan
        // las apps de música (los reels y videos no lo ponen a bailar)
        // Con el permiso de música se mira el estado de Spotify (o la app de música) directamente: así funciona igual
        // cuando suena por Android Auto, donde el celular no siempre reporta audio propio. Sin permiso, el audio del celular.
        boolean play = puedeTocarMusica() ? suenaAppDeMusica() : suenaMedia && am.isMusicActive();
        // suena algo en el celular que no es una app de música (reels, Facebook): Cubi no baila con eso
        boolean video = suenaMedia && am.isMusicActive() && !play;
        if (video != videoMandado) { videoMandado = video; enviar("musica|/musica?video=" + (video ? 1 : 0)); }
        if (play != musicaPlay) { musicaPlay = play; ritmoMandado = ""; enviar("musica|/musica?play=" + (play ? 1 : 0)); }  // al volver, se manda otra vez el instrumento
        if (musicaPlay && !siguiendo) seguirLetra();
        // los callbacks no avisan cuando pasa el tiempo: mientras algo esté en juego se vuelve a mirar
        if (!asisEstado.isEmpty() || hablaDesde != 0 || ahora < pitidoHasta || ventana || musicaPlay || ahora < charlaHasta) ui.postDelayed(revisar, 400);
        else if (listo) ui.postDelayed(revisar, 2000);  // con Cubi conectado se vuelve a mirar: con Android Auto no siempre llega el aviso de audio
        // "Hola Cubi" escucha solo cuando nadie más usa el micrófono ni hay llamada
        // ni con un video sonando (Facebook, reels): eso lo despertaba de la nada
        // con música SÍ escucha: en el carro la música sale por los parlantes del carro y uno le quiere hablar igual
        boolean libre = asisEstado.isEmpty() && am.getMode() == AudioManager.MODE_NORMAL && !otaActiva && ahora >= charlaHasta && !video;
        if (oidoQuiere && modelo != null && libre) arrancarOido();
        else pararOido();
    }

    static final List<String> APPS_MUSICA = Arrays.asList("com.spotify.music", "com.google.android.apps.youtube.music",
            "deezer.android.app", "com.apple.android.music", "com.amazon.mp3", "com.soundcloud.android", "com.aspiro.tidal");

    private boolean suenaAppDeMusica() {
        try {
            for (MediaController c : getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(this, Oyente.class)))
                if (APPS_MUSICA.contains(c.getPackageName()) && c.getPlaybackState() != null
                        && c.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) return true;
        } catch (SecurityException ignored) {
        }
        return false;
    }

    // ---------- "Hola Cubi": el celular escucha sin internet (Vosk, modelo español de ~40 MB) y abre Gemini ----------
    static final String MODELO = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip";
    // el modelo no conoce "Cubi": lo oye como cuba o cubo. Solo despierta con "Hola" + eso: con "Cubi" solo,
    // cualquier video o canción (reels) lo despertaba de la nada
    // "robot" sí está en el vocabulario del modelo: "Hola robot" / "Oye robot" se oyen mucho mejor que "cubi"
    static final String GRAMATICA = "[\"hola cubi\", \"hola cuba\", \"hola cubo\", \"oye cuba\", \"oye cubo\", \"hola robot\", \"oye robot\", \"[unk]\"]";
    static final Pattern HOLA = Pattern.compile("\\b(h?ola|oye)\\s+((cu|ku)[bv]\\w{0,2}|robot)\\b");
    // para que la app muestre qué pasa: si el micrófono trae sonido, qué oyó por última vez y si falló algo
    private volatile int nivelMic = -1;
    private volatile String oidoOyo = "", oidoError = "";
    private volatile boolean oidoQuiere;
    private volatile Thread oido, hiloOido;
    private volatile int miSesion = -1;
    private volatile org.vosk.Model modelo;
    private boolean bajando;
    private long ultimoHola;

    void hola(boolean si) {
        getSharedPreferences("cubi", 0).edit().putBoolean("oido", si).apply();
        oidoQuiere = si;
        primerPlano();
        if (si) prepararOido();
        else pararOido();
    }

    boolean holaActivo() { return oidoQuiere; }

    // La primera vez baja el modelo; después solo lo carga (tarda unos segundos)
    private void prepararOido() {
        if (modelo != null || bajando) return;
        bajando = true;
        new Thread(() -> {
            File dir = new File(getFilesDir(), "vosk-es");
            try {
                if (!new File(dir, "listo").exists()) {
                    js("toast('Bajando el oído de Cubi (40 MB), solo esta vez…')");
                    bajarModelo(dir);
                }
                modelo = new org.vosk.Model(dir.getAbsolutePath());
                js("toast('Listo: di “Hola Cubi”')");
                ui.post(this::revisarAsistente);
            } catch (Throwable e) {   // también los errores de la librería (antes se quedaba "preparando" para siempre)
                oidoError = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
                js("toast(" + JSONObject.quote("No pude preparar “Hola Cubi”: " + oidoError) + ")");
            }
            bajando = false;
        }).start();
    }

    private void bajarModelo(File dir) throws Exception {
        String raiz = dir.getCanonicalPath() + File.separator;
        try (InputStream in = new URL(MODELO).openStream(); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            byte[] b = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                String nombre = e.getName();
                int barra = nombre.indexOf('/');
                if (barra < 0 || barra == nombre.length() - 1) continue;  // se quita la carpeta de arriba del zip
                File f = new File(dir, nombre.substring(barra + 1));
                if (!f.getCanonicalPath().startsWith(raiz)) throw new Exception("archivo raro en el modelo");
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (FileOutputStream o = new FileOutputStream(f)) {
                    int n;
                    while ((n = z.read(b)) > 0) o.write(b, 0, n);
                }
            }
        }
        new File(dir, "listo").createNewFile();
    }

    private void arrancarOido() {
        if (oido != null || (hiloOido != null && hiloOido.isAlive())) return;   // nunca dos reconocedores a la vez
        Thread t = new Thread(() -> {
            AudioRecord r = null;
            try (org.vosk.Recognizer rec = new org.vosk.Recognizer(modelo, 16000, GRAMATICA)) {
                int tam = Math.max(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 8192);
                // fuente MIC: así Copi (y Cubi mismo) saben que es el oído de un robot y no un asistente escuchando
                r = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, tam);
                miSesion = r.getAudioSessionId();
                r.startRecording();
                short[] b = new short[2048];
                int pico = 0, bloques = 0;
                oidoError = "";
                while (oido == Thread.currentThread()) {
                    int n = r.read(b, 0, b.length);
                    if (n < 0) { oidoError = "el micrófono respondió " + n; break; }
                    for (int i = 0; i < n; i++) pico = Math.max(pico, Math.abs(b[i]));
                    if (++bloques >= 8) { nivelMic = Math.min(100, pico * 100 / 8000); pico = 0; bloques = 0; }   // ~1 s
                    boolean fin = rec.acceptWaveForm(b, n);
                    String texto = fin ? rec.getResult() : rec.getPartialResult();
                    if (fin) {
                        String oyo = texto.replaceAll("(?s).*\"text\"\\s*:\\s*\"([^\"]*)\".*", "$1").replace("[unk]", "algo").trim();
                        if (!oyo.isEmpty() && !oyo.startsWith("{")) oidoOyo = oyo;
                    }
                    if (HOLA.matcher(texto).find()) {
                        rec.reset();
                        ui.post(this::oyoHola);
                    }
                }
            } catch (Throwable e) {  // sin permiso o sin micrófono: se reintenta en la próxima revisión (y la app lo muestra)
                oidoError = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
            } finally {
                nivelMic = -1;
                if (r != null) r.release();
                if (oido == Thread.currentThread()) { oido = null; miSesion = -1; }
            }
        });
        oido = hiloOido = t;
        t.start();
    }

    private void pararOido() {
        if (oido == null) return;
        oido = null;
        miSesion = -1;
    }

    private void oyoHola() {
        long ahora = System.currentTimeMillis();
        if (ahora - ultimoHola < 4000) return;
        ultimoHola = ahora;
        pararOido();  // el micrófono es para Gemini
        enviar("asis|/asistente?estado=escucha");
        if (claveClaude().isEmpty() && hayInternet()) abrirAsistente();   // sin internet Gemini no sirve: Cubi entiende lo básico
        else charla();
    }

    // para la app: por qué "Hola Cubi" escucha o no
    String estadoHola() {
        if (!oidoQuiere) return "Apagado.";
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return "Falta el permiso del micrófono: apágalo y préndelo.";
        if (modelo == null) return bajando ? "Preparando el oído (la primera vez baja 40 MB)…" : "El oído no está listo: necesita internet una sola vez para bajarse.";
        if (!micFondo) return "Android no le dio el micrófono porque Cubi arrancó con la app cerrada: abre la app una vez.";
        String sinRed = hayInternet() ? "" : " Sin internet entiende: baila, música, pausa, siguiente, volumen, la hora, chistes y kamehameha.";
        if (oido != null) {
            String m = nivelMic < 0 ? "" : nivelMic == 0 ? " ⚠️ El micrófono llega en silencio: otra app lo está usando o Android lo bloqueó (cierra y abre la app)."
                : " Micrófono: " + nivelMic + "%.";
            return "Escuchando: di “Hola Cubi” u “Oye robot”." + m + (oidoOyo.isEmpty() ? "" : " Lo último que oyó: “" + oidoOyo + "”.") + sinRed;
        }
        if (!oidoError.isEmpty()) return "Falló el oído: " + oidoError;
        return "En pausa (video, llamada o Cubi hablando)." + sinRed;
    }

    // ---------- actualización por Bluetooth ----------
    void ota(byte[] datos) {
        if (otaActiva) return;
        if (ota == null || gatt == null) {
            js("otaFin(false," + JSONObject.quote("Cubi no está conectado por Bluetooth.") + ")");
            return;
        }
        fw = datos;
        String md5;
        try {
            StringBuilder h = new StringBuilder();
            for (byte x : MessageDigest.getInstance("MD5").digest(fw)) h.append(String.format("%02x", x));
            md5 = h.toString();
        } catch (Exception e) {
            md5 = "";
        }
        otaActiva = true;
        otaTerminando = false;
        otaEnviados = 0;
        otaTotal = fw.length;
        otaPct = -1;
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        js("otaEmpieza(" + otaTotal + ")");
        enviar("ota|/ota/inicio?tam=" + otaTotal + (md5.isEmpty() ? "" : "&md5=" + md5));
    }

    private void respuestaOta(String r) {
        if (!otaActiva) return;
        if (r.startsWith("!")) {
            otaFallo(r.substring(1));
            return;
        }
        if (otaEnviados == 0 && !otaTerminando && otaPct < 0) {  // respuesta al inicio: ahora sí manda el firmware
            otaPct = 0;
            synchronized (cola) {
                for (int i = 0; i < fw.length; i += PEDAZO) {
                    byte[] p = new byte[Math.min(PEDAZO, fw.length - i)];
                    System.arraycopy(fw, i, p, 0, p.length);
                    cola.add(new Paquete(true, p));
                }
            }
            siguiente();
        } else if (otaEnviados >= otaTotal) {  // respuesta al fin: verificado, el robot se reinicia
            otaTerminando = true;
        }
    }

    private void otaFallo(String msg) {
        synchronized (cola) {
            cola.clear();
        }
        otaFin(false, msg);
        if (gatt != null) enviar("ota|/ota/cancelar");
    }

    private void otaFin(boolean ok, String msg) {
        if (!otaActiva) return;
        otaActiva = false;
        fw = null;
        js("otaFin(" + ok + "," + JSONObject.quote(msg) + ")");
    }
}
