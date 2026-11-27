package com.cubi.robot;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Session;
import androidx.car.app.hardware.CarHardwareManager;
import androidx.car.app.hardware.common.CarValue;
import androidx.car.app.hardware.info.CarInfo;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import java.util.Arrays;
import java.util.concurrent.Executor;

// ENSAYO (rama prueba-obd): le pregunta al carro, por la vía oficial de Android Auto, la batería y la autonomía, y con
// eso y el GPS saca el consumo, la autonomía dinámica y la carga (las cuentas van en CubiEnergia).
// El puerto OBD del Ora 5 no entrega nada (probado), así que esta es la fuente. Solo LEE; nada toca el carro.
// Es la misma pantalla del carro (CubiCarro) con este agregado encima, para no tocar ese archivo.
// ponytail: muestra el resultado en un aviso y lo deja en las preferencias y en un registro; cuando se apruebe cómo
// se ve, esto se integra en CubiCarro y se dibuja en la pantalla.
public class CubiCarroDatos extends CubiCarro {
    static final String TAG = "CubiDatos";
    static final String P_FUEL = "com.google.android.gms.permission.CAR_FUEL";

    static final CubiEnergia cuentas = new CubiEnergia();   // el promedio vive mientras viva la app y se guarda en las preferencias
    static boolean cargado, pedido, avisoCarga;
    static String crudo = "";
    static Location antes;
    static LocationListener gps;

    @NonNull @Override public Session onCreateSession() {
        final Session s = super.onCreateSession();
        s.getLifecycle().addObserver(new DefaultLifecycleObserver() {
            // cada conexión con el carro es un viaje nuevo y hay que volver a pedirle los datos
            @Override public void onCreate(@NonNull LifecycleOwner o) {
                try { empezar(s.getCarContext()); } catch (Throwable e) { Log.w(TAG, "no se pudo empezar", e); }
            }
            @Override public void onStart(@NonNull LifecycleOwner o) {
                final CarContext cc = s.getCarContext();
                new Handler(Looper.getMainLooper()).postDelayed(() -> avisar(cc), 6000);   // da tiempo a que el carro conteste
            }
            @Override public void onDestroy(@NonNull LifecycleOwner o) {
                try { if (gps != null) ((LocationManager) s.getCarContext().getSystemService(Context.LOCATION_SERVICE)).removeUpdates(gps); } catch (Throwable e) { }
                gps = null; antes = null;
            }
        });
        return s;
    }

    static void empezar(CarContext cc) {
        SharedPreferences p = cc.getSharedPreferences("cubi", 0);
        if (!cargado) {
            cargado = true;
            cuentas.promKWh = p.getFloat("energia_prom_kwh", 0); cuentas.promKm = p.getFloat("energia_prom_km", 0);
            cuentas.capacidad = p.getFloat("energia_capacidad", 58.3f); cuentas.semilla = p.getFloat("energia_semilla", 14f);
            registrar(cc, "AUTOPRUEBA " + CubiEnergia.autoprueba());
        }
        cuentas.viajeKWh = 0; cuentas.viajeKm = 0; cuentas.energia = Double.NaN; cuentas.cargando = false; cuentas.kw = Double.NaN;
        crudo = ""; avisoCarga = false;
        registrar(cc, "CONEXION nivel=" + cc.getCarAppApiLevel());
        if (cc.getCarAppApiLevel() < 3) { registrar(cc, "Android Auto muy viejo para dar datos del carro"); return; }
        if (ContextCompat.checkSelfPermission(cc, P_FUEL) == PackageManager.PERMISSION_GRANTED) { oir(cc); return; }
        if (pedido) return;   // se pide una sola vez por arranque
        pedido = true;
        // el permiso se concede en el celular (con el carro quieto): sale como "Información del vehículo"
        cc.requestPermissions(Arrays.asList(P_FUEL), (dados, negados) -> {
            if (dados.contains(P_FUEL)) oir(cc); else registrar(cc, "Falta el permiso de datos del carro (acéptalo en el celular)");
        });
    }

    static void oir(CarContext cc) {
        Executor ex = ContextCompat.getMainExecutor(cc);
        CarInfo info = cc.getCarService(CarHardwareManager.class).getCarInfo();
        try {
            info.addEnergyLevelListener(ex, n -> {
                String t = "ENERGIA bat=" + v(n.getBatteryPercent()) + " rango_m=" + v(n.getRangeRemainingMeters());
                if (t.equals(crudo)) return;   // el carro repite el dato unas 3 veces por segundo: solo se atiende si cambió
                crudo = t;
                registrar(cc, t);
                Float bat = valor(n.getBatteryPercent()), rango = valor(n.getRangeRemainingMeters());
                if (bat == null) return;
                SharedPreferences p = cc.getSharedPreferences("cubi", 0);
                if (Double.isNaN(cuentas.energia)) {   // primera lectura de esta conexión: ¿cambió mientras Cubi no miraba?
                    float ultima = p.getFloat("energia_bat", -1);
                    if (ultima >= 0 && Math.abs(bat - ultima) >= 2) registrar(cc, "TRAMO SIN REGISTRO de " + ultima + " a " + bat + " %");
                }
                cuentas.lectura(System.currentTimeMillis(), bat, rango == null ? Double.NaN : rango / 1000.0);
                String linea = cuentas.linea();
                registrar(cc, "CUENTAS " + linea + " | viaje_km=" + cuentas.viajeKm + " viaje_kwh=" + cuentas.viajeKWh);
                // lo último que se supo del carro: lo leerán la app del celular y el robot
                p.edit().putFloat("energia_bat", bat).putLong("energia_hora", System.currentTimeMillis()).putString("energia_linea", linea)
                        .putBoolean("energia_cargando", cuentas.cargando)
                        .putFloat("energia_prom_kwh", (float) cuentas.promKWh).putFloat("energia_prom_km", (float) cuentas.promKm).apply();
                if (!cuentas.cargando) avisoCarga = false;
                else if (!avisoCarga && !Double.isNaN(cuentas.kw)) { avisoCarga = true; avisar(cc); }   // ya se sabe a cuánto carga
            });
        } catch (Throwable e) { registrar(cc, "Batería: error " + e.getClass().getSimpleName()); }
        // ¿el carro dice si el cable de carga está puesto? (no todos lo contestan)
        try { info.addEvStatusListener(ex, c -> registrar(cc, "CARGADOR tapa_abierta=" + v(c.getEvChargePortOpen()) + " cable_puesto=" + v(c.getEvChargePortConnected()))); }
        catch (Throwable e) { registrar(cc, "Cargador: error " + e.getClass().getSimpleName()); }
        // los kilómetros salen del GPS: el kilometraje que manda este carro por Android Auto es basura
        if (ContextCompat.checkSelfPermission(cc, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            registrar(cc, "Sin permiso de GPS: no hay consumo, solo batería"); return;
        }
        try {
            LocationManager lm = (LocationManager) cc.getSystemService(Context.LOCATION_SERVICE);
            if (gps != null) lm.removeUpdates(gps);
            antes = null;
            gps = l -> {
                if (l.getAccuracy() > 30) return;
                boolean anda = l.hasSpeed() && l.getSpeed() >= 1f;   // parado, el GPS baila y sumaría metros falsos
                if (antes != null && anda) cuentas.metros(antes.distanceTo(l));
                if (antes == null || anda) antes = l;
            };
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, gps, Looper.getMainLooper());
        } catch (Throwable e) { registrar(cc, "GPS: error " + e.getClass().getSimpleName()); }
    }

    static Float valor(CarValue<Float> c) { return c != null && c.getStatus() == CarValue.STATUS_SUCCESS ? c.getValue() : null; }

    // el valor, o por qué no vino: 2 = el carro no lo tiene, 3 = no disponible ahora
    static String v(CarValue<?> c) {
        if (c == null) return "(nada)";
        if (c.getStatus() == CarValue.STATUS_SUCCESS) return String.valueOf(c.getValue());
        return "(sin dato, estado " + c.getStatus() + ")";
    }

    // Registro en el celular: Android/data/com.cubi.robot/files/datos_carro.txt (se lee después por cable).
    // Solo escribe cuando la línea cambia. ponytail: tope de 2 MB y deja de escribir; si se vuelve fijo, rotar el archivo.
    static String ultima = "";
    static synchronized void registrar(CarContext cc, String linea) {
        Log.i(TAG, linea);
        if (linea.equals(ultima)) return;
        ultima = linea;
        try {
            java.io.File f = new java.io.File(cc.getExternalFilesDir(null), "datos_carro.txt");
            if (f.length() > 2_000_000) return;
            String hora = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) { w.write(hora + " " + linea + "\n"); }
        } catch (Throwable e) { Log.w(TAG, "registro", e); }
    }

    // el aviso solo deja dos renglones: va lo que importa; lo demás queda en el registro
    static void avisar(CarContext cc) {
        try { CarToast.makeText(cc, cuentas.linea(), CarToast.LENGTH_LONG).show(); } catch (Throwable e) { Log.w(TAG, "aviso", e); }
    }
}
