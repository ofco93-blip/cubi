package com.cubi.robot;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Los radares de velocidad (fotomultas) de OpenStreetMap: nodos highway=speed_camera, con su límite si lo tienen.
// Se piden a Overpass alrededor del carro (8 km, otra vez al alejarse 3 km) y también con los mapas sin internet;
// todos se guardan en el celular (radares.json), así que lo ya visto sirve sin señal.
// ponytail: OpenStreetMap no tiene todos los radares de Colombia; los que falten se pueden agregar allá y llegan solos.
final class CubiRadares {
    static final class Radar { final double la, lo; final int max; Radar(double la, double lo, int max) { this.la = la; this.lo = lo; this.max = max; } }
    private static final Map<String, Radar> todos = new LinkedHashMap<>();
    static volatile int version;                       // sube cada vez que cambia la lista (el mapa de Google repinta los íconos)
    private static boolean cargado; private static volatile boolean pidiendo;
    private static double pLat = Double.NaN, pLon; private static long pT;

    static synchronized List<Radar> lista() { return new ArrayList<>(todos.values()); }

    private static synchronized void cargar(Context c) {
        if (cargado) return;
        cargado = true;
        try {
            File f = new File(c.getFilesDir(), "radares.json");
            if (!f.exists()) return;
            byte[] bs = new byte[(int) f.length()]; try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { int o = 0, k; while (o < bs.length && (k = in.read(bs, o, bs.length - o)) > 0) o += k; }
            JSONArray a = new JSONArray(new String(bs, "UTF-8"));
            for (int i = 0; i < a.length(); i++) { JSONArray r = a.getJSONArray(i); poner(r.getDouble(0), r.getDouble(1), r.getInt(2)); }
            version++;
        } catch (Throwable ignored) { }
    }
    private static synchronized void guardar(Context c) {
        try {
            JSONArray a = new JSONArray();
            for (Radar r : todos.values()) a.put(new JSONArray().put(r.la).put(r.lo).put(r.max));
            try (FileOutputStream o = new FileOutputStream(new File(c.getFilesDir(), "radares.json"))) { o.write(a.toString().getBytes("UTF-8")); }
        } catch (Throwable ignored) { }
    }
    private static void poner(double la, double lo, int max) { todos.put(String.format(Locale.US, "%.5f,%.5f", la, lo), new Radar(la, lo, max)); }

    // lo que contesta Overpass, a la lista
    private static int sumar(String consulta) throws Exception {
        String u = "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(consulta, "UTF-8");
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setConnectTimeout(12000); h.setReadTimeout(30000); h.setRequestProperty("User-Agent", "robot-app");
        if (h.getResponseCode() != 200) throw new java.io.IOException("HTTP " + h.getResponseCode());
        String t; try (java.io.InputStream in = h.getInputStream()) { java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(); byte[] b = new byte[16384]; int k; while ((k = in.read(b)) > 0) o.write(b, 0, k); t = o.toString("UTF-8"); }
        JSONArray els = new JSONObject(t).getJSONArray("elements"); int n = 0;
        synchronized (CubiRadares.class) {
            for (int i = 0; i < els.length(); i++) {
                JSONObject e = els.getJSONObject(i); if (!e.has("lat")) continue;
                JSONObject tg = e.optJSONObject("tags"); int max = -1;
                if (tg != null) try { max = Integer.parseInt(tg.optString("maxspeed").replaceAll("[^0-9].*$", "")); } catch (Exception ignored) { }
                poner(e.getDouble("lat"), e.getDouble("lon"), max); n++;
            }
            version++;
        }
        return n;
    }

    // con cada posición del GPS: si se alejó 3 km de donde se pidió (y pasó un minuto), se piden los de alrededor
    static void cerca(final Context c, final double lat, final double lon) {
        cargar(c);
        long ahora = System.currentTimeMillis();
        if (pidiendo || ahora - pT < 60000) return;
        if (!Double.isNaN(pLat) && metros(pLat, pLon, lat, lon) < 3000) return;
        pidiendo = true; pT = ahora;
        new Thread(() -> {
            try { sumar(String.format(Locale.US, "[out:json][timeout:15];node[\"highway\"=\"speed_camera\"](around:8000,%.5f,%.5f);out;", lat, lon)); pLat = lat; pLon = lon; guardar(c); }
            catch (Throwable ignored) { }
            finally { pidiendo = false; }
        }).start();
    }
    // los de un rectángulo (sur, oeste, norte, este), con la descarga de mapas sin internet; espera la respuesta
    static int zona(Context c, double s, double w, double n, double e) {
        cargar(c);
        try { int k = sumar(String.format(Locale.US, "[out:json][timeout:25];node[\"highway\"=\"speed_camera\"](%.4f,%.4f,%.4f,%.4f);out;", s, w, n, e)); guardar(c); return k; }
        catch (Throwable t) { return -1; }
    }

    // el radar que viene: el más cercano adelante (a menos de 700 m y dentro de 40° del rumbo). Sin rumbo, el más cercano a 300 m.
    static Radar adelante(double lat, double lon, double rumbo, double[] dist) {
        Radar mejor = null; double dm = 1e9;
        for (Radar r : lista()) {
            double d = metros(lat, lon, r.la, r.lo);
            if (d > (Double.isNaN(rumbo) ? 300 : 700) || d >= dm) continue;
            if (!Double.isNaN(rumbo) && d > 40) {
                double b = Math.toDegrees(Math.atan2((r.lo - lon) * Math.cos(Math.toRadians(lat)), r.la - lat));
                if (Math.abs(((b - rumbo) % 360 + 540) % 360 - 180) > 40) continue;
            }
            mejor = r; dm = d;
        }
        if (dist != null) dist[0] = dm;
        return mejor;
    }
    static double metros(double la0, double lo0, double la1, double lo1) { return Math.hypot((la1 - la0) * 111320, (lo1 - lo0) * 111320 * Math.cos(Math.toRadians(la0))); }
}
