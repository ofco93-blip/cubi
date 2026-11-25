package com.cubi.robot;

import android.app.Activity;
import android.app.Application;
import android.app.Presentation;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Point;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.View;
import android.view.ViewOutlineProvider;
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
// con dos capas: abajo el dibujo de Cubi (Zelda, letra, barra) y encima, en el recuadro del mapa, la vista de Google.
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
    private static boolean terminos(Context c) {
        try { return NavigationApi.areTermsAccepted((Application) c.getApplicationContext()); } catch (Throwable t) { return false; }
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
            NavigationApi.setApiKey(k);
            NavigationApi.getNavigator((Application) c.getApplicationContext(), new NavigationApi.NavigatorListener() {
                @Override public void onNavigatorReady(Navigator n) {
                    nav = n; pidiendo = false; estado = "Google: listo.";
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
            NavigationApi.setApiKey(llave(a));
            NavigationApi.showTermsAndConditionsDialog(a, "Cubi", ok -> {
                estado = ok ? "" : "Google: falta aceptar los términos.";
                if (ok) preparar(a);
            });
        } catch (Throwable t) { estado = "Google: " + t; }
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

    boolean abrir(Context c, SurfaceContainer sc, final Pintor p, final float radio) {
        try {
            if (sc.getSurface() == null || sc.getWidth() == 0 || sc.getHeight() == 0 || sc.getDpi() == 0) return false;
            vd = c.getSystemService(DisplayManager.class).createVirtualDisplay("CubiGoogle", sc.getWidth(), sc.getHeight(), sc.getDpi(),
                    sc.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            pr = new Presentation(c, vd.getDisplay());
            FrameLayout raiz = new FrameLayout(pr.getContext());
            lienzo = new View(pr.getContext()) { @Override protected void onDraw(Canvas k) { p.pintar(k); } };
            vista = new NavigationViewForAuto(c);
            vista.onCreate(null); vista.onStart(); vista.onResume();
            vista.setOutlineProvider(new ViewOutlineProvider() {       // las esquinas redondas del recuadro
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radio); }
            });
            vista.setClipToOutline(true);
            vista.setVisibility(View.INVISIBLE);
            raiz.addView(lienzo, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            raiz.addView(vista, new FrameLayout.LayoutParams(1, 1));
            pr.setContentView(raiz);
            pr.show();
            vista.getMapAsync(m -> { mapa = m; try { m.followMyLocation(GoogleMap.CameraPerspective.TILTED); } catch (Throwable t) { } });
            return true;
        } catch (Throwable t) {
            estado = "Google: no pude abrir el mapa en el carro (" + t + ").";
            cerrar();
            return false;
        }
    }

    // cada cuadro: repinta lo de Cubi y acomoda el mapa de Google en su recuadro (vacío = sin mapa a la vista)
    void cuadro(RectF r) {
        if (lienzo == null || vista == null) return;
        lienzo.invalidate();
        if (r.isEmpty()) { if (vista.getVisibility() == View.VISIBLE) vista.setVisibility(View.INVISIBLE); donde.setEmpty(); return; }
        if (!r.equals(donde)) {
            donde.set(r);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(r.width()), Math.round(r.height()));
            lp.leftMargin = Math.round(r.left); lp.topMargin = Math.round(r.top);
            vista.setLayoutParams(lp);
        }
        if (vista.getVisibility() != View.VISIBLE) vista.setVisibility(View.VISIBLE);
    }
    void mover(float dx, float dy) { if (mapa != null) try { mapa.moveCamera(CameraUpdateFactory.scrollBy(dx, dy)); } catch (Throwable t) { } }
    void acercar(float f, float x, float y) {
        if (mapa != null) try { mapa.animateCamera(CameraUpdateFactory.zoomBy(f - 1, new Point((int) (x - donde.left), (int) (y - donde.top)))); } catch (Throwable t) { }
    }
    void cerrar() {
        try { if (vista != null) { vista.onPause(); vista.onStop(); vista.onDestroy(); } } catch (Throwable t) { }
        try { if (pr != null) pr.dismiss(); } catch (Throwable t) { }
        try { if (vd != null) vd.release(); } catch (Throwable t) { }
        vista = null; pr = null; vd = null; mapa = null; lienzo = null;
    }
}
