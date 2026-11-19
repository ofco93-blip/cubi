package com.cubi.robot;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.car.app.AppManager;
import androidx.car.app.CarAppService;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.validation.HostValidator;
import androidx.core.graphics.drawable.IconCompat;

import java.util.List;
import java.util.Locale;

// Cubi en la pantalla del carro (Android Auto). Se anuncia como app de navegación porque solo esas pueden dibujar
// libremente y salir en la vista dividida; no navega: pinta la carita, el giro que el celular lee de Maps o Waze, el
// mini mapa de calles (si "Modo mapa" está prendido) y lo que suena. Todo sale de CubiService; nada toca el carro.
public class CubiCarro extends CarAppService {
    @NonNull @Override public HostValidator createHostValidator() { return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR; }

    @NonNull @Override public Session onCreateSession() {
        return new Session() {
            @NonNull @Override public Screen onCreateScreen(@NonNull Intent intent) { return new Pantalla(getCarContext()); }
        };
    }

    static class Pantalla extends Screen implements SurfaceCallback {
        private final Handler h = new Handler(Looper.getMainLooper());
        private final SharedPreferences prefs;
        private final Rect zona = new Rect(), firme = new Rect();   // lo que se ve, y lo que además queda libre de botones
        private final Lienzo lienzo = new Lienzo();
        private Surface sup;
        private int ancho, alto;
        private boolean sonaba;
        private final Runnable cuadro = new Runnable() {
            @Override public void run() { pintar(); if (sup != null) h.postDelayed(this, 33); }
        };

        Pantalla(CarContext c) {
            super(c);
            prefs = c.getSharedPreferences("cubi", 0);
            lienzo.assets = c.getAssets();
            c.getCarService(AppManager.class).setSurfaceCallback(this);
        }

        @NonNull @Override public Template onGetTemplate() {
            return new NavigationTemplate.Builder().setActionStrip(new ActionStrip.Builder()
                    .addAction(tecla(R.drawable.carro_antes, -1)).addAction(tecla(sonaba ? R.drawable.carro_pausa : R.drawable.carro_play, 0)).addAction(tecla(R.drawable.carro_sigue, 1))
                    .addAction(new Action.Builder().setTitle("Opciones").setOnClickListener(() -> getScreenManager().push(new Opciones(getCarContext()))).build()).build()).build();
        }

        private Action tecla(int icono, int cual) {
            return new Action.Builder().setIcon(new CarIcon.Builder(IconCompat.createWithResource(getCarContext(), icono)).build())
                    .setOnClickListener(() -> { if (CubiService.yo != null) CubiService.yo.musicaTecla(cual); }).build();
        }

        @Override public void onSurfaceAvailable(@NonNull SurfaceContainer s) {
            sup = s.getSurface(); ancho = s.getWidth(); alto = s.getHeight();
            h.removeCallbacks(cuadro); h.post(cuadro);
        }
        @Override public void onVisibleAreaChanged(@NonNull Rect r) { zona.set(r); }
        @Override public void onStableAreaChanged(@NonNull Rect r) { firme.set(r); }
        @Override public void onSurfaceDestroyed(@NonNull SurfaceContainer s) { sup = null; h.removeCallbacks(cuadro); }

        private void pintar() {
            boolean suena = CubiService.yo != null && CubiService.yo.musicaPlay;
            if (suena != sonaba) { sonaba = suena; invalidate(); }   // el botón cambia entre pausa y seguir
            Surface s = sup;
            if (s == null || !s.isValid()) return;
            Canvas c;
            try { c = s.lockHardwareCanvas(); } catch (Exception e) { return; }
            try {
                Rect a = zona.isEmpty() ? new Rect(0, 0, ancho, alto) : zona;
                lienzo.dibujar(c, a, firme, CubiService.yo, prefs.getInt("carroVista", prefs.getBoolean("carroSola", false) ? 1 : 0), prefs.getInt("ojos", 0xffffff), prefs.getBoolean("carroZelda", true), prefs.getInt("carroFondo", -1));
            } catch (Throwable t) {
                // un error al dibujar no puede tumbar la app: se apagan los clips (queda la Zelda dibujada) y el error
                // sale en letra pequeña en la pantalla del carro, para poder verlo
                lienzo.assets = null; lienzo.falla = String.valueOf(t);
            } finally {
                try { s.unlockCanvasAndPost(c); } catch (Throwable t) { }
            }
        }
    }

    // Las opciones: tres renglones; cada toque pasa a la siguiente opción de ese renglón. El personaje es solo de esta pantalla: el robot sigue con el suyo.
    static class Opciones extends Screen {
        static final String[] FONDOS = {"Automático (según la hora y la música)", "Cielo", "Chicle", "Menta", "Lila", "Noche"};
        static final String[] VISTAS = {"Personaje, y mapa cuando hay ruta", "Solo el personaje", "Solo el mapa"};
        Opciones(CarContext c) { super(c); }

        @NonNull @Override public Template onGetTemplate() {
            SharedPreferences p = getCarContext().getSharedPreferences("cubi", 0);
            boolean zelda = p.getBoolean("carroZelda", true);
            int fondo = p.getInt("carroFondo", -1), vista = p.getInt("carroVista", p.getBoolean("carroSola", false) ? 1 : 0);
            ItemList lista = new ItemList.Builder()
                    .addItem(fila("Personaje en esta pantalla", zelda ? "Zelda" : "Cubi", () -> p.edit().putBoolean("carroZelda", !zelda).apply()))
                    .addItem(fila("Fondo de Zelda", FONDOS[fondo + 1], () -> p.edit().putInt("carroFondo", fondo >= 4 ? -1 : fondo + 1).apply()))
                    .addItem(fila("Vista", VISTAS[vista], () -> p.edit().putInt("carroVista", (vista + 1) % 3).apply()))
                    .build();
            return new ListTemplate.Builder().setTitle("Opciones de Cubi").setHeaderAction(Action.BACK).setSingleList(lista).build();
        }

        private Row fila(String titulo, String valor, Runnable alTocar) {
            return new Row.Builder().setTitle(titulo).addText(valor).setOnClickListener(() -> { alTocar.run(); invalidate(); }).build();
        }
    }

    // El dibujo. Las medidas van en "u" (una centésima del alto útil) para que sirva en cualquier pantalla.
    static class Lienzo {
        static final int AZUL = 0xff46a5ff, TINTA = 0xffe6ecfb, GRIS = 0xff93a3c7, PANEL = 0xff0c1322, VELO = 0x55000000;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path flecha = new Path(), recorte = new Path();
        private final Path[] calles = {new Path(), new Path(), new Path()};   // 0 calles, 1 avenidas, 2 la ruta
        private List<String> callesDe;

        // vista: 0 personaje con mapa, 1 solo el personaje, 2 solo el mapa. zelda: el personaje es la gata (si no, los ojos de Cubi)
        void dibujar(Canvas c, Rect a, Rect firme, CubiService s, int vista, int ojos, boolean zelda, int fondo) {
            long ahora = System.currentTimeMillis();
            c.drawColor(0xff000000);
            dibujarTodo(c, a, firme, s, vista, ojos, zelda, fondo, ahora);
            if (falla != null) texto(c, falla, a.left + 12, a.bottom - 10, 14, GRIS, false, Paint.Align.LEFT, a.width() - 24);
        }
        private void dibujarTodo(Canvas c, Rect a, Rect firme, CubiService s, int vista, int ojos, boolean zelda, int fondo, long ahora) {
            boolean musica = s != null && !s.cancionTitulo.isEmpty(), suena = musica && s.musicaPlay;
            String cancion = !musica ? "" : "♪ " + s.cancionTitulo + (s.cancionArtista.isEmpty() ? "" : " · " + s.cancionArtista);
            boolean nav = s != null && !s.navDir.isEmpty() && ahora - s.navCuando < 120000;
            boolean aLoAncho = a.width() >= a.height() * 1.5f;
            // angosta = la vista dividida de Android Auto: los botones quedan sobre la carita (se baja todo a la zona libre)
            // y al lado ya está la tarjeta de música, así que la franja de la canción sobra
            // ponytail: una pantalla vertical a pantalla completa también cae aquí y se queda sin franja; darle su propio caso si aparece
            if (!aLoAncho && !firme.isEmpty()) a = firme;
            boolean franja = musica && aLoAncho && !(zelda && vista == 0);   // con Zelda la canción ya va en su cajita
            fundido = false;
            float u = Math.min(a.height(), a.width() * (aLoAncho ? 0.5f : 1f)) / 100f;
            int modo = suena ? 2 : nav ? 1 : (ahora / 1000) % 34 >= 28 ? 1 : 0;   // Zelda: canta con música, va despierta manejando y, si no, duerme (y cada rato se despierta)
            if (vista == 1) {
                if (zelda) { zelda(c, a.left, a.top, a.right, a.bottom, 0, ahora, modo, fondo, s, 1); return; }
                cara(c, a.centerX(), a.centerY() - (franja ? 5 * u : 0), 60 * u, ojos, suena, ahora);
                if (franja) texto(c, cancion, a.centerX(), a.bottom - 9 * u, 5.5f * u, GRIS, false, Paint.Align.CENTER, a.width() - 12 * u);
                return;
            }
            if (zelda && vista == 0 && !nav) { zelda(c, a.left, a.top, a.right, a.bottom, 0, ahora, modo, fondo, s, 1); return; }   // sin ruta: Zelda en grande
            // la carita a un lado (arriba si la pantalla es angosta) y el panel con lo demás
            float corte = aLoAncho ? a.left + a.width() * 0.38f : a.top + a.height() * 0.46f, m = 3 * u;
            if (vista == 2) corte = aLoAncho ? a.left : a.top;                      // solo el mapa: el panel ocupa todo
            else if (zelda) {                                                       // con ruta: el mapa en grande y, a un lado (abajo si es angosta), los datos del viaje y Zelda
                // el fondo del clip llena toda la pantalla y encima, translúcidos, van el mapa y los datos: así cambian de
                // color con el clip (azul, o violeta con rap) y todo queda fundido
                fundido = true;
                if (aLoAncho) {                                                     // Zelda a la izquierda, el mapa en lo que queda libre y la cajita abajo
                    zelda(c, a.left, a.top, a.right, a.bottom, 0, ahora, modo, fondo, s, 2);
                    float izq = Math.max(zLibre, a.left + a.width() * 0.3f) + m, abajo = a.bottom - Math.max(a.height() * 0.16f, 30) - m, parte = suena ? a.top + m + (abajo - a.top - m) * 0.68f : abajo;
                    if (suena) {                                                    // la letra, sobre una tarjeta oscura para que se lea
                        gr.set(izq, parte + m / 2, a.right - m, abajo); p.setStyle(Paint.Style.FILL); p.setColor(0x73000000); c.drawRoundRect(gr, 5 * u, 5 * u, p);
                        letra(c, s, gr.centerX(), gr.top + gr.height() * 0.4f, gr.width() * 0.92f, gr.height() * 0.26f);
                    }
                    r.set(izq, a.top + m, a.right - m, parte - (suena ? m / 2 : 0));
                } else {                                                            // angosta: el mapa arriba; abajo Zelda y los datos del viaje
                    float cy = a.top + a.height() * 0.62f, cx = a.left + a.width() * 0.42f;
                    zelda(c, a.left, cy, cx, a.bottom, 0, ahora, modo, fondo, s, 3); viaje(c, cx + m / 2, cy, a.right - m, a.bottom - m, s, u);
                    r.set(a.left + m, a.top, a.right - m, cy - m);
                }
            }
            else if (aLoAncho) cara(c, (a.left + corte) / 2, a.centerY(), 46 * u, ojos, suena, ahora);
            else cara(c, a.centerX(), (a.top + corte) / 2, Math.min(46 * u, (corte - a.top) * 0.95f), ojos, suena, ahora);
            if (zelda && vista == 0) { }                                             // el panel ya quedó puesto arriba
            else if (aLoAncho) r.set(corte, a.top + m, a.right - m, a.bottom - m); else r.set(a.left + m, corte, a.right - m, a.bottom - m);
            float x0 = r.left, y0 = r.top, x1 = r.right, y1 = r.bottom, w = x1 - x0;
            recorte.rewind(); recorte.addRoundRect(r, 5 * u, 5 * u, Path.Direction.CW);
            c.save(); c.clipPath(recorte);
            boolean conMapa = nav && s.mapaTramos != null && s.navMapa();
            p.setStyle(Paint.Style.FILL); p.setColor(fundido ? VELO : PANEL); if (!(fundido && conMapa)) c.drawRect(r, p);   // fundido y con mapa: el velo lo pone el mapa, desvanecido
            if (conMapa) mapa(c, s, x0, y0, x1, y1, u);
            float pie = franja ? 22 * u : 0;
            if (nav) {
                // el giro: sobre el mapa va en una tarjeta arriba a la izquierda; sin mapa, grande y al centro
                boolean llego = s.navDir.equals("llegamos");
                String dist = llego ? "Llegaste" : metros(s.navMetros), calle = llego ? "" : s.navCalle;
                float k = conMapa ? (y1 - y0 < 62 * u ? 0.72f : 1f) : 1.5f, fx = x0 + 15 * u * k, fy = conMapa ? y0 + 3 * u + 14 * u * k : (y0 + y1 - pie) / 2;
                if (conMapa) { p.setColor(fundido ? 0x8005070c : 0xd905070c); r.set(x0 + 3 * u, y0 + 3 * u, x0 + Math.min(w - 3 * u, 78 * u * k), y0 + 3 * u + 28 * u * k); c.drawRoundRect(r, 4 * u, 4 * u, p); }
                if (!llego) flecha(c, fx, fy, 9 * u * k, s.navDir, s.navForma);
                float tx = llego ? x0 + 8 * u * k : fx + 15 * u * k, tw = (conMapa ? x0 + 76 * u * k : x1 - 4 * u) - tx;
                if (dist.isEmpty()) texto(c, calle, tx, fy + 2.5f * u * k, 6.5f * u * k, TINTA, true, Paint.Align.LEFT, tw);   // indicación sin metros
                else {
                    texto(c, dist, tx, fy - (calle.isEmpty() ? -4 * u * k : 1 * u * k), 12 * u * k, TINTA, true, Paint.Align.LEFT, tw);
                    if (!calle.isEmpty()) texto(c, calle, tx, fy + 8 * u * k, 5.5f * u * k, GRIS, false, Paint.Align.LEFT, tw);
                }
            } else {
                // sin ruta: la hora y, si hay GPS, la velocidad
                java.util.Calendar cal = java.util.Calendar.getInstance();
                float cy = (y0 + y1 - pie) / 2;
                texto(c, String.format(Locale.US, "%d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)), x0 + w / 2, cy + 4 * u, 28 * u, TINTA, true, Paint.Align.CENTER, w);
                if (s != null && s.kmh >= 3) texto(c, Math.round(s.kmh) + " km/h", x0 + w / 2, cy + 17 * u, 7 * u, GRIS, false, Paint.Align.CENTER, w);
                if (s == null) texto(c, "Abre la app de Cubi en el celular", x0 + w / 2, cy + 20 * u, 5.5f * u, GRIS, false, Paint.Align.CENTER, w - 8 * u);
            }
            if (nav && fundido && aLoAncho) {
                String d = s.navDestM < 0 ? "" : metros(s.navDestM) + (s.navLlega.isEmpty() ? " al destino" : "  ·  llegamos " + s.navLlega);
                boolean haySeg = s.navMetros >= 0 && s.kmh >= 8 && s.navMetros * 3.6 / s.kmh < 100;
                if (haySeg) d += (d.isEmpty() ? "" : "  ·  ") + "giro en " + Math.round(s.navMetros * 3.6 / s.kmh) + " s";
                if (!d.isEmpty()) {
                    p.setColor(0x8005070c); c.drawRect(x0, y1 - 13 * u, x1, y1, p);
                    texto(c, d, x0 + w / 2, y1 - 4.5f * u, 6 * u, TINTA, true, Paint.Align.CENTER, w - 8 * u);
                }
            }
            if (franja) {
                p.setColor(0xe605070c); c.drawRect(x0, y1 - pie, x1, y1, p);
                float tx = x0 + 5 * u;
                android.graphics.Bitmap car = s.cancionCaratula;
                if (car != null && !car.isRecycled()) {
                    r.set(x0 + 3 * u, y1 - pie + 3 * u, x0 + pie - 3 * u, y1 - 3 * u);
                    recorte.rewind(); recorte.addRoundRect(r, 2 * u, 2 * u, Path.Direction.CW);
                    c.save(); c.clipPath(recorte); p.setColor(0xffffffff); p.setFilterBitmap(true); c.drawBitmap(car, null, r, p); c.restore();
                    tx = x0 + pie + u;
                }
                texto(c, s.cancionTitulo, tx, y1 - pie / 2 - 0.5f * u, 6.5f * u, suena ? TINTA : GRIS, true, Paint.Align.LEFT, x1 - 4 * u - tx);
                texto(c, s.cancionArtista, tx, y1 - pie / 2 + 6.5f * u, 5 * u, GRIS, false, Paint.Align.LEFT, x1 - 4 * u - tx);
            }
            c.restore();
        }

        // ---- Zelda, la gata: el mismo dibujo del robot. Su cuadro de color con la hora atrás (si conHora) y ella asomada por delante.
        static final int[] FONDO_Z = {0xff35c3e8, 0xfff0457e, 0xff27c48f, 0xff8b6cf0, 0xff262262}, LETRA_Z = {0xffffd23f, 0xffffffff, 0xff0f3d33, 0xfffff3b0, 0xff9a95ff};
        private final Path gp = new Path();
        private final RectF gr = new RectF();
        // Los clips de Zelda en 3D: cuadros sueltos en assets/zelda/<clip>/NN.jpg (48 por clip, 12 por segundo).
        android.content.res.AssetManager assets;
        private final android.graphics.Matrix mz = new android.graphics.Matrix();
        static final String[] QUIETA = {"quieta"}, BAILES = {"cabeceo", "pata", "cabeceo", "baile"}, RAP = {"rapcabeza", "rapmano", "rapcabeza"};
        float zLibre;                                                 // hasta dónde llega Zelda en su clip: de ahí a la derecha queda fondo libre
        boolean fundido;                                              // el mapa y los datos van translúcidos sobre el fondo de Zelda
        String falla;                                               // el último error al dibujar, si hubo
        private android.graphics.Bitmap cuadroZ, mascZ;                // el cuadro de ahora (la gata, sin fondo) y su silueta
        private final Paint pm = new Paint(Paint.FILTER_BITMAP_FLAG);   // pinta la silueta como recorte: deja de la capa solo donde la silueta es blanca
        { pm.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)); pm.setColorFilter(new android.graphics.ColorMatrixColorFilter(new float[]{0,0,0,0,0, 0,0,0,0,0, 0,0,0,0,0, 1,0,0,0,0})); }
        private String cuadroDe = "";
        private android.graphics.Bitmap cuadro(String clip, int i) {
            String clave = clip + i;
            if (clave.equals(cuadroDe) && cuadroZ != null) return cuadroZ;
            // se decodifica siempre sobre el mismo bitmap: sin esto cada cuadro pedía 3,7 MB nuevos, 12 veces por segundo
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inMutable = true; o.inBitmap = cuadroZ;
            try (java.io.InputStream in = assets.open(String.format(Locale.US, "zelda/%s/%02d.jpg", clip, i + 1))) {
                android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeStream(in, null, o);
                if (b != null) { cuadroZ = b; cuadroDe = clave; }
                // su silueta (blanco donde está ella), para poder dejar texto por detrás
                o.inBitmap = mascZ;
                try (java.io.InputStream im = assets.open(String.format(Locale.US, "zelda/%s/m%02d.jpg", clip, i + 1))) { mascZ = android.graphics.BitmapFactory.decodeStream(im, null, o); } catch (Throwable e) { mascZ = null; }
            } catch (Throwable e) {
                cuadroZ = null; cuadroDe = ""; falla = String.valueOf(e);   // el siguiente intento pide un bitmap nuevo
                return null;
            }
            return cuadroZ;
        }
        // Los fondos: arriba, abajo y la repisa donde apoya las patas. En el orden de las opciones: cielo, chicle, menta, lila y noche.
        static final int[][] TEMA = {{0xff8fc6d6, 0xff6fb0c6, 0xffa5d6de}, {0xfff7b6cb, 0xffee8fb0, 0xfffbd0dc}, {0xffa2dec6, 0xff74c7a8, 0xffbdead9},
                {0xff6a5ea0, 0xff4f447f, 0xff8073b6}, {0xff16244a, 0xff0a1226, 0xff22335f}};
        static final int CREMA = 0xfffff0d9, NARANJA = 0xfff28c28, CAFE = 0xff3a2212;   // los colores de Zelda, para la barra
        private android.graphics.Shader fondoSh; private int fondoT = -1; private float fondoY, fondoH;
        // Zelda. Los clips traen solo a la gata (su silueta va aparte), así que el fondo lo pinta la app: automático es cielo de
        // día, noche de noche y lila con rap; o el que se escoja en Opciones. Ella va a la izquierda, en espejo.
        // estilo 0: en un cuadro redondeado; 1: pantalla entera con texto y barra; 2: entera con barra (el mapa y la letra los
        // pone quien llama, a la derecha de zLibre); 3: entera, solo ella.
        // Los bucles se encadenan: todos terminan en la misma postura (patas apoyadas), cada uno se pasa del final hacia atrás
        // y de vuelta (6 s) y al volver se cambia por otro sin que se note el corte.
        // ponytail: el bucle se escoge al azar por canción, no por la parte de la canción; y si la música arranca o para a
        // mitad de un bucle, ese cambio sí salta. Seguir el ritmo de verdad pide el tempo de la canción.
        private void zelda(Canvas c, float x0, float y0, float x1, float y1, float radio, long ahora, int modo, int fondo, CubiService sv, int estilo) {
            boolean lleno = estilo > 0, conBarra = estilo == 1 || estilo == 2, conTexto = estilo == 1;
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int h = cal.get(java.util.Calendar.HOUR_OF_DAY);
            String hora = (h % 12 == 0 ? 12 : h % 12) + ":" + String.format(Locale.US, "%02d", cal.get(java.util.Calendar.MINUTE));
            float w = x1 - x0, alto = y1 - y0, caja = conBarra ? Math.max(alto * 0.16f, 30) : 0, vh = alto - caja;
            boolean musicaZ = modo == 2;
            String[] lista = !musicaZ ? QUIETA : sv != null && "urbano".equals(sv.ritmoCancion) ? RAP : BAILES;
            final int n = 76, vuelta = 2 * n - 2;                     // 76 cuadros a 24 por segundo
            long cuadros = ahora * 24 / 1000;
            int j = (int) (cuadros % vuelta), semilla = (int) (cuadros / vuelta) * 7919 + (sv == null ? 0 : sv.cancionTitulo.hashCode());
            android.graphics.Bitmap b = assets == null ? null : cuadro(lista[(semilla & 0x7fffffff) % lista.length], n - 1 - (j < n ? j : vuelta - j));
            int tm = fondo >= 0 && fondo < TEMA.length ? fondo : !CubiService.esDeDia() ? 4 : lista == RAP ? 3 : 0;
            c.save(); gp.rewind(); gr.set(x0, y0, x1, y1); gp.addRoundRect(gr, radio, radio, Path.Direction.CW); if (!lleno) c.clipPath(gp);
            // el fondo y la repisa. El clip es de 1280x720; aquí se ajusta al alto: "sc" pasa de sus puntos a la pantalla.
            float sc = vh / 720f, libre = x0 + 1280 * sc * 0.62f, repisa = y0 + 688 * sc;   // libre: donde termina ella y empieza el fondo vacío
            zLibre = libre;
            if (fondoSh == null || fondoT != tm || fondoY != y0 || fondoH != vh) {
                fondoT = tm; fondoY = y0; fondoH = vh;
                fondoSh = new android.graphics.LinearGradient(0, y0, 0, y0 + vh, TEMA[tm][0], TEMA[tm][1], android.graphics.Shader.TileMode.CLAMP);
            }
            p.setStyle(Paint.Style.FILL); p.setColor(0xffffffff); p.setShader(fondoSh);
            if (lleno) c.drawPaint(p); else c.drawRect(x0, y0, x1, y1, p);
            p.setShader(null); p.setColor(TEMA[tm][2]);
            if (lleno) c.drawRect(-1e5f, repisa, 1e5f, 1e5f, p); else c.drawRect(x0, repisa, x1, y1, p);
            if (b == null || mascZ == null) {                         // sin clips: la Zelda dibujada de antes
                float t = Math.min(w, alto) * 0.26f;
                gato(c, (x0 + x1) / 2, y1 - t * 1.1f, t, ahora, modo, y1, 0xffffffff);
                c.restore(); return;
            }
            // Como los fondos de iPhone: la hora queda por DETRÁS de Zelda y la letra por delante.
            // Sin nada más en pantalla va enorme, a la derecha, medio metida detrás de ella; con letra o mapa a la derecha
            // sube detrás de las orejas, que la tapan a medias. En pantalla angosta y con música no cabe: va en la barra.
            boolean anchoZ = x1 - libre > w * 0.3f, ocupado = musicaZ || estilo == 2, horaGrande = conBarra && (anchoZ || !musicaZ);
            if (horaGrande) {
                tp.setTypeface(Typeface.DEFAULT_BOLD); tp.clearShadowLayer(); tp.setColor(0xffffffff);
                if (anchoZ && !ocupado) {
                    float tam = vh * 0.5f; tp.setTextSize(tam);
                    float th = tp.measureText(hora), cabe = (x1 - libre) * 1.1f;
                    if (th > cabe) { tam *= cabe / th; tp.setTextSize(tam); th = cabe; }
                    tp.setTextAlign(Paint.Align.LEFT); c.drawText(hora, libre - th * 0.2f, y0 + vh * 0.5f + tam * 0.36f, tp);
                } else {
                    tp.setTextSize(100); tp.setTextSize(Math.min(vh * 0.24f, 100 * (anchoZ ? libre - x0 : w) * 0.92f / tp.measureText(hora)));
                    tp.setTextAlign(Paint.Align.CENTER); c.drawText(hora, anchoZ ? x0 + 1280 * sc * 0.36f : (x0 + x1) / 2, y0 + vh * 0.225f, tp);
                }
            }
            // ella: su cuadro (840x648, recortado del clip desde el punto 360,40), dejando solo donde su silueta es blanca
            c.saveLayer(x0, y0, x0 + 921 * sc, repisa, null);
            mz.setScale(-sc, sc); mz.postTranslate(x0 + 920 * sc, y0 + 40 * sc); c.concat(mz);
            p.setFilterBitmap(true); p.setColor(0xffffffff); c.drawBitmap(b, 0, 0, p);
            c.scale(b.getWidth() / (float) mascZ.getWidth(), b.getHeight() / (float) mascZ.getHeight()); c.drawBitmap(mascZ, 0, 0, pm);
            c.restore();
            if (conTexto && musicaZ && sv != null) {                  // la letra (o el nombre de la canción) y el artista, por delante
                if (anchoZ) letra(c, sv, (libre + x1) / 2, y0 + vh * 0.42f, (x1 - libre) * 0.9f, alto * 0.13f);
                else letra(c, sv, (x0 + x1) / 2, y0 + vh * 0.1f, w * 0.9f, alto * 0.065f);
            }
            if (conBarra) barra(c, x0, y1 - caja, x1, y1, sv, horaGrande ? "" : hora, musicaZ && !(conTexto && sv != null && sv.letraAhora.isEmpty()));
            c.restore();
        }
        // El renglón de la letra que va (o el nombre de la canción si no hay letra), en uno o dos pisos, y debajo el artista.
        private void letra(Canvas c, CubiService sv, float tx, float ty, float tw, float tam) {
            String t = (sv.letraAhora.isEmpty() ? sv.cancionTitulo : sv.letraAhora).toUpperCase(Locale.ROOT), l1 = t, l2 = "";
            tp.setTypeface(Typeface.DEFAULT_BOLD); tp.setTextAlign(Paint.Align.CENTER); tp.setColor(0xffffffff); tp.setShadowLayer(tam * 0.22f, 0, tam * 0.06f, 0xaa000000);
            tp.setTextSize(tam);
            if (tp.measureText(t) > tw) {                             // largo: en dos pisos, partido en el espacio más cercano a la mitad
                int corte = -1;
                for (int i = t.indexOf(' '); i >= 0; i = t.indexOf(' ', i + 1)) if (corte < 0 || Math.abs(i - t.length() / 2) < Math.abs(corte - t.length() / 2)) corte = i;
                if (corte > 0) { l1 = t.substring(0, corte); l2 = t.substring(corte + 1); }
            }
            for (String l : new String[]{l1, l2}) {
                if (l.isEmpty()) continue;
                tp.setTextSize(tam); tp.setTextSize(Math.min(tam, tam * tw / Math.max(1, tp.measureText(l))));
                c.drawText(l, tx, ty, tp); ty += tam * 1.1f;
            }
            tp.setTextSize(tam * 0.5f); tp.setAlpha(225);
            c.drawText(TextUtils.ellipsize(sv.cancionArtista, tp, tw, TextUtils.TruncateAt.END).toString(), tx, ty - tam * 0.3f, tp);
            tp.setAlpha(255); tp.clearShadowLayer();
        }
        // La barra de abajo, en dos pastillas con los colores de Zelda: a la izquierda (crema) el clima con su dibujo, y la
        // hora si no va en grande; a la derecha (naranja) la carátula y el nombre de la canción.
        private void barra(Canvas c, float x0, float y0, float x1, float y1, CubiService sv, String hora, boolean conTitulo) {
            float alto = y1 - y0, mg = alto * 0.13f, t = y0 + mg, bm = y1 - mg, ph = bm - t, base = t + ph * 0.68f, tam = ph * 0.52f;
            String estado = sv == null || sv.climaGrados == Integer.MIN_VALUE ? "" : sv.climaEstado, grados = estado.isEmpty() && (sv == null || sv.climaGrados == Integer.MIN_VALUE) ? "" : sv.climaGrados + "°";
            String izq = (hora.isEmpty() ? "" : hora + (grados.isEmpty() ? "" : "   ")) + grados;
            p.setStyle(Paint.Style.FILL);
            if (!izq.isEmpty()) {
                tp.setTypeface(Typeface.DEFAULT_BOLD); tp.setTextSize(tam);
                float wi = tp.measureText(izq); tp.setTypeface(Typeface.DEFAULT); tp.setTextSize(tam * 0.72f);
                float we = estado.isEmpty() ? 0 : tp.measureText(estado) + ph * 0.25f, dib = grados.isEmpty() ? 0 : ph * 0.95f;
                float x = x0 + mg * 2, fin = Math.min(x + ph * 0.45f + dib + wi + we + ph * 0.45f, (x0 + x1) / 2 - mg);
                p.setColor(CREMA); c.drawRoundRect(x, t, fin, bm, ph / 2, ph / 2, p);
                x += ph * 0.45f;
                if (dib > 0) { clima(c, x + ph * 0.36f, t + ph / 2, ph * 0.3f, estado.toLowerCase(Locale.ROOT)); x += dib; }
                texto(c, izq, x, base, tam, CAFE, true, Paint.Align.LEFT, fin - x);
                if (we > 0) texto(c, estado, x + wi + ph * 0.25f, base, tam * 0.72f, 0xff8a5a2b, false, Paint.Align.LEFT, fin - ph * 0.3f - x - wi);
            }
            if (sv == null || sv.cancionTitulo.isEmpty()) return;
            android.graphics.Bitmap car = sv.cancionCaratula;
            boolean hayCar = car != null && !car.isRecycled();
            if (!hayCar && !conTitulo) return;
            String tit = conTitulo ? sv.cancionTitulo : "";
            tp.setTypeface(Typeface.DEFAULT_BOLD); tp.setTextSize(tam * 0.86f);
            float wt = tit.isEmpty() ? 0 : Math.min(tp.measureText(tit), (x1 - x0) * 0.4f), lado = ph - mg, fin = x1 - mg * 2;
            float ini = fin - (hayCar ? lado + mg : ph * 0.4f) - (wt > 0 ? wt + ph * 0.75f : mg * 0.5f);
            p.setColor(NARANJA); c.drawRoundRect(ini, t, fin, bm, ph / 2, ph / 2, p);
            if (wt > 0) texto(c, tit, ini + ph * 0.45f, base - tam * 0.03f, tam * 0.86f, CAFE, true, Paint.Align.LEFT, wt + 2);
            if (hayCar) {
                gr.set(fin - mg * 0.5f - lado, t + mg * 0.5f, fin - mg * 0.5f, bm - mg * 0.5f); gp.rewind(); gp.addRoundRect(gr, lado / 2, lado / 2, Path.Direction.CW);
                c.save(); c.clipPath(gp); p.setColor(0xffffffff); p.setFilterBitmap(true); c.drawBitmap(car, null, gr, p); c.restore();
            }
        }
        // el dibujito del clima: sol, nube, o nube con gotas
        private void clima(Canvas c, float cx, float cy, float r, String e) {
            boolean sol = e.contains("sol") || e.contains("despej"), agua = e.contains("lluv") || e.contains("llov") || e.contains("torment");
            p.setStyle(Paint.Style.FILL);
            if (sol || e.contains("parcial")) { p.setColor(NARANJA); c.drawCircle(cx + (sol ? 0 : r * 0.5f), cy - (sol ? 0 : r * 0.45f), r * (sol ? 0.95f : 0.6f), p); }
            if (!sol) {
                p.setColor(0xff9a8672);
                c.drawCircle(cx - r * 0.5f, cy + r * 0.1f, r * 0.55f, p); c.drawCircle(cx + r * 0.15f, cy - r * 0.25f, r * 0.7f, p); c.drawCircle(cx + r * 0.75f, cy + r * 0.15f, r * 0.5f, p);
                c.drawRect(cx - r * 0.5f, cy + r * 0.1f, cx + r * 0.75f, cy + r * 0.65f, p);
            }
            if (agua) {
                p.setColor(0xff3a86c8); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(r * 0.2f); p.setStrokeCap(Paint.Cap.ROUND);
                for (int i = -1; i <= 1; i++) c.drawLine(cx + i * r * 0.55f, cy + r * 0.9f, cx + i * r * 0.55f - r * 0.15f, cy + r * 1.3f, p);
                p.setStyle(Paint.Style.FILL);
            }
        }
        // el cuadro pequeño con los datos del viaje: a cuánto está el destino, a qué hora se llega y en cuántos segundos toca girar
        private void viaje(Canvas c, float x0, float y0, float x1, float y1, CubiService sv, float u) {
            p.setStyle(Paint.Style.FILL); p.setColor(fundido ? VELO : PANEL); gr.set(x0, y0, x1, y1); c.drawRoundRect(gr, 5 * u, 5 * u, p);
            float cx = (x0 + x1) / 2, alto = y1 - y0, w = x1 - x0 - 4 * u;
            final int GRIS = fundido ? 0xe6ffffff : Lienzo.GRIS, AZUL = fundido ? 0xffffffff : Lienzo.AZUL;
            boolean hayDest = sv.navDestM >= 0, haySeg = sv.navMetros >= 0 && sv.kmh >= 8 && sv.navMetros * 3.6 / sv.kmh < 100;
            if (hayDest) {
                texto(c, metros(sv.navDestM), cx, y0 + alto * 0.3f, alto * 0.24f, TINTA, true, Paint.Align.CENTER, w);
                texto(c, sv.navLlega.isEmpty() ? "al destino" : "llegamos " + sv.navLlega, cx, y0 + alto * 0.5f, alto * 0.12f, GRIS, false, Paint.Align.CENTER, w);
            }
            if (haySeg) texto(c, "giro en " + Math.round(sv.navMetros * 3.6 / sv.kmh) + " s", cx, y0 + alto * (hayDest ? 0.8f : 0.55f), alto * 0.15f, AZUL, true, Paint.Align.CENTER, w);
            if (!hayDest && !haySeg) texto(c, "En ruta", cx, y0 + alto * 0.55f, alto * 0.16f, GRIS, true, Paint.Align.CENTER, w);
        }
        private void ovalo(Canvas c, float cx, float cy, float rx, float ry) { gr.set(cx - rx, cy - ry, cx + rx, cy + ry); c.drawOval(gr, p); }
        private void tri(Canvas c, float ax, float ay, float bx, float by, float cx, float cy) { gp.rewind(); gp.moveTo(ax, ay); gp.lineTo(bx, by); gp.lineTo(cx, cy); gp.close(); c.drawPath(gp, p); }
        // modo: 0 duerme, 1 despierta, 2 canta. borde: la y del borde donde apoya las manos. Las medidas del gato van en "s".
        // ponytail: en el carro la boca y el baile van a un pulso fijo; usar la letra y el pulso de la canción cuando el servicio los exponga
        private void gato(Canvas c, float cx, float cy, float s, long ms, int modo, float borde, int tinta) {
            final int N = 0xfff28c28, O = 0xffd96f14, C = 0xffffe3bd, T = 0xff3a2212, ROSA = 0xffec7f86;
            float t = (ms % 3600000L) / 1000f;
            boolean duerme = modo == 0, canta = modo == 2;
            float golpe = canta ? (float) Math.abs(Math.sin(t * Math.PI * 104 / 60)) : 0, k = canta && t % 4 > 1.2f && t % 4 < 2.2f ? 1 : 0;
            float cola = (duerme ? 0.08f : canta ? 0.4f : 0.25f) * (float) Math.sin(t * (canta ? 5.4f : duerme ? 0.6f : 1.3f));
            float qy = cy + s * (canta ? -0.1f * golpe : 0.04f * (float) Math.sin(t * 1.6f));
            c.save(); c.translate(cx, qy); c.rotate((float) Math.toDegrees(canta ? 0.07 * Math.sin(t * 3.4) : 0.025 * Math.sin(t * 0.9))); c.scale(s, s);
            p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            for (int pasada = 0; pasada < 2; pasada++) {                 // la silueta: primero de sombra (una capa, para que no se oscurezca donde se cruza) y luego de color
                if (pasada == 0) { c.saveLayerAlpha(-2.4f, -1.4f, 2.6f, 2.8f, 51); c.translate(0.06f, 0.08f); } else c.save();
                p.setColor(pasada == 0 ? 0xff000000 : N);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(0.42f); gp.rewind(); gp.moveTo(0.7f, 1.5f); gp.cubicTo(1.5f, 1.4f, 1.55f + cola, 0.5f, 1.2f + cola, 0.05f); c.drawPath(gp, p);
                p.setStyle(Paint.Style.FILL); ovalo(c, 0, 1.25f, 1.08f, 1f); ovalo(c, 0, 0, 0.86f, 0.7f);
                for (int l = -1; l <= 1; l += 2) {
                    tri(c, l * 0.78f, -0.28f, l * 0.66f, -1f, l * 0.18f, -0.58f);
                    gp.rewind(); gp.moveTo(l * 0.72f, -0.05f); gp.lineTo(l * 1.08f, 0.12f); gp.lineTo(l * 0.8f, 0.2f); gp.lineTo(l * 1.04f, 0.42f); gp.lineTo(l * 0.66f, 0.45f); gp.close(); c.drawPath(gp, p);
                }
                c.restore();
            }
            p.setColor(C); ovalo(c, 1.2f + cola, 0.08f, 0.2f, 0.2f); ovalo(c, 0, 1.2f, 0.6f, 0.7f);
            p.setColor(0xfff7b8a0); for (int l = -1; l <= 1; l += 2) tri(c, l * 0.66f, -0.34f, l * 0.62f, -0.8f, l * 0.34f, -0.54f);
            p.setStyle(Paint.Style.STROKE); p.setColor(O); p.setStrokeWidth(0.09f);
            for (float x = -0.2f; x <= 0.21f; x += 0.2f) c.drawLine(x, -0.62f, x * 0.8f, -(0.36f - Math.abs(x) * 0.3f), p);
            p.setStyle(Paint.Style.FILL); p.setColor(C); ovalo(c, 0, 0.26f, 0.4f, 0.28f);
            p.setColor(ROSA); tri(c, -0.09f, 0.12f, 0.09f, 0.12f, 0, 0.23f);
            if (canta) { p.setColor(0xff5a1e22); ovalo(c, 0, 0.37f, 0.13f, 0.04f + 0.13f * golpe); p.setColor(ROSA); ovalo(c, 0, 0.4f + 0.08f * golpe, 0.08f, 0.05f * golpe); }
            else { p.setStyle(Paint.Style.STROKE); p.setColor(T); p.setStrokeWidth(0.04f); gp.rewind(); gp.moveTo(0, 0.23f); gp.lineTo(0, 0.3f); gp.moveTo(-0.14f, 0.3f); gp.quadTo(-0.07f, 0.4f, 0, 0.3f); gp.quadTo(0.07f, 0.4f, 0.14f, 0.3f); c.drawPath(gp, p); }
            p.setStyle(Paint.Style.STROKE); p.setColor(0xd9ffffff); p.setStrokeWidth(0.025f);
            for (int l = -1; l <= 1; l += 2) { c.drawLine(l * 0.34f, 0.236f, l * 0.98f, 0.08f, p); c.drawLine(l * 0.34f, 0.292f, l * 0.98f, 0.36f, p); }
            float abre = (t % 6.7f > 6.56f ? 0.12f : 1) * (!canta && t % 23 > 20.5f ? 0.45f : 1), mx = canta ? 0 : 0.05f * Math.max(-1, Math.min(1, (float) Math.sin(t * 0.45f) * 3));
            boolean feliz = canta && (golpe > 0.75f || k > 0);
            for (int l = -1; l <= 1; l += 2) {                           // los ojos verdes; dormida o cantando duro, cerrados
                float ex = l * 0.34f + mx;
                if (duerme || feliz) {
                    float ey = duerme ? -0.16f : -0.02f;
                    p.setStyle(Paint.Style.STROKE); p.setColor(T); p.setStrokeWidth(0.06f); gr.set(ex - 0.13f, ey - 0.13f, ex + 0.13f, ey + 0.13f); c.drawArc(gr, duerme ? 27 : 207, 126, false, p);
                    continue;
                }
                p.setStyle(Paint.Style.FILL); p.setColor(0xff35b85f); gr.set(ex - 0.12f, -0.1f - 0.2f * abre, ex + 0.12f, -0.1f + 0.2f * abre); c.drawRoundRect(gr, 0.12f, 0.12f, p);
                p.setColor(0xff12301c); gr.set(ex - 0.04f, -0.1f - 0.15f * abre, ex + 0.04f, -0.1f + 0.15f * abre); c.drawRoundRect(gr, 0.04f, 0.04f, p);
                if (abre > 0.5f) { p.setColor(0xffffffff); ovalo(c, ex + 0.05f, -0.2f, 0.04f, 0.04f); }
            }
            c.restore();
            // las manos (no se ladean): en el borde, o en el micrófono; en la nota larga, las dos
            p.setStrokeCap(Paint.Cap.ROUND);
            float hx = 0.38f * (1 - k), hy = 0.63f + 0.05f * k, bx = 0.82f * (1 - k), by = 1.32f;
            if (canta) {
                p.setStyle(Paint.Style.STROKE); p.setColor(0xff2c303e); p.setStrokeWidth(s * 0.14f); c.drawLine(cx + bx * s, qy + by * s, cx + (hx + (bx - hx) * 0.18f) * s, qy + (hy + (by - hy) * 0.18f) * s, p);
                p.setStyle(Paint.Style.FILL); p.setColor(0xffced4e2); c.drawCircle(cx + hx * s, qy + hy * s, s * 0.17f, p);
            }
            for (int l = -1; l <= 1; l += 2) {
                float x, y;
                if (!canta) { x = cx + l * s * 0.74f; y = borde - s * 0.13f; }
                else if (l > 0) { x = cx + (0.72f - 0.57f * k) * s; y = qy + (1.14f - 0.08f * k) * s; }
                else { float ay = 0.5f - 0.32f * golpe; x = cx + (-1.02f + 0.87f * k) * s; y = qy + (ay + (1 - ay) * k) * s; }
                p.setStyle(Paint.Style.FILL); p.setColor(O); ovalo(c, x, y, s * 0.31f, s * 0.26f); p.setColor(C); ovalo(c, x, y, s * 0.27f, s * 0.22f);
                p.setStyle(Paint.Style.STROKE); p.setColor(O); p.setStrokeWidth(s * 0.035f); c.drawLine(x - 0.09f * s, y - 0.02f * s, x - 0.09f * s, y + 0.14f * s, p); c.drawLine(x + 0.09f * s, y - 0.02f * s, x + 0.09f * s, y + 0.14f * s, p);
            }
            p.setStyle(Paint.Style.FILL);
            if (duerme) for (int i = 0; i < 3; i++) {                    // las zetas
                float q = (t * 0.28f + i / 3f) % 1;
                tp.setTypeface(Typeface.DEFAULT_BOLD); tp.setTextAlign(Paint.Align.CENTER); tp.setColor(tinta); tp.setAlpha((int) (255 * Math.sin(q * Math.PI))); tp.setTextSize(s * (0.3f + 0.3f * q));
                c.drawText("z", cx + s * (0.75f + 0.5f * q), cy - s * (0.7f + q), tp); tp.setAlpha(255);
            }
        }

        // dos ojos cápsula como los de Cubi: parpadean cada 5 s y con música se mecen
        private void cara(Canvas c, float cx, float cy, float em, int color, boolean musica, long t) {
            float fase = (t % 5000) / 5000f, cierre = fase > 0.93f ? 1 - Math.abs(fase - 0.96f) / 0.03f : 0;   // 0 abierto, 1 cerrado
            float w = 0.37f * em, hh = 0.67f * em * (1 - 0.88f * Math.max(0, cierre)), sep = 0.34f * em;
            float sube = musica ? (float) Math.sin(t / 1000.0 * Math.PI * 4) * 0.035f * em : 0;
            p.setStyle(Paint.Style.FILL);
            for (int lado = -1; lado <= 1; lado += 2) {
                float x = cx + lado * (sep + w) / 2, y = cy + sube * (musica ? lado * 0.4f + 1 : 1);
                p.setColor(0x30000000 | color);   // el resplandor
                r.set(x - w * 0.62f, y - hh / 2 - w * 0.12f, x + w * 0.62f, y + hh / 2 + w * 0.12f); c.drawRoundRect(r, 0.26f * em, 0.26f * em, p);
                p.setColor(0xff000000 | color);
                r.set(x - w / 2, y - hh / 2, x + w / 2, y + hh / 2); c.drawRoundRect(r, Math.min(0.2f * em, hh / 2), Math.min(0.2f * em, hh / 2), p);
            }
        }

        private void flecha(Canvas c, float x, float y, float rr, String dir, int forma) {
            flecha.rewind();
            if (dir.equals("maps_recto")) {
                flecha.moveTo(0, rr); flecha.lineTo(0, -rr); flecha.moveTo(-rr * 0.6f, -rr * 0.4f); flecha.lineTo(0, -rr); flecha.lineTo(rr * 0.6f, -rr * 0.4f);
            } else if (forma == 3) {   // en U: sube, da la vuelta y baja
                flecha.moveTo(-rr * 0.5f, rr); flecha.lineTo(-rr * 0.5f, -rr * 0.3f);
                r.set(-rr * 0.5f, -rr * 0.85f, rr * 0.6f, rr * 0.25f); flecha.arcTo(r, 180, 180); flecha.lineTo(rr * 0.6f, rr * 0.7f);
                flecha.moveTo(rr * 0.1f, rr * 0.25f); flecha.lineTo(rr * 0.6f, rr * 0.75f); flecha.lineTo(rr * 1.1f, rr * 0.25f);
            } else {                   // giro a la derecha; a la izquierda es el mismo dibujo en espejo
                flecha.moveTo(-rr * 0.5f, rr); flecha.lineTo(-rr * 0.5f, -rr * 0.3f); flecha.lineTo(rr * 0.7f, -rr * 0.3f);
                flecha.moveTo(rr * 0.2f, -rr * 0.85f); flecha.lineTo(rr * 0.75f, -rr * 0.3f); flecha.lineTo(rr * 0.2f, rr * 0.25f);
            }
            c.save(); c.translate(x, y); if (dir.equals("maps_izq")) c.scale(-1, 1);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(rr * 0.34f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); p.setColor(AZUL);
            c.drawPath(flecha, p); c.restore(); p.setStyle(Paint.Style.FILL);
        }

        // las calles que bajó el celular (en metros desde el centro, de a 3 m), giradas para que el rumbo quede hacia arriba
        private final Path[] zonasP = {new Path(), new Path(), new Path()};   // agua, parques y ríos
        private List<String> zonasDe;
        private final android.graphics.Matrix inclina = new android.graphics.Matrix();
        private final Paint pd = new Paint();
        private float rumboS = Float.NaN, degrY, degrH;
        private static void trazar(String v, Path q, boolean cerrar) {
            for (int i = 1; i + 4 <= v.length(); i += 4) {
                float x = (Integer.parseInt(v.substring(i, i + 2), 16) - 128) * 3, y = (Integer.parseInt(v.substring(i + 2, i + 4), 16) - 128) * 3;
                if (i == 1) q.moveTo(x, y); else q.lineTo(x, y);
            }
            if (cerrar) q.close();
        }
        // El mapa, inclinado como en un navegador: lo de adelante se aleja y se angosta. El carro es la flecha de abajo y
        // el mapa gira con él. Entre una posición del GPS y la siguiente (llega una por segundo) se estima el avance con
        // la velocidad, y el giro se suaviza, para que se mueva parejo. Con Zelda (fundido) va translúcido y se desvanece
        // hacia arriba sobre el fondo del clip.
        private void mapa(Canvas c, CubiService s, float x0, float y0, float x1, float y1, float u) {
            List<String> tramos = s.mapaTramos, zs = s.mapaZonas;
            if (tramos != callesDe) {
                callesDe = tramos;
                for (Path q : calles) q.rewind();
                for (String v : tramos) trazar(v, calles[Math.min(2, Math.max(0, v.charAt(0) - '0'))], false);
            }
            if (zs != zonasDe) {
                zonasDe = zs;
                for (Path q : zonasP) q.rewind();
                if (zs != null) for (String v : zs) { int t = v.charAt(0) - '3'; if (t >= 0 && t < 3) trazar(v, zonasP[t], t != 2); }
            }
            float w = x1 - x0, h = y1 - y0, cx = x0 + w / 2, cy = y0 + h * 0.66f, k = w / 400f;
            float rumbo = s.mapaRumbo;
            if (Float.isNaN(rumboS)) rumboS = rumbo;
            rumboS = (rumboS + (((rumbo - rumboS + 540) % 360) - 180) * 0.12f + 360) % 360;
            float dt = Math.max(0, Math.min(1.5f, (System.currentTimeMillis() - s.mapaCuando) / 1000f));
            double ra = Math.toRadians(rumbo);
            float px = (float) (s.mapaDx + Math.sin(ra) * s.mapaVel * dt), py = (float) (s.mapaDy + Math.cos(ra) * s.mapaVel * dt);
            if (fundido) { c.saveLayer(x0, y0, x1, y1, null); p.setStyle(Paint.Style.FILL); p.setColor(VELO); c.drawRect(x0, y0, x1, y1, p); } else c.save();
            c.save(); c.translate(cx, cy);
            // un rectángulo del suelo (ancho a, b hacia adelante, e hacia atrás) se pinta como trapecio: angosto y alto lo lejano
            float a = w * 0.6f, b = h * 1.7f, e = h * 0.3f;
            inclina.setPolyToPoly(new float[]{-a, -b, a, -b, a, e, -a, e}, 0, new float[]{-a * 0.42f, -h * 0.74f, a * 0.42f, -h * 0.74f, a * 1.35f, h * 0.24f, -a * 1.35f, h * 0.24f}, 0, 4);
            c.concat(inclina); c.clipRect(-a * 4, -b * 5, a * 4, e);       // nada de lo que queda detrás del carro (ahí la inclinación se rompe)
            c.rotate(-rumboS); c.scale(k, -k); c.translate(-px, -py);
            p.setStyle(Paint.Style.FILL);
            p.setColor(fundido ? 0x662a62c8 : 0xff1d3a66); c.drawPath(zonasP[0], p);
            p.setColor(fundido ? 0x6648b46c : 0xff16382c); c.drawPath(zonasP[1], p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(fundido ? 0x882a62c8 : 0xff1d3a66); p.setStrokeWidth(8); c.drawPath(zonasP[2], p);
            // fundido: calles blancas translúcidas y la ruta blanca, que se leen sobre cualquier color de fondo
            p.setColor(fundido ? 0x55ffffff : 0xff26344f); p.setStrokeWidth(6); c.drawPath(calles[0], p);
            p.setColor(fundido ? 0x80ffffff : 0xff34466a); p.setStrokeWidth(10); c.drawPath(calles[1], p);
            p.setColor(fundido ? 0xffffffff : AZUL); p.setStrokeWidth(7); c.drawPath(calles[2], p);
            c.restore(); p.setStyle(Paint.Style.FILL);
            // el carro: una flecha
            p.setColor(0x40000000); c.drawCircle(cx, cy, 8 * u, p);
            gp.rewind(); gp.moveTo(cx, cy - 6 * u); gp.lineTo(cx + 4.6f * u, cy + 4.6f * u); gp.lineTo(cx, cy + 2 * u); gp.lineTo(cx - 4.6f * u, cy + 4.6f * u); gp.close();
            p.setColor(0xffffffff); c.drawPath(gp, p);
            gp.rewind(); gp.moveTo(cx, cy - 3.4f * u); gp.lineTo(cx + 2.6f * u, cy + 2.8f * u); gp.lineTo(cx, cy + 1.2f * u); gp.lineTo(cx - 2.6f * u, cy + 2.8f * u); gp.close();
            p.setColor(fundido ? 0xfff28c28 : 0xff2f6fd0); c.drawPath(gp, p);
            if (fundido) {                                               // se desvanece hacia arriba: se funde con el fondo
                if (degrY != y0 || degrH != h) {
                    degrY = y0; degrH = h;
                    pd.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
                    pd.setShader(new android.graphics.LinearGradient(0, y0, 0, y0 + h * 0.4f, 0x00000000, 0xff000000, android.graphics.Shader.TileMode.CLAMP));
                }
                c.drawRect(x0, y0, x1, y1, pd);
            }
            c.restore();
        }

        static String metros(double m) {
            if (m < 0) return "";
            if (m >= 1000) return String.format(Locale.US, "%.1f km", m / 1000).replace('.', ',');
            return (m >= 100 ? Math.round(m / 10) * 10 : Math.round(m)) + " m";
        }

        private void texto(Canvas c, String t, float x, float y, float tam, int color, boolean negrita, Paint.Align al, float cabe) {
            tp.setTextSize(tam); tp.setColor(color); tp.setTextAlign(al); tp.setTypeface(negrita ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            c.drawText(TextUtils.ellipsize(t, tp, Math.max(cabe, tam), TextUtils.TruncateAt.END).toString(), x, y, tp);
        }
    }
}
