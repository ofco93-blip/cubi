package com.cubi.robot;

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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

// Mapi: la pantalla redonda del mapa (debajo del volante). Va por su propia conexión de Bluetooth, aparte de la de Cubi,
// y le manda lo que dibuja: las calles (las mismas del mini mapa), dónde va el carro, el giro, la pila, el clima y la hora.
// Solo manda lo que cambió; la posición, cada segundo. También lo actualiza (el firmware sale en cada versión: mapi.ino.bin).
final class CubiMapi {
    static final UUID SRV = UUID.fromString("6e400101-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID RX = UUID.fromString("6e400102-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID TX = UUID.fromString("6e400103-b5a3-f393-e0a9-e50e24dcca9e");
    static final UUID OTA = UUID.fromString("6e400104-b5a3-f393-e0a9-e50e24dcca9e");
    static volatile CubiMapi yo;
    static boolean listo() { CubiMapi m = yo; return m != null && m.listo; }
    static String estado() { CubiMapi m = yo; return m == null ? "Apagado." : m.estado; }

    private final CubiService sv;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner; private ScanCallback scanCb;
    private BluetoothGatt gatt; private BluetoothGattCharacteristic rx, otaC;
    private volatile boolean listo, ocupado, parado;
    private static final class Paq { final boolean fw; final byte[] d; Paq(boolean f, byte[] x) { fw = f; d = x; } }
    private final ArrayDeque<Paq> cola = new ArrayDeque<>();
    private volatile String estado = "Buscando a Mapi…";
    private static volatile List<String> ultMapa; private static volatile int ultVias; private static volatile String ultCierre;
    private String yaGiro = "", yaLlega = "", yaPila = "", yaClima = "", yaLimite = "", yaAjuste = "", yaAlt = ""; private long yaHora, yaSinGps, yaCalle, yaLim;
    private volatile int limiteAhora = -1; private double limLa = Double.NaN, limLo;
    // la actualización
    private byte[] fw; private int enviados; private volatile boolean actualizando, terminando;

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
                        if (actualizando) estado = terminando ? "Mapi se reinició con la versión nueva." : "Se cortó la actualización: Mapi sigue con la versión que tenía.";
                        else estado = "Mapi se desconectó; lo busco otra vez.";
                        actualizando = terminando = false; fw = null;
                        ui.postDelayed(CubiMapi.this::buscar, 3000);
                    }
                }
                @Override public void onMtuChanged(BluetoothGatt g, int mtu, int st) { g.discoverServices(); }
                @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
                    BluetoothGattService s = g.getService(SRV);
                    if (s == null) { g.disconnect(); return; }
                    rx = s.getCharacteristic(RX); otaC = s.getCharacteristic(OTA);
                    BluetoothGattCharacteristic tx = s.getCharacteristic(TX);
                    if (tx != null) {                                   // los avisos de Mapi (las respuestas de la actualización)
                        g.setCharacteristicNotification(tx, true);
                        BluetoothGattDescriptor dsc = tx.getDescriptor(CubiService.CCCD);
                        if (dsc != null) { dsc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE); g.writeDescriptor(dsc); return; }
                    }
                    empezar();
                }
                @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int st) { empezar(); }
                @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) {
                    Paq p;
                    synchronized (cola) { p = enVuelo; enVuelo = null; ocupado = false; }
                    if (p != null && p.fw && actualizando) {
                        if (st != BluetoothGatt.GATT_SUCCESS) { fallo("Falló un pedazo del envío; Mapi sigue con la versión que tenía."); return; }
                        enviados += p.d.length;
                        estado = "Actualizando Mapi… " + (enviados * 100L / fw.length) + " %";
                        if (enviados >= fw.length) { terminando = true; mandar("/ota/fin"); }
                    }
                    siguiente();
                }
                @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) { if (Build.VERSION.SDK_INT < 33) llega(c.getValue()); }
                @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] v) { llega(v); }
            }, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) { gatt = null; estado = "Falta el permiso de Bluetooth."; }
    }
    private Paq enVuelo;

    private void empezar() {
        if (listo) return;
        listo = true; version = ""; estado = "Conectado · firmware anterior a la 2.0.400 (toca Actualizar Mapi).";
        yaGiro = yaLlega = yaPila = yaClima = yaLimite = yaAjuste = yaAlt = ""; yaHora = 0; yaCalle = 0;            // recién conectado: se manda todo otra vez
        mandar("/hola");                                               // ¿qué versión tiene? (la de antes no contesta)
        ui.post(this::mandarMapa);
    }
    private volatile String version = "";
    static String version() { CubiMapi m = yo; return m == null ? "" : m.version; }
    static boolean actualizando() { CubiMapi m = yo; return m != null && m.actualizando; }

    private void llega(byte[] v) {
        String s = new String(v, StandardCharsets.UTF_8).trim();
        if (s.startsWith("ver|")) { version = s.substring(4); if (!actualizando) estado = "Conectado · firmware de la versión " + version + "."; return; }
        if (!s.startsWith("ota|") || !actualizando) return;
        if (s.startsWith("ota|!")) { fallo("Mapi no aceptó la actualización: " + s.substring(5)); return; }
        if (terminando) { estado = "Mapi verificó la versión nueva y se está reiniciando…"; return; }
        if (enviados == 0) {                                          // aceptó el inicio: ahora sí va el archivo, en pedazos
            synchronized (cola) {
                for (int i = 0; i < fw.length; i += 240) {
                    byte[] p = new byte[Math.min(240, fw.length - i)];
                    System.arraycopy(fw, i, p, 0, p.length);
                    cola.add(new Paq(true, p));
                }
            }
            siguiente();
        }
    }
    private void fallo(String msg) {
        synchronized (cola) { cola.clear(); }
        actualizando = terminando = false; fw = null; estado = msg;
        mandar("/ota/cancelar");
    }

    // la actualización: el firmware de Mapi (de la última versión publicada)
    void ota(byte[] datos) {
        if (actualizando) return;
        if (!listo || otaC == null) { estado = "Mapi no está conectado (o tiene un firmware que no se actualiza por Bluetooth: ese se pone por el cable)."; return; }
        if (datos.length < 100000 || (datos[0] & 0xFF) != 0xE9) { estado = "Ese archivo no es un firmware de Mapi."; return; }
        String md5 = "";
        try { StringBuilder h = new StringBuilder(); for (byte x : MessageDigest.getInstance("MD5").digest(datos)) h.append(String.format("%02x", x)); md5 = h.toString(); } catch (Exception ignored) { }
        fw = datos; enviados = 0; terminando = false; actualizando = true;
        estado = "Actualizando Mapi… 0 %";
        try { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } catch (SecurityException ignored) { }
        synchronized (cola) { cola.clear(); }
        mandar("/ota/inicio?tam=" + datos.length + (md5.isEmpty() ? "" : "&md5=" + md5));
    }

    private void mandar(String s) {
        if (!listo) return;
        synchronized (cola) { if (cola.size() < 80) cola.add(new Paq(false, s.getBytes(StandardCharsets.UTF_8))); }
        siguiente();
    }
    private void siguiente() {
        synchronized (cola) {
            if (!listo || ocupado || cola.isEmpty() || gatt == null || rx == null) return;
            Paq p = cola.poll();
            BluetoothGattCharacteristic c = p.fw ? otaC : rx;
            c.setValue(p.d); c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);   // con confirmación: ninguno se pierde
            enVuelo = p;
            try { ocupado = gatt.writeCharacteristic(c); } catch (SecurityException e) { ocupado = false; }
            if (!ocupado) enVuelo = null;
        }
    }

    // las calles nuevas (las arma CubiService para el mini mapa): se guardan para mandarlas también al reconectar
    static void mapa(List<String> mensajes, int deVias, String cierre) {
        ultMapa = mensajes; ultVias = deVias; ultCierre = cierre;
        CubiMapi m = yo; if (m != null) m.ui.post(m::mandarMapa);
    }
    private void mandarMapa() {
        List<String> l = ultMapa;
        if (!listo || actualizando || l == null) return;
        for (int i = 0; i < l.size(); i++) mandar("/mapa?" + (i < ultVias ? "w=" : "g=") + l.get(i) + (i == 0 ? "&n=1" : ""));
        mandar("/mapa?w=" + ultCierre + "&f=1");
    }

    // "calle 5 norte" → "Calle 5 Norte"
    private static String titulo(String s) {
        StringBuilder b = new StringBuilder(); boolean ini = true;
        for (char ch : s.toCharArray()) { b.append(ini ? Character.toUpperCase(ch) : ch); ini = ch == ' '; }
        return b.toString();
    }
    private static String cod(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return ""; } }

    // cada segundo: la posición y lo que haya cambiado
    private final Runnable tic = new Runnable() { @Override public void run() {
        if (parado) return;
        ui.postDelayed(this, 1000);
        if (!listo || actualizando) return;
        try {
            long ahora = System.currentTimeMillis();
            // sin posición del GPS (adentro de la casa, o el GPS apagado): el mapa sale con la última ubicación conocida
            if ((!sv.mapaListo || ahora - sv.mapaCuando > 10000) && ahora - yaSinGps > 10000) { yaSinGps = ahora; sv.mapaSinGps(); }
            if (sv.mapaListo && ahora - sv.mapaCuando < 15000)
                mandar(String.format(Locale.US, "/mapa?x=%d&y=%d&r=%d", Math.round(sv.mapaDx), Math.round(sv.mapaDy), Math.round(sv.mapaRumbo)));
            if (ahora - yaHora > 30000) {
                yaHora = ahora; java.util.Calendar c = java.util.Calendar.getInstance();
                mandar("/hora?m=" + (c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)));
                yaGiro = "";                                               // y el giro otra vez, por si Mapi se reinició
            }
            // hay ruta mientras siga el aviso de navegación de Maps (o haya destino puesto en Cubi): al quitarla, Mapi vuelve al mapa normal
            // la flecha solo mientras alguien navega de verdad: el Google de Mascota Auto con destino puesto, o el aviso de
            // Maps/Waze en el celular. Antes bastaba un destino guardado y la flecha se quedaba al quitar la navegación.
            boolean nav = !sv.navDir.isEmpty() && ((CubiGoogle.guiando() && sv.destinoPunto() != null) || (Oyente.rutaViva() && ahora - sv.navCuando < 120000));
            String d = !nav ? "" : sv.navDir.equals("maps_izq") ? "izq" : sv.navDir.equals("maps_der") ? "der" : sv.navDir.equals("llegamos") ? "fin" : "recto";
            // "Dirígete al nordeste hacia la Calle 5" (el primer aviso de Maps, sin metros): en Mapi va como "Sigue por Calle 5"
            String calle = sv.navCalle; boolean sigue = false;
            if (nav && sv.navMetros < 15) {                     // Google lo manda con 0 m: salía "ya · Dirígete al nordeste."
                String u = sv.navUltima.toLowerCase(Locale.ROOT).replaceAll("[.·•…].*$", "").trim();
                if (u.startsWith("dir") || u.startsWith("head") || u.startsWith("sig") || u.startsWith("contin")) {
                    java.util.regex.Matcher hm = java.util.regex.Pattern.compile("(?:hacia|por|toward|on) (?:la |el )?(.+)$").matcher(u);
                    d = "recto"; sigue = true; calle = hm.find() ? "Sigue por " + titulo(hm.group(1)) : "Sigue derecho";
                    if (calle.length() > 30) calle = calle.substring(0, 30).trim();
                }
            }
            String g = "/giro?d=" + d + (nav && sv.navMetros >= 0 && !sigue ? "&m=" + Math.round(sv.navMetros) : "") + (nav ? "&c=" + cod(calle) : "");
            if (!g.equals(yaGiro)) { yaGiro = g; mandar(g); }
            // cuánto falta y a qué hora se llega: del Google de Mascota Auto si guía; si no, del aviso de Maps/Waze; si no, de la
            // ruta de Cubi. Antes solo se leía el aviso de Maps y con el Google de Mascota Auto no salía nada.
            int faltan = -1, faltaM = -1; String llega = "";
            if (nav && CubiGoogle.guiando()) {
                CubiGoogle.falta();
                if (CubiGoogle.faltaSeg >= 0) {
                    faltan = Math.max(1, Math.round(CubiGoogle.faltaSeg / 60f)); faltaM = CubiGoogle.faltaMet;
                    java.util.Calendar c = java.util.Calendar.getInstance(); c.add(java.util.Calendar.SECOND, CubiGoogle.faltaSeg);
                    llega = new java.text.SimpleDateFormat(android.text.format.DateFormat.is24HourFormat(sv) ? "H:mm" : "h:mm", Locale.US).format(c.getTime());
                }
            } else if (nav && !sv.navLlega.isEmpty()) {
                llega = sv.navLlega; faltaM = sv.navDestM;
                // los minutos, de la hora de llegada ("8:58", "8:58 p. m." o "20:58"); el viaje cabe en 12 h
                java.util.regex.Matcher hl = java.util.regex.Pattern.compile("(\\d{1,2})[:.](\\d{2})").matcher(llega);
                if (hl.find()) {
                    java.util.Calendar c = java.util.Calendar.getInstance();
                    int ahoraM = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE), llM = Integer.parseInt(hl.group(1)) * 60 + Integer.parseInt(hl.group(2));
                    faltan = ((llM - ahoraM) % 720 + 720) % 720;
                }
            }
            // ponytail: la ruta propia de Cubi ya no se usa aquí: puede ser otro destino (el guardado) y daba 15 min donde Maps decía 5
            String ll = "/llega?h=" + cod(llega) + (faltaM >= 0 ? "&d=" + faltaM : "") + (faltan >= 0 ? "&t=" + faltan : "");
            final String fuente = !nav ? "sin ruta" : CubiGoogle.guiando() ? "google_mascota" : !sv.navLlega.isEmpty() ? "aviso_maps" : "sin datos";
            CubiCarroDatos.cada(sv, "MAPI_LLEGA", 0, () -> fuente + " | " + ll);   // caja negra: de dónde salió lo que muestra Mapi
            if (!ll.equals(yaLlega)) { yaLlega = ll; mandar(ll); }
            CubiEnergia en = CubiCarroDatos.cuentas;
            final boolean hayPila = !Double.isNaN(en.energia);
            CubiCarroDatos.cada(sv, "MAPI_PILA", 0, () -> hayPila ? "con datos del carro" : "sin datos del carro (Mascota Auto no abierta en Android Auto)");
            if (!Double.isNaN(en.energia)) {
                double h = en.horasParaLleno();
                String p = String.format(Locale.US, "/pila?b=%d&r=%d&c=%d&k=%.1f&p=%.1f", Math.round(en.bateria), Math.round(en.autonomiaKm()), en.cargando ? 1 : 0,
                        Double.isNaN(en.kw) ? 0 : en.kw, en.promedio())
                        + (Double.isNaN(en.rangoCarro) ? "" : "&w=" + Math.round(en.rangoCarro * CubiCarro.Lienzo.WLTP_DE_NEDC))
                        + (Double.isNaN(h) ? "" : String.format(Locale.US, "&h=%.2f", h));
                if (!p.equals(yaPila)) { yaPila = p; mandar(p); }
            }
            // con Google, andando: la calle por donde va (cada minuto) y el límite de velocidad de la vía (cada 20 s)
            if (CubiGoogleApis.hay(sv) && sv.kmh > 5 && !Double.isNaN(sv.posLat)) {
                final double la = sv.posLat, lo = sv.posLon;
                if (ahora - yaCalle > 60000) { yaCalle = ahora; new Thread(() -> { String[] dir = CubiGoogleApis.direccion(sv, la, lo); if (dir != null && !dir[1].isEmpty()) ui.post(() -> mandar("/calle?c=" + cod(dir[1]))); }).start(); }
                if (ahora - yaLim > 20000 && !CubiGoogleApis.sinLimites && !Double.isNaN(limLa)) {
                    yaLim = ahora; final double a0 = limLa, o0 = limLo;
                    new Thread(() -> limiteAhora = CubiGoogleApis.limite(sv, a0, o0, la, lo)).start();
                }
                limLa = la; limLo = lo;
            }
            android.content.SharedPreferences pr = sv.getSharedPreferences("cubi", 0);   // el zoom y la flecha (pestaña Mapi)
            String aj = String.format(Locale.US, "/ajuste?z=%.2f&f=%.2f&g=%.0f&e=%d", pr.getFloat("mapiZoom", 1), pr.getFloat("mapiFlecha", 1),
                    pr.getFloat("mapiGiro", 0), Math.round(pr.getFloat("mapiEstilo", 0)));
            if (!aj.equals(yaAjuste)) { yaAjuste = aj; mandar(aj); }
            int alt = sv.altura != Integer.MIN_VALUE ? sv.altura : sv.climaAltura;   // la altura del GPS (o la del clima)
            String al = "/alt" + (alt == Integer.MIN_VALUE ? "" : "?m=" + alt);
            if (!al.equals(yaAlt)) { yaAlt = al; mandar(al); }
            String lm = "/limite?v=" + limiteAhora + "&x=" + (limiteAhora > 0 && sv.kmh > limiteAhora + 5 ? 1 : 0);   // x: va pasado del límite
            if (!lm.equals(yaLimite)) { yaLimite = lm; mandar(lm); }
            if (sv.climaGrados != Integer.MIN_VALUE) {
                String c = "/clima?t=" + sv.climaGrados + "&e=" + sv.climaEstado + "&l=" + cod(sv.climaLugar) + "&n=" + (sv.climaNoche ? 1 : 0);
                if (!c.equals(yaClima)) { yaClima = c; mandar(c); }
            }
        } catch (Exception ignored) { }
    } };
}
