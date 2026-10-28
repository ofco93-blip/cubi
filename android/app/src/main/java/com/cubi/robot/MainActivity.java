package com.cubi.robot;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
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
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.UUID;

// La interfaz vive en assets/index.html. Todo con el robot va por Bluetooth (BLE), también la actualización.
@SuppressLint("MissingPermission")
public class MainActivity extends Activity {
    static final UUID SRV = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID OTA = UUID.fromString("6e400004-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final int PEDAZO = 240;  // bytes del firmware por escritura (cabe en el MTU de 247)

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());

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
    private boolean otaActiva, otaTerminando;

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
        web.setWebViewClient(new WebViewClient());
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    @Override
    public void onBackPressed() {
        if (otaActiva) return;  // durante la actualización no se sale
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (gatt != null) gatt.close();
        super.onDestroy();
    }

    private void js(String code) {
        ui.post(() -> web.evaluateJavascript(code, null));
    }

    private void avisar(String estado) {
        js("bleEstado(" + JSONObject.quote(estado) + ")");
    }

    // ---------- permisos ----------
    private String[] permisosBle() {
        return Build.VERSION.SDK_INT >= 31
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean tienePermisos() {
        for (String p : permisosBle())
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(permisosBle(), 2);
                return false;
            }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (code != 2) return;
        boolean ok = r.length > 0;
        for (int x : r) ok &= x == PackageManager.PERMISSION_GRANTED;
        if (ok) buscar();
        else avisar("sin-permiso");
    }

    // ---------- buscar y conectar ----------
    private void buscar() {
        if (gatt != null || !tienePermisos()) return;
        BluetoothAdapter a = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        if (a == null || !a.isEnabled()) {
            avisar("apagado");
            return;
        }
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
        ui.postDelayed(() -> {
            if (scanCb != null) {
                pararBusqueda();
                avisar("no-encontrado");
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
                    if (otaEnviados >= otaTotal) enviarTexto("ota|/ota/fin");
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
    // Las respuestas "ota|..." son de la actualización y las maneja Java; las demás van a la interfaz.
    private void llega(byte[] v) {
        synchronized (entrada) {
            entrada.append(new String(v, StandardCharsets.UTF_8));
            int n;
            while ((n = entrada.indexOf("\n")) >= 0) {
                String linea = entrada.substring(0, n);
                entrada.delete(0, n + 1);
                if (linea.startsWith("ota|")) respuestaOta(linea.substring(4));
                else js("bleRespuesta(" + JSONObject.quote(linea) + ")");
            }
        }
    }

    private void enviarTexto(String s) {
        synchronized (cola) {
            cola.add(new Paquete(false, s.getBytes(StandardCharsets.UTF_8)));
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

    // ---------- actualización por Bluetooth ----------
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != 3 || res != RESULT_OK || data == null || data.getData() == null) {
            if (req == 3) js("otaCancelada()");
            return;
        }
        try (InputStream in = getContentResolver().openInputStream(data.getData())) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            fw = o.toByteArray();
        } catch (Exception e) {
            js("otaFin(false," + JSONObject.quote("No pude leer el archivo") + ")");
            return;
        }
        // Un firmware del ESP32 empieza con 0xE9 y pesa más de 100 KB: así no se sube cualquier archivo por error
        if (fw.length < 100000 || (fw[0] & 0xFF) != 0xE9) {
            js("otaFin(false," + JSONObject.quote("Ese archivo no es un firmware de Cubi. Elige cubi.ino.bin.") + ")");
            return;
        }
        if (ota == null || gatt == null) {
            js("otaFin(false," + JSONObject.quote("Cubi no está conectado por Bluetooth.") + ")");
            return;
        }
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        js("otaEmpieza(" + otaTotal + ")");
        enviarTexto("ota|/ota/inicio?tam=" + otaTotal + (md5.isEmpty() ? "" : "&md5=" + md5));
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
        if (gatt != null) enviarTexto("ota|/ota/cancelar");
        otaFin(false, msg);
    }

    private void otaFin(boolean ok, String msg) {
        if (!otaActiva) return;
        otaActiva = false;
        fw = null;
        ui.post(() -> getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        js("otaFin(" + ok + "," + JSONObject.quote(msg) + ")");
        if (ok) ui.postDelayed(this::buscar, 6000);  // se vuelve a conectar cuando Cubi termina de reiniciar
    }

    class Puente {
        @JavascriptInterface
        public void bleBuscar() {
            ui.post(MainActivity.this::buscar);
        }

        @JavascriptInterface
        public void bleEnviar(String orden) {
            if (otaActiva) return;  // durante la actualización no se manda nada más
            synchronized (cola) {
                if (cola.size() < 20) cola.add(new Paquete(false, orden.getBytes(StandardCharsets.UTF_8)));
            }
            siguiente();
        }

        @JavascriptInterface
        public void otaElegir() {
            if (otaActiva) return;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            ui.post(() -> startActivityForResult(i, 3));
        }
    }
}
