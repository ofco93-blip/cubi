package com.cubi.robot;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

// Mapi: la pantalla redonda del mapa (debajo del volante). Va por su propia conexión de Bluetooth, aparte de la de Cubi,
// y le manda lo que dibuja: las calles (las mismas del mini mapa), dónde va el carro, el giro, la pila, el clima y la hora.
// Solo manda lo que cambió; la posición, cada segundo.
final class CubiMapi {
    static final UUID SRV = UUID.fromString("6e400101-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID RX = UUID.fromString("6e400102-b5a3-f393-e0a9-e50e24dcca9e");
    static volatile CubiMapi yo;
    static boolean listo() { CubiMapi m = yo; return m != null && m.listo; }
    static String estado() { CubiMapi m = yo; return m == null ? "Apagado." : m.estado; }

    private final CubiService sv;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner; private ScanCallback scanCb;
    private BluetoothGatt gatt; private BluetoothGattCharacteristic rx;
    private volatile boolean listo, ocupado, parado;
    private final ArrayDeque<byte[]> cola = new ArrayDeque<>();
    private volatile String estado = "Buscando a Mapi…";
    private static volatile List<String> ultMapa; private static volatile int ultVias; private static volatile String ultCierre;
    private String yaGiro = "", yaLlega = "", yaPila = "", yaClima = ""; private long yaHora;

    CubiMapi(CubiService s) { sv = s; ui.post(this::buscar); ui.postDelayed(tic, 1000); }

    void parar() {
        parado = true; ui.removeCallbacksAndMessages(null);
        try { pararBusqueda(); if (gatt != null) gatt.close(); } catch (SecurityException ignored) { }
        gatt = null; listo = false;
    }

    private void buscar() {
        if (parado || gatt != null) return;
        try {
            BluetoothAdapter a = ((BluetoothManager) sv.getSystemService(android.content.Context.BLUETOOTH_SERVICE)).getAdapter();
            if (a == null || !a.isEnabled()) { estado = "El Bluetooth del celular está apagado."; ui.postDelayed(this::buscar, 15000); return; }
            scanner = a.getBluetoothLeScanner();
            scanCb = new ScanCallback() { @Override public void onScanResult(int t, ScanResult r) { pararBusqueda(); conectar(r.getDevice()); } };
            scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SRV)).build()),
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb);
            estado = "Buscando a Mapi…";
        } catch (SecurityException e) { estado = "Falta el permiso de Bluetooth."; return; }
        ui.postDelayed(() -> { if (scanCb != null) { pararBusqueda(); estado = "No encuentro a Mapi (¿está prendido?)."; ui.postDelayed(this::buscar, 30000); } }, 12000);
    }

    private void pararBusqueda() {
        try { if (scanner != null && scanCb != null) scanner.stopScan(scanCb); } catch (SecurityException ignored) { }
        scanCb = null;
    }

    private void conectar(BluetoothDevice d) {
        if (gatt != null) return;
        estado = "Conectando con Mapi…";
        try {
            gatt = d.connectGatt(sv, false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt g, int st, int nuevo) {
                    if (nuevo == BluetoothProfile.STATE_CONNECTED) g.requestMtu(247);
                    else if (nuevo == BluetoothProfile.STATE_DISCONNECTED) {
                        g.close();
                        synchronized (cola) { gatt = null; listo = ocupado = false; cola.clear(); }
                        estado = "Mapi se desconectó; lo busco otra vez.";
                        ui.postDelayed(CubiMapi.this::buscar, 3000);
                    }
                }
                @Override public void onMtuChanged(BluetoothGatt g, int mtu, int st) { g.discoverServices(); }
                @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
                    BluetoothGattService s = g.getService(SRV);
                    if (s == null) { g.disconnect(); return; }
                    rx = s.getCharacteristic(RX);
                    listo = true; estado = "Conectado.";
                    yaGiro = yaLlega = yaPila = yaClima = ""; yaHora = 0;        // recién conectado: se manda todo otra vez
                    ui.post(() -> mandarMapa());
                }
                @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
                    synchronized (cola) { ocupado = false; } siguiente();
                }
            }, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) { gatt = null; estado = "Falta el permiso de Bluetooth."; }
    }

    private void mandar(String s) {
        if (!listo) return;
        synchronized (cola) { if (cola.size() < 80) cola.add(s.getBytes(StandardCharsets.UTF_8)); }
        siguiente();
    }
    private void siguiente() {
        synchronized (cola) {
            if (!listo || ocupado || cola.isEmpty() || gatt == null || rx == null) return;
            rx.setValue(cola.poll()); rx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);   // con confirmación: ninguno se pierde
            try { ocupado = gatt.writeCharacteristic(rx); } catch (SecurityException e) { ocupado = false; }
        }
    }

    // las calles nuevas (las arma CubiService para el mini mapa): se guardan para mandarlas también al reconectar
    static void mapa(List<String> mensajes, int deVias, String cierre) {
        ultMapa = mensajes; ultVias = deVias; ultCierre = cierre;
        CubiMapi m = yo; if (m != null) m.ui.post(m::mandarMapa);
    }
    private void mandarMapa() {
        List<String> l = ultMapa;
        if (!listo || l == null) return;
        for (int i = 0; i < l.size(); i++) mandar("/mapa?" + (i < ultVias ? "w=" : "g=") + l.get(i) + (i == 0 ? "&n=1" : ""));
        mandar("/mapa?w=" + ultCierre + "&f=1");
    }

    private static String cod(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return ""; } }

    // cada segundo: la posición y lo que haya cambiado
    private final Runnable tic = new Runnable() { @Override public void run() {
        if (parado) return;
        ui.postDelayed(this, 1000);
        if (!listo) return;
        try {
            long ahora = System.currentTimeMillis();
            if (sv.mapaListo && ahora - sv.mapaCuando < 15000)
                mandar(String.format(Locale.US, "/mapa?x=%d&y=%d&r=%d", Math.round(sv.mapaDx), Math.round(sv.mapaDy), Math.round(sv.mapaRumbo)));
            if (ahora - yaHora > 30000) {
                yaHora = ahora; java.util.Calendar c = java.util.Calendar.getInstance();
                mandar("/hora?m=" + (c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)));
            }
            boolean nav = !sv.navDir.isEmpty() && ahora - sv.navCuando < 120000;
            String d = !nav ? "" : sv.navDir.equals("maps_izq") ? "izq" : sv.navDir.equals("maps_der") ? "der" : sv.navDir.equals("llegamos") ? "fin" : "recto";
            String g = "/giro?d=" + d + (nav && sv.navMetros >= 0 ? "&m=" + Math.round(sv.navMetros) : "") + (nav ? "&c=" + cod(sv.navCalle) : "");
            if (!g.equals(yaGiro)) { yaGiro = g; mandar(g); }
            String ll = "/llega?h=" + cod(nav ? sv.navLlega : "") + (nav && sv.navDestM >= 0 ? "&d=" + sv.navDestM : "");
            if (!ll.equals(yaLlega)) { yaLlega = ll; mandar(ll); }
            CubiEnergia en = CubiCarroDatos.cuentas;
            if (!Double.isNaN(en.energia)) {
                double h = en.horasParaLleno();
                String p = String.format(Locale.US, "/pila?b=%d&r=%d&c=%d&k=%.1f&p=%.1f", Math.round(en.bateria), Math.round(en.autonomiaKm()), en.cargando ? 1 : 0,
                        Double.isNaN(en.kw) ? 0 : en.kw, en.promedio())
                        + (Double.isNaN(en.rangoCarro) ? "" : "&w=" + Math.round(en.rangoCarro * CubiCarro.Lienzo.WLTP_DE_NEDC))
                        + (Double.isNaN(h) ? "" : String.format(Locale.US, "&h=%.2f", h));
                if (!p.equals(yaPila)) { yaPila = p; mandar(p); }
            }
            if (sv.climaGrados != Integer.MIN_VALUE) {
                String c = "/clima?t=" + sv.climaGrados + "&e=" + sv.climaEstado + "&l=" + cod(sv.climaLugar) + "&n=" + (sv.climaNoche ? 1 : 0);
                if (!c.equals(yaClima)) { yaClima = c; mandar(c); }
            }
        } catch (Exception ignored) { }
    } };
}
