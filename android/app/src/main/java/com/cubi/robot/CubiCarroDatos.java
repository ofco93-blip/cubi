package com.cubi.robot;

import android.content.pm.PackageManager;
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

// ENSAYO (rama prueba-obd): le pregunta al carro, por la vía oficial de Android Auto, la batería y la autonomía.
// El puerto OBD del Ora 5 no entrega nada (probado), así que esta es la otra fuente. Solo LEE; nada toca el carro.
// Es la misma pantalla del carro (CubiCarro) con este agregado encima, para no tocar ese archivo.
// ponytail: muestra el resultado en un aviso y lo guarda en las preferencias ("datos_carro"); si el carro sí entrega
// los datos, esto se integra en CubiCarro y se dibuja en la pantalla.
public class CubiCarroDatos extends CubiCarro {
    static final String TAG = "CubiDatos";
    static final String P_FUEL = "com.google.android.gms.permission.CAR_FUEL";
    static final String P_KM = "com.google.android.gms.permission.CAR_MILEAGE";

    @NonNull @Override public Session onCreateSession() {
        final Session s = super.onCreateSession();
        s.getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onStart(@NonNull LifecycleOwner o) {
                try { preguntar(s.getCarContext()); } catch (Throwable e) { Log.w(TAG, "no se pudo preguntar", e); }
            }
        });
        return s;
    }

    static boolean hecho, pedido;
    static final StringBuilder texto = new StringBuilder();

    static void preguntar(CarContext cc) {
        if (hecho) { new Handler(Looper.getMainLooper()).postDelayed(() -> avisar(cc), 4000); return; }
        int nivel = cc.getCarAppApiLevel();
        if (nivel < 3) { anotar(cc, "Android Auto nivel " + nivel + ": muy viejo para dar datos del carro"); avisar(cc); return; }
        boolean falta = ContextCompat.checkSelfPermission(cc, P_FUEL) != PackageManager.PERMISSION_GRANTED;
        if (falta && pedido) return;   // se pide una sola vez por arranque
        if (falta) {
            pedido = true;
            // el permiso se concede en el celular (con el carro quieto)
            cc.requestPermissions(Arrays.asList(P_FUEL, P_KM), (dados, negados) -> {
                if (dados.contains(P_FUEL)) leer(cc); else { anotar(cc, "Falta el permiso de datos del carro (acéptalo en el celular)"); avisar(cc); }
            });
            return;
        }
        leer(cc);
    }

    static void leer(CarContext cc) {
        hecho = true;
        Executor ex = ContextCompat.getMainExecutor(cc);
        CarInfo info = cc.getCarService(CarHardwareManager.class).getCarInfo();
        try { info.fetchModel(ex, m -> anotar(cc, "Carro: " + v(m.getManufacturer()) + " " + v(m.getName()) + " " + v(m.getYear()))); }
        catch (Throwable e) { anotar(cc, "Modelo: error " + e.getClass().getSimpleName()); }
        try { info.fetchEnergyProfile(ex, p -> anotar(cc, "Conectores: " + v(p.getEvConnectorTypes()) + " combustibles: " + v(p.getFuelTypes()))); }
        catch (Throwable e) { anotar(cc, "Perfil: error " + e.getClass().getSimpleName()); }
        try {
            final boolean[] primera = {true};
            info.addEnergyLevelListener(ex, n -> {
                String t = "Batería " + v(n.getBatteryPercent()) + " % · " + km(n.getRangeRemainingMeters()) + " · baja: " + v(n.getEnergyIsLow());
                energia = t;
                cc.getSharedPreferences("cubi", 0).edit().putString("datos_carro_energia", t).apply();
                Log.i(TAG, t);
                if (primera[0]) { primera[0] = false; anotar(cc, t); }
            });
        } catch (Throwable e) { anotar(cc, "Batería: error " + e.getClass().getSimpleName()); }
        try {
            final boolean[] primera = {true};
            info.addMileageListener(ex, k -> { if (primera[0]) { primera[0] = false; anotar(cc, "Kilometraje: " + v(k.getOdometerMeters()) + " m"); } });
        } catch (Throwable e) { anotar(cc, "Kilometraje: error " + e.getClass().getSimpleName()); }
        new Handler(Looper.getMainLooper()).postDelayed(() -> avisar(cc), 8000);   // da tiempo a que el carro conteste
    }

    // el valor, o por qué no vino: 2 = el carro no lo tiene, 3 = no disponible ahora
    static String v(CarValue<?> c) {
        if (c == null) return "(nada)";
        if (c.getStatus() == CarValue.STATUS_SUCCESS) return String.valueOf(c.getValue());
        return "(sin dato, estado " + c.getStatus() + ")";
    }

    static void anotar(CarContext cc, String linea) {
        Log.i(TAG, linea);
        synchronized (texto) { texto.append(linea).append('\n'); }
        cc.getSharedPreferences("cubi", 0).edit().putString("datos_carro", texto.toString()).apply();
    }

    static volatile String energia;

    static String km(CarValue<Float> c) {
        if (c == null || c.getStatus() != CarValue.STATUS_SUCCESS || c.getValue() == null) return "autonomía " + v(c);
        return Math.round(c.getValue() / 1000f) + " km";
    }

    // el aviso solo deja dos renglones: va lo que importa (batería y autonomía); lo demás queda en el registro
    static void avisar(CarContext cc) {
        String t = energia != null ? energia : "Batería: el carro todavía no la ha contestado";
        try { CarToast.makeText(cc, t, CarToast.LENGTH_LONG).show(); } catch (Throwable e) { Log.w(TAG, "aviso", e); }
    }
}
