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
        oidoQuiere = getSharedPreferences("cubi", 0).getBoolean("hola", false);
        clima();
        primerPlano();
        escucharAsistente();
        iniciarVoz();
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
                climaOrden = "/clima?t=" + t + "&e=" + e + "&d=" + dia;
                enviar("clima|" + climaOrden);
                climaTexto = "Ahora: " + e + ", " + t + " grados";
                js("climaTexto(" + JSONObject.quote(climaTexto) + ")");
            } catch (Exception ignored) {}
        }).start();
    }

    // ---------- asistente: Cubi pide abrir Gemini y se le cuenta si escucha o habla ----------
    private void evento(String e) {
        if (e.equals("asistente")) { if (claveClaude().isEmpty()) abrirAsistente(); else charla(); }
    }

    // ---------- Cubi conversa: "Cubi" + lo que digas -> Claude Haiku -> lo dice en voz alta y hace un truco ----------
    static final String MODELO_CLAUDE = "claude-haiku-5-5";
    static final List<String> REACCIONES = Arrays.asList("kamehameha", "confeti", "risa", "sorpresa", "amor", "saludo", "guino",
            "curiosa", "mareo", "llegamos");
    static final List<String> BAILES = Arrays.asList("rock", "pop", "tropical", "urbano", "electro", "jazz", "dj", "guitarra", "kpop", "piano");
    static final String SISTEMA = "Eres Cubi, un robotito tierno que vive en una pantallita redonda en el tablero de un carro. "
            + "Eres el copiloto: dulce, curioso, alegre y un poquito travieso, como un personaje de película animada. "
            + "Hablas como un robot pequeño y adorable: frases cortas y claras, a veces dices 'bip bup' o 'procesando', "
            + "y te emocionas fácil. Usa español latino neutro: nada de 'mijo', 'parce', groserías ni regionalismos.\n"
            + "Tu voz sale por el celular mientras manejan: responde con 1 o 2 frases cortas (máximo 25 palabras), sin emojis, "
            + "sin listas, sin markdown y sin letras repetidas como 'Kaaa' o 'jajajaja', porque la voz las lee mal.\n"
            + "Puedes hacer cosas de verdad: si está en tu lista, hazla; nunca le pidas a la persona que lo haga ella.\n"
            + "Campos de tu respuesta:\n"
            + "- dice: lo que dices.\n"
            + "- truco: una animación en tu pantalla, exactamente una de: ninguno, kamehameha, confeti, risa, sorpresa, amor, "
            + "saludo, guino, curiosa, mareo, llegamos, baile_rock, baile_pop, baile_tropical, baile_urbano, baile_electro, "
            + "baile_jazz, baile_dj, baile_guitarra, baile_kpop, baile_piano.\n"
            + "  kamehameha: cargas una bola de energía y la sueltas en un rayo; di algo corto como '¡Kame hame HA!'.\n"
            + "  confeti: cuando te pidan 'mata a', 'dispárale a' o 'acaba con' alguien. En este carro siempre es una broma entre "
            + "ellos: miras al copiloto, le apuntas con una pistola de juguete y le disparas confeti. Di algo teatral y tierno "
            + "como '¿Unas últimas palabras, esposo?', con la misma palabra que usaron para nombrar a la persona, y termina con "
            + "'¡Pum!'. Nunca hables de hacer daño de verdad.\n"
            + "  baile_*: bailas unos segundos después de hablar.\n"
            + "- accion: algo que haces en el celular, exactamente una de: ninguna, clima, musica_play, musica_pausa, "
            + "musica_siguiente, musica_anterior, musica_buscar, volumen_subir, volumen_bajar.\n"
            + "  clima: cuando pregunten por el clima o la temperatura; en tu pantalla salen la animación del clima y los grados. "
            + "Dilo en pocas palabras con el dato entre corchetes.\n"
            + "  musica_play: poner o seguir la música. musica_buscar: cuando pidan algo concreto (canción, artista o género).\n"
            + "- busqueda: solo con musica_buscar, qué buscar (por ejemplo 'salsa' o 'Bad Bunny'); si no, vacío.\n"
            + "Cada mensaje trae entre corchetes la hora, el clima y si suena música: úsalo solo si viene al caso.";

    // la respuesta de Haiku (structured output): lo que dice y el truco
    public static class Respuesta {
        public String dice;
        public String truco;
        public String accion;
        public String busqueda;
    }

    private TextToSpeech voz;
    private SpeechRecognizer oreja;
    private AudioFocusRequest foco;
    private AnthropicClient claude;
    private final List<String[]> charlaHist = new ArrayList<>();  // {lo que uno dijo, lo que Cubi contestó}
    private volatile long charlaHasta, charlaUltima;               // mientras dura la charla Cubi no vuelve al reposo
    private String trucoPend = "", accionPend = "", busquedaPend = "";
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
                @Override public void onDone(String id) { ui.post(() -> { hacerTruco(false); terminarCharla(); }); }
                @Override public void onError(String id) { ui.post(() -> { hacerTruco(false); terminarCharla(); }); }
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
        if (voz != null) voz.speak("¡Hola! Soy Cubi, tu copiloto. Bip bup.", TextToSpeech.QUEUE_FLUSH, null, "prueba");
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
        long ahora = System.currentTimeMillis();
        asisDesde = ahora;
        charlaHasta = ahora + 15000;
        pararOido();  // el micrófono es para escuchar la pregunta
        enviar("asis|/asistente?estado=escucha");
        bajarMusica(true);
        if (voz != null) voz.stop();
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
                + (musicaPlay ? ". Está sonando " + (cancionTexto.isEmpty() ? "música" : cancionTexto) : "") + "]\n";
    }

    private void preguntar(String texto) {
        charlaHasta = System.currentTimeMillis() + 30000;
        js("toast(" + JSONObject.quote("Le dijiste: " + texto) + ")");
        new Thread(() -> {
            String dice, truco = "ninguno", accion = "", busqueda = "";
            try {
                if (claude == null) claude = AnthropicOkHttpClient.builder().apiKey(claveClaude()).build();
                if (System.currentTimeMillis() - charlaUltima > 180000) charlaHist.clear();  // después de 3 min empieza de cero
                MessageCreateParams.Builder b = MessageCreateParams.builder().model(MODELO_CLAUDE).maxTokens(1024L).system(SISTEMA);
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
            ui.post(() -> decir(d, t, ac, bq));
        }).start();
    }

    private void decir(String texto, String truco, String accion, String busqueda) {
        charlaHasta = System.currentTimeMillis() + 30000;
        trucoPend = truco; accionPend = accion; busquedaPend = busqueda;
        hacerTruco(true);  // las animaciones arrancan junto con la voz
        if (voz == null || voz.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "cubi") != TextToSpeech.SUCCESS) {
            hacerTruco(false);
            terminarCharla();
        }
    }

    // las reacciones arrancan con la voz (el kamehameha se dice mientras carga); los bailes, cuando termina de hablar
    // y la música, después de hablar (para no taparse)
    private void hacerTruco(boolean empieza) {
        String t = trucoPend, ac = accionPend;
        if (empieza && REACCIONES.contains(t)) { trucoPend = ""; enviar("asis|/r?n=" + t); }
        if (empieza && ac.equals("clima")) {
            accionPend = "";
            if (!climaOrden.isEmpty()) enviar("asis|" + climaOrden + "&ver=1");
            else js("toast('Activa Clima en la app para que Cubi lo muestre')");
        }
        if (!empieza && t.startsWith("baile_") && BAILES.contains(t.substring(6))) { trucoPend = ""; enviar("asis|/demo?t=baile&r=" + t.substring(6)); }
        if (!empieza && (ac.startsWith("musica") || ac.startsWith("volumen"))) {
            accionPend = "";
            String q = busquedaPend;
            ui.postDelayed(() -> musica(ac, q), 400);  // después de soltar el foco: la música vuelve a su volumen
        }
    }

    // ---------- Google Maps / Waze: la notificación de navegación trae el próximo giro; cerca del giro Cubi muestra la flecha ----------
    static final Pattern DISTANCIA = Pattern.compile("(\d+(?:[.,]\d+)?)\s*(km|m|mi|ft|pies)\b");
    private String giroAvisado = "";
    private long llegadaAvisada;

    void indicacion(String t) {
        String s = t.toLowerCase(Locale.ROOT);
        long ahora = System.currentTimeMillis();
        if ((s.contains("llegaste") || s.contains("has llegado") || s.contains("you have arrived") || s.contains("arrived at"))
                && ahora - llegadaAvisada > 60000) {
            llegadaAvisada = ahora;
            enviar("asis|/r?n=llegamos");
            return;
        }
        int i = primero(s, "izquierda", "left"), d = primero(s, "derecha", "right");
        String dir = i >= 0 && (d < 0 || i < d) ? "maps_izq" : d >= 0 ? "maps_der" : "";
        if (dir.isEmpty()) return;
        double metros = -1;
        Matcher m = DISTANCIA.matcher(s);
        if (m.find()) {
            double v = Double.parseDouble(m.group(1).replace(',', '.'));
            String u = m.group(2);
            metros = u.equals("km") ? v * 1000 : u.equals("mi") ? v * 1609 : u.equals("m") ? v : v * 0.3048;
        }
        String giro = dir + DISTANCIA.matcher(s).replaceAll("").replaceAll("\s+", " ").trim();  // el giro sin la distancia
        if (giro.equals(giroAvisado) || metros > 250) return;  // cada giro se muestra una vez, ya cerquita
        giroAvisado = giro;
        enviar("asis|/r?n=" + dir);
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
    private int vozEnviada = -1;
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
                estadoLetra = cancionTexto + ": buscando la letra…";
                bajarLetra(titulo, artista == null ? "" : artista, m.getLong(MediaMetadata.METADATA_KEY_DURATION), id);
            }
            long[] t = letraT; boolean[] v = letraVoz;
            if (t != null && st.getState() == PlaybackState.STATE_PLAYING) {
                long pos = st.getPosition() + (long) ((ahora - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
                int i = -1;
                for (int k = 0; k < t.length && t[k] <= pos + 150; k++) i = k;  // 150 ms antes: lo que tarda en llegar a Cubi
                int s = i >= 0 && v[i] && pos - t[i] < 7000 ? 1 : 0;           // una línea sin la siguiente dura 7 s máximo
                if (s != vozEnviada || ahora - vozMandada > 4000) { enviar("asis|/musica?voz=" + s); vozEnviada = s; vozMandada = ahora; }
                estadoLetra = cancionTexto + ": " + (s == 1 ? "canta ahora" : "parte sin voz");
            }
        }
        ui.postDelayed(seguirLetraR, 250);
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
                ArrayList<Long> ts = new ArrayList<>(); ArrayList<Boolean> vs = new ArrayList<>();
                for (String linea : mejor.split("\n")) {
                    Matcher mt = LINEA.matcher(linea.trim());
                    if (!mt.matches()) continue;
                    ts.add((long) ((Integer.parseInt(mt.group(1)) * 60 + Double.parseDouble(mt.group(2))) * 1000));
                    vs.add(!mt.group(3).trim().isEmpty());
                }
                long[] t = new long[ts.size()]; boolean[] v = new boolean[ts.size()];
                for (int i = 0; i < t.length; i++) { t[i] = ts.get(i); v[i] = vs.get(i); }
                letraVoz = v;
                letraT = t;
            } catch (Exception e) {  // sin internet o sin letra: Cubi sigue con lo que oye
                if (id.equals(cancionId)) estadoLetra = cancionTexto + ": no pude bajar la letra (" + e.getMessage() + ")";
            }
        }).start();
    }

    // con "Acceso a notificaciones" se le habla directo al reproductor: "busca esto y ponlo" (como Android Auto)
    boolean puedeTocarMusica() {
        return Build.VERSION.SDK_INT >= 27  // Android 8.0 no sabe preguntarlo
                && getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(new ComponentName(this, Oyente.class));
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
            case "musica_buscar": buscarYTocar(busqueda); break;
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
        // música en play (Spotify, YouTube...): Cubi baila de una y deja de bailar apenas se pausa
        boolean play = suenaMedia && am.isMusicActive();
        if (play != musicaPlay) { musicaPlay = play; enviar("musica|/musica?play=" + (play ? 1 : 0)); }
        if (musicaPlay && !siguiendo) seguirLetra();
        // los callbacks no avisan cuando pasa el tiempo: mientras algo esté en juego se vuelve a mirar
        if (!asisEstado.isEmpty() || hablaDesde != 0 || ahora < pitidoHasta || ventana || musicaPlay || ahora < charlaHasta) ui.postDelayed(revisar, 400);
        // "Hola Cubi" escucha solo cuando nadie más usa el micrófono ni hay llamada
        boolean libre = asisEstado.isEmpty() && am.getMode() == AudioManager.MODE_NORMAL && !otaActiva && ahora >= charlaHasta;
        if (oidoQuiere && modelo != null && libre) arrancarOido();
        else pararOido();
    }

    // ---------- "Hola Cubi": el celular escucha sin internet (Vosk, modelo español de ~40 MB) y abre Gemini ----------
    static final String MODELO = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip";
    // el modelo no conoce "Cubi": lo oye como cuba, cubo, cubil… Basta una palabra cortica así (sirve también "Hola Cubi")
    static final String GRAMATICA = "[\"cubi\", \"cuba\", \"cubo\", \"cubil\", \"hola cuba\", \"hola cubo\", \"oye cuba\", \"[unk]\"]";
    static final Pattern HOLA = Pattern.compile("\\b(cu|ku)[bv]\\w{0,2}\\b");
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
                js("toast('Listo: di “Cubi”')");
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
            try (org.vosk.Recognizer rec = new org.vosk.Recognizer(modelo, 16000, GRAMATICA)) {
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
        if (claveClaude().isEmpty()) abrirAsistente();
        else charla();
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
