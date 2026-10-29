package com.cubi.robot;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
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
        oidoQuiere = getSharedPreferences("cubi", 0).getBoolean("hola", false);
        clima();
        primerPlano();
        escucharAsistente();
        if (oidoQuiere) prepararOido();
    }

    private Notification aviso;

    // Con "Hola Cubi" el servicio también usa el micrófono. Android solo lo deja pedir con la app a la vista:
    // si arrancó solo (en segundo plano) sigue sin micrófono hasta que se abra la app.
    void primerPlano() {
        if (Build.VERSION.SDK_INT < 29) { startForeground(1, aviso); return; }
        int tipo = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (oidoQuiere && Build.VERSION.SDK_INT >= 30 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == 0)
            tipo |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        try {
            startForeground(1, aviso, tipo);
        } catch (Exception e) {
            startForeground(1, aviso, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        }
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
                ui.post(() -> { musicaPlay = false; revisarAsistente(); });  // al reconectar le cuenta si hay música
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
    void clima() {
        ui.removeCallbacks(climaCada);
        if (!climaActivo()) return;
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
                enviar("clima|/clima?t=" + t + "&e=" + e + "&d=" + dia);
                climaTexto = "Ahora: " + e + ", " + t + " grados";
                js("climaTexto(" + JSONObject.quote(climaTexto) + ")");
            } catch (Exception ignored) {}
        }).start();
    }

    // ---------- asistente: Cubi pide abrir Gemini y se le cuenta si escucha o habla ----------
    private void evento(String e) {
        if (e.equals("asistente")) abrirAsistente();
    }

    private long asisDesde;
    private String asisEstado = "";
    private boolean musicaPlay;  // lo último que se le dijo a Cubi: suena música en el celular

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

    private void revisarAsistente() {
        ui.removeCallbacks(revisar);
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        long ahora = System.currentTimeMillis();
        boolean ventana = ahora - asisDesde < 120000;
        boolean graba = false;  // la grabación de "Hola Cubi" (la nuestra) no cuenta
        for (AudioRecordingConfiguration c : am.getActiveRecordingConfigurations())
            if (c.getClientAudioSessionId() != miSesion) graba = true;
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
        if (hablaOtro && hablaDesde == 0) { hablaDesde = ahora; pitido = suenaAsis; }
        if (!hablaOtro && hablaDesde != 0) {
            if (ahora - hablaDesde < 1200 && pitido && !asisEstado.equals("habla")) pitidoHasta = ahora + 8000;
            hablaDesde = 0;
        }
        boolean habla = hablaOtro && (ventana || asisEstado.equals("habla") || ahora - hablaDesde >= 1200);
        if (habla) pitidoHasta = 0;
        boolean oye = !hablaOtro && (graba || ahora < pitidoHasta);
        String nuevo = habla ? "habla" : oye ? "escucha" : "";
        if (nuevo.isEmpty() && ahora - asisDesde < 6000) nuevo = "escucha";  // Gemini tarda en abrir el micrófono
        if (!nuevo.equals(asisEstado) || (!nuevo.isEmpty() && ahora - ultimoEnvio > 5000)) {  // se repite: en Cubi el estado caduca
            boolean fin = nuevo.isEmpty();
            if (!(fin && asisEstado.isEmpty())) enviar("asis|/asistente?estado=" + (fin ? "fin" : nuevo));
            asisEstado = nuevo;
            ultimoEnvio = ahora;
        }
        // música en play (Spotify, YouTube...): Cubi baila de una y deja de bailar apenas se pausa
        boolean play = suenaMedia && am.isMusicActive();
        if (play != musicaPlay) { musicaPlay = play; enviar("musica|/musica?play=" + (play ? 1 : 0)); }
        // los callbacks no avisan cuando pasa el tiempo: mientras algo esté en juego se vuelve a mirar
        if (!asisEstado.isEmpty() || hablaDesde != 0 || ahora < pitidoHasta || ventana || musicaPlay) ui.postDelayed(revisar, 400);
        // "Hola Cubi" escucha solo cuando nadie más usa el micrófono ni hay llamada
        boolean libre = asisEstado.isEmpty() && am.getMode() == AudioManager.MODE_NORMAL && !otaActiva;
        if (oidoQuiere && modelo != null && libre) arrancarOido();
        else pararOido();
    }

    // ---------- "Hola Cubi": el celular escucha sin internet (Vosk, modelo español de ~40 MB) y abre Gemini ----------
    static final String MODELO = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip";
    // el modelo no conoce "Cubi": lo oye como cuba, cubo, cubil… Basta "hola" seguido de algo así
    static final Pattern HOLA = Pattern.compile("\\bh?ola\\s+(cu|ku|qu|co)[bpv]\\w*");
    private volatile boolean oidoQuiere;
    private volatile Thread oido;
    private volatile int miSesion = -1;
    private volatile org.vosk.Model modelo;
    private boolean bajando;
    private long ultimoHola;

    void hola(boolean si) {
        getSharedPreferences("cubi", 0).edit().putBoolean("hola", si).apply();
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
            } catch (Exception e) {
                js("toast(" + JSONObject.quote("No pude preparar “Hola Cubi”: " + e.getMessage()) + ")");
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
        if (oido != null) return;
        Thread t = new Thread(() -> {
            AudioRecord r = null;
            try (org.vosk.Recognizer rec = new org.vosk.Recognizer(modelo, 16000)) {
                int tam = Math.max(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 8192);
                r = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, tam);
                miSesion = r.getAudioSessionId();
                r.startRecording();
                short[] b = new short[2048];
                while (oido == Thread.currentThread()) {
                    int n = r.read(b, 0, b.length);
                    if (n < 0) break;
                    String texto = rec.acceptWaveForm(b, n) ? rec.getResult() : rec.getPartialResult();
                    if (HOLA.matcher(texto).find()) {
                        rec.reset();
                        ui.post(this::oyoHola);
                    }
                }
            } catch (Exception ignored) {  // sin permiso o sin micrófono: se reintenta en la próxima revisión
            } finally {
                if (r != null) r.release();
                if (oido == Thread.currentThread()) { oido = null; miSesion = -1; }
            }
        });
        oido = t;
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
        abrirAsistente();
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
