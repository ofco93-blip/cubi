package com.cubi.robot;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Los servicios de Google que usa Cubi con la llave del usuario (la misma del mapa de Google). Cada uno devuelve null
// (o -1) si no hay llave, si el servicio no está activado en su Google Cloud o si falla: quien llama sigue con lo de
// siempre (OpenStreetMap, OSRM, Open-Meteo). Todos tienen cuota gratis al mes; lo que pase se cobra a su cuenta.
//  - Sugerencias al escribir (Places Autocomplete) y el punto del sitio escogido (Place Details): 10.000 + 10.000 gratis.
//  - Rutas con tráfico (Routes, con el tráfico en la línea): 5.000 gratis.
//  - Clima (Weather): 10.000 gratis.   - Dirección y calle (Geocoding): 10.000 gratis.
//  - Límite de velocidad (Roads): 5.000 gratis, pero Google solo lo da a ciertos proyectos: si dice que no, se apaga.
// A Google le llega la ubicación (para eso son), igual que al usar su mapa.
final class CubiGoogleApis {
    private static String huellaApp;
    static volatile boolean sinLimites;                        // Google dijo que este proyecto no puede pedir límites de velocidad

    static boolean hay(Context c) { return !CubiGoogle.llave(c).isEmpty(); }

    // la huella (SHA-1) de la firma de la app: Google la pide si la llave está restringida a esta app
    private static String huella(Context c) {
        if (huellaApp != null) return huellaApp;
        try {
            android.content.pm.Signature[] ss = Build.VERSION.SDK_INT >= 28
                    ? c.getPackageManager().getPackageInfo(c.getPackageName(), android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.getApkContentsSigners()
                    : c.getPackageManager().getPackageInfo(c.getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES).signatures;
            StringBuilder sb = new StringBuilder();
            for (byte x : java.security.MessageDigest.getInstance("SHA-1").digest(ss[0].toByteArray())) sb.append(String.format("%02X", x));
            return huellaApp = sb.toString();
        } catch (Throwable t) { return huellaApp = ""; }
    }

    // una consulta: GET si cuerpo es null, POST con JSON si no. Devuelve el JSON, o null si no respondió 200.
    static JSONObject pedir(Context c, String url, String cuerpo, String campos) {
        String k = CubiGoogle.llave(c);
        if (k.isEmpty()) return null;
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(8000); h.setReadTimeout(10000);
            h.setRequestProperty("X-Goog-Api-Key", k);
            h.setRequestProperty("X-Android-Package", c.getPackageName());
            String hu = huella(c); if (!hu.isEmpty()) h.setRequestProperty("X-Android-Cert", hu);
            if (campos != null) h.setRequestProperty("X-Goog-FieldMask", campos);
            if (cuerpo != null) {
                h.setRequestMethod("POST"); h.setDoOutput(true); h.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream o = h.getOutputStream()) { o.write(cuerpo.getBytes("UTF-8")); }
            }
            int cod = h.getResponseCode();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = cod < 400 ? h.getInputStream() : h.getErrorStream()) { byte[] buf = new byte[8192]; for (int n; in != null && (n = in.read(buf)) > 0; ) b.write(buf, 0, n); }
            if (cod != 200) { ultimoError = url.replaceAll("[?].*", "") + " → " + cod; return null; }
            return new JSONObject(b.toString("UTF-8"));
        } catch (Throwable t) { ultimoError = t.toString(); return null; }
    }
    static volatile String ultimoError = "";
    private static String km(double m) { return m < 0 ? "" : m < 10000 ? String.format(Locale.US, "%.1f km", m / 1000).replace('.', ',') : Math.round(m / 1000) + " km"; }

    // ---------- sugerencias mientras se escribe: {nombre, dónde queda, "pid:ID", "3,2 km"} ----------
    static List<String[]> sugerencias(Context c, String q, double lat, double lon, String sesion) {
        try {
            JSONObject b = new JSONObject().put("input", q).put("languageCode", "es").put("sessionToken", sesion);
            if (!Double.isNaN(lat)) {
                JSONObject centro = new JSONObject().put("latitude", lat).put("longitude", lon);
                b.put("locationBias", new JSONObject().put("circle", new JSONObject().put("center", centro).put("radius", 30000.0))).put("origin", centro);
            }
            JSONObject r = pedir(c, "https://places.googleapis.com/v1/places:autocomplete", b.toString(), null);
            if (r == null) return null;
            ArrayList<String[]> l = new ArrayList<>();
            JSONArray s = r.optJSONArray("suggestions");
            for (int i = 0; s != null && i < s.length(); i++) {
                JSONObject p = s.getJSONObject(i).optJSONObject("placePrediction");
                if (p == null) continue;
                JSONObject f = p.optJSONObject("structuredFormat");
                String nom = f != null && f.optJSONObject("mainText") != null ? f.getJSONObject("mainText").optString("text") : p.optJSONObject("text") != null ? p.getJSONObject("text").optString("text") : "";
                String sec = f != null && f.optJSONObject("secondaryText") != null ? f.getJSONObject("secondaryText").optString("text") : "";
                l.add(new String[]{nom, sec, "pid:" + p.optString("placeId"), km(p.optDouble("distanceMeters", -1))});
            }
            return l;
        } catch (Throwable t) { return null; }
    }
    // el punto del sitio escogido: "lat,lon" (cierra la sesión de sugerencias)
    static String punto(Context c, String placeId, String sesion) {
        JSONObject r = pedir(c, "https://places.googleapis.com/v1/places/" + placeId + "?languageCode=es&sessionToken=" + sesion, null, "location");
        JSONObject l = r == null ? null : r.optJSONObject("location");
        return l == null ? null : String.format(Locale.US, "%.6f,%.6f", l.optDouble("latitude"), l.optDouble("longitude"));
    }

    // ---------- clima: {grados, de día (1/0)} y el estado con las palabras de Cubi ----------
    static String clima(Context c, double lat, double lon, int[] salida) {
        JSONObject r = pedir(c, String.format(Locale.US, "https://weather.googleapis.com/v1/currentConditions:lookup?location.latitude=%.4f&location.longitude=%.4f&languageCode=es", lat, lon), null, null);
        if (r == null || r.optJSONObject("temperature") == null || r.optJSONObject("weatherCondition") == null) return null;
        String t = r.getJSONObject("weatherCondition").optString("type");
        salida[0] = (int) Math.round(r.getJSONObject("temperature").optDouble("degrees")); salida[1] = r.optBoolean("isDaytime", true) ? 1 : 0;
        return t.contains("THUNDER") ? "tormenta" : t.contains("SNOW") || t.contains("HAIL") ? "nieve" : t.contains("RAIN") || t.contains("SHOWER") || t.contains("DRIZZLE") ? "lluvia"
                : t.contains("FOG") || t.contains("HAZE") || t.contains("MIST") ? "niebla" : t.contains("CLOUD") || t.contains("OVERCAST") ? "nubes" : "despejado";
    }

    // ---------- la dirección ("Carrera 9 #20-15, Popayán") y la calle sola ----------
    static String[] direccion(Context c, double lat, double lon) {
        JSONObject r = pedir(c, String.format(Locale.US, "https://maps.googleapis.com/maps/api/geocode/json?latlng=%.6f,%.6f&language=es", lat, lon), null, null);
        JSONArray rs = r == null ? null : r.optJSONArray("results");
        if (rs == null || rs.length() == 0) return null;
        try {
            JSONObject a = rs.getJSONObject(0); String calle = "";
            JSONArray cs = a.optJSONArray("address_components");
            for (int i = 0; cs != null && i < cs.length(); i++) {
                JSONArray ty = cs.getJSONObject(i).optJSONArray("types");
                if (ty != null && ty.toString().contains("\"route\"")) { calle = cs.getJSONObject(i).optString("short_name"); break; }
            }
            return new String[]{a.optString("formatted_address").replaceAll(", Colombia$", ""), calle};
        } catch (Throwable t) { return null; }
    }

    // ---------- el límite de velocidad de la vía por donde va (km/h), o -1 ----------
    static int limite(Context c, double la0, double lo0, double la1, double lo1) {
        if (sinLimites) return -1;
        JSONObject r = pedir(c, String.format(Locale.US, "https://roads.googleapis.com/v1/speedLimits?path=%.6f,%.6f%%7C%.6f,%.6f&units=KPH", la0, lo0, la1, lo1), null, null);
        if (r == null) { if (ultimoError.contains("→ 403")) sinLimites = true; return -1; }
        JSONArray s = r.optJSONArray("speedLimits");
        return s == null || s.length() == 0 ? -1 : (int) Math.round(s.optJSONObject(s.length() - 1).optDouble("speedLimit", -1));
    }

    // ---------- la ruta con tráfico: los puntos de la línea y por tramos cómo va (0 normal, 1 lento, 2 trancado) ----------
    static final class Ruta { double[] la, lo; double seg, metros; int[][] trafico; }
    static Ruta ruta(Context c, double la0, double lo0, double la1, double lo1) {
        try {
            JSONObject b = new JSONObject()
                    .put("origin", new JSONObject().put("location", new JSONObject().put("latLng", new JSONObject().put("latitude", la0).put("longitude", lo0))))
                    .put("destination", new JSONObject().put("location", new JSONObject().put("latLng", new JSONObject().put("latitude", la1).put("longitude", lo1))))
                    .put("travelMode", "DRIVE").put("routingPreference", "TRAFFIC_AWARE").put("polylineQuality", "HIGH_QUALITY")
                    .put("extraComputations", new JSONArray().put("TRAFFIC_ON_POLYLINE")).put("languageCode", "es");
            JSONObject r = pedir(c, "https://routes.googleapis.com/directions/v2:computeRoutes", b.toString(),
                    "routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline,routes.travelAdvisory.speedReadingIntervals");
            JSONArray rs = r == null ? null : r.optJSONArray("routes");
            if (rs == null || rs.length() == 0) return null;
            JSONObject ro = rs.getJSONObject(0);
            Ruta x = new Ruta();
            List<double[]> p = decodificar(ro.getJSONObject("polyline").getString("encodedPolyline"));
            if (p.size() < 2) return null;
            x.la = new double[p.size()]; x.lo = new double[p.size()];
            for (int i = 0; i < p.size(); i++) { x.la[i] = p.get(i)[0]; x.lo[i] = p.get(i)[1]; }
            x.metros = ro.optDouble("distanceMeters", 0);
            x.seg = Double.parseDouble(ro.optString("duration", "0s").replace("s", ""));
            JSONObject ta = ro.optJSONObject("travelAdvisory");
            JSONArray iv = ta == null ? null : ta.optJSONArray("speedReadingIntervals");
            ArrayList<int[]> t = new ArrayList<>();
            for (int i = 0; iv != null && i < iv.length(); i++) {
                JSONObject s = iv.getJSONObject(i); String v = s.optString("speed");
                t.add(new int[]{s.optInt("startPolylinePointIndex", 0), s.optInt("endPolylinePointIndex", 0), v.equals("TRAFFIC_JAM") ? 2 : v.equals("SLOW") ? 1 : 0});
            }
            x.trafico = t.toArray(new int[0][]);
            return x;
        } catch (Throwable t) { return null; }
    }
    // la línea comprimida de Google (formato "encoded polyline")
    static List<double[]> decodificar(String s) {
        ArrayList<double[]> l = new ArrayList<>(); int i = 0, la = 0, lo = 0;
        while (i < s.length()) {
            int r = 0, sh = 0, b;
            do { b = s.charAt(i++) - 63; r |= (b & 0x1f) << sh; sh += 5; } while (b >= 0x20 && i < s.length());
            la += (r & 1) != 0 ? ~(r >> 1) : r >> 1;
            r = 0; sh = 0;
            do { b = s.charAt(i++) - 63; r |= (b & 0x1f) << sh; sh += 5; } while (b >= 0x20 && i < s.length());
            lo += (r & 1) != 0 ? ~(r >> 1) : r >> 1;
            l.add(new double[]{la / 1e5, lo / 1e5});
        }
        return l;
    }

    // comprobación mínima del decodificador (el ejemplo de la documentación de Google)
    static String autoprueba() {
        List<double[]> p = decodificar("_p~iF~ps|U_ulLnnqC_mqNvxq`@");
        if (p.size() != 3 || Math.abs(p.get(0)[0] - 38.5) > 1e-6 || Math.abs(p.get(0)[1] + 120.2) > 1e-6 || Math.abs(p.get(2)[0] - 43.252) > 1e-6) return "polilínea mal";
        return "ok";
    }
}
