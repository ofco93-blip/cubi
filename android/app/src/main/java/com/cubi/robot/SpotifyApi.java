package com.cubi.robot;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Spotify Web API con la cuenta Premium del usuario (OAuth PKCE, sin secreto), igual que Copi: busca y reproduce de
 * verdad en el Spotify del celular. Usa la misma app de Spotify Developers que Copi; su redirect es cubi://spotify
 * (hay que agregarlo en el panel de esa app). Todo lo de red se llama en un hilo aparte.
 */
class SpotifyApi {
    static final String CLIENT_ID = "5b2755e8ce78403b873a58bd23b966c5";
    static final String REDIRECT = "cubi://spotify";
    static final String SPOTIFY = "com.spotify.music";
    private static final String SCOPES = "user-modify-playback-state user-read-playback-state user-read-currently-playing playlist-read-private";
    private static final Pattern LISTA = Pattern.compile("(?i)playlist|lista|mix|radio|para (el|la|concentrar|dormir|entrenar|bailar)");

    private final Context c;
    private final SharedPreferences p;

    SpotifyApi(Context c) {
        this.c = c;
        p = c.getSharedPreferences("spotify", Context.MODE_PRIVATE);
    }

    boolean conectado() { return p.getString("refresh", null) != null; }

    void desconectar() { p.edit().clear().apply(); }

    /** Abre el login de Spotify en el navegador; vuelve a la app por cubi://spotify. */
    void conectar() throws Exception {
        byte[] r = new byte[48];
        new SecureRandom().nextBytes(r);
        int f = Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;
        String verificador = Base64.encodeToString(r, f);
        String reto = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verificador.getBytes(StandardCharsets.UTF_8)), f);
        p.edit().putString("verificador", verificador).apply();
        Uri u = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("redirect_uri", REDIRECT)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("code_challenge", reto)
                .appendQueryParameter("scope", SCOPES)
                .build();
        c.startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Llega el code del navegador: lo cambia por tokens. */
    boolean recibirCodigo(String code) {
        try {
            guardar(postForm("https://accounts.spotify.com/api/token", new String[]{"grant_type", "authorization_code", "code", code,
                    "redirect_uri", REDIRECT, "client_id", CLIENT_ID, "code_verifier", p.getString("verificador", "")}));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void guardar(JSONObject j) throws Exception {
        SharedPreferences.Editor e = p.edit().putString("token", j.getString("access_token"))
                .putLong("vence", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000 - 60000);
        String rf = j.optString("refresh_token");
        if (!rf.isEmpty()) e.putString("refresh", rf);
        e.apply();
    }

    private String token() throws Exception {
        String t = p.getString("token", null);
        if (t != null && System.currentTimeMillis() < p.getLong("vence", 0)) return t;
        String rf = p.getString("refresh", null);
        if (rf == null) throw new Exception("sin sesión de Spotify");
        guardar(postForm("https://accounts.spotify.com/api/token", new String[]{"grant_type", "refresh_token", "refresh_token", rf, "client_id", CLIENT_ID}));
        return p.getString("token", null);
    }

    private static String leer(HttpURLConnection h) throws Exception {
        InputStream in = h.getResponseCode() < 300 ? h.getInputStream() : h.getErrorStream();
        if (in == null) return "";
        try (InputStream i = in) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = i.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        }
    }

    private static JSONObject postForm(String url, String[] campos) throws Exception {  // campos: clave, valor, clave, valor...
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setConnectTimeout(10000);
        h.setReadTimeout(10000);
        h.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        StringBuilder s = new StringBuilder();
        for (int i = 0; i + 1 < campos.length; i += 2)
            s.append(s.length() > 0 ? "&" : "").append(campos[i]).append('=').append(URLEncoder.encode(campos[i + 1], "UTF-8"));
        try (OutputStream o = h.getOutputStream()) { o.write(s.toString().getBytes(StandardCharsets.UTF_8)); }
        String t = leer(h);
        if (h.getResponseCode() >= 300) throw new Exception("HTTP " + h.getResponseCode() + " " + t);
        return new JSONObject(t);
    }

    /** {código HTTP, respuesta} */
    private Object[] api(String metodo, String ruta, JSONObject cuerpo) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL("https://api.spotify.com/v1" + ruta).openConnection();
        h.setRequestMethod(metodo);
        h.setConnectTimeout(10000);
        h.setReadTimeout(10000);
        h.setRequestProperty("Authorization", "Bearer " + token());
        if (cuerpo != null) {
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = h.getOutputStream()) { o.write(cuerpo.toString().getBytes(StandardCharsets.UTF_8)); }
        } else if (metodo.equals("PUT")) {
            h.setDoOutput(true);
            h.setFixedLengthStreamingMode(0);
        }
        return new Object[]{h.getResponseCode(), leer(h)};
    }

    /** El Spotify de este celular (o el activo). Si no aparece, abre la app y reintenta. */
    private String dispositivo() throws Exception {
        for (int intento = 0; intento < 3; intento++) {
            Object[] r = api("GET", "/me/player/devices", null);
            if ((int) r[0] == 200) {
                JSONArray ds = new JSONObject((String) r[1]).getJSONArray("devices");
                JSONObject activo = null, cel = null;
                for (int i = 0; i < ds.length(); i++) {
                    JSONObject d = ds.getJSONObject(i);
                    if (d.optString("type").equals("Smartphone")) { if (d.optBoolean("is_active")) return d.getString("id"); if (cel == null) cel = d; }
                    if (d.optBoolean("is_active") && activo == null) activo = d;
                }
                if (cel != null) return cel.getString("id");
                if (activo != null) return activo.getString("id");
            }
            if (intento == 0) {
                Intent a = c.getPackageManager().getLaunchIntentForPackage(SPOTIFY);
                if (a != null) c.startActivity(a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            }
            Thread.sleep(2500);
        }
        return null;
    }

    private static String norm(String s) { return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9áéíóúñ ]", "").trim(); }

    /** Busca y reproduce. Devuelve lo que puso, o null si no pudo. */
    String reproducir(String que) {
        try {
            Object[] r = api("GET", "/search?q=" + URLEncoder.encode(que, "UTF-8") + "&type=track,artist,playlist&limit=3&market=CO", null);
            if ((int) r[0] != 200) return null;
            JSONObject j = new JSONObject((String) r[1]);
            JSONObject artista = primero(j, "artists"), cancion = primero(j, "tracks"), lista = primero(j, "playlists");
            boolean quiereLista = LISTA.matcher(que).find();
            JSONObject cuerpo = new JSONObject();
            String nombre;
            if (quiereLista && lista != null) { cuerpo.put("context_uri", lista.getString("uri")); nombre = "lista " + lista.optString("name"); }
            else if (artista != null && norm(artista.optString("name")).equals(norm(que))) { cuerpo.put("context_uri", artista.getString("uri")); nombre = artista.optString("name"); }
            else if (cancion != null) {
                cuerpo.put("uris", new JSONArray().put(cancion.getString("uri")));
                JSONArray as = cancion.optJSONArray("artists");
                nombre = cancion.optString("name") + (as != null && as.length() > 0 ? " de " + as.getJSONObject(0).optString("name") : "");
            }
            else if (artista != null) { cuerpo.put("context_uri", artista.getString("uri")); nombre = artista.optString("name"); }
            else if (lista != null) { cuerpo.put("context_uri", lista.getString("uri")); nombre = "lista " + lista.optString("name"); }
            else return null;
            String uri = cuerpo.has("uris") ? cuerpo.getJSONArray("uris").getString(0) : cuerpo.getString("context_uri");
            String dev = dispositivo();
            if (dev != null && (int) api("PUT", "/me/player/play?device_id=" + dev, cuerpo)[0] < 300) return nombre;
            // plan B (como Copi): abrir esa canción o artista exacto en Spotify con autoplay
            String[] partes = uri.split(":");  // spotify:track:ID
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/" + partes[1] + "/" + partes[2] + "?autoplay=true"))
                    .setPackage(SPOTIFY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            return nombre;
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONObject primero(JSONObject j, String tipo) {
        JSONObject t = j.optJSONObject(tipo);
        JSONArray a = t == null ? null : t.optJSONArray("items");
        if (a == null) return null;
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) return a.optJSONObject(i);  // las listas pueden venir nulas
        return null;
    }
}
