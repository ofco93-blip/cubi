package com.cubi.robot;

import android.app.Activity;
import android.app.Application;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.View;
import android.widget.FrameLayout;

import androidx.car.app.SurfaceContainer;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.libraries.navigation.NavigationApi;
import com.google.android.libraries.navigation.NavigationViewForAuto;
import com.google.android.libraries.navigation.Navigator;
import com.google.android.libraries.navigation.Waypoint;

// La navegación de Google dentro de la pantalla del carro. Es opcional: solo se prende si el usuario pegó su propia
// llave de Google Cloud en la pestaña Mapa y aceptó los términos de Google en el celular. Sin llave, nada de esto corre
// y Cubi sigue con su mapa de siempre.
// Cómo va en pantalla: en vez de pintar directo en la superficie del carro, se monta sobre ella una pantalla virtual
// con dos capas: abajo la vista de Google y encima el dibujo de Cubi (Zelda, letra, barra), que deja un hueco
// transparente donde va el mapa: así el mapa queda por detrás de Zelda.
// ponytail: sin probar en un carro (hace falta una llave que funcione). El botón Destino dibujado y la tarjeta del giro
// de Cubi no salen en este modo: Google pinta lo suyo dentro del recuadro.
final class CubiGoogle {
    interface Pintor { void pintar(Canvas c); }

    // ---------- la llave, los términos y el navegador (uno solo para toda la app) ----------
    static volatile Navigator nav;
    static volatile String estado = "";
    private static boolean pidiendo;
    private static double irLat = Double.NaN, irLon;

    static String llave(Context c) { return c.getSharedPreferences("cubi", 0).getString("llaveGoogle", "").trim(); }
    // Google solo deja poner la llave una vez por arranque de la app (la segunda lanza un error).
    // ponytail: cambiar la llave por otra pide cerrar y volver a abrir Cubi.
    private static String puesta = "";
    private static void poner(Context c) { String k = llave(c); if (!k.isEmpty() && !k.equals(puesta)) { NavigationApi.setApiKey(k); puesta = k; } }
    private static boolean terminos(Context c) {
        // La llave va SIEMPRE antes de cualquier consulta a Google: sin ella, esa consulta arranca por dentro un hilo
        // que la busca en el manifiesto, no la halla y tumba la app al abrir (pasó en la 2.0.340).
        try { poner(c); return NavigationApi.areTermsAccepted((Application) c.getApplicationContext()); } catch (Throwable t) { return false; }
    }
    // Los dos mapas siguen sirviendo: con la llave puesta, el usuario escoge cuál sale (se aplica al volver a conectar el carro)
    static boolean elegido(Context c) { return c.getSharedPreferences("cubi", 0).getBoolean("mapaGoogle", true); }
    static boolean puede(Context c) { return !llave(c).isEmpty() && terminos(c); }
    static boolean lista(Context c) { return puede(c) && elegido(c); }

    // pide el navegador a Google (en el hilo principal); no hace nada si falta la llave o aceptar los términos
    static void preparar(Context c) {
        String k = llave(c);
        if (k.isEmpty() || nav != null || pidiendo || !terminos(c)) return;
        pidiendo = true; estado = "Google: conectando…";
        try {
            poner(c);
            NavigationApi.getNavigator((Application) c.getApplicationContext(), new NavigationApi.NavigatorListener() {
                @Override public void onNavigatorReady(Navigator n) {
                    nav = n; pidiendo = false; estado = "Google: listo.";
                    aplicarVoz(c);
                    if (!Double.isNaN(irLat)) ir(irLat, irLon);
                }
                @Override public void onError(int cod) {
                    pidiendo = false;
                    estado = "Google: " + (cod == NavigationApi.ErrorCode.NOT_AUTHORIZED ? "la llave no sirve, o al proyecto le falta activar la navegación o la facturación."
                            : cod == NavigationApi.ErrorCode.TERMS_NOT_ACCEPTED ? "falta aceptar los términos de Google."
                            : cod == NavigationApi.ErrorCode.NETWORK_ERROR ? "sin internet."
                            : cod == NavigationApi.ErrorCode.LOCATION_PERMISSION_MISSING ? "falta el permiso de ubicación." : "error " + cod + ".");
                }
            });
        } catch (Throwable t) { pidiendo = false; estado = "Google: " + t; }
    }

    // en el celular: muestra los términos de Google (una sola vez) y, si se aceptan, deja todo listo
    static void aceptar(Activity a) {
        try {
            poner(a);
            NavigationApi.showTermsAndConditionsDialog(a, "Cubi", ok -> {
                estado = ok ? "" : "Google: falta aceptar los términos.";
                if (ok) preparar(a);
            });
        } catch (Throwable t) { estado = puesta.isEmpty() || puesta.equals(llave(a)) ? "Google: " + t : "Google: para cambiar la llave, cierra Cubi del todo y vuelve a abrirla."; }
    }

    // la voz de la navegación: 0 todo, 1 solo alertas, 2 callada (el botón del parlante en la pantalla del carro)
    static int voz(Context c) { return c.getSharedPreferences("cubi", 0).getInt("googleVoz", 0) % 3; }
    static void vozSiguiente(Context c) { c.getSharedPreferences("cubi", 0).edit().putInt("googleVoz", (voz(c) + 1) % 3).apply(); aplicarVoz(c); }
    private static void aplicarVoz(Context c) {
        Navigator n = nav;
        if (n != null) try { n.setAudioGuidance(new int[]{Navigator.AudioGuidance.VOICE_ALERTS_AND_GUIDANCE, Navigator.AudioGuidance.VOICE_ALERTS_ONLY, Navigator.AudioGuidance.SILENT}[voz(c)]); } catch (Throwable t) { }
    }
    // lo que falta según Google ("12 min  ·  5,3 km  ·  llega 2:15"); vacío si no hay ruta. Se pregunta cada 2 s, no cada cuadro.
    private static String faltaTxt = ""; private static long faltaEn;
    static String falta() {
        Navigator n = nav; long t = System.currentTimeMillis();
        if (n == null || Double.isNaN(irLat)) return "";
        if (t - faltaEn < 2000) return faltaTxt;
        faltaEn = t;
        try {
            com.google.android.libraries.navigation.TimeAndDistance td = n.getCurrentTimeAndDistance();
            if (td == null || td.getMeters() <= 0) return faltaTxt = "";
            int seg = td.getSeconds(), min = Math.max(1, Math.round(seg / 60f)); double km = td.getMeters() / 1000.0;
            java.util.Calendar cal = java.util.Calendar.getInstance(); cal.add(java.util.Calendar.SECOND, seg);
            int hh = cal.get(java.util.Calendar.HOUR);
            return faltaTxt = (min >= 60 ? min / 60 + " h " + min % 60 + " min" : min + " min") + "  ·  "
                    + (km < 10 ? String.format(java.util.Locale.US, "%.1f km", km).replace('.', ',') : Math.round(km) + " km")
                    + "  ·  llega " + String.format(java.util.Locale.US, "%d:%02d", hh == 0 ? 12 : hh, cal.get(java.util.Calendar.MINUTE));
        } catch (Throwable e) { return faltaTxt = ""; }
    }

    static void ir(double lat, double lon) {
        irLat = lat; irLon = lon;
        final Navigator n = nav;
        if (n == null) return;
        try {
            estado = "Google: calculando la ruta…";
            n.setDestination(Waypoint.builder().setLatLng(lat, lon).build()).setOnResultListener(st -> {
                if (st == Navigator.RouteStatus.OK) { n.startGuidance(); estado = "Google: navegando."; }
                else estado = "Google: no hay ruta (" + st + ").";
            });
        } catch (Throwable t) { estado = "Google: " + t; }
    }
    static void parar() {
        irLat = Double.NaN;
        Navigator n = nav;
        if (n != null) try { n.stopGuidance(); n.clearDestinations(); estado = "Google: listo."; } catch (Throwable t) { }
    }

    // ---------- la pantalla del carro ----------
    private VirtualDisplay vd;
    private Presentation pr;
    private NavigationViewForAuto vista;
    private GoogleMap mapa;
    private View lienzo;
    private final RectF donde = new RectF();
    private View borde;                                               // encima del mapa: desvanece sus orillas hacia el fondo
    // El mapa de Google es una imagen sólida: para que se funda, se le pone el MISMO tono al fondo de la pantalla y a la
    // tierra del mapa (TONO), se pinta en oscuro y sus orillas se desvanecen hacia ese tono.
    // ponytail: el estilo se pide al mapa; si la navegación de Google lo ignora, queda su oscuro de fábrica y solo
    // coinciden las orillas. Ajustar TONO con una foto del carro.
    static final int TONO = 0xff101b38;
    private static final String ESTILO = "[{\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#101b38\"}]},"
            + "{\"elementType\":\"labels.text.fill\",\"stylers\":[{\"color\":\"#c8d4f0\"}]},"
            + "{\"elementType\":\"labels.text.stroke\",\"stylers\":[{\"color\":\"#0a1226\"}]},"
            + "{\"featureType\":\"road\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#2a3a66\"}]},"
            + "{\"featureType\":\"road.arterial\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#3a4d80\"}]},"
            + "{\"featureType\":\"road.highway\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#4a62a0\"}]},"
            + "{\"featureType\":\"water\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#0c2a55\"}]},"
            + "{\"featureType\":\"poi.park\",\"elementType\":\"geometry\",\"stylers\":[{\"color\":\"#12303f\"}]}]";

    boolean abrir(Context c, SurfaceContainer sc, final Pintor p, final float u) {
        try {
            if (sc.getSurface() == null || sc.getWidth() == 0 || sc.getHeight() == 0 || sc.getDpi() == 0) return false;
            vd = c.getSystemService(DisplayManager.class).createVirtualDisplay("CubiGoogle", sc.getWidth(), sc.getHeight(), sc.getDpi(),
                    sc.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            pr = new Presentation(c, vd.getDisplay());
            FrameLayout raiz = new FrameLayout(pr.getContext());
            lienzo = new View(pr.getContext()) { @Override protected void onDraw(Canvas k) { p.pintar(k); } };
            lienzo.setLayerType(View.LAYER_TYPE_HARDWARE, null);      // capa propia: el hueco del mapa se borra a transparente
            vista = new NavigationViewForAuto(c);
            vista.onCreate(null); vista.onStart(); vista.onResume();
            vista.setVisibility(View.INVISIBLE);
            borde = new View(pr.getContext()) {                        // las cuatro orillas, del tono del fondo a transparente
                private final android.graphics.Paint q = new android.graphics.Paint();
                private void lado(Canvas k, float x0, float y0, float x1, float y1, float gx0, float gy0, float gx1, float gy1) {
                    q.setShader(new android.graphics.LinearGradient(gx0, gy0, gx1, gy1, TONO, TONO & 0x00ffffff, android.graphics.Shader.TileMode.CLAMP));
                    k.drawRect(x0, y0, x1, y1, q);
                }
                @Override protected void onDraw(Canvas k) {
                    float w = getWidth(), h = getHeight(), f = 10 * u;
                    lado(k, 0, 0, f * 1.6f, h, 0, 0, f * 1.6f, 0);       // izquierda (hacia Zelda), más ancha
                    lado(k, w - f, 0, w, h, w, 0, w - f, 0);
                    lado(k, 0, 0, w, f * 1.3f, 0, 0, 0, f * 1.3f);
                    lado(k, 0, h - f, w, h, 0, h, 0, h - f);
                }
            };
            borde.setVisibility(View.INVISIBLE);
            raiz.addView(vista, new FrameLayout.LayoutParams(1, 1));
            raiz.addView(borde, new FrameLayout.LayoutParams(1, 1));
            raiz.addView(lienzo, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            pr.setContentView(raiz);
            pr.show();
            vista.getMapAsync(m -> {
                mapa = m;
                try { m.setMapColorScheme(com.google.android.gms.maps.model.MapColorScheme.DARK); } catch (Throwable t) { }
                try { m.setMapStyle(new com.google.android.gms.maps.model.MapStyleOptions(ESTILO)); } catch (Throwable t) { }
                try { m.setMyLocationEnabled(true); } catch (Throwable t) { }
                try { m.setPadding(Math.round(margen), 0, 0, Math.round(pieM)); } catch (Throwable t) { }
                try { m.followMyLocation(GoogleMap.CameraPerspective.TILTED); } catch (Throwable t) { }
            });
            return true;
        } catch (Throwable t) {
            estado = "Google: no pude abrir el mapa en el carro (" + t + ").";
            cerrar();
            return false;
        }
    }

    // cada cuadro: repinta lo de Cubi y acomoda el mapa de Google en su recuadro (vacío = sin mapa a la vista)
    // izq: lo que tapa Zelda a la izquierda (el carrito se centra en lo que queda libre). lat, lon: el GPS de Cubi.
    private float margen, pieM; private double lat = Double.NaN, lon; private boolean ubicado; private long tocado;
    void cuadro(RectF r, float izq, float pie, double la, double lo) {
        if (lienzo == null || vista == null) return;
        lienzo.invalidate();
        lat = la; lon = lo;
        if (izq != margen || pie != pieM) { margen = izq; pieM = pie; if (mapa != null) try { mapa.setPadding(Math.round(izq), 0, 0, Math.round(pie)); } catch (Throwable t) { } }
        // al abrir (apenas hay GPS) y a los 10 s de haberlo movido con el dedo, el mapa vuelve al carro
        if (mapa != null && ((!ubicado && !Double.isNaN(la)) || (tocado != 0 && System.currentTimeMillis() - tocado > 10000))) centrar();
        if (r.isEmpty()) { if (vista.getVisibility() == View.VISIBLE) { vista.setVisibility(View.INVISIBLE); borde.setVisibility(View.INVISIBLE); } donde.setEmpty(); return; }
        if (!r.equals(donde)) {
            donde.set(r);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(r.width()), Math.round(r.height()));
            lp.leftMargin = Math.round(r.left); lp.topMargin = Math.round(r.top);
            vista.setLayoutParams(lp);
            borde.setLayoutParams(new FrameLayout.LayoutParams(lp));
        }
        if (vista.getVisibility() != View.VISIBLE) { vista.setVisibility(View.VISIBLE); borde.setVisibility(View.VISIBLE); }
    }
    void mover(float dx, float dy) { tocado = System.currentTimeMillis(); if (mapa != null) try { mapa.moveCamera(CameraUpdateFactory.scrollBy(dx, dy)); } catch (Throwable t) { } }
    // el botón de centrar: salta a donde está el carro (por si Google aún no tiene su propia ubicación) y lo sigue
    void centrar() {
        tocado = 0; if (mapa == null) return;
        if (!Double.isNaN(lat)) { ubicado = true; try { mapa.moveCamera(CameraUpdateFactory.newLatLngZoom(new com.google.android.gms.maps.model.LatLng(lat, lon), 16.5f)); } catch (Throwable t) { } }
        try { mapa.followMyLocation(GoogleMap.CameraPerspective.TILTED); } catch (Throwable t) { }
    }
    void acercar(float f, float x, float y) {
        tocado = System.currentTimeMillis();
        if (mapa != null) try { mapa.animateCamera(CameraUpdateFactory.zoomBy(f - 1, new Point((int) (x - donde.left), (int) (y - donde.top)))); } catch (Throwable t) { }
    }
    void cerrar() {
        try { if (vista != null) { vista.onPause(); vista.onStop(); vista.onDestroy(); } } catch (Throwable t) { }
        try { if (pr != null) pr.dismiss(); } catch (Throwable t) { }
        try { if (vd != null) vd.release(); } catch (Throwable t) { }
        vista = null; pr = null; vd = null; mapa = null; lienzo = null; borde = null;
    }
}
