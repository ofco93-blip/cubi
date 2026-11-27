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
        final Thread.UncaughtExceptionHandler antes = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((hilo, e) -> {   // se cerró sola: se anota la causa para verla al volver a abrir
            try {
                java.io.StringWriter w = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(w));
                String t = w.toString();
                getSharedPreferences("cubi", 0).edit().putString("caida", new java.text.SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new java.util.Date())
                        + " " + (t.length() > 900 ? t.substring(0, 900) : t)).commit();
            } catch (Throwable ignorado) {
            }
            if (antes != null) antes.uncaughtException(hilo, e);
        });
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
        if (CubiMapi.yo == null) CubiMapi.yo = new CubiMapi(this);   // Mapi, la pantalla redonda del mapa: su propia conexión
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        if (gatt != null) gatt.close();
        if (CubiMapi.yo != null) { CubiMapi.yo.parar(); CubiMapi.yo = null; }
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
                    if (listo && !otaActiva && !otaTerminando) guardarParqueo();   // estaba conectado y se fue: se apagó el carro
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
                    ui.post(CubiService.this::revisarAsistente);   // sin Cubi, el celular deja de escuchar "Hola Cubi"
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
                ui.post(CubiService.this::mandarHora);
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
        int pq = s.indexOf("paq=");   // el personaje escogido, para dibujar el mismo en la pantalla del carro
        if (pq >= 0) try { getSharedPreferences("cubi", 0).edit().putInt("paq", Integer.parseInt(s.substring(pq + 4).replaceAll("\\D.*", ""))).apply(); } catch (NumberFormatException ignored) { }
        int oj = s.indexOf("ojos=");   // el color de la cara se guarda para pintarla igual en la pantalla del carro
        if (oj >= 0 && s.length() >= oj + 11) try { getSharedPreferences("cubi", 0).edit().putInt("ojos", Integer.parseInt(s.substring(oj + 5, oj + 11), 16)).apply(); } catch (NumberFormatException ignored) { }
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

    // "¿Dónde parqueé?": cuando Cubi se desconecta (se apagó el carro) se guarda la última ubicación del celular, con su
    // hora. No avisa: se ve en la app, con un botón que la abre en Maps. Se guarda completa (6 decimales) solo en el celular.
    @SuppressLint("MissingPermission")
    void guardarParqueo() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        Location mejor = antesGps;   // con el GPS de la app prendido, la del camino es la más fresca
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (mejor == null || l.getTime() > mejor.getTime())) mejor = l;
            }
        } catch (Exception e) { return; }
        if (mejor != null) getSharedPreferences("cubi", 0).edit().putString("parqueo",
                String.format(Locale.US, "%.6f,%.6f,%d", mejor.getLatitude(), mejor.getLongitude(), mejor.getTime())).apply();
    }

    // "lat,lon,hora" de donde quedó el carro, o vacío
    String parqueo() { return getSharedPreferences("cubi", 0).getString("parqueo", ""); }

    private final Runnable climaCada = this::clima;
    private long climaCuando;
    // la hora, para el reloj del gato: el robot no tiene reloj propio, sigue contando desde lo que le diga el celular
    void mandarHora() { Calendar c = Calendar.getInstance(); enviar("reloj|/ajuste?reloj=" + (c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE))); }

    void clima() {
        ui.removeCallbacks(climaCada);
        mandarHora();
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
                    + "&current=temperature_2m,weather_code,precipitation,is_day&hourly=precipitation_probability&forecast_hours=6&timezone=auto").openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(10000);
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) { byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); }
                JSONObject todo = new JSONObject(b.toString("UTF-8")), cur = todo.getJSONObject("current");
                int llueve = -1;   // la probabilidad de lluvia más alta de las próximas 6 horas
                org.json.JSONArray pp = todo.optJSONObject("hourly") == null ? null : todo.optJSONObject("hourly").optJSONArray("precipitation_probability");
                if (pp != null) for (int i = 0; i < pp.length(); i++) llueve = Math.max(llueve, pp.optInt(i, 0));
                int t = (int) Math.round(cur.getDouble("temperature_2m")), cod = cur.getInt("weather_code"), dia = cur.getInt("is_day");
                // códigos de la OMM: 0-1 despejado, 2-3 nubes, 45-48 niebla, 51-67 y 80-82 lluvia, 71-77 y 85-86 nieve, 95-99 tormenta
                String e = cod <= 1 ? "despejado" : cod <= 3 ? "nubes" : cod <= 48 ? "niebla" : cod <= 67 ? "lluvia"
                    : cod <= 77 ? "nieve" : cod <= 82 ? "lluvia" : cod <= 86 ? "nieve" : "tormenta";
                // El código es un pronóstico y en el trópico dice "llovizna" a cada rato sin que caiga nada: solo cuenta
                // como lluvia si además marca agua cayendo ahora (0,2 mm o más). Si no, nubes.
                if (e.equals("lluvia") && cur.optDouble("precipitation", 1) < 0.2) e = "nubes";
                climaOrden = "/clima?t=" + t + "&e=" + e + "&d=" + dia;
                climaGrados = t; climaEstado = e; climaNoche = dia == 0;
                if (todo.has("elevation")) climaAltura = (int) Math.round(todo.optDouble("elevation"));   // la del terreno, por si el GPS no da la suya
                enviar("clima|" + climaOrden);
                climaTexto = "Ahora: " + e + ", " + t + " grados" + (llueve < 0 ? "" : ". Probabilidad de lluvia en las próximas 6 horas: " + llueve + " por ciento");
                js("climaTexto(" + JSONObject.quote(climaTexto) + ")");
            } catch (Exception ignored) {}
            try {   // el pueblo o la ciudad por donde va, con el buscador de direcciones del propio celular; sin señal queda el último
                java.util.List<android.location.Address> as = new android.location.Geocoder(this, new Locale("es")).getFromLocation(Double.parseDouble(ll[0]), Double.parseDouble(ll[1]), 1);
                if (as != null && !as.isEmpty()) {
                    android.location.Address a = as.get(0);
                    String n = a.getLocality() != null ? a.getLocality() : a.getSubAdminArea() != null ? a.getSubAdminArea() : a.getAdminArea();
                    if (n != null) climaLugar = n;
                }
            } catch (Exception ignored) {}
            if (climaLugar.isEmpty()) try {
                JSONObject a = new JSONObject(bajarTexto("https://nominatim.openstreetmap.org/reverse?format=json&zoom=10&accept-language=es&lat=" + ll[0] + "&lon=" + ll[1])).optJSONObject("address");
                if (a != null) for (String k : new String[]{"city", "town", "village", "municipality", "county"}) if (a.has(k)) { climaLugar = a.optString(k); break; }
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
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, navMapa() || carroMapa() ? 1000 : 5000, 0, oyeGps, Looper.getMainLooper()); }
        catch (Exception e) { oyeGps = null; }
    }

    // ---------- mini mapa: las calles de alrededor (OpenStreetMap) y por dónde va el carro, para la tarjeta de giro ----------
    // Solo con "Modo mapa" prendido y una ruta andando. La posición sale del celular hacia overpass-api.de (el servicio
    // público de OpenStreetMap) cada vez que se avanza unos 110 m, para pedir las calles de 240 m a la redonda.
    private volatile double mapaLat0, mapaLon0;
    volatile boolean mapaListo; private volatile boolean mapaCargando, mapaForzar;   // mapaListo: también lo mira Mapi
    private long mapaPedido;
    volatile float mapaRumbo;
    volatile double mapaDx, mapaDy;                 // dónde va el carro respecto al centro del mapa bajado (m)
    // La pantalla del carro también usa el mapa: mientras esté a la vista (avisa en cada cuadro) se bajan las calles aunque
    // el robot no esté en modo mapa. ponytail: al cerrarla, el GPS sigue a 1 s hasta el próximo cambio de ajuste del GPS.
    volatile long carroViendo;
    boolean carroMapa() { return System.currentTimeMillis() - carroViendo < 6000; }
    void carroEntra() { carroViendo = System.currentTimeMillis(); gps(); }
    // al conectarse el carro: si a los 6 s no suena nada, le da "play" al último reproductor (como el botón del volante)
    void carroPrende() {
        ui.postDelayed(() -> {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (musicaPlay || am.isMusicActive()) return;
            long t = android.os.SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0));
            am.dispatchMediaKeyEvent(new android.view.KeyEvent(t, t, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0));
        }, 6000);
    }
    volatile java.util.List<String> mapaZonas;      // el agua, los parques y los ríos, para la pantalla del carro
    volatile long mapaCuando;                       // cuándo llegó la última posición y a qué velocidad (m/s): el carro estima el avance entre una y otra
    volatile float mapaVel;
    volatile java.util.List<String> mapaTramos;     // las calles bajadas, para dibujarlas también en la pantalla del carro
    volatile String mapaEstado = "Mini mapa: esperando el GPS.";
    // la ruta: el destino lo escribe uno en la app; se busca en OpenStreetMap (nominatim) y la ruta la calcula OSRM
    // (router.project-osrm.org, gratis y sin cuenta). No es la ruta de Google: es la de ese servicio.
    private volatile double destLat = Double.NaN, destLon;
    private volatile String destNombre = "", rutaEstado = "";
    volatile double[] rutaLat, rutaLon;
    // Lo que falta del viaje según la ruta propia (la de OSRM): sirve cuando Maps no lo dice. rutaAcum: metros desde la
    // salida hasta cada punto; rutaDura: segundos de toda la ruta, sin tráfico.
    private volatile float[] rutaAcum; private volatile double rutaDura;
    volatile int rutaFaltaM = -1, rutaFaltaMin = -1;
    volatile String rutaLlega = "";
    private void faltaRuta(double lat, double lon) {
        double[] la = rutaLat, lo = rutaLon; float[] ac = rutaAcum;
        if (la == null || lo == null || ac == null || ac.length != la.length || la.length < 2) { rutaFaltaM = -1; rutaFaltaMin = -1; rutaLlega = ""; return; }
        double kx = Math.cos(Math.toRadians(lat)), mejor = 1e18; int k = 0;
        for (int i = 0; i < la.length; i++) { double dx = (lo[i] - lon) * kx, dy = la[i] - lat, d2 = dx * dx + dy * dy; if (d2 < mejor) { mejor = d2; k = i; } }
        float total = ac[ac.length - 1], falta = total - ac[k];
        int min = total <= 0 ? 0 : (int) Math.round(rutaDura * falta / total / 60);
        rutaFaltaM = Math.round(falta); rutaFaltaMin = min;
        if (falta < 60 && Math.hypot((la[la.length - 1] - lat) * 111320, (lo[lo.length - 1] - lon) * 111320 * kx) < 120) { llegue(); return; }   // llegó: se acaba la ruta y queda el resumen
        rutaLlega = new java.text.SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm", Locale.US).format(new java.util.Date(System.currentTimeMillis() + min * 60000L));
    }
    private volatile boolean rutaCalculando, destBuscando;
    private long rutaPedida, destIntento;

    boolean navCompleto() { return getSharedPreferences("cubi", 0).getBoolean("navCompleto", false); }
    void navCompletoOn(boolean si) { getSharedPreferences("cubi", 0).edit().putBoolean("navCompleto", si).apply(); }
    String destinoTexto() { return getSharedPreferences("cubi", 0).getString("destino", ""); }

    // el destino escrito en la app ("" lo quita)
    String destinoNombre() { return destinoTexto().isEmpty() ? "" : destNombre; }
    // El viaje: lo andado y el tiempo desde que se puso el destino; al llegar queda el resumen un rato en la pantalla del carro.
    private double viajeM; private long viajeDesde; private Location viajeAntes;
    volatile String resumen = ""; volatile long resumenCuando;
    private void llegue() {
        if (viajeDesde > 0 && viajeM > 200) {
            long min = Math.max(1, (System.currentTimeMillis() - viajeDesde) / 60000);
            resumen = "Llegaste" + (destNombre.isEmpty() || destNombre.equals("el destino") ? "" : " a " + destNombre) + "  ·  " + String.format(Locale.US, "%.1f km", viajeM / 1000).replace('.', ',')
                    + " en " + (min < 60 ? min + " min" : min / 60 + " h " + min % 60 + " min");
        } else resumen = "Llegaste";
        resumenCuando = System.currentTimeMillis();
        destino("");
    }
    void destino(String txt) { destino(txt, null); }
    // nombre: cómo se llama el sitio cuando txt es solo el punto (lat,lon), para mostrarlo
    void destino(String txt, String nombre) {
        final String q = txt == null ? "" : txt.trim();
        getSharedPreferences("cubi", 0).edit().putString("destino", q).apply();
        destLat = Double.NaN; rutaLat = null; rutaLon = null; rutaPedida = 0; mapaForzar = true;
        rutaFaltaM = -1; rutaFaltaMin = -1; rutaLlega = "";
        viajeM = 0; viajeAntes = null; viajeDesde = q.isEmpty() ? 0 : System.currentTimeMillis();
        if (q.isEmpty()) { rutaEstado = ""; destNombre = ""; return; }
        final int turno = ++destTurno;                       // un pedido nuevo deja sin efecto la búsqueda anterior que siga andando
        Matcher punto = Pattern.compile("^(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)$").matcher(q);
        if (punto.matches()) {                               // ya viene el punto exacto (lat,lon): no hay nada que buscar
            destNombre = nombre == null || nombre.trim().isEmpty() || nombre.trim().equals(q) ? "el destino" : nombre.contains(",") ? nombre.substring(0, nombre.indexOf(',')).trim() : nombre.trim();
            destLat = Double.parseDouble(punto.group(1)); destLon = Double.parseDouble(punto.group(2));
            rutaEstado = "Ruta: destino marcado; se traza cuando el GPS diga dónde estás."; return;
        }
        destBuscando = true; destIntento = System.currentTimeMillis();
        rutaEstado = "Ruta: buscando “" + q + "”…";
        new Thread(() -> {
            try {
                java.util.List<String[]> ops = sitios(q);
                if (turno != destTurno) return;
                if (ops.isEmpty()) { rutaEstado = "Ruta: no encontré “" + q + "”. Prueba con la dirección o agrega la ciudad."; return; }
                String[] o = ops.get(0);                              // el más cercano de los que se llaman así
                String[] ll = o[2].split(",");
                destNombre = o[0];
                destLat = Double.parseDouble(ll[0]); destLon = Double.parseDouble(ll[1]);
                rutaEstado = "Ruta: destino “" + destNombre + "”; se traza cuando el GPS diga dónde estás.";
            } catch (Exception e) {
                if (turno == destTurno) rutaEstado = "Ruta: no pude buscar el destino (¿hay internet?).";
            } finally {
                if (turno == destTurno) destBuscando = false;
            }
        }).start();
    }
    private volatile int destTurno;
    // Los sitios que se llaman así, del más cercano al más lejano: {nombre, dónde queda, "lat,lon", "12 km"}.
    // Primero SOLO cerca del carro (unos 65 km a la redonda); si no hay, en el país; y por último en todo el mundo. Y de
    // lo que salga gana el más cercano: el primero de la lista de ese servicio puede quedar a 150 km (pasó con "Campanario").
    // Para escoger en el carro: primero los sitios y negocios de 20 km a la redonda cuyo nombre CONTIENE lo que se buscó
    // ("centro" trae "Campanario Centro Comercial"), del más cercano al más lejano, y después lo de sitios(q).
    // Sale de overpass-api.de (el mismo servicio de las calles). Si falla o tarda, queda solo lo de sitios(q).
    // ponytail: no hay "abierto ahora" ni estrellas; eso es de Google.
    // Con la llave de Google puesta, el buscador del carro es el de Google (Places, "searchText"): entiende direcciones y
    // negocios como Google Maps. Hay que activar "Places API (New)" en el mismo proyecto de la llave; si no está (403) o
    // falla, devuelve null y se sigue con el buscador de Cubi. Google lo cobra aparte de la navegación.
    // Le manda a Google lo que se buscó y la ubicación (para traer primero lo cercano), igual que su mapa.
    private java.util.List<String[]> sitiosGoogle(String q) {
        String k = CubiGoogle.llave(this);
        if (k.isEmpty() || q.trim().isEmpty()) return null;
        try {
            final Location aqui = antesGps;
            JSONObject b = new JSONObject().put("textQuery", q).put("languageCode", "es");
            if (aqui != null) b.put("locationBias", new JSONObject().put("circle", new JSONObject().put("radius", 30000)
                    .put("center", new JSONObject().put("latitude", aqui.getLatitude()).put("longitude", aqui.getLongitude()))));
            HttpURLConnection h = (HttpURLConnection) new URL("https://places.googleapis.com/v1/places:searchText").openConnection();
            h.setConnectTimeout(8000); h.setReadTimeout(10000); h.setRequestMethod("POST"); h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            h.setRequestProperty("X-Goog-Api-Key", k);
            h.setRequestProperty("X-Goog-FieldMask", "places.displayName,places.formattedAddress,places.location");
            h.setRequestProperty("X-Android-Package", getPackageName());   // por si la llave está restringida a esta app
            String hu = huella(); if (!hu.isEmpty()) h.setRequestProperty("X-Android-Cert", hu);
            try (java.io.OutputStream o = h.getOutputStream()) { o.write(b.toString().getBytes("UTF-8")); }
            int cod = h.getResponseCode();
            if (cod != 200) {
                CubiGoogle.busca = "El buscador de Google no respondió (" + cod + ")" + (cod == 403 ? ": activa “Places API (New)” en tu proyecto de Google Cloud y, si la llave tiene restricción de API, agrégala ahí." : ".") + " Mientras tanto busca Cubi.";
                h.disconnect(); return null;
            }
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = h.getInputStream()) { byte[] bf = new byte[8192]; for (int n; (n = in.read(bf)) > 0; ) o.write(bf, 0, n); }
            JSONArray ps = new JSONObject(o.toString("UTF-8")).optJSONArray("places");
            ArrayList<String[]> r = new ArrayList<>();
            for (int i = 0; ps != null && i < ps.length() && r.size() < 10; i++) {
                JSONObject p = ps.getJSONObject(i), n = p.optJSONObject("displayName"), l = p.optJSONObject("location");
                if (l == null) continue;
                double la = l.getDouble("latitude"), lo = l.getDouble("longitude");
                String nom = n == null ? "" : n.optString("text"), dir = p.optString("formattedAddress"), km = "";
                if (nom.isEmpty()) { nom = dir; dir = ""; }
                if (aqui != null) { double d = Math.hypot((la - aqui.getLatitude()) * 111.32, (lo - aqui.getLongitude()) * 111.32 * Math.cos(Math.toRadians(la))); km = d < 10 ? String.format(Locale.US, "%.1f km", d).replace('.', ',') : Math.round(d) + " km"; }
                r.add(new String[]{nom, dir, String.format(Locale.US, "%.6f,%.6f", la, lo), km});
            }
            CubiGoogle.busca = "";
            return r;
        } catch (Throwable e) { return null; }
    }
    // la huella (SHA-1) con la que está firmada la app: Google la pide si la llave está restringida a esta app
    private String huella() {
        try {
            android.content.pm.Signature[] ss = Build.VERSION.SDK_INT >= 28
                    ? getPackageManager().getPackageInfo(getPackageName(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.getApkContentsSigners()
                    : getPackageManager().getPackageInfo(getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES).signatures;
            StringBuilder sb = new StringBuilder();
            for (byte x : java.security.MessageDigest.getInstance("SHA-1").digest(ss[0].toByteArray())) sb.append(String.format("%02X", x));
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }
    java.util.List<String[]> sitios(String q, boolean negocios) throws Exception {
        if (negocios) { java.util.List<String[]> g = sitiosGoogle(q); if (g != null && !g.isEmpty()) return g; }   // con llave: busca Google
        java.util.List<String[]> ops = sitios(q);
        final Location aqui = antesGps;
        String re = q.trim().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").replaceAll("\\s+", " ").trim();
        if (!negocios || aqui == null || q.indexOf(',') >= 0 || re.length() < 3) return ops;
        try {
            re = re.replaceAll("[aá]", "[aá]").replaceAll("[eé]", "[eé]").replaceAll("[ií]", "[ií]").replaceAll("[oó]", "[oó]").replaceAll("[uúü]", "[uúü]");   // con o sin tilde
            String ql = "[out:json][timeout:8];nwr[\"name\"~\"" + re + "\",i][!\"highway\"](around:20000," + String.format(Locale.US, "%.4f,%.4f", aqui.getLatitude(), aqui.getLongitude()) + ");out center tags 40;";
            JSONArray els = new JSONObject(bajarTexto("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(ql, "UTF-8"), 10000, 1)).getJSONArray("elements");
            ArrayList<String[]> cerca = new ArrayList<>(); ArrayList<Double> lejos = new ArrayList<>(); java.util.HashSet<String> ya = new java.util.HashSet<>();
            for (int i = 0; i < els.length(); i++) {
                JSONObject o = els.getJSONObject(i), t = o.optJSONObject("tags"), c = o.optJSONObject("center");
                if (t == null || (c == null && !o.has("lat"))) continue;
                String nom = t.optString("name");
                double la = c != null ? c.getDouble("lat") : o.getDouble("lat"), lo = c != null ? c.getDouble("lon") : o.getDouble("lon");
                double km = Math.hypot((la - aqui.getLatitude()) * 111.32, (lo - aqui.getLongitude()) * 111.32 * Math.cos(Math.toRadians(la)));
                if (nom.isEmpty() || !ya.add(nom.toLowerCase(Locale.ROOT) + Math.round(km * 2))) continue;   // el mismo sitio dibujado dos veces (punto y edificio)
                String donde = t.optString("addr:street"), ciudad = t.optString("addr:city");
                if (!ciudad.isEmpty()) donde += (donde.isEmpty() ? "" : ", ") + ciudad;
                int en = 0;
                while (en < cerca.size() && lejos.get(en) <= km) en++;
                cerca.add(en, new String[]{nom, donde, String.format(Locale.US, "%.6f,%.6f", la, lo), km < 10 ? String.format(Locale.US, "%.1f km", km).replace('.', ',') : Math.round(km) + " km"});
                lejos.add(en, km);
            }
            for (String[] o : ops) { boolean rep = false; for (String[] k : cerca) if (k[0].equalsIgnoreCase(o[0]) && k[3].equals(o[3])) rep = true; if (!rep) cerca.add(o); }
            return cerca;
        } catch (Exception e) { return ops; }
    }
    java.util.List<String[]> sitios(String q) throws Exception {
        String base = "https://nominatim.openstreetmap.org/search?format=json&limit=8&accept-language=es&q=" + URLEncoder.encode(q, "UTF-8");
        ArrayList<String> us = new ArrayList<>();
        final Location aqui = antesGps;
        if (aqui != null) us.add(base + String.format(Locale.US, "&bounded=1&viewbox=%.3f,%.3f,%.3f,%.3f", aqui.getLongitude() - 0.6, aqui.getLatitude() + 0.6,
                aqui.getLongitude() + 0.6, aqui.getLatitude() - 0.6));
        String pais = null;
        try { pais = ((android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE)).getNetworkCountryIso(); } catch (Exception e) { }
        if (pais == null || pais.length() != 2) pais = Locale.getDefault().getCountry();
        if (pais != null && pais.length() == 2) us.add(base + "&countrycodes=" + pais.toLowerCase(Locale.ROOT));
        us.add(base);
        JSONArray r = new JSONArray();
        for (int i = 0; i < us.size() && (i < us.size() - 1 || r.length() == 0); i++) {
            if (i > 0) Thread.sleep(1100);                            // ese servicio pide no más de una consulta por segundo
            JSONArray p = new JSONArray(bajarTexto(us.get(i)));
            for (int j = 0; j < p.length(); j++) r.put(p.getJSONObject(j));
        }
        if ((r == null || r.length() == 0) && q.indexOf(',') > 2) { Thread.sleep(1100); return sitios(q.substring(0, q.indexOf(',')).trim()); }   // la dirección completa no está: solo el nombre del sitio
        ArrayList<String[]> ops = new ArrayList<>(); final ArrayList<Double> lejos = new ArrayList<>(); java.util.HashSet<String> vistos = new java.util.HashSet<>();
        for (int i = 0; r != null && i < r.length(); i++) {
            JSONObject o = r.getJSONObject(i);
            if (!vistos.add(o.optString("osm_type") + o.optLong("osm_id"))) continue;
            String todo = o.optString("display_name", q); int coma = todo.indexOf(',');
            double la = o.getDouble("lat"), lo = o.getDouble("lon");
            double km = aqui == null ? -1 : Math.hypot((la - aqui.getLatitude()) * 111.32, (lo - aqui.getLongitude()) * 111.32 * Math.cos(Math.toRadians(la)));
            double kk = km < 0 ? -1 : km + ("highway".equals(o.optString("class")) ? 1e5 : 0);   // las calles van después de los pueblos y sitios
            int en = 0;
            while (en < ops.size() && (kk < 0 || lejos.get(en) <= kk)) en++;      // se mete en su puesto: del más cercano al más lejano
            String donde = coma < 0 ? "" : todo.substring(coma + 1).trim();
            ops.add(en, new String[]{coma < 0 ? todo : todo.substring(0, coma), donde.length() > 60 ? donde.substring(0, 60) : donde,
                    String.format(Locale.US, "%.6f,%.6f", la, lo), km < 0 ? "" : km < 10 ? String.format(Locale.US, "%.1f km", km).replace('.', ',') : Math.round(km) + " km"});
            lejos.add(en, kk);
        }
        return ops;
    }
    String rutaAviso() { return rutaLat != null ? "" : rutaEstado; }   // para la pantalla del carro: qué pasa mientras no hay ruta pintada

    private static String bajarTexto(String u) throws Exception { return bajarTexto(u, 30000, 2); }

    // espera: cuánto aguanta la respuesta (ms). intentos: cuántas veces insiste si el servidor dice que está ocupado
    // (el de OpenStreetMap lo hace seguido: 429, 502, 503 o 504), esperando un poco más cada vez.
    private static String bajarTexto(String u, int espera, int intentos) throws Exception {
        for (int i = 1; ; i++) {
            HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
            h.setConnectTimeout(8000); h.setReadTimeout(espera);
            h.setRequestProperty("User-Agent", "robot-app");
            int c = h.getResponseCode();
            if ((c == 429 || c == 502 || c == 503 || c == 504) && i < intentos) { h.disconnect(); Thread.sleep(3000L * i); continue; }
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = h.getInputStream()) { byte[] b = new byte[8192]; for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n); }
            return o.toString("UTF-8");
        }
    }

    // ---------- el mapa de la pantalla del carro ----------
    // Va aparte del del robot (que solo entiende 375 m a la redonda): 900 m alrededor de un punto adelantado hacia donde
    // va el carro, porque la respuesta puede tardar más de medio minuto. Las líneas van en metros desde (lat0, lon0).
    static class MapaCarro { double lat0, lon0; float[][] lineas; byte[] clase; String[] nombre;   // nombre: el de la calle, o null
        float[] sitios; String[] sitioNom; byte[] sitioCl; }   // los sitios con nombre (x, y en metros): 6 ciudad o pueblo, 7 barrio, 8 sitio de interés   // clase: 0 calle, 1 avenida, 2 ruta, 3 agua, 4 parque, 5 río
    volatile MapaCarro carroDatos;
    volatile String carroEstado = "Mapa: esperando el GPS.";
    volatile double posLat = Double.NaN, posLon;
    private volatile boolean carroCargando;
    private long carroPedido;

    private static float[] aMetros(double[] la, double[] lo, double lat, double lon, double kx) {
        float[] p = new float[la.length * 2];
        for (int i = 0; i < la.length; i++) { p[2 * i] = (float) ((lo[i] - lon) * kx); p[2 * i + 1] = (float) ((la[i] - lat) * 111320); }
        return p;
    }

    // ---------- el mapa del país guardado en el celular (sin internet) ----------
    // Un solo archivo con las vías de Colombia, que arma herramientas/mapa/mapa_pais.py (ahí está el formato) y se baja
    // una vez desde la pestaña Mapa. Con él, el mapa del carro no pide nada por internet.
    static final String PAIS_URL = "https://github.com/ofco93-blip/cubi/releases/download/mapa-co/colombia2.cubimapa";   // el 2 trae los nombres de las calles; el primero no
    private volatile String paisEstado = "";
    private volatile boolean paisBajando;
    private volatile int[] paisIndice; private volatile long paisDe;
    File mapaPais() { return new File(getFilesDir(), "colombia.cubimapa"); }
    String estadoPais() {
        if (!paisEstado.isEmpty()) return paisEstado;
        File f = mapaPais();
        return f.exists() ? "Descargado (" + f.length() / 1000000 + " MB): el mapa del carro sale sin internet." : "Sin descargar.";
    }
    void borrarPais() { if (!paisBajando) { mapaPais().delete(); paisIndice = null; } }
    void bajarPais() {
        if (paisBajando) return;
        paisBajando = true; paisEstado = "Descargando…";
        new Thread(() -> {
            File tmp = new File(getFilesDir(), "colombia.tmp");
            try {
                HttpURLConnection h = (HttpURLConnection) new URL(PAIS_URL).openConnection();
                h.setConnectTimeout(15000); h.setReadTimeout(30000);
                long total = h.getContentLengthLong(), van = 0;
                try (InputStream in = h.getInputStream(); FileOutputStream o = new FileOutputStream(tmp)) {
                    byte[] b = new byte[65536];
                    for (int n; (n = in.read(b)) > 0; ) { o.write(b, 0, n); van += n; paisEstado = "Descargando: " + (total > 0 ? van * 100 / total + " %" : van / 1000000 + " MB"); }
                }
                if ((total > 0 && van != total) || !tmp.renameTo(mapaPais())) throw new java.io.IOException();   // a medias no sirve
                paisIndice = null; paisEstado = "";
            } catch (Exception e) { tmp.delete(); paisEstado = "No se pudo descargar (¿hay internet?). Toca otra vez para reintentar."; }
            finally { paisBajando = false; }
        }).start();
    }
    private static int varint(byte[] b, int[] p) { int v = 0; for (int s = 0; ; s += 7) { int x = b[p[0]++]; v |= (x & 127) << s; if (x >= 0) return v; } }
    // las vías a menos de 1300 m de (clat, clon), en metros desde (lat, lon); null si no hay archivo o no cubre esa zona
    private MapaCarro callesGuardadas(double clat, double clon, double lat, double lon) throws Exception {
        File f = mapaPais();
        if (!f.exists()) return null;
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            int[] ix = paisIndice;
            if (ix == null || paisDe != f.lastModified()) {           // el índice se lee una vez
                byte[] c = new byte[12]; r.readFully(c);
                if (!new String(c, 0, 8, "US-ASCII").equals("CUBIMAP1")) return null;
                byte[] b = new byte[java.nio.ByteBuffer.wrap(c, 8, 4).getInt() * 12]; r.readFully(b);
                ix = new int[b.length / 4]; java.nio.ByteBuffer.wrap(b).asIntBuffer().get(ix);
                paisIndice = ix; paisDe = f.lastModified();
            }
            double kx = 111320 * Math.cos(Math.toRadians(lat));
            float cx = (float) ((clon - lon) * kx), cy = (float) ((clat - lat) * 111320);
            ArrayList<float[]> ls = new ArrayList<>(); ArrayList<Byte> cs = new ArrayList<>(); ArrayList<String> ns = new ArrayList<>();
            ArrayList<Float> sx = new ArrayList<>(); ArrayList<String> sn = new ArrayList<>(); ArrayList<Byte> sc = new ArrayList<>();
            int fy = (int) Math.floor((clat + 90) / 0.02), fx = (int) Math.floor((clon + 180) / 0.02);
            byte[] t = new byte[8192]; int[] p = new int[1];
            for (int y = fy - 1; y <= fy + 1; y++) for (int x = fx - 1; x <= fx + 1; x++) {
                int k = y << 16 | x, a = 0, z = ix.length / 3 - 1, e = -1;
                while (a <= z && e < 0) { int m = (a + z) >>> 1; if (ix[3 * m] < k) a = m + 1; else if (ix[3 * m] > k) z = m - 1; else e = m; }
                if (e < 0) continue;
                byte[] zb = new byte[ix[3 * e + 2]]; r.seek(ix[3 * e + 1]); r.readFully(zb);
                java.util.zip.Inflater inf = new java.util.zip.Inflater(); inf.setInput(zb);
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                while (!inf.finished()) { int q = inf.inflate(t); if (q == 0) break; o.write(t, 0, q); }
                inf.end();
                byte[] b = o.toByteArray();
                for (p[0] = 0; p[0] < b.length; ) {
                    byte cl = b[p[0]++]; String nom = null;
                    if (cl < 0) { int ln = varint(b, p); nom = new String(b, p[0], ln, "UTF-8"); p[0] += ln; cl &= 127; }   // +128: trae nombre
                    int np = varint(b, p); boolean cerca = false;
                    long pa = y * 20000L - 90000000L, po = x * 20000L - 180000000L;
                    float[] v = new float[np * 2];
                    for (int i = 0; i < np; i++) {
                        int u = varint(b, p); pa += (u >>> 1) ^ -(u & 1);
                        u = varint(b, p); po += (u >>> 1) ^ -(u & 1);
                        v[2 * i] = (float) ((po / 1e6 - lon) * kx); v[2 * i + 1] = (float) ((pa / 1e6 - lat) * 111320);
                        cerca |= Math.abs(v[2 * i] - cx) < 1300 && Math.abs(v[2 * i + 1] - cy) < 1300;
                    }
                    if (cerca && np >= 2 && cl < 6) { ls.add(v); cs.add(cl); ns.add(nom); }
                    else if (cerca && np == 1 && cl >= 6 && nom != null) { sx.add(v[0]); sx.add(v[1]); sn.add(nom); sc.add(cl); }   // un sitio
                }
            }
            if (ls.isEmpty()) return null;
            MapaCarro d = new MapaCarro();
            d.lat0 = lat; d.lon0 = lon; d.lineas = ls.toArray(new float[0][]); d.clase = new byte[cs.size()]; d.nombre = ns.toArray(new String[0]);
            for (int i = 0; i < d.clase.length; i++) d.clase[i] = cs.get(i);
            d.sitios = new float[sx.size()]; d.sitioNom = sn.toArray(new String[0]); d.sitioCl = new byte[sc.size()];
            for (int i = 0; i < d.sitios.length; i++) d.sitios[i] = sx.get(i);
            for (int i = 0; i < d.sitioCl.length; i++) d.sitioCl[i] = sc.get(i);
            return d;
        }
    }

    private void bajarCarro(double clat, double clon, double lat, double lon) {
        try {
            try {                                                    // primero el mapa guardado: sale al instante y sin internet
                MapaCarro g = callesGuardadas(clat, clon, lat, lon);
                if (g != null) { carroDatos = g; carroEstado = ""; return; }
            } catch (Exception e) { }                                // archivo dañado: por internet, como antes
            // El centro se ajusta a una cuadrícula de unos 440 m (por eso el radio es más grande): así la misma zona pide
            // siempre lo mismo y lo bajado se guarda en el celular (comprimido, 90 días). Por las rutas de siempre sale al instante.
            long ia = Math.round(clat / 0.004), io = Math.round(clon / 0.004);
            clat = ia * 0.004; clon = io * 0.004;
            java.io.File guardado = new java.io.File(new java.io.File(getFilesDir(), "calles"), ia + "_" + io + ".gz");
            JSONArray els = null;
            if (guardado.exists() && System.currentTimeMillis() - guardado.lastModified() < 90L * 86400000) try {
                java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream in = new java.util.zip.GZIPInputStream(new java.io.FileInputStream(guardado))) { byte[] b = new byte[8192]; for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n); }
                els = new JSONObject(o.toString("UTF-8")).getJSONArray("elements");
            } catch (Exception e) { els = null; }
            if (els == null && carroDatos == null) carroEstado = "Mapa: bajando las calles…";
            String donde = "(around:1150," + String.format(Locale.US, "%.5f,%.5f", clat, clon) + ");";
            String q = "[out:json][timeout:25];(way[highway~\"^(motorway|trunk|primary|secondary|tertiary|residential|unclassified|living_street)(_link)?$\"]" + donde
                    + "way[natural=water]" + donde + "way[waterway~\"^(river|stream|canal)$\"]" + donde + "way[leisure~\"^(park|garden)$\"]" + donde + ");out geom;";
            // El servidor principal se satura seguido: se turna con uno de respaldo, primero con poca paciencia (12 s) y luego con más.
            String[] servidor = {"https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"};
            Exception falla = null;
            for (int i = 0; i < 4 && els == null; i++) try {
                String txt = bajarTexto(servidor[i % 2] + "?data=" + URLEncoder.encode(q, "UTF-8"), i < 2 ? 12000 : 60000, 1);
                JSONObject r = new JSONObject(txt);
                if (r.has("remark")) throw new java.io.IOException(r.optString("remark"));   // respondió a medias (se le acabó el tiempo)
                els = r.getJSONArray("elements");
                try {
                    guardado.getParentFile().mkdirs();
                    java.io.File[] viejos = guardado.getParentFile().listFiles();
                    if (viejos != null) for (java.io.File v : viejos) if (System.currentTimeMillis() - v.lastModified() > 90L * 86400000) v.delete();
                    try (java.io.OutputStream o = new java.util.zip.GZIPOutputStream(new java.io.FileOutputStream(guardado))) { o.write(txt.getBytes("UTF-8")); }
                } catch (Exception e) { guardado.delete(); }   // sin guardar igual sirve
            } catch (Exception e) { els = null; falla = e; }
            if (els == null) throw falla;
            double kx = 111320 * Math.cos(Math.toRadians(lat));
            java.util.ArrayList<float[]> ls = new java.util.ArrayList<>();
            java.util.ArrayList<Byte> cs = new java.util.ArrayList<>();
            java.util.ArrayList<String> ns = new java.util.ArrayList<>();
            for (double[][] a : aguaGrande) { ls.add(aMetros(a[0], a[1], lat, lon, kx)); cs.add((byte) 3); ns.add(null); }
            for (int e = 0; e < els.length(); e++) {
                JSONObject w = els.getJSONObject(e);
                JSONArray g = w.optJSONArray("geometry");
                if (g == null || g.length() < 2) continue;
                JSONObject tags = w.optJSONObject("tags") == null ? new JSONObject() : w.getJSONObject("tags");
                String tipo = tags.optString("highway");
                boolean grande = tipo.startsWith("motorway") || tipo.startsWith("trunk") || tipo.startsWith("primary") || tipo.startsWith("secondary");
                double[] la = new double[g.length()], lo = new double[g.length()];
                for (int i = 0; i < g.length(); i++) { la[i] = g.getJSONObject(i).getDouble("lat"); lo[i] = g.getJSONObject(i).getDouble("lon"); }
                ls.add(aMetros(la, lo, lat, lon, kx));
                cs.add((byte) (!tipo.isEmpty() ? (grande ? 1 : 0) : tags.has("waterway") ? 5 : tags.has("natural") ? 3 : 4));
                ns.add(tipo.isEmpty() ? null : tags.has("name") ? tags.optString("name") : tags.has("ref") ? tags.optString("ref") : null);
            }
            if (ls.isEmpty()) { carroEstado = "Mapa: no hay calles en el mapa por aquí."; return; }
            MapaCarro d = new MapaCarro();
            d.lat0 = lat; d.lon0 = lon; d.lineas = ls.toArray(new float[0][]); d.clase = new byte[cs.size()]; d.nombre = ns.toArray(new String[0]);
            for (int i = 0; i < d.clase.length; i++) d.clase[i] = cs.get(i);
            carroDatos = d; carroEstado = "";
        } catch (Exception e) {
            carroEstado = carroDatos == null ? "Mapa: el servidor de calles está ocupado; sigo intentando…" : "";
        } finally {
            carroCargando = false;
        }
    }

    private void calcularRuta(double lat, double lon) {
        try {
            // Se lee también la respuesta de error: el servicio dice por qué no hay ruta (NoRoute, NoSegment, muy seguido…)
            HttpURLConnection h = (HttpURLConnection) new URL(String.format(Locale.US,
                    "https://router.project-osrm.org/route/v1/driving/%.5f,%.5f;%.5f,%.5f?overview=full&geometries=geojson", lon, lat, destLon, destLat)).openConnection();
            h.setConnectTimeout(8000); h.setReadTimeout(30000); h.setRequestProperty("User-Agent", "robot-app");
            int cod = h.getResponseCode();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = cod < 400 ? h.getInputStream() : h.getErrorStream()) { byte[] b = new byte[8192]; for (int n; in != null && (n = in.read(b)) > 0; ) bo.write(b, 0, n); }
            String txt = bo.toString("UTF-8");
            JSONObject r = txt.startsWith("{") ? new JSONObject(txt) : new JSONObject();
            if (cod >= 400 || !"Ok".equals(r.optString("code"))) {
                String por = r.optString("code", "");
                boolean sinCamino = por.equals("NoRoute") || por.equals("NoSegment");
                rutaEstado = sinCamino ? "No hay camino por carretera hasta “" + destNombre + "”. Prueba con el nombre completo o la ciudad."
                        : "Ruta a “" + destNombre + "”: el servicio de rutas respondió " + cod + (por.isEmpty() ? "" : " (" + por + ")") + "; reintento.";
                if (sinCamino) rutaPedida = System.currentTimeMillis() + 100000;   // insistir no lo arregla: otro intento en 2 min
                return;
            }
            JSONObject ruta = r.getJSONArray("routes").getJSONObject(0);
            JSONArray c = ruta.getJSONObject("geometry").getJSONArray("coordinates");
            double[] la = new double[c.length()], lo = new double[c.length()];
            for (int i = 0; i < c.length(); i++) { lo[i] = c.getJSONArray(i).getDouble(0); la[i] = c.getJSONArray(i).getDouble(1); }
            float[] ac = new float[la.length];
            for (int i = 1; i < la.length; i++) ac[i] = ac[i - 1] + (float) Math.hypot((lo[i] - lo[i - 1]) * 111320 * Math.cos(Math.toRadians(la[i])), (la[i] - la[i - 1]) * 111320);
            rutaAcum = ac; rutaDura = ruta.optDouble("duration", 0);
            rutaLat = la; rutaLon = lo; mapaForzar = true;
            rutaEstado = String.format(Locale.US, "Ruta: %.1f km hasta “%s”.", ruta.optDouble("distance", 0) / 1000, destNombre);
        } catch (Exception e) {
            rutaEstado = "Ruta a “" + destNombre + "”: sin respuesta del servicio de rutas (" + e.getClass().getSimpleName() + "); reintento.";
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
        if (!(navMapa() || carroMapa() || CubiMapi.listo()) || !navActiva()) return;
        // el destino guardado se vuelve a buscar al arrancar (una vez por minuto si falla)
        if (Double.isNaN(destLat) && !destinoTexto().isEmpty() && !destBuscando && ahora - destIntento > 60000) destino(destinoTexto());
        boolean hayDestino = !Double.isNaN(destLat);
        if (ahora - navCuando > 120000 && !hayDestino && !carroMapa() && !CubiMapi.listo()) return;   // sin ruta ni destino solo hay mapa para la pantalla del carro
        if (l.hasBearing() && l.hasSpeed() && l.getSpeed() > 1.5f) mapaRumbo = l.getBearing();   // parado, el rumbo del GPS no sirve
        final double lat = l.getLatitude(), lon = l.getLongitude();
        posLat = lat; posLon = lon;
        if (carroMapa()) {                                   // el mapa del carro: se vuelve a pedir al alejarse 400 m de donde se pidió
            MapaCarro d = carroDatos;
            double ido = d == null ? 1e9 : Math.hypot((lat - d.lat0) * 111320, (lon - d.lon0) * 111320 * Math.cos(Math.toRadians(lat)));
            if (ido > 400 && !carroCargando && ahora - carroPedido > 10000) {
                carroPedido = ahora; carroCargando = true;
                double ade = Math.min(500, (l.hasSpeed() ? l.getSpeed() : 0) * 35), ra = Math.toRadians(mapaRumbo);   // adelantado: donde estará en ~35 s
                final double clat = lat + Math.cos(ra) * ade / 111320, clon = lon + Math.sin(ra) * ade / (111320 * Math.cos(Math.toRadians(lat)));
                new Thread(() -> bajarCarro(clat, clon, lat, lon)).start();
            }
        }
        // la ruta: la primera vez, y otra vez si el carro se sale más de 70 m de la línea (máximo cada 20 s)
        if (hayDestino && !rutaCalculando && ahora - rutaPedida > 20000 && (rutaLat == null || fueraDeRuta(lat, lon) > 70)) {
            rutaPedida = ahora; rutaCalculando = true;
            new Thread(() -> calcularRuta(lat, lon)).start();
        }
        faltaRuta(lat, lon);
        double dy = (lat - mapaLat0) * 111320, dx = (lon - mapaLon0) * 111320 * Math.cos(Math.toRadians(mapaLat0));
        double lejos = Math.hypot(dx, dy);
        mapaDx = dx; mapaDy = dy; mapaCuando = ahora; mapaVel = l.hasSpeed() ? l.getSpeed() : 0;
        if (viajeDesde > 0) {                                // lo andado del viaje (quieto no suma: el GPS brinca)
            if (viajeAntes != null && l.hasSpeed() && l.getSpeed() > 1) viajeM += l.distanceTo(viajeAntes);
            viajeAntes = l;
        }
        if ((navMapa() || CubiMapi.listo()) && (!mapaListo || lejos > 130 || mapaForzar) && !mapaCargando && (ahora - mapaPedido > 8000 || mapaForzar)) {
            mapaPedido = ahora; mapaCargando = true; mapaForzar = false;
            new Thread(() -> bajarCalles(lat, lon)).start();
        }
        // la posición solo se manda con una ruta andando en Maps o Waze: mientras llegue, Cubi deja el mapa fijo
        if (navMapa() && mapaListo && lejos < 375 && ahora - navCuando < 120000)
            enviar(String.format(Locale.US, "asis|/mapa?x=%d&y=%d&r=%d&v=%d&k=%d&d=%d", Math.round(dx), Math.round(dy), Math.round(mapaRumbo), navCompleto() ? 1 : 0,
                    kmh < 0 ? -1 : Math.round(kmh), esDeDia() ? 1 : 0));
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

    // Embalses, lagunas y ríos anchos: en OpenStreetMap son figuras grandes hechas de muchos pedazos. Se bajan aparte y
    // solo al alejarse 800 m de la última vez (1,5 km a la redonda), porque pesan; aquí se guardan ya unidas en anillos.
    private volatile java.util.List<double[][]> aguaGrande = new java.util.ArrayList<>();
    private double aguaLat = Double.NaN, aguaLon;

    private void bajarAguaGrande(double lat, double lon) throws Exception {
        String q = "[out:json][timeout:20];relation[natural=water](around:1500," + String.format(Locale.US, "%.5f,%.5f", lat, lon) + ");out geom;";
        JSONArray els = new JSONObject(bajarTexto("https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(q, "UTF-8"))).getJSONArray("elements");
        java.util.ArrayList<double[][]> sal = new java.util.ArrayList<>();
        for (int e = 0; e < els.length(); e++) {
            JSONArray ms = els.getJSONObject(e).optJSONArray("members");
            java.util.ArrayList<java.util.ArrayList<double[]>> tr = new java.util.ArrayList<>();
            for (int i = 0; ms != null && i < ms.length(); i++) {
                JSONObject m = ms.getJSONObject(i);
                JSONArray g = m.optJSONArray("geometry");
                // ponytail: las islas (inner) no se recortan del agua; restarlas si algún día estorban
                if (g == null || g.length() < 2 || !"outer".equals(m.optString("role"))) continue;
                java.util.ArrayList<double[]> t = new java.util.ArrayList<>();
                for (int k = 0; k < g.length(); k++) t.add(new double[]{g.getJSONObject(k).getDouble("lat"), g.getJSONObject(k).getDouble("lon")});
                tr.add(t);
            }
            while (!tr.isEmpty()) {                       // se pegan por las puntas hasta cerrar cada anillo
                java.util.ArrayList<double[]> a = tr.remove(tr.size() - 1);
                boolean pego = true;
                while (pego && !igual(a.get(0), a.get(a.size() - 1))) {
                    pego = false;
                    for (int i = 0; i < tr.size() && !pego; i++) {
                        java.util.ArrayList<double[]> t = tr.get(i);
                        double[] fin = a.get(a.size() - 1);
                        if (igual(t.get(0), fin)) a.addAll(t.subList(1, t.size()));
                        else if (igual(t.get(t.size() - 1), fin)) for (int k = t.size() - 2; k >= 0; k--) a.add(t.get(k));
                        else continue;
                        tr.remove(i); pego = true;
                    }
                }
                if (a.size() < 4) continue;
                double[] la = new double[a.size()], lo = new double[a.size()];
                for (int i = 0; i < la.length; i++) { la[i] = a.get(i)[0]; lo[i] = a.get(i)[1]; }
                sal.add(new double[][]{la, lo});
            }
        }
        aguaGrande = sal; aguaLat = lat; aguaLon = lon;
    }
    private static boolean igual(double[] a, double[] b) { return a[0] == b[0] && a[1] == b[1]; }

    // una figura cerrada (agua, parque): se recorta al cuadro de 375 m y se escribe con máximo 48 puntos (cabe en un mensaje)
    static void poligono(double[] la, double[] lo, double lat, double lon, String clase, java.util.ArrayList<String> sale) {
        double kx = 111320 * Math.cos(Math.toRadians(lat));
        int n = la.length;
        double[] x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) { x[i] = (lo[i] - lon) * kx; y[i] = (la[i] - lat) * 111320; }
        for (int lado = 0; lado < 4; lado++) {
            double[] nx = new double[2 * n + 2], ny = new double[2 * n + 2];
            int m = 0;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                double da = lado == 0 ? x[i] + 375 : lado == 1 ? 375 - x[i] : lado == 2 ? y[i] + 375 : 375 - y[i];
                double db = lado == 0 ? x[j] + 375 : lado == 1 ? 375 - x[j] : lado == 2 ? y[j] + 375 : 375 - y[j];
                if (da >= 0) { nx[m] = x[i]; ny[m++] = y[i]; }
                if ((da >= 0) != (db >= 0)) { double f = da / (da - db); nx[m] = x[i] + (x[j] - x[i]) * f; ny[m++] = y[i] + (y[j] - y[i]) * f; }
            }
            x = nx; y = ny; n = m;
            if (n < 3) return;
        }
        int paso = (n + 47) / 48, c = 0, ax = -1, ay = -1;
        StringBuilder sb = new StringBuilder(clase);
        for (int i = 0; i < n; i += paso) {
            int qx = 128 + (int) Math.round(x[i] / 3), qy = 128 + (int) Math.round(y[i] / 3);
            if (qx == ax && qy == ay) continue;
            sb.append(String.format("%02x%02x", qx, qy)); c++; ax = qx; ay = qy;
        }
        if (c >= 3) sale.add(sb.toString());
    }

    private void bajarCalles(double lat, double lon) {
        try {
            if (Double.isNaN(aguaLat) || Math.hypot((lat - aguaLat) * 111320, (lon - aguaLon) * 111320 * Math.cos(Math.toRadians(lat))) > 800)
                try { bajarAguaGrande(lat, lon); } catch (Exception e) { aguaLat = lat; aguaLon = lon; }   // sin el agua grande igual salen las calles; se reintenta 800 m más adelante
            String donde = "(around:360," + String.format(Locale.US, "%.5f,%.5f", lat, lon) + ");";
            String q = "[out:json][timeout:10];(way[highway~\"^(motorway|trunk|primary|secondary|tertiary|residential|unclassified|living_street)(_link)?$\"]" + donde
                    + "way[natural=water]" + donde + "way[waterway~\"^(river|stream|canal)$\"]" + donde + "way[leisure~\"^(park|garden)$\"]" + donde + ");out geom;";
            JSONArray els = null; Exception falla = null;   // el servidor principal se cae seguido: si no responde, el de respaldo
            for (String sv : new String[]{"https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter"}) try {
                JSONObject r = new JSONObject(bajarTexto(sv + "?data=" + URLEncoder.encode(q, "UTF-8"), 12000, 1));
                if (r.has("remark")) throw new java.io.IOException(r.optString("remark"));
                els = r.getJSONArray("elements"); break;
            } catch (Exception e) { falla = e; }
            if (els == null) throw falla;
            java.util.ArrayList<String> ruta = new java.util.ArrayList<>(), avenidas = new java.util.ArrayList<>(), calles = new java.util.ArrayList<>(), zonas = new java.util.ArrayList<>();
            double[] rla = rutaLat, rlo = rutaLon;
            if (rla != null && rlo != null) tramos(rla, rlo, lat, lon, "2", ruta);
            for (double[][] a : aguaGrande) poligono(a[0], a[1], lat, lon, "3", zonas);
            for (int e = 0; e < els.length(); e++) {
                JSONObject w = els.getJSONObject(e);
                JSONArray g = w.optJSONArray("geometry");
                if (g == null) continue;
                JSONObject tags = w.optJSONObject("tags") == null ? new JSONObject() : w.getJSONObject("tags");
                String tipo = tags.optString("highway");
                boolean grande = tipo.startsWith("motorway") || tipo.startsWith("trunk") || tipo.startsWith("primary") || tipo.startsWith("secondary");
                double[] la = new double[g.length()], lo = new double[g.length()];
                for (int i = 0; i < g.length(); i++) { la[i] = g.getJSONObject(i).getDouble("lat"); lo[i] = g.getJSONObject(i).getDouble("lon"); }
                if (!tipo.isEmpty()) tramos(la, lo, lat, lon, grande ? "1" : "0", grande ? avenidas : calles);
                else if (tags.has("waterway")) tramos(la, lo, lat, lon, "5", zonas);
                else if (tags.has("natural")) poligono(la, lo, lat, lon, "3", zonas);
                else if (tags.has("leisure")) poligono(la, lo, lat, lon, "4", zonas);
            }
            // Cubi guarda hasta 260 tramos y 2000 puntos. Las vías van como siempre (158 tramos, 890 puntos: primero la ruta,
            // luego las avenidas); el agua y los parques van aparte (90 tramos, 1000 puntos) y el Cubi viejo los ignora.
            java.util.ArrayList<String> todas = new java.util.ArrayList<>(), extra = new java.util.ArrayList<>();
            int puntos = 0;
            for (java.util.ArrayList<String> grupo : java.util.Arrays.asList(ruta, avenidas, calles)) for (String v : grupo) {
                int np = (v.length() - 1) / 4;
                if (todas.size() >= 158 || puntos + np > 890) continue;
                todas.add(v); puntos += np;
            }
            puntos = 0;
            for (String v : zonas) {
                int np = (v.length() - 1) / 4;
                if (extra.size() >= 90 || puntos + np > 1000) continue;
                extra.add(v); puntos += np;
            }
            if (todas.isEmpty()) { mapaEstado = "Mini mapa: no hay calles en el mapa por aquí."; return; }
            java.util.ArrayList<String> mensajes = new java.util.ArrayList<>();
            int deVias = 0;
            for (java.util.ArrayList<String> lista : java.util.Arrays.asList(todas, extra)) {
                StringBuilder parte = new StringBuilder();
                for (String v : lista) {
                    // un tramo muy largo se parte para que quepa en un mensaje (repite el punto del corte)
                    while (v.length() > 197) { mensajes.add((parte.length() > 0 ? parte + "." : "") + v.substring(0, 197)); parte = new StringBuilder(); v = v.charAt(0) + v.substring(193); }
                    if (parte.length() > 0 && parte.length() + 1 + v.length() > 200) { mensajes.add(parte.toString()); parte = new StringBuilder(); }
                    if (parte.length() > 0) parte.append('.');
                    parte.append(v);
                }
                if (parte.length() > 0) mensajes.add(parte.toString());
                if (lista == todas) deVias = mensajes.size();
            }
            boolean alRobot = navMapa();                  // el robot solo recibe el mapa si está en ese modo; la pantalla del carro lo usa siempre
            for (int i = 0; alRobot && i < mensajes.size(); i++) {   // de a poquito: Cubi atiende una orden por cuadro
                enviar("asis|/mapa?" + (i < deVias ? "w=" : "g=") + mensajes.get(i) + (i == 0 ? "&n=1" : ""));
                Thread.sleep(160);
            }
            // el cierre va en un mensaje de vías (repite un tramo corto) para que también lo entienda el Cubi viejo
            String cierre = todas.get(0).length() > 197 ? todas.get(0).substring(0, 197) : todas.get(0);
            mapaLat0 = lat; mapaLon0 = lon; mapaListo = true; mapaDx = mapaDy = 0; mapaZonas = extra; mapaTramos = todas;
            CubiMapi.mapa(mensajes, deVias, cierre);         // las mismas calles para Mapi
            if (alRobot) enviar("asis|/mapa?w=" + cierre + "&f=1");
            mapaEstado = "Mini mapa: " + todas.size() + " tramos de calle" + (ruta.isEmpty() ? "" : ", con la ruta") + (extra.isEmpty() ? "" : ", " + extra.size() + " de agua y parques") + ".";
        } catch (Exception e) {
            mapaEstado = "Mini mapa: no pude bajar las calles (¿hay internet?).";
        } finally {
            mapaCargando = false;
        }
    }

    private void posicion(Location l) {
        long ahora = System.currentTimeMillis();
        navRefrescar();
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
        if (e.equals("nombre")) { nombreT = System.currentTimeMillis(); despertoPor = hora() + " el robot oyó “Hi Lili”"; }
        if (e.equals("asistente") && System.currentTimeMillis() - nombreT > 2000) despertoPor = hora() + " doble toque en la pantalla del robot";
        if (e.equals("asistente")) { if (claveClaude().isEmpty()) abrirAsistente(); else charla(); }
        if (e.equals("cancelar")) {   // lo tocaron mientras escuchaba o hablaba: se corta todo
            try { if (oreja != null) oreja.cancel(); } catch (Throwable ignorado) {}
            ui.removeCallbacks(orejaTarda);
            if (voz != null) voz.stop();
            asisDesde = 0; asisEstado = "";
            terminarCharla();
        }
    }

    // ---------- Cubi conversa: "Cubi" + lo que digas -> Claude Haiku -> lo dice en voz alta y hace un truco ----------
    static final String MODELO_CLAUDE = "claude-haiku-5-5";
    static final List<String> REACCIONES = Arrays.asList("kamehameha", "confeti", "risa", "sorpresa", "amor", "saludo", "guino",
            "curiosa", "mareo", "llegamos", "ppt_piedra", "ppt_papel", "ppt_tijera",
            "cinturon", "sonrojo", "enojo", "papas", "bebida", "consola", "leemapa", "cono", "crispetas", "feliz", "triste", "sueno", "calor", "frio");
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
            + "- truco: una animación en tu pantalla, exactamente una de: ninguno, kamehameha, confeti, piedra_papel_tijera, risa, sorpresa, amor, "
            + "saludo, guino, curiosa, mareo, llegamos, feliz, triste, enojo, sonrojo, sueno, calor, frio, cinturon, papas, crispetas, cono, bebida, consola, leemapa, baile_rock, baile_pop, baile_tropical, baile_urbano, baile_electro, "
            + "baile_jazz, baile_dj, baile_guitarra, baile_kpop, baile_piano, baile_manos, baile_acordeon, baile_vela.\n"
            + "  kamehameha: cargas una bola de energía y la lanzas hacia adelante. Solo di algo cortico antes, como "
            + "'¡Mira esto!'; el canto 'Ka-me-ha-me-ha' lo hago yo justo después.\n"
            + "  confeti: cuando te pidan 'mata a', 'dispárale a' o 'acaba con' alguien. En este carro siempre es una broma entre "
            + "ellos: miras al copiloto, le apuntas con una pistola láser de juguete y le disparas confeti. Solo di algo teatral y tierno como "
            + "'¡Prepárate, esposo!' o '¿Unas últimas palabras, esposo?', con la misma palabra que usaron para nombrar a la "
            + "persona. No cuentes ni digas '¡Pum!': la cuenta 3, 2, 1 y el disparo los hago yo justo después. "
            + "Nunca hables de hacer daño de verdad.\n"
            + "  piedra_papel_tijera: cuando quieran jugar. Di solo 'Piedra, papel o tijera'; tu jugada sale al azar en la pantalla y yo "
            + "agrego en voz alta qué sacaste. Pon sigue en true: la persona te dice qué sacó y tú dices quién ganó (tu jugada "
            + "viene entre corchetes en el siguiente mensaje).\n"
            + "  Las emociones (feliz, triste, enojo, sonrojo, sueno, calor, frio) úsalas cuando vengan al caso con lo que dices. "
            + "cinturon: te pones el cinturón (cuando arrancan o te lo piden). papas, crispetas, cono, bebida: comes o tomas eso "
            + "(si hablan de comida o te lo piden). consola: juegas videojuegos. leemapa: miras un mapa de papel.\n"
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
        try { escuchar0(); }
        catch (Throwable e) { js("toast(" + JSONObject.quote("Cubi no pudo escuchar: " + e) + ")"); charlaHasta = 0; try { revisarAsistente(); } catch (Throwable ignorado) {} }
    }

    private void escuchar0() {
        long ahora = System.currentTimeMillis();
        asisDesde = ahora;
        charlaHasta = ahora + 15000;
        pararOido();  // el micrófono es para escuchar la pregunta
        enviar("asis|/asistente?estado=escucha");
        bajarMusica(true);
        if (voz != null) voz.stop();
        if (!hayInternet() && modelo != null) { escucharSinInternet(); revisarAsistente(); return; }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { oidoPropio("Este celular no tiene el reconocedor de voz de Google"); revisarAsistente(); return; }
        if (oreja == null) {
            oreja = SpeechRecognizer.createSpeechRecognizer(this);
            oreja.setRecognitionListener(oye);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-CO");
        orejaLista = false;
        ui.removeCallbacks(orejaTarda);
        ui.postDelayed(() -> {   // que "Cubi" (Vosk) suelte el micrófono primero
            try { oreja.startListening(i); } catch (Throwable e) { ui.removeCallbacks(orejaTarda); oidoPropio("El oído de Google no arrancó"); }
        }, 300);
        ui.postDelayed(orejaTarda, 4300);   // si en 4 s Google ni siquiera abre el micrófono, no se queda esperando
        revisarAsistente();
    }

    // El reconocedor de Google a veces no arranca o no contesta (sin permiso de micrófono en la app de Google, servicio
    // apagado...). Antes Cubi se quedaba con cara de escuchar sin hacer nada: ahora escucha con su propio oído (Vosk).
    private boolean orejaLista;
    private final Runnable orejaTarda = () -> {
        if (orejaLista) return;
        try { if (oreja != null) oreja.cancel(); } catch (Throwable ignorado) {}
        oidoPropio("El oído de Google no respondió");
    };

    private void oidoPropio(String porque) {
        if (modelo != null) { js("toast(" + JSONObject.quote(porque + ": te escucho con mi propio oído") + ")"); escucharSinInternet(); }
        else { js("toast(" + JSONObject.quote(porque + ". Prende “Hola Cubi” una vez para bajar mi propio oído.") + ")"); terminarCharla(); }
    }

    // el tablero de Maps va claro de 6 a 18 h
    static boolean esDeDia() { int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY); return h >= 6 && h < 18; }

    boolean hayInternet() {
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        try {
            android.net.NetworkCapabilities c = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return c != null && c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (SecurityException e) { return true; }   // sin permiso para mirar la red: se intenta con internet
    }

    // sin internet el reconocedor de Google no sirve: oye con Vosk (el mismo modelo de "Hola Cubi", en el celular)
    private volatile boolean oyendoSolo;
    private void escucharSinInternet() {
        if (oyendoSolo) return;   // el aviso de "tardó" y el de "falló" pueden llegar los dos
        oyendoSolo = true;
        new Thread(() -> {
            String texto = "";
            AudioRecord r = null;
            try { Thread h = hiloOido; if (h != null) h.join(1500); } catch (Throwable ignorado) {}   // que "Hola Cubi" suelte el micrófono primero
            SystemClock.sleep(200);
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
            } catch (Throwable ignored) {
            } finally {
                if (r != null) r.release();
            }
            oyendoSolo = false;
            String t = texto;
            ui.post(() -> {
                try { if (t.isEmpty()) terminarCharla(); else preguntar(t); }
                catch (Throwable e) { js("toast(" + JSONObject.quote("Cubi: " + e) + ")"); charlaHasta = 0; }
            });
        }).start();
    }

    static final String[] CHISTES = {
            "¿Qué le dice un semáforo a otro? No me mires, que me estoy cambiando.",
            "¿Por qué el libro de matemáticas está triste? Porque tiene muchos problemas.",
            "¿Qué hace una abeja en el gimnasio? Zum-ba.",
            "Había un carro tan viejo, tan viejo, que en vez de airbag tenía una almohada.",
            "¿Cuál es el colmo de un robot? Tener un corazón de lata y que se lo oxiden."};

    static String sinTildes(String t) { return java.text.Normalizer.normalize(t.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", ""); }

    // Órdenes que Cubi entiende de una, con internet o sin él, sin preguntarle a Claude (más rápido y no falla):
    // "dispárale a mi esposo" -> "¡Prepárate, esposo!" y dispara · "dispara" -> "¡Prepárate!" y dispara ·
    // "ataca" / "lanza" / "kamehameha" -> el kamehameha · "piedra, papel o tijera" -> juega
    static final Pattern DISPARA = Pattern.compile("\\b(disparale|dispara|disparen|dispare|mata|matalo|matala|acaba con|fusila)\\b(?:\\s+a)?(?:\\s+(?:mi|mis|la|el|los|las|ese|esa|este|esta))?(?:\\s+(\\w+))?");
    static final Pattern ATRAS = Pattern.compile("\\b(al|a la|a el|a los|a las)\\s+(?:(\\w+)\\s+)?de atras\\b");
    static final Pattern ATACA = Pattern.compile("\\b(ataca|ataque|atacalo|atacala|lanza|lanzalo|kamehameha|kame|ame ame|came came)\\b");
    static final Pattern JUEGA = Pattern.compile("\\b(piedra|tijera|tijeras)\\b");
    static String[] directo(String dicho) {
        String s = sinTildes(dicho);
        Matcher m = DISPARA.matcher(s);
        if (m.find() && s.contains("atras")) {   // "mata al de atrás", "a la de atrás", "a la tonta de atrás": apunta de frente
            Matcher t = ATRAS.matcher(s);
            String quien = "atrás";
            if (t.find()) {
                String art = t.group(1), adj = t.group(2);
                quien = adj != null ? adj + " de atrás" : (art.equals("a la") ? "la" : art.equals("a las") ? "las" : art.equals("a los") ? "los" : "el") + " de atrás";
            }
            return new String[]{"¡Prepárate, " + quien + "!", "confeti_atras", ""};
        }
        m.reset();
        if (m.find()) {
            String q = m.group(2);
            boolean nombre = q != null && q.length() > 2 && !q.matches("ahora|pues|porfa|por|favor|cubi|robot|que|con|una|uno|todos|todo|alguien");
            return new String[]{nombre ? "¡Prepárate, " + q + "!" : "¡Prepárate!", "confeti", ""};
        }
        if (ATACA.matcher(s).find()) return new String[]{"¡Mira esto!", "kamehameha", ""};
        if (JUEGA.matcher(s).find()) return new String[]{"Piedra, papel o tijera.", "piedra_papel_tijera", ""};
        return null;
    }

    // lo que Cubi sabe hacer sin internet: {lo que dice, truco, acción}; null si no lo entendió
    String[] sinInternet(String dicho) {
        String s = sinTildes(dicho);
        java.util.function.Predicate<String> dice = p -> Pattern.compile("\\b(" + p + ")\\b").matcher(s).find();
        Calendar c = Calendar.getInstance();
        int h = c.get(Calendar.HOUR), m = c.get(Calendar.MINUTE);
        if (dice.test("hora")) return new String[]{"Son las " + (h == 0 ? 12 : h) + (m == 0 ? " en punto" : " y " + m) + ".", "ninguno", ""};
        String[] orden = directo(dicho);
        if (orden != null) return orden;
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
            ui.removeCallbacks(orejaTarda);
            ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r == null || r.isEmpty()) terminarCharla();
            else try { preguntar(r.get(0)); } catch (Throwable e) { js("toast(" + JSONObject.quote("Cubi: " + e) + ")"); charlaHasta = 0; }
        }
        @Override public void onError(int e) {   // 6 y 7 = no dijo nada o no se entendió; otro número es un fallo del reconocedor
            ui.removeCallbacks(orejaTarda);
            if (e == 6 || e == 7) { terminarCharla(); return; }
            oidoPropio("El oído de Google falló (error " + e + (e == 9 ? ": la app de Google no tiene permiso de micrófono" : "") + ")");
        }
        @Override public void onReadyForSpeech(Bundle b) { orejaLista = true; ui.removeCallbacks(orejaTarda); }
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
                + (musicaPlay ? ". Está sonando " + (cancionTexto.isEmpty() ? "música" : cancionTexto) : "")
                + (System.currentTimeMillis() - pptCuando < 60000 ? ". En piedra, papel o tijera tú acabas de sacar " + pptJugada : "") + "]\n";
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
        String[] orden = directo(texto);
        if (orden != null) {   // dispara, ataca, piedra papel o tijera: de una; en el juego deja el micrófono abierto para saber quién ganó
            decir(orden[0], orden[1], orden[2], "", orden[1].startsWith("piedra") && !claveClaude().isEmpty() && hayInternet());
            return;
        }
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
            } catch (Throwable e) {   // también los errores de las librerías: antes cerraban la app
                dice = "Algo me falló.";
                js("toast(" + JSONObject.quote("Cubi: " + e) + ")");
            }
            charlaUltima = System.currentTimeMillis();
            String d = dice, t = truco, ac = accion, bq = busqueda;
            boolean sg = sigue;
            ui.post(() -> decir(d, t, ac, bq, sg));
        }).start();
    }

    private boolean confetiFrente;   // la pistola apunta de frente, a los de atrás
    private String pptJugada = ""; private long pptCuando;   // lo que sacó Cubi en piedra, papel o tijera

    private void decir(String texto, String truco, String accion, String busqueda, boolean sigue) {
        confetiFrente = "confeti_atras".equals(truco);
        if (confetiFrente) truco = "confeti";
        if ("piedra_papel_tijera".equals(truco)) {   // la jugada sale al azar aquí, no la escoge Claude
            pptJugada = new String[]{"piedra", "papel", "tijera"}[(int) (Math.random() * 3)]; pptCuando = System.currentTimeMillis();
            truco = "ppt_" + pptJugada; texto = texto + " ¡Saqué " + pptJugada + "!";
        }
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
        if (empieza && REACCIONES.contains(t) && !t.equals("kamehameha")) { trucoPend = ""; enviar("asis|/r?n=" + t + (confetiFrente && t.equals("confeti") ? "&f=1" : "")); }
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
    volatile long navCuando;
    private long navMirada;
    // Maps y Waze solo avisan cuando cambia la indicación: parado, o en un tramo largo, pasan minutos sin aviso. Mientras
    // su aviso de navegación siga en el celular, la ruta se da por viva (se mira cada 10 s).
    void navRefrescar() {
        long ahora = System.currentTimeMillis();
        if (navDir.isEmpty() || ahora - navMirada < 10000) return;
        navMirada = ahora;
        if (ahora - navCuando > 30000 && com.cubi.robot.Oyente.rutaViva()) navCuando = ahora;   // con el nombre completo: aquí adentro hay otra cosa llamada Oyente
    }
    double[] destinoPunto() { return Double.isNaN(destLat) ? null : new double[]{destLat, destLon}; }
    private long flechaCuando;
    // el último giro, para la pantalla del carro
    volatile String navDir = "", navCalle = "";
    volatile int navForma;
    volatile double navMetros = -1;
    volatile String giroEstado = "";       // para la app: si el caminito azul del carro salió o por qué no
    volatile int navDestM = -1;            // a cuánto está el destino (m) y la hora de llegada, si Maps las dio
    volatile String navLlega = "";
    private String flechaAntes = "";

    // para la app: por qué salen o no las flechas
    String estadoNav() {
        if (!navActiva()) return "Apagado.";
        if (!puedeTocarMusica()) return "Falta el acceso a notificaciones: sin eso Cubi no ve las indicaciones.";
        String mini = !navMapa() ? "" : " " + (gpsActivo() ? mapaEstado + (rutaEstado.isEmpty() ? "" : " " + rutaEstado) : "Mini mapa: prende “Usar el GPS” para que salga.");
        if (navUltima.isEmpty()) return "Esperando una ruta: empieza a navegar en Google Maps o Waze con el celular (con Android Auto en la pantalla del carro, Maps no le avisa al celular).";
        long hace = (System.currentTimeMillis() - navCuando) / 1000;
        return "Lo último que vio (hace " + (hace < 90 ? hace + " s" : hace / 60 + " min") + "): “" + navUltima + "” → " + navDecision + "." + mini + (giroEstado.isEmpty() ? "" : " " + giroEstado);
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
            navDecision = "¡llegamos!"; navDir = "llegamos";
            if (!destinoTexto().isEmpty()) llegue();                  // Maps dijo que llegó: la ruta propia también se acaba
            enviar("asis|/r?n=llegamos");
            return;
        }
        int i = primero(s, "izquierda", "left"), d = primero(s, "derecha", "right");
        boolean porTexto = i >= 0 || d >= 0;
        boolean rectoTexto = s.contains("recto") || s.contains("derecho") || s.contains("continúa") || s.contains("continua")
                || s.contains("sigue") || s.contains("straight") || s.contains("continue");
        // la forma del giro, para el dibujo de la flecha: 1 curva suave, 2 cerrada, 3 en U, 4 glorieta, 5 salida o mantenerse
        boolean glorieta = tiene(s, "rotonda", "glorieta", "redondel", "romboy", "roundabout");
        boolean enU = lado == 3 || tiene(s, "cambio de sentido", "vuelta en u", "giro en u", "media vuelta", "retorno", "u-turn", "u turn");
        int forma = glorieta ? 4 : enU ? 3
                : tiene(s, "ligeramente", "levemente", "curva", "incorpór", "incorpor", "slight", "merge") ? 1
                : tiene(s, "cerrad", "brusc", "pronunciad", "sharp") ? 2
                : tiene(s, "mantente", "mantén", "mantenga", "salida", "sal por", "rampa", "bifurc", "desvío", "keep ", "exit", "ramp", "fork") ? 5 : 0;
        // ponytail: una U sin lado se toma a la izquierda (así se retorna manejando por la derecha)
        String dir = i >= 0 && (d < 0 || i < d) ? "maps_izq" : d >= 0 ? "maps_der" : lado < 0 || enU ? "maps_izq" : lado == 1 ? "maps_der"
                : lado == 2 || rectoTexto || glorieta ? "maps_recto" : "";
        if (dir.isEmpty()) { navDecision = "no se entiende la indicación"; return; }
        boolean recto = dir.equals("maps_recto"), mapa = navMapa();
        if (recto && forma != 4) forma = 0;
        if (recto) porTexto = rectoTexto && lado != 2;
        // el resumen del viaje se aparta primero, para no confundir los km al destino con los metros al giro
        String destino = "";
        Matcher rs = RESUMEN.matcher(s);
        if (rs.find()) {
            double v = Double.parseDouble(rs.group(3).replace(',', '.'));
            int minutos = (rs.group(1) == null ? 0 : Integer.parseInt(rs.group(1)) * 60) + Integer.parseInt(rs.group(2));
            String hora = rs.group(5) != null ? rs.group(5)   // si Maps no trae la hora, se calcula con los minutos que faltan
                    : new java.text.SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm", Locale.US).format(new java.util.Date(ahora + minutos * 60000L));
            navDestM = (int) Math.round(rs.group(4).equals("km") ? v * 1000 : rs.group(4).equals("mi") ? v * 1609 : v); navLlega = hora;
            destino = "&d=" + navDestM + "&h=" + hora;
            s = RESUMEN.matcher(s).replaceFirst(" ").trim();
            t = RESUMEN.matcher(t).replaceFirst(" ").trim();
        }
        double metros = -1;
        Matcher m = DISTANCIA.matcher(s);
        if (m.find()) {
            double v = Double.parseDouble(m.group(1).replace(',', '.'));
            String u = m.group(2);
            metros = u.equals("km") ? v * 1000 : u.equals("mi") ? v * 1609 : u.equals("m") ? v : v * 0.3048;
        }
        String cual = new String[]{"", "curva suave a la ", "curva cerrada a la ", "en U por la ", "glorieta, ", "salida a la "}[forma] + (recto ? "derecho" : dir.equals("maps_izq") ? "izquierda" : "derecha") + (metros >= 0 ? (recto ? " por " : " en ") + Math.round(metros) + " m" : "")
                + (porTexto ? " (por el texto)" : " (por el dibujo)");
        // la calle: el texto sin la distancia ni los tiempos, corto para que quepa
        String calle = DISTANCIA.matcher(t).replaceAll(" ").replaceAll("\\d+\\s*(min|h)\\b", " ").replaceAll("[·•|]", " ").replaceAll("\\s+", " ").trim();
        Matcher sm = SALIDA.matcher(s);
        if (glorieta && sm.find())                      // en la glorieta lo que sirve es cuál salida
            calle = "Salida " + (sm.group(1) != null ? sm.group(1) : sm.group(3) != null ? sm.group(3) : "" + (1 + Arrays.asList("primera", "segunda", "tercera", "cuarta", "quinta").indexOf(sm.group(2))));
        if (calle.length() > 26) calle = calle.substring(0, 26).trim();
        navDir = dir; navForma = forma; navMetros = metros; navCalle = calle;
        // como la voz de Maps: cada giro se avisa tres veces y entre aviso y aviso Cubi sigue en lo suyo:
        // con tiempo (800 m), cuando falta poco (300 m) y cuando ya toca (80 m). Derecho, una sola vez.
        String etapa = recto ? "recto" : metros < 0 ? "cerca" : metros <= 80 ? "ya" : metros <= 300 ? "cerca" : "lejos";
        String giro = dir + etapa + DISTANCIA.matcher(s).replaceAll("").replaceAll("\\s+", " ").trim();  // el giro sin la distancia
        // derecho se muestra una vez por indicación, a cualquier distancia; en modo mapa todo se muestra siempre
        if (metros > 800 && !recto && !mapa) { navDecision = cual + ", todavía lejos"; return; }
        navDecision = cual + (mapa ? ", en pantalla (modo mapa)" : ", flecha mostrada");
        // el texto de Maps cambia solo (la hora de llegada): la misma flecha no se repite antes de 25 s
        // solo en modo mapa se manda seguido (la pantalla de navegación se queda fija)
        boolean seguido = mapa && ahora - flechaCuando > 1200;
        if (!seguido && (giro.equals(giroAvisado) || ((dir + etapa).equals(flechaAntes) && ahora - flechaCuando < 25000))) return;
        giroAvisado = giro; flechaAntes = dir + etapa; flechaCuando = ahora;
        String q = "";
        try { if (!calle.isEmpty()) q = "&t=" + URLEncoder.encode(calle, "UTF-8").replace("+", "%20"); } catch (Exception ignored) { }
        enviar("asis|/r?n=" + dir + (metros >= 0 ? "&m=" + Math.round(metros) : "") + "&f=" + forma + q + destino + (mapa ? "&q=1" : ""));
    }

    // el resumen del viaje en el aviso de Maps: "25 min · 12 km · 5:42 p. m." (cuánto falta, a cuánto está el destino y la hora de llegada)
    static final Pattern RESUMEN = Pattern.compile("(?:(\\d+)\\s*h\\s*)?(\\d+)\\s*min\\b[^0-9]{0,8}(\\d+(?:[.,]\\d+)?)\\s*(km|m|mi)\\b(?:[^0-9]{0,24}(\\d{1,2}:\\d{2})(?:\\s*[ap]\\.?\\s?m\\.?)?)?", Pattern.CASE_INSENSITIVE);
    static final Pattern SALIDA = Pattern.compile("(\\d+)\\s*\\.?\\s*[ªaº°]?\\s*salida|(primera|segunda|tercera|cuarta|quinta)\\s+salida|(\\d)(?:st|nd|rd|th)\\s+exit");
    private static boolean tiene(String s, String... p) { for (String x : p) if (s.contains(x)) return true; return false; }

    private static int primero(String s, String a, String b) {
        int i = s.indexOf(a), j = s.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }

    // ---------- Cubi canta cuando canta el artista: letra sincronizada (LRCLIB, gratis) + posición de la canción ----------
    private MediaController reproductor;
    private long reproductorVisto, vozMandada;
    private String cancionId = "";
    volatile String cancionTitulo = "", cancionArtista = "";
    volatile android.graphics.Bitmap cancionCaratula;
    String cancionTexto = "";              // "Título de Artista", para que Haiku sepa qué suena
    private volatile long[] letraT;        // cuándo empieza cada línea (ms)
    private volatile boolean[] letraVoz;   // si esa línea tiene letra (las vacías son partes sin voz)
    volatile String letraSigue = "";       // el renglón que viene después, para mostrarlo apagado
    volatile String letraAhora = "";       // el renglón de la letra que se está cantando
    private volatile String[] letraTxt;    // el texto de cada línea, para mostrarlo en la pantalla de música de la gata
    private volatile String[] letraSil;    // una vocal por sílaba de cada línea: la boca de Cubi las va diciendo
    private volatile boolean[] letraCoro;  // la línea se repite en la canción: es del coro
    private int vozEnviada = -1, lineaEnviada = -1;
    private boolean siguiendo;
    volatile String estadoLetra = "";      // lo que pasa con la letra, para verlo en la app
    private final Runnable seguirLetraR = this::seguirLetra;
    static final Pattern LINEA = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\](.*)");

    // los botones de música de la pantalla del carro: -1 anterior, 0 pausa o seguir, 1 siguiente
    void musicaTecla(int cual) {
        if (!puedeTocarMusica()) return;
        try {
            MediaController c = reproductor;
            if (c == null) {
                List<MediaController> l = getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(this, Oyente.class));
                if (l.isEmpty()) return;
                c = l.get(0);
            }
            MediaController.TransportControls t = c.getTransportControls();
            if (cual < 0) t.skipToPrevious(); else if (cual > 0) t.skipToNext();
            else if (c.getPlaybackState() != null && c.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) t.pause(); else t.play();
        } catch (SecurityException ignored) {
        }
    }

    // la barrita de avance de la pantalla del carro: lleva la canción a esa parte (0 = el principio, 1 = el final)
    void musicaIr(float parte) {
        if (!puedeTocarMusica() || musicaDura <= 0) return;
        try {
            MediaController c = reproductor != null ? reproductor : buscarReproductor();
            if (c == null) return;
            long a = (long) (musicaDura * Math.max(0, Math.min(1, parte)));
            c.getTransportControls().seekTo(a);
            musicaPos = a; musicaPosEn = SystemClock.elapsedRealtime();   // la barrita salta de una, sin esperar a que la app avise
        } catch (SecurityException ignored) {
        }
    }

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
            android.graphics.Bitmap car = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (car == null) car = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            String titulo = m.getString(MediaMetadata.METADATA_KEY_TITLE), artista = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            String id = artista + "|" + titulo;
            cancionCaratula = titulo == null ? car : caratula(id, car);
            if (titulo != null && !id.equals(cancionId)) {
                cancionId = id;
                cancionTexto = titulo + (artista != null ? " de " + artista : "");
                cancionTitulo = titulo; cancionArtista = artista == null ? "" : artista; letraAhora = ""; letraSigue = "";
                try {   // el nombre de la canción, para la pantalla de música del gato (sin lo que va entre paréntesis)
                    String tc = titulo.replaceAll("\\s*[\\(\\[].*$", "").replaceAll("\\s+-\\s+.*$", ""), ar = cancionArtista.split(",")[0];
                    enviar("cancion|/musica?tit=" + URLEncoder.encode(tc.length() > 26 ? tc.substring(0, 26) : tc, "UTF-8").replace("+", "%20")
                            + "&art=" + URLEncoder.encode(ar.length() > 22 ? ar.substring(0, 22) : ar, "UTF-8").replace("+", "%20"));
                } catch (Exception ignored) { }
                letraT = null;
                ritmoCancion = ""; introPiano = false; ritmoMandado = "";
                estadoLetra = cancionTexto + ": buscando la letra…";
                pedirInstrumento(titulo, artista == null ? "" : artista, id);
                bajarLetra(titulo, artista == null ? "" : artista, m.getLong(MediaMetadata.METADATA_KEY_DURATION), id);
            }
            long[] t = letraT; boolean[] v = letraVoz;
            long pos = st.getPosition() + (long) ((ahora - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
            musicaDura = m.getLong(MediaMetadata.METADATA_KEY_DURATION); musicaPos = pos; musicaPosEn = ahora;   // para la barrita de avance del carro
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
                    String[] tx = letraTxt;
                    letraAhora = tx != null && i < tx.length ? tx[i] : "";
                    letraSigue = tx != null && i + 1 < tx.length ? tx[i + 1] : "";
                    if (tx != null && i < tx.length) try {   // el renglón que empieza, para la pantalla de la gata (el Cubi viejo lo ignora)
                        enviar("letra|/musica?let=" + URLEncoder.encode(tx[i].length() > 64 ? tx[i].substring(0, 64) : tx[i], "UTF-8").replace("+", "%20"));
                    } catch (Exception ignored) { }
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

    volatile int climaGrados = Integer.MIN_VALUE;   // el clima de ahora, para la cajita de la pantalla del carro
    volatile String climaEstado = "";
    volatile boolean climaNoche;                   // de noche el dibujo del clima lleva luna en vez de sol
    volatile String climaLugar = "";               // la ciudad o el pueblo de donde es ese clima
    volatile int climaAltura = Integer.MIN_VALUE;  // metros sobre el mar del terreno ahí
    volatile String ritmoCancion = "";   // el instrumento de la canción (por su género)
    private volatile boolean introPiano;         // la canción empieza solo con piano
    private String ritmoMandado = "";

    static final String[][] INSTRUMENTOS = {{"pop", "kpop"}, {"electronica", "dj"}, {"urbano", "urbano"}, {"rock", "rock"},
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
                else if (g.contains("reggaet") || g.contains("urban") || g.contains("trap") || g.contains("hip") || g.contains("rap")) elegido = "urbano";
                else if (g.contains("electr") || g.contains("house") || g.contains("dance") || g.contains("edm")) elegido = "dj";
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
            } catch (Throwable e) {  // sin internet o la respuesta no llegó: otra vez en un rato
                if (intento == 3) { if (id.equals(cancionId)) ritmoCancion = "kpop"; return; }
                SystemClock.sleep(3000L * intento);
            }
        } }).start();
    }

    // Lo que ya sonó queda en el celular: la letra y la carátula de cada canción, para que salgan al instante y sin señal.
    // ponytail: no se borra nada (unos 25 KB por canción); si algún día pesa, borrar los más viejos al pasar de N archivos.
    private File guardado(String carpeta, String id, String ext) {
        return new File(new File(getFilesDir(), carpeta), Integer.toHexString(id.toLowerCase(Locale.ROOT).hashCode()) + "_" + id.length() + ext);
    }
    private String caratulaVista = "", caratulaLeida = "";
    private android.graphics.Bitmap caratulaVieja;
    private android.graphics.Bitmap caratula(String id, android.graphics.Bitmap car) {
        if (car == null) {                                           // el reproductor no la dio: la guardada, si hay
            if (!id.equals(caratulaLeida)) { caratulaLeida = id; caratulaVieja = android.graphics.BitmapFactory.decodeFile(guardado("caratulas", id, ".jpg").getPath()); }
            return caratulaVieja;
        }
        if (!id.equals(caratulaVista)) {                             // una vez por canción, achicada a 320
            caratulaVista = id;
            final File f = guardado("caratulas", id, ".jpg");
            final android.graphics.Bitmap b = car;
            if (!f.exists()) new Thread(() -> {
                try {
                    f.getParentFile().mkdirs();
                    float k = 320f / Math.max(1, Math.max(b.getWidth(), b.getHeight()));
                    android.graphics.Bitmap c = k < 1 ? android.graphics.Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * k), Math.round(b.getHeight() * k), true) : b;
                    try (FileOutputStream o = new FileOutputStream(f)) { c.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, o); }
                } catch (Exception e) { f.delete(); }
            }).start();
        }
        return car;
    }

    // busca la canción en LRCLIB y se queda con la letra sincronizada de la versión de duración más parecida
    private void bajarLetra(String titulo, String artista, long durMs, String id) {
        new Thread(() -> {
            try {
                String mejor = null; double dif = 1e9;
                File gl = guardado("letras", id, ".lrc");
                if (gl.exists()) {                                   // ya sonó antes: la letra guardada
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    try (InputStream in = new java.io.FileInputStream(gl)) { byte[] b = new byte[8192]; for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n); }
                    mejor = o.toString("UTF-8"); dif = 0;
                } else {
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
                for (int i = 0; i < a.length(); i++) {
                    JSONObject r = a.getJSONObject(i);
                    String sync = r.optString("syncedLyrics", "");
                    if (sync.isEmpty() || sync.equals("null")) continue;
                    double d = durMs > 0 ? Math.abs(r.optDouble("duration", 0) - durMs / 1000.0) : 0;
                    if (d < dif) { dif = d; mejor = sync; }
                }
                if (mejor != null && dif <= 10) try {
                    gl.getParentFile().mkdirs();
                    try (FileOutputStream o = new FileOutputStream(gl)) { o.write(mejor.getBytes("UTF-8")); }
                } catch (Exception e) { gl.delete(); }
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
                letraTxt = xs.toArray(new String[0]);
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
    volatile long musicaDura, musicaPos, musicaPosEn;   // por dónde va la canción (ms) y cuándo se miró
    volatile boolean musicaPlay;  // lo último que se le dijo a Cubi: suena música en el celular
    private boolean videoMandado;  // lo último que se le dijo: el celular está con un video (reels, Facebook)

    // Abre el asistente del celular (Gemini), como "Hey Google". Con la pantalla apagada necesita el permiso
    // "Mostrar sobre otras apps". En Samsung el comando de voz genérico abre Bixby: primero se le pide a Google.
    // la app ya abrió la ventanita del asistente (showAssist): Cubi pone cara de escuchar
    void asistenteAbierto() {
        asisDesde = System.currentTimeMillis();
        asisEstado = "escucha";
        enviar("asis|/asistente?estado=escucha");
        revisarAsistente();
    }

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
        // solo con Cubi conectado: con el robot apagado contestaba el celular solo, sin carita
        if (oidoQuiere && modelo != null && libre && listo) arrancarOido();
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
    private volatile String despertoPor = "";   // qué lo puso a escuchar la última vez, para el diagnóstico
    private long nombreT;
    private static String hora() { return new java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date()); }
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
                int pico = 0, bloques = 0, seguidas = 0;
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
                    // ponytail: una suposición a medias del reconocedor cambia de un bloque a otro; solo cuenta si se sostiene.
                    // Si aún se despierta solo, subir los bloques o dejar solo el resultado final.
                    Matcher m = HOLA.matcher(texto);
                    seguidas = m.find() ? seguidas + 1 : 0;
                    if (seguidas >= (fin ? 1 : 3)) {
                        seguidas = 0;
                        despertoPor = hora() + " el celular oyó “" + m.group() + "”";
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

    // Por qué se cerró sola la app la última vez. Los errores de Java los anota la app; los nativos (librerías) y los
    // "no responde" los guarda Android (11 o más nuevo): de ahí se sacan el motivo y las librerías que aparecen.
    String caida() {
        String java = getSharedPreferences("cubi", 0).getString("caida", "");
        if (Build.VERSION.SDK_INT < 30) return java;
        try {
            for (android.app.ApplicationExitInfo x : getSystemService(android.app.ActivityManager.class).getHistoricalProcessExitReasons(null, 0, 5)) {
                int r = x.getReason();
                boolean nativo = r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, anr = r == android.app.ApplicationExitInfo.REASON_ANR;
                if (r == android.app.ApplicationExitInfo.REASON_CRASH) break;   // de Java: ya está anotado arriba
                if (!nativo && !anr && r != android.app.ApplicationExitInfo.REASON_LOW_MEMORY) continue;
                StringBuilder t = new StringBuilder(new java.text.SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new java.util.Date(x.getTimestamp())))
                        .append(nativo ? " cierre nativo" : anr ? " no respondía" : " sin memoria").append(" (").append(x.getStatus()).append(") ")
                        .append(x.getDescription() == null ? "" : x.getDescription());
                try (InputStream in = x.getTraceInputStream()) {   // de la traza solo los nombres de librería y función
                    if (in != null) {
                        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                        byte[] b = new byte[65536]; int n;
                        while ((n = in.read(b)) > 0 && o.size() < 400000) o.write(b, 0, n);
                        Matcher m = Pattern.compile(anr ? "at [\\w.$]+\\([\\w.:]+\\)" : "lib[\\w.+-]+\\.so|_Z\\w{6,60}|vosk\\w+|Java_\\w+").matcher(o.toString("ISO-8859-1"));
                        java.util.LinkedHashSet<String> v = new java.util.LinkedHashSet<>();
                        while (m.find() && v.size() < 14) v.add(m.group());
                        t.append(" ").append(String.join(" ", v));
                    }
                } catch (Throwable ignorado) {
                }
                return t + (java.isEmpty() ? "" : " | " + java);
            }
        } catch (Throwable ignorado) {
        }
        return java;
    }

    // para la app: por qué "Hola Cubi" escucha o no
    String estadoHola() {
        String caida = caida();
        return estadoHola0() + (despertoPor.isEmpty() ? "" : " Lo último que lo despertó: " + despertoPor + ".") + (caida.isEmpty() ? "" : " — La última vez que la app se cerró sola: " + caida);
    }

    private String estadoHola0() {
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
