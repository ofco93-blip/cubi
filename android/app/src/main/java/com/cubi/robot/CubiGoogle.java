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
    static volatile String busca = "";                             // por qué no está buscando Google (se ve en la pestaña Mapa); vacío = bien
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
                    try { n.registerServiceForNavUpdates(c.getPackageName(), CubiGiros.class.getName(), 1); } catch (Throwable t) { }   // los giros llegan a CubiGiros
                    try { n.addRouteChangedListener(CubiGoogle::rutaDeGoogle); } catch (Throwable t) { }   // si Google recalcula, Mapi pinta la nueva
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
    static volatile int faltaSeg = -1, faltaMet = -1;   // lo mismo en números (para Mapi); se ponen al llamar falta()
    static String falta() {
        Navigator n = nav; long t = System.currentTimeMillis();
        if (n == null || Double.isNaN(irLat)) return "";
        if (t - faltaEn < 2000) return faltaTxt;
        faltaEn = t;
        try {
            com.google.android.libraries.navigation.TimeAndDistance td = n.getCurrentTimeAndDistance();
            if (td == null || td.getMeters() <= 0) { faltaSeg = faltaMet = -1; return faltaTxt = ""; }
            faltaSeg = td.getSeconds(); faltaMet = td.getMeters();
            int seg = td.getSeconds(), min = Math.max(1, Math.round(seg / 60f)); double km = td.getMeters() / 1000.0;
            java.util.Calendar cal = java.util.Calendar.getInstance(); cal.add(java.util.Calendar.SECOND, seg);
            int hh = cal.get(java.util.Calendar.HOUR);
            return faltaTxt = (min >= 60 ? min / 60 + " h " + min % 60 + " min" : min + " min") + "  ·  "
                    + (km < 10 ? String.format(java.util.Locale.US, "%.1f km", km).replace('.', ',') : Math.round(km) + " km")
                    + "  ·  llega " + String.format(java.util.Locale.US, "%d:%02d", hh == 0 ? 12 : hh, cal.get(java.util.Calendar.MINUTE));
        } catch (Throwable e) { return faltaTxt = ""; }
    }

    // El giro que viene, según Google (llega a CubiGiros cada vez que cambia): se deja donde Cubi ya guarda el giro de
    // Maps, así la pantalla del carro lo pinta igual: flecha, metros y calle, en el cuadrito que ocupa el puesto de la hora.
    private static java.util.HashMap<Integer, String> maniobras; private static boolean conGiro;
    static void giro(com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo i) {
        CubiService s = CubiService.yo;
        if (s == null || i == null) return;
        com.google.android.libraries.mapsplatform.turnbyturn.model.StepInfo p = i.getCurrentStep();
        if (p == null || i.getNavState() != com.google.android.libraries.mapsplatform.turnbyturn.model.NavState.ENROUTE) { sinGiro(); return; }
        if (maniobras == null) {                                      // los nombres de las maniobras (TURN_LEFT, ROUNDABOUT_…), leídos de Google
            maniobras = new java.util.HashMap<>();
            try { for (java.lang.reflect.Field f : com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver.class.getFields()) if (f.getType() == int.class) maniobras.put(f.getInt(null), f.getName()); } catch (Throwable t) { }
        }
        String n = maniobras.get(p.getManeuver()); if (n == null) n = "";
        Integer d = i.getDistanceToCurrentStepMeters();
        boolean fin = n.startsWith("DESTINATION");
        String dir = fin && d != null && d < 30 ? "llegamos" : n.contains("LEFT") || (n.contains("U_TURN") && n.endsWith("COUNTERCLOCKWISE")) ? "maps_izq" : n.contains("RIGHT") || n.contains("U_TURN") ? "maps_der" : "maps_recto";
        String calle = p.getFullRoadName(); if (calle == null) calle = ""; if (calle.length() > 26) calle = calle.substring(0, 26).trim();
        s.navForma = n.contains("U_TURN") ? 3 : n.contains("ROUNDABOUT") ? 4 : n.contains("SLIGHT") ? 1 : n.contains("SHARP") ? 2 : n.contains("RAMP") || n.contains("FORK") || n.contains("KEEP") ? 5 : 0;
        s.navMetros = d == null ? -1 : d; s.navMetrosCuando = System.currentTimeMillis(); s.navCalle = fin ? "tu destino" : calle; s.navDir = dir; s.navCuando = System.currentTimeMillis(); conGiro = true;
    }
    static boolean guiando() { return conGiro && !Double.isNaN(irLat); }   // el Google de Mascota Auto está dando giros ahora
    private static void sinGiro() { CubiService s = CubiService.yo; if (conGiro && s != null) s.navDir = ""; conGiro = false; }

    static void ir(double lat, double lon) {
        irLat = lat; irLon = lon;
        final Navigator n = nav;
        if (n == null) return;
        try {
            estado = "Google: calculando la ruta…";
            n.setDestination(Waypoint.builder().setLatLng(lat, lon).build()).setOnResultListener(st -> {
                if (st == Navigator.RouteStatus.OK) { n.startGuidance(); estado = "Google: navegando."; rutaDeGoogle(); }
                else estado = "Google: no hay ruta (" + st + ").";
            });
        } catch (Throwable t) { estado = "Google: " + t; }
    }
    // la línea de la ruta que va guiando Google, para que Mapi pinte exactamente la misma (antes Cubi calculaba la suya
    // aparte y al recalcular a veces salían distintas)
    static void rutaDeGoogle() {
        Navigator n = nav; CubiService s = CubiService.yo;
        if (n == null || s == null || Double.isNaN(irLat)) return;
        try {
            java.util.ArrayList<double[]> pts = new java.util.ArrayList<>();
            for (com.google.android.libraries.navigation.RouteSegment g : n.getRouteSegments())
                for (com.google.android.gms.maps.model.LatLng p : g.getLatLngs()) pts.add(new double[]{p.latitude, p.longitude});
            if (pts.size() < 2) return;
            double[] la = new double[pts.size()], lo = new double[pts.size()];
            for (int i = 0; i < la.length; i++) { la[i] = pts.get(i)[0]; lo[i] = pts.get(i)[1]; }
            s.rutaDeGoogle(la, lo);
        } catch (Throwable ignored) { }
    }
    // el rumbo del mapa de Google en la pantalla del carro (hacia dónde mira la cámara que sigue al carro): Mapi gira igual
    static volatile float rumboG = Float.NaN; static volatile long rumboGT;
    static float rumbo() { return System.currentTimeMillis() - rumboGT < 3000 ? rumboG : Float.NaN; }
    static void parar() {
        irLat = Double.NaN; sinGiro();
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
            raiz.addView(vista, new FrameLayout.LayoutParams(1, 1));
            raiz.addView(lienzo, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            pr.setContentView(raiz);
            pr.show();
            vista.getMapAsync(m -> {
                mapa = m;
                try { m.setMapColorScheme(com.google.android.gms.maps.model.MapColorScheme.DARK); } catch (Throwable t) { }
                try { m.setMapStyle(new com.google.android.gms.maps.model.MapStyleOptions(ESTILO)); } catch (Throwable t) { }
                try { m.setMyLocationEnabled(true); } catch (Throwable t) { }
                try { m.setPadding(Math.round(mar.left), Math.round(mar.top), Math.round(mar.right), Math.round(mar.bottom)); } catch (Throwable t) { }
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
    // m: lo que tapan Zelda (izquierda), la barra (abajo) y los bordes del carro; el carrito se centra en lo que queda libre. lat, lon: el GPS de Cubi.
    private final RectF mar = new RectF(); private double lat = Double.NaN, lon; private boolean ubicado; private long tocado;
    void cuadro(RectF r, RectF m, double la, double lo) {
        if (lienzo == null || vista == null) return;
        lienzo.invalidate();
        lat = la; lon = lo;
        if (mapa != null && tocado == 0) try { rumboG = mapa.getCameraPosition().bearing; rumboGT = System.currentTimeMillis(); } catch (Throwable ignored) { }
        if (mapa != null && !Double.isNaN(la) && (CubiRadares.version != radVer || Double.isNaN(radLa) || CubiRadares.metros(radLa, radLo, la, lo) > 8000)) ponerRadares(la, lo);
        if (!m.equals(mar)) { mar.set(m); if (mapa != null) try { mapa.setPadding(Math.round(m.left), Math.round(m.top), Math.round(m.right), Math.round(m.bottom)); } catch (Throwable t) { } }
        // al abrir (apenas hay GPS) y a los 10 s de haberlo movido con el dedo, el mapa vuelve al carro
        if (mapa != null && ((!ubicado && !Double.isNaN(la)) || (tocado != 0 && System.currentTimeMillis() - tocado > 10000))) centrar();
        if (r.isEmpty()) { if (vista.getVisibility() == View.VISIBLE) vista.setVisibility(View.INVISIBLE); donde.setEmpty(); return; }
        if (!r.equals(donde)) {
            donde.set(r);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(r.width()), Math.round(r.height()));
            lp.leftMargin = Math.round(r.left); lp.topMargin = Math.round(r.top);
            vista.setLayoutParams(lp);
        }
        if (vista.getVisibility() != View.VISIBLE) vista.setVisibility(View.VISIBLE);
    }
    // los radares (OpenStreetMap) como íconos rojos en el mapa de Google: los de 15 km a la redonda, otra vez al alejarse 8 km
    private final java.util.List<com.google.android.gms.maps.model.Marker> marcas = new java.util.ArrayList<>();
    private int radVer = -1; private double radLa = Double.NaN, radLo;
    private static com.google.android.gms.maps.model.BitmapDescriptor iconoRadar;
    private void ponerRadares(double la, double lo) {
        radVer = CubiRadares.version; radLa = la; radLo = lo;
        try {
            for (com.google.android.gms.maps.model.Marker k : marcas) k.remove();
            marcas.clear();
            if (iconoRadar == null) iconoRadar = com.google.android.gms.maps.model.BitmapDescriptorFactory.fromBitmap(dibujoRadar(56));
            for (CubiRadares.Radar r : CubiRadares.lista()) {
                if (marcas.size() >= 80 || CubiRadares.metros(la, lo, r.la, r.lo) > 15000) continue;
                com.google.android.gms.maps.model.Marker k = mapa.addMarker(new com.google.android.gms.maps.model.MarkerOptions()
                        .position(new com.google.android.gms.maps.model.LatLng(r.la, r.lo)).icon(iconoRadar).anchor(0.5f, 0.5f).zIndex(5));
                if (k != null) marcas.add(k);
            }
        } catch (Throwable ignored) { }
    }
    // el ícono: un círculo rojo con borde blanco y una camarita blanca adentro
    static android.graphics.Bitmap dibujoRadar(int n) {
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float r = n / 2f;
        p.setColor(0x55000000); c.drawCircle(r, r + n * 0.03f, r * 0.92f, p);
        p.setColor(0xffffffff); c.drawCircle(r, r, r * 0.9f, p);
        p.setColor(0xffd93025); c.drawCircle(r, r, r * 0.74f, p);
        p.setColor(0xffffffff);
        c.drawRoundRect(r - r * 0.42f, r - r * 0.24f, r + r * 0.42f, r + r * 0.3f, r * 0.1f, r * 0.1f, p);   // el cuerpo
        c.drawRect(r - r * 0.16f, r - r * 0.36f, r + r * 0.12f, r - r * 0.2f, p);                          // la tapita de arriba
        p.setColor(0xffd93025); c.drawCircle(r, r + r * 0.03f, r * 0.17f, p);                             // el lente
        return b;
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
        vista = null; pr = null; vd = null; mapa = null; lienzo = null; marcas.clear(); radVer = -1; radLa = Double.NaN;
    }
}
