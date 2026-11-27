package com.cubi.robot;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

// Mapas sin internet: las calles, el agua y los parques de las ciudades que uno escribe en la app (y las carreteras entre
// ellas) se bajan una vez de OpenFreeMap (OpenStreetMap en cuadros de ~2,4 km), se recortan a lo que dibuja Mapi y se
// guardan en el celular. Mientras se maneja se leen de ahí; Overpass solo se usa fuera de lo guardado.
// La lista de ciudades vive solo en el celular (ajustes de la app), nunca en el código.
final class CubiMapas {
    static final int Z = 14, N = 1 << Z;
    static volatile String estado = "";
    static volatile boolean bajando;

    static File dir(Context c) { File d = new File(c.getFilesDir(), "mapas"); d.mkdirs(); return d; }
    static int tx(double lon) { return (int) Math.floor((lon + 180) / 360 * N); }
    static int ty(double lat) { double r = Math.toRadians(lat); return (int) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * N); }
    private static File cuadro(File d, int x, int y) { return new File(d, x + "_" + y + ".bin"); }

    static String resumen(Context c) {
        if (!estado.isEmpty()) return estado;
        File[] f = dir(c).listFiles(); long b = 0; int n = 0;
        if (f != null) for (File x : f) if (x.getName().endsWith(".bin")) { n++; b += x.length(); }
        return n == 0 ? "Sin mapas guardados." : n + " cuadros guardados (" + (b / 1048576 + 1) + " MB).";
    }

    // las calles alrededor (± m metros) en la misma forma que contesta Overpass; null si falta algún cuadro
    // ponytail: arma JSON de cuadros enteros (hasta 4) y luego tramos() recorta; si se pone lento, recortar aquí
    static JSONArray leer(Context c, double lat, double lon, double m) {
        double dLat = m / 111320, dLon = m / (111320 * Math.cos(Math.toRadians(lat)));
        int x0 = tx(lon - dLon), x1 = tx(lon + dLon), y0 = ty(lat + dLat), y1 = ty(lat - dLat);
        File d = dir(c);
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) if (!cuadro(d, x, y).exists()) return null;
        String[][] tags = {{"highway", "residential"}, {"highway", "primary"}, null, {"natural", "water"}, {"leisure", "park"}, {"waterway", "river"}};
        try {
            JSONArray els = new JSONArray();
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++)
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(cuadro(d, x, y))))) {
                    while (in.available() > 0) {
                        int k = in.readByte(), n = in.readShort();
                        JSONArray g = new JSONArray();
                        for (int i = 0; i < n; i++) {
                            double px = (x + in.readShort() / 4096.0) / N, py = (y + in.readShort() / 4096.0) / N;
                            g.put(new JSONObject().put("lat", Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * py))))).put("lon", px * 360 - 180));
                        }
                        if (k >= 0 && k < tags.length && tags[k] != null) els.put(new JSONObject().put("tags", new JSONObject().put(tags[k][0], tags[k][1])).put("geometry", g));
                    }
                }
            return els;
        } catch (Exception e) { return null; }
    }

    // ---------------------------------------------------------------- escoger dónde
    // lo que se escribe en la app: las ciudades, pueblos y departamentos que coinciden (en el país del celular)
    static JSONArray buscar(String q, String pais) throws Exception {
        JSONArray r = new JSONArray(texto("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=10&accept-language=es&q=" + URLEncoder.encode(q, "UTF-8")
                + (pais == null || pais.length() != 2 ? "" : "&countrycodes=" + pais.toLowerCase(Locale.ROOT)))), o = new JSONArray();
        for (int i = 0; i < r.length(); i++) {
            JSONObject x = r.getJSONObject(i); String at = x.optString("addresstype");
            String tipo = at.equals("state") ? "Departamento" : at.equals("county") || at.equals("region") ? "Región"
                    : at.matches("city|town|municipality") ? "Ciudad" : at.matches("village|hamlet|suburb|neighbourhood") ? "Pueblo" : null;
            JSONArray bb = x.optJSONArray("boundingbox");
            if (tipo == null || bb == null) continue;
            String d = x.optString("display_name"); int coma = d.indexOf(',');
            o.put(new JSONObject().put("n", x.optString("name")).put("d", coma < 0 ? "" : d.substring(coma + 1).trim()).put("t", tipo)
                    .put("la", x.getDouble("lat")).put("lo", x.getDouble("lon")).put("s", bb.getDouble(0)).put("no", bb.getDouble(1)).put("w", bb.getDouble(2)).put("e", bb.getDouble(3)));
        }
        return o;
    }
    private static boolean entera(JSONObject z) { return z.optString("t").equals("Departamento") || z.optString("t").equals("Región"); }
    // los cuadros de lo escogido: una ciudad o pueblo, 12 km a la redonda; un departamento o región, entero (su rectángulo)
    private static Set<Long> cuadrosDe(JSONArray zs) throws Exception {
        Set<Long> cuadros = new HashSet<>();
        for (int i = 0; i < zs.length(); i++) {
            JSONObject z = zs.getJSONObject(i);
            if (entera(z)) { for (int x = tx(z.getDouble("w")); x <= tx(z.getDouble("e")); x++) for (int y = ty(z.getDouble("no")); y <= ty(z.getDouble("s")); y++) cuadros.add(((long) x << 20) | y); }
            else alrededor(z.getDouble("la"), z.getDouble("lo"), 12000, cuadros);
        }
        return cuadros;
    }
    static int cuenta(String zonas) { try { return cuadrosDe(new JSONArray(zonas)).size(); } catch (Exception e) { return 0; } }

    // ---------------------------------------------------------------- la descarga
    static void descargar(Context c, String zonas) {
        if (bajando) return;
        bajando = true; estado = "Empezando…";
        new Thread(() -> {
            try {
                JSONArray zs = new JSONArray(zonas); List<double[]> ciudades = new ArrayList<>(); List<String> faltan = new ArrayList<>();
                for (int i = 0; i < zs.length(); i++) ciudades.add(new double[]{zs.getJSONObject(i).getDouble("la"), zs.getJSONObject(i).getDouble("lo")});
                Set<Long> cuadros = cuadrosDe(zs);
                // las carreteras: cada ciudad se une con la más cercana de las ya unidas (árbol mínimo) por la ruta de OSRM
                List<Integer> unidas = new ArrayList<>(); if (!ciudades.isEmpty()) unidas.add(0);
                while (unidas.size() < ciudades.size()) {
                    int a = -1, b = -1; double mejor = Double.MAX_VALUE;
                    for (int i : unidas) for (int j = 0; j < ciudades.size(); j++)
                        if (!unidas.contains(j) && metros(ciudades.get(i), ciudades.get(j)) < mejor) { mejor = metros(ciudades.get(i), ciudades.get(j)); a = i; b = j; }
                    unidas.add(b);
                    double[] p = ciudades.get(a), q = ciudades.get(b);
                    estado = "Buscando las carreteras… " + (unidas.size() - 1) + " de " + (ciudades.size() - 1);
                    try {
                        JSONArray co = new JSONObject(texto(String.format(Locale.US, "https://router.project-osrm.org/route/v1/driving/%.5f,%.5f;%.5f,%.5f?overview=full&geometries=geojson", p[1], p[0], q[1], q[0])))
                                .getJSONArray("routes").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates");
                        double[] antes = null;
                        for (int i = 0; i < co.length(); i++) {
                            double[] ll = {co.getJSONArray(i).getDouble(1), co.getJSONArray(i).getDouble(0)};
                            if (antes != null) {                            // un punto cada ~300 m y 600 m a cada lado
                                int pasos = (int) (metros(antes, ll) / 300);
                                for (int s = 1; s <= pasos; s++) { double f = s / (pasos + 1.0); alrededor(antes[0] + (ll[0] - antes[0]) * f, antes[1] + (ll[1] - antes[1]) * f, 600, cuadros); }
                            }
                            alrededor(ll[0], ll[1], 600, cuadros); antes = ll;
                        }
                    } catch (Exception e) { faltan.add("una carretera"); }
                }
                final String plantilla = new JSONObject(texto("https://tiles.openfreemap.org/planet")).getJSONArray("tiles").getString(0);
                final File d = dir(c); final List<Long> pendientes = new ArrayList<>();
                for (long t : cuadros) if (!cuadro(d, (int) (t >> 20), (int) (t & 0xFFFFF)).exists()) pendientes.add(t);
                final AtomicInteger hechos = new AtomicInteger(), fallos = new AtomicInteger();
                ExecutorService ex = Executors.newFixedThreadPool(4);
                for (final long t : pendientes) ex.execute(() -> {
                    int x = (int) (t >> 20), y = (int) (t & 0xFFFFF);
                    try {
                        byte[] crudo = bytes(plantilla.replace("{z}", "" + Z).replace("{x}", "" + x).replace("{y}", "" + y));
                        File tmp = new File(d, x + "_" + y + ".tmp");
                        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(recortar(crudo)); }
                        if (!tmp.renameTo(cuadro(d, x, y))) fallos.incrementAndGet();
                    } catch (Exception e) { fallos.incrementAndGet(); }
                    estado = "Descargando mapas… " + hechos.incrementAndGet() + " de " + pendientes.size();
                });
                ex.shutdown(); ex.awaitTermination(3, TimeUnit.HOURS);
                bajando = false; estado = "";
                String r = resumen(c);
                if (fallos.get() > 0) r += " " + fallos.get() + " no bajaron: toca Descargar otra vez.";
                if (!faltan.isEmpty()) r += " No encontré: " + android.text.TextUtils.join(", ", faltan) + ".";
                estado = r;
            } catch (Exception e) {
                estado = "No pude descargar los mapas (¿hay internet?).";
            } finally { bajando = false; }
        }).start();
    }

    private static double metros(double[] a, double[] b) { return Math.hypot((a[0] - b[0]) * 111320, (a[1] - b[1]) * 111320 * Math.cos(Math.toRadians(a[0]))); }
    private static void alrededor(double lat, double lon, double m, Set<Long> sale) {
        double dLat = m / 111320, dLon = m / (111320 * Math.cos(Math.toRadians(lat)));
        for (int x = tx(lon - dLon); x <= tx(lon + dLon); x++) for (int y = ty(lat + dLat); y <= ty(lat - dLat); y++) sale.add(((long) x << 20) | y);
    }

    private static byte[] bytes(String u) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setConnectTimeout(15000); h.setReadTimeout(30000); h.setRequestProperty("User-Agent", "robot-app");
        int code = h.getResponseCode();
        if (code == 204 || code == 404) return new byte[0];         // cuadro sin nada (monte, mar)
        if (code != 200) throw new IOException("HTTP " + code);
        try (InputStream in = h.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[16384]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        }
    }
    private static String texto(String u) throws IOException { return new String(bytes(u), "UTF-8"); }

    // ---------------------------------------------------------------- de un cuadro de OpenFreeMap a lo que usa Mapi
    // El cuadro viene en Mapbox Vector Tile (protobuf). Se guarda solo esto: tipo (0 calle, 1 avenida, 3 agua, 4 parque,
    // 5 río), cuántos puntos y los puntos en coordenadas del cuadro (0..4096). Bogotá centro: de ~450 kB a ~20 kB.
    static byte[] recortar(byte[] t) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bo);
        Pb tile = new Pb(t, 0, t.length);
        while (tile.mas()) {
            if (tile.campo() != 3 || tile.tipo != 2) { tile.saltar(); continue; }
            Pb ly = tile.sub();
            String nombre = ""; int ext = 4096; List<String> keys = new ArrayList<>(), vals = new ArrayList<>(); List<Pb> fs = new ArrayList<>();
            while (ly.mas()) {
                int f = ly.campo();
                if (f == 1 && ly.tipo == 2) nombre = ly.texto();
                else if (f == 2 && ly.tipo == 2) fs.add(ly.sub());
                else if (f == 3 && ly.tipo == 2) keys.add(ly.texto());
                else if (f == 4 && ly.tipo == 2) { Pb v = ly.sub(); String s = ""; while (v.mas()) { if (v.campo() == 1 && v.tipo == 2) s = v.texto(); else v.saltar(); } vals.add(s); }
                else if (f == 5 && ly.tipo == 0) ext = (int) ly.varint();
                else ly.saltar();
            }
            if (!nombre.equals("transportation") && !nombre.equals("water") && !nombre.equals("waterway") && !nombre.equals("landcover")) continue;
            for (Pb ft : fs) {
                String clase = "", sub = ""; int tipo = 0; int[] geo = null;
                while (ft.mas()) {
                    int f = ft.campo();
                    if (f == 2 && ft.tipo == 2) {
                        int[] tg = ft.empacados();
                        for (int i = 0; i + 1 < tg.length; i += 2) {
                            if (tg[i] >= keys.size() || tg[i + 1] >= vals.size()) continue;
                            String k = keys.get(tg[i]);
                            if (k.equals("class")) clase = vals.get(tg[i + 1]); else if (k.equals("subclass")) sub = vals.get(tg[i + 1]);
                        }
                    } else if (f == 3 && ft.tipo == 0) tipo = (int) ft.varint();
                    else if (f == 4 && ft.tipo == 2) geo = ft.empacados();
                    else ft.saltar();
                }
                int k = -1;
                if (nombre.equals("transportation") && tipo == 2) k = clase.matches("motorway|trunk|primary|secondary") ? 1 : clase.matches("tertiary|minor") ? 0 : -1;
                else if (nombre.equals("water") && tipo == 3) k = 3;
                else if (nombre.equals("waterway") && tipo == 2 && clase.matches("river|stream|canal")) k = 5;
                else if (nombre.equals("landcover") && tipo == 3 && sub.matches("park|garden")) k = 4;
                if (k >= 0 && geo != null) trazos(geo, ext, k, out);
            }
        }
        return bo.toByteArray();
    }

    // las órdenes de dibujo del cuadro: 1 mover, 2 línea, 7 cerrar; los números van en zigzag y relativos al anterior
    // ponytail: los huecos de un lago (islas) salen como otro lago encima; restarlos si algún día estorban
    private static void trazos(int[] g, int ext, int k, DataOutputStream out) throws IOException {
        int x = 0, y = 0, i = 0; List<int[]> pts = new ArrayList<>();
        while (i < g.length) {
            int cmd = g[i] & 7, cnt = g[i] >>> 3; i++;
            if (cmd == 7) { if (!pts.isEmpty()) pts.add(pts.get(0)); continue; }
            if (cmd == 1) { escribir(pts, k, out); pts = new ArrayList<>(); }
            for (int c = 0; c < cnt && i + 1 < g.length; c++, i += 2) {
                x += (g[i] >>> 1) ^ -(g[i] & 1); y += (g[i + 1] >>> 1) ^ -(g[i + 1] & 1);
                pts.add(new int[]{x * 4096 / ext, y * 4096 / ext});
            }
        }
        escribir(pts, k, out);
    }
    private static void escribir(List<int[]> p, int k, DataOutputStream out) throws IOException {
        if (p.size() < 2) return;
        int n = Math.min(p.size(), Short.MAX_VALUE);
        out.writeByte(k); out.writeShort(n);
        for (int j = 0; j < n; j++) { out.writeShort(p.get(j)[0]); out.writeShort(p.get(j)[1]); }
    }

    // lector mínimo de protobuf (solo lo que trae un cuadro)
    static final class Pb {
        final byte[] b; int p; final int fin; int tipo;
        Pb(byte[] b, int p, int fin) { this.b = b; this.p = p; this.fin = fin; }
        boolean mas() { return p < fin; }
        long varint() { long v = 0; int s = 0; while (true) { int c = b[p++] & 0xFF; v |= (long) (c & 127) << s; s += 7; if (c < 128) return v; } }
        int campo() { long k = varint(); tipo = (int) (k & 7); return (int) (k >>> 3); }
        void saltar() { if (tipo == 0) varint(); else if (tipo == 1) p += 8; else if (tipo == 5) p += 4; else if (tipo == 2) { int n = (int) varint(); p += n; } else p = fin; }
        Pb sub() { int n = (int) varint(); Pb s = new Pb(b, p, p + n); p += n; return s; }
        String texto() { int n = (int) varint(); String s = new String(b, p, n, java.nio.charset.StandardCharsets.UTF_8); p += n; return s; }
        int[] empacados() { Pb s = sub(); int[] o = new int[16]; int n = 0; while (s.mas()) { if (n == o.length) o = Arrays.copyOf(o, n * 2); o[n++] = (int) s.varint(); } return Arrays.copyOf(o, n); }
    }
}
