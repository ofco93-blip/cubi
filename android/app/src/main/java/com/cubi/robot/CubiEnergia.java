package com.cubi.robot;

import java.util.Locale;

// Las cuentas de energía del carro, sin nada de Android adentro: consumo del viaje, promedio, autonomía dinámica y carga.
// Entra lo único que el Ora 5 entrega por Android Auto (porcentaje entero y autonomía en km) más los metros del GPS.
// Medido en el carro (2026-11-26): la autonomía del carro es fija, ~4,97 km por cada 1 %, así que sirve de batería
// "fina" (1 km = 0,2 %); el kilometraje que manda es basura, por eso la distancia viene del GPS.
final class CubiEnergia {
    // ponytail: constante medida en una sola carga (34-37 %); si otro carro o una actualización la cambia, se ve en
    // que rango/4,97 se aleja del porcentaje y entonces se usa el porcentaje entero. Calibrarla sola si hace falta.
    static final double KM_POR_PUNTO = 4.97;
    static final double KM_MEMORIA = 300;     // el promedio recuerda más o menos los últimos 300 km
    static final double KM_PESO = 20;         // al arrancar, el promedio pesa como 20 km ya recorridos

    double capacidad = 58.3;   // kWh. ponytail: es la total de fábrica; la utilizable puede ser ~5 % menos (ajuste en la app)
    double semilla = 14;       // kWh/100 km mientras no haya historial propio
    double promKWh, promKm;    // historial (se guarda entre viajes)

    double viajeKWh, viajeKm;  // desde que arrancó esta sesión
    double energia = Double.NaN, bateria = Double.NaN, rangoCarro = Double.NaN;
    boolean cargando; double kw = Double.NaN;
    private double kmLectura; private long cargaT0; private double cargaE0;

    // Una lectura del carro. rangoKm puede venir NaN.
    void lectura(long ahoraMs, double bat, double rangoKm) {
        double fino = rangoKm / KM_POR_PUNTO;
        double soc = !Double.isNaN(fino) && Math.abs(fino - bat) <= 1.2 ? fino : bat;
        double e = soc / 100 * capacidad;
        bateria = bat; rangoCarro = rangoKm;
        if (Double.isNaN(energia)) { energia = e; kmLectura = viajeKm; return; }
        double gasto = energia - e;                       // positivo: se gastó
        boolean quieto = viajeKm - kmLectura < 0.03;
        if (gasto < 0 && quieto) {                        // sube estando quieto: está cargando (no cuenta como consumo)
            if (!cargando) { cargando = true; cargaT0 = ahoraMs; cargaE0 = e; kw = Double.NaN; }
            else if (ahoraMs - cargaT0 >= 60000) kw = (e - cargaE0) / ((ahoraMs - cargaT0) / 3600000.0);
        } else if (gasto != 0 || !quieto) {               // andando (o gastando parado): cuenta; frenando en bajada resta
            cargando = false; kw = Double.NaN;
            viajeKWh += gasto; promKWh += gasto;
        }
        energia = e; kmLectura = viajeKm;
    }

    void metros(double m) {
        viajeKm += m / 1000; promKm += m / 1000;
        if (promKm > KM_MEMORIA) { promKWh *= KM_MEMORIA / promKm; promKm = KM_MEMORIA; }
    }

    double consumoViaje() { return viajeKm >= 2 ? viajeKWh / viajeKm * 100 : Double.NaN; }   // los primeros km saltan mucho
    double promedio() { return promKm >= 5 ? limitar(promKWh / promKm * 100) : semilla; }
    // lo que se usa para la autonomía: el promedio al arrancar y cada vez más lo de este viaje
    double consumo() { return limitar((promedio() * KM_PESO + viajeKWh * 100) / (KM_PESO + viajeKm)); }
    double autonomiaKm() { return energia / consumo() * 100; }
    // ponytail: lineal; en la realidad la carga se frena después del 80 %, así que arriba de eso tarda más
    double horasParaLleno() { return cargando && kw > 0.3 ? (capacidad - energia) / kw : Double.NaN; }
    static double limitar(double c) { return Math.max(8, Math.min(35, c)); }

    // un renglón corto para avisos y para el registro
    String linea() {
        if (Double.isNaN(energia)) return "Batería: sin dato";
        String b = String.format(Locale.US, "%.0f %%", bateria);
        if (cargando) {
            double h = horasParaLleno();
            return Double.isNaN(h) ? "Cargando · " + b
                    : String.format(Locale.US, "Cargando %.1f kW · %s · lleno en %d h %02d min", kw, b, (int) h, (int) (h * 60) % 60);
        }
        double v = consumoViaje();
        return String.format(Locale.US, "%s · %.0f km reales (carro: %.0f) · %s%.1f prom",
                b, autonomiaKm(), rangoCarro, Double.isNaN(v) ? "" : String.format(Locale.US, "%.1f viaje · ", v), promedio());
    }

    // Comprobación mínima: un viaje de 10 km a 15 kWh/100 km y luego una carga a ~5 kW. Devuelve "ok" o qué falló.
    static String autoprueba() {
        CubiEnergia c = new CubiEnergia();
        long t = 0; double e = 0.50 * c.capacidad;
        c.lectura(t, 50, e / c.capacidad * 100 * KM_POR_PUNTO);
        for (int i = 0; i < 100; i++) {                    // 100 tramos de 100 m, 0,015 kWh cada uno
            c.metros(100); e -= 0.015; t += 6000;
            c.lectura(t, Math.floor(e / c.capacidad * 100), Math.round(e / c.capacidad * 100 * KM_POR_PUNTO));
        }
        double v = c.consumoViaje();
        if (!(v > 13.5 && v < 16.5)) return "consumo del viaje " + v;
        double a = c.autonomiaKm(), esperada = e / 14.33 * 100;   // mezcla: (14*20 + 15*10) / 30
        if (Math.abs(a - esperada) > 12) return "autonomía " + a + " en vez de " + esperada;
        for (int i = 0; i < 5; i++) {                      // quieto: sube 1 km de autonomía cada 80 s (~5,2 kW)
            e += KM_POR_PUNTO > 0 ? c.capacidad / 100 / KM_POR_PUNTO : 0; t += 80000;
            c.lectura(t, Math.floor(e / c.capacidad * 100), Math.round(e / c.capacidad * 100 * KM_POR_PUNTO));
        }
        if (!c.cargando) return "no notó la carga";
        if (!(c.kw > 4.2 && c.kw < 6.4)) return "potencia de carga " + c.kw;
        if (Math.abs(c.consumoViaje() - v) > 0.01) return "la carga cambió el consumo";
        return "ok";
    }
}
