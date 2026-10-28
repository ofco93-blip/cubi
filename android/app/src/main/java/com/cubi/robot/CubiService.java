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
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.UUID;

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
        Notification n = nb.setContentTitle("Cubi")
                .setContentText("Conectado por Bluetooth: doble toque en Cubi abre Gemini")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(1, n);
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

    // ---------- asistente: Cubi pide abrir Gemini y se le cuenta si escucha o habla ----------
    private void evento(String e) {
        if (e.equals("asistente")) abrirAsistente();
    }

    private long asisDesde;
    private String asisEstado = "";
    private long ultimoActivo;

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
                asisEstado = "";
                ultimoActivo = asisDesde;
                enviar("asis|/asistente?estado=escucha");
                ui.postDelayed(this::seguirAsistente, 400);
                return;
            } catch (Exception ignored) {
            }
        }
        enviar("asis|/asistente?estado=fin");
        js("toast('No pude abrir Gemini: dale a la app el permiso “Mostrar sobre otras apps”')");
    }

    // Mira qué audio hay en el celular (sin escuchar su contenido): si Gemini graba → escucha; si suena → habla.
    private void seguirAsistente() {
        long ahora = System.currentTimeMillis();
        if (ahora - asisDesde > 90000) { ponerAsis("fin"); return; }
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        boolean graba = !am.getActiveRecordingConfigurations().isEmpty();
        boolean habla = false;
        for (AudioPlaybackConfiguration c : am.getActivePlaybackConfigurations()) {
            int u = c.getAudioAttributes().getUsage();
            if (u == AudioAttributes.USAGE_ASSISTANT || u == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
                    || u == AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY) habla = true;
        }
        if (habla) ponerAsis("habla");
        else if (graba) ponerAsis("escucha");
        if (habla || graba) ultimoActivo = ahora;
        // terminó: 4 s sin que Gemini grabe ni hable (o 12 s si nunca arrancó)
        if (ahora - ultimoActivo > (asisEstado.isEmpty() ? 12000 : 4000)) { ponerAsis("fin"); return; }
        ui.postDelayed(this::seguirAsistente, 300);
    }

    private void ponerAsis(String e) {
        if (e.equals(asisEstado)) return;
        asisEstado = e;
        enviar("asis|/asistente?estado=" + e);
        if (e.equals("fin")) asisDesde = 0;
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
