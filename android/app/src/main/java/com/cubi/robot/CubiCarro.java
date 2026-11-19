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
            @Override public void run() { pintar(); if (sup != null) h.postDelayed(this, 50); }
        };

        Pantalla(CarContext c) {
            super(c);
            prefs = c.getSharedPreferences("cubi", 0);
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
            } finally {
                s.unlockCanvasAndPost(c);
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
        static final int AZUL = 0xff46a5ff, TINTA = 0xffe6ecfb, GRIS = 0xff93a3c7, PANEL = 0xff0c1322;
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
            boolean musica = s != null && !s.cancionTitulo.isEmpty(), suena = musica && s.musicaPlay;
            String cancion = !musica ? "" : "♪ " + s.cancionTitulo + (s.cancionArtista.isEmpty() ? "" : " · " + s.cancionArtista);
            boolean nav = s != null && !s.navDir.isEmpty() && ahora - s.navCuando < 120000;
            boolean aLoAncho = a.width() >= a.height() * 1.5f;
            // angosta = la vista dividida de Android Auto: los botones quedan sobre la carita (se baja todo a la zona libre)
            // y al lado ya está la tarjeta de música, así que la franja de la canción sobra
            // ponytail: una pantalla vertical a pantalla completa también cae aquí y se queda sin franja; darle su propio caso si aparece
            if (!aLoAncho && !firme.isEmpty()) a = firme;
            boolean franja = musica && aLoAncho;
            float u = Math.min(a.height(), a.width() * (aLoAncho ? 0.5f : 1f)) / 100f;
            int modo = suena ? 2 : nav ? 1 : (ahora / 1000) % 34 >= 28 ? 1 : 0;   // Zelda: canta con música, va despierta manejando y, si no, duerme (y cada rato se despierta)
            if (vista == 1) {
                if (zelda) { zelda(c, a.left, a.top, a.right, a.bottom, 0, ahora, modo, fondo, s, true); return; }
                cara(c, a.centerX(), a.centerY() - (franja ? 5 * u : 0), 60 * u, ojos, suena, ahora);
                if (franja) texto(c, cancion, a.centerX(), a.bottom - 9 * u, 5.5f * u, GRIS, false, Paint.Align.CENTER, a.width() - 12 * u);
                return;
            }
            if (zelda && vista == 0 && !nav) { zelda(c, a.left, a.top, a.right, a.bottom, 0, ahora, modo, fondo, s, true); return; }   // sin ruta: Zelda en grande
            // la carita a un lado (arriba si la pantalla es angosta) y el panel con lo demás
            float corte = aLoAncho ? a.left + a.width() * 0.38f : a.top + a.height() * 0.46f, m = 3 * u;
            if (vista == 2) corte = aLoAncho ? a.left : a.top;                      // solo el mapa: el panel ocupa todo
            else if (zelda) {                                                       // con ruta: el mapa en grande y, a un lado (abajo si es angosta), los datos del viaje y Zelda
                if (aLoAncho) {
                    float cx = a.left + a.width() * 0.72f, cy = a.top + a.height() * 0.42f;
                    viaje(c, cx, a.top + m, a.right - m, cy - m / 2, s, u); zelda(c, cx, cy + m / 2, a.right - m, a.bottom - m, 5 * u, ahora, modo, fondo, s, false);
                    r.set(a.left + m, a.top + m, cx - m, a.bottom - m);
                } else {
                    float cy = a.top + a.height() * 0.62f, cx = a.left + a.width() * 0.42f;
                    zelda(c, a.left + m, cy, cx - m / 2, a.bottom - m, 5 * u, ahora, modo, fondo, s, false); viaje(c, cx + m / 2, cy, a.right - m, a.bottom - m, s, u);
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
            p.setStyle(Paint.Style.FILL); p.setColor(PANEL); c.drawRect(r, p);
            boolean conMapa = nav && s.mapaTramos != null && s.navMapa();
            if (conMapa) mapa(c, s, x0 + w / 2, y0 + (y1 - y0) * 0.62f, w / 300f, u);
            float pie = franja ? 22 * u : 0;
            if (nav) {
                // el giro: sobre el mapa va en una tarjeta arriba a la izquierda; sin mapa, grande y al centro
                boolean llego = s.navDir.equals("llegamos");
                String dist = llego ? "Llegaste" : metros(s.navMetros), calle = llego ? "" : s.navCalle;
                float k = conMapa ? 1f : 1.5f, fx = x0 + 15 * u * k, fy = conMapa ? y0 + 17 * u : (y0 + y1 - pie) / 2;
                if (conMapa) { p.setColor(0xd905070c); r.set(x0 + 3 * u, y0 + 3 * u, x0 + Math.min(w - 3 * u, 78 * u), y0 + 31 * u); c.drawRoundRect(r, 4 * u, 4 * u, p); }
                if (!llego) flecha(c, fx, fy, 9 * u * k, s.navDir, s.navForma);
                float tx = llego ? x0 + 8 * u * k : fx + 15 * u * k, tw = (conMapa ? x0 + 76 * u : x1 - 4 * u) - tx;
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
            if (nav && s.kmh >= 0 && (conMapa || aLoAncho)) {
                float vx = x1 - 5 * u, vy = franja ? y1 - pie - 10 * u : y1 - 12 * u;
                texto(c, String.valueOf(Math.round(s.kmh)), vx, vy, 14 * u, TINTA, true, Paint.Align.RIGHT, 40 * u);
                texto(c, "KM/H", vx, vy + 6 * u, 4 * u, GRIS, false, Paint.Align.RIGHT, 40 * u);
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
        private void zelda(Canvas c, float x0, float y0, float x1, float y1, float radio, long ahora, int modo, int fondo, CubiService sv, boolean grande) {
            java.util.Calendar cal = java.util.Calendar.getInstance();
            int h = cal.get(java.util.Calendar.HOUR_OF_DAY), tm = fondo >= 0 ? fondo : modo == 2 ? 1 : h >= 19 || h < 6 ? 4 : 0;
            float w = x1 - x0, alto = y1 - y0, s = Math.min(w, alto) * (grande ? 0.24f : 0.3f);
            c.save(); gp.rewind(); gr.set(x0, y0, x1, y1); gp.addRoundRect(gr, radio, radio, Path.Direction.CW); c.clipPath(gp);
            p.setStyle(Paint.Style.FILL); p.setColor(FONDO_Z[tm]); c.drawRect(gr, p);
            tp.setTypeface(Typeface.DEFAULT_BOLD); tp.setTextAlign(Paint.Align.CENTER); tp.setColor(LETRA_Z[tm]);
            boolean alLado = grande && w > alto * 1.5f;               // pantalla ancha: el texto a la izquierda y ella a la derecha
            float tx = alLado ? x0 + w * 0.33f : (x0 + x1) / 2, tw = alLado ? w * 0.58f : w * 0.9f;
            if (grande && modo == 2 && sv != null) {                  // con música: el renglón de la letra que va (o el nombre de la canción), en grande
                String letra = sv.letraAhora.isEmpty() ? sv.cancionTitulo : sv.letraAhora, abajo = sv.letraAhora.isEmpty() ? sv.cancionArtista : sv.cancionTitulo;
                letra = letra.toUpperCase(Locale.ROOT);
                String l1 = letra, l2 = "";
                tp.setTextSize(alto * 0.16f);
                if (tp.measureText(letra) > tw) {                     // largo: en dos pisos, partido en el espacio más cercano a la mitad
                    int corte = -1;
                    for (int i = letra.indexOf(' '); i >= 0; i = letra.indexOf(' ', i + 1)) if (corte < 0 || Math.abs(i - letra.length() / 2) < Math.abs(corte - letra.length() / 2)) corte = i;
                    if (corte > 0) { l1 = letra.substring(0, corte); l2 = letra.substring(corte + 1); }
                }
                float yy = y0 + alto * (alLado ? 0.36f : 0.2f);
                for (String l : new String[]{l1, l2}) {
                    if (l.isEmpty()) continue;
                    tp.setTextSize(alto * 0.16f); tp.setTextSize(Math.min(alto * 0.16f, alto * 0.16f * tw / Math.max(1, tp.measureText(l))));
                    c.drawText(l, tx, yy, tp); yy += alto * 0.17f;
                }
                tp.setAlpha(170); tp.setTextSize(alto * 0.07f); c.drawText(TextUtils.ellipsize(abajo, tp, tw, TextUtils.TruncateAt.END).toString(), tx, yy - alto * 0.04f, tp); tp.setAlpha(255);
            } else if (grande) {                                      // sin música: la hora enorme, y ella por delante
                String hora = (h % 12 == 0 ? 12 : h % 12) + ":" + String.format(Locale.US, "%02d", cal.get(java.util.Calendar.MINUTE));
                tp.setTextSize(100); tp.setTextSize(Math.min(alto * (alLado ? 0.62f : 0.46f), 100 * tw / tp.measureText(hora)));
                c.drawText(hora, tx, alLado ? y0 + alto * 0.5f + tp.getTextSize() * 0.36f : y0 + alto * 0.08f + tp.getTextSize() * 0.8f, tp);
            }
            gato(c, alLado ? x0 + w * 0.76f : (x0 + x1) / 2 + (grande && modo != 2 ? w * 0.12f : 0), y1 - s * 1.1f, s, ahora, modo, y1, LETRA_Z[tm]);
            c.restore();
        }
        // el cuadro pequeño con los datos del viaje: a cuánto está el destino, a qué hora se llega y en cuántos segundos toca girar
        private void viaje(Canvas c, float x0, float y0, float x1, float y1, CubiService sv, float u) {
            p.setStyle(Paint.Style.FILL); p.setColor(PANEL); gr.set(x0, y0, x1, y1); c.drawRoundRect(gr, 5 * u, 5 * u, p);
            float cx = (x0 + x1) / 2, alto = y1 - y0, w = x1 - x0 - 4 * u;
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
        private void mapa(Canvas c, CubiService s, float cx, float cy, float k, float u) {
            List<String> tramos = s.mapaTramos;
            if (tramos != callesDe) {
                callesDe = tramos;
                for (Path q : calles) q.rewind();
                for (String v : tramos) {
                    Path q = calles[Math.min(2, Math.max(0, v.charAt(0) - '0'))];
                    for (int i = 1; i + 4 <= v.length(); i += 4) {
                        float x = (Integer.parseInt(v.substring(i, i + 2), 16) - 128) * 3, y = (Integer.parseInt(v.substring(i + 2, i + 4), 16) - 128) * 3;
                        if (i == 1) q.moveTo(x, y); else q.lineTo(x, y);
                    }
                }
            }
            c.save(); c.translate(cx, cy); c.rotate(-s.mapaRumbo); c.scale(k, -k); c.translate((float) -s.mapaDx, (float) -s.mapaDy);
            p.setStyle(Paint.Style.STROKE); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(0xff26344f); p.setStrokeWidth(7); c.drawPath(calles[0], p);
            p.setColor(0xff34466a); p.setStrokeWidth(11); c.drawPath(calles[1], p);
            p.setColor(AZUL); p.setStrokeWidth(9); c.drawPath(calles[2], p);
            c.restore(); p.setStyle(Paint.Style.FILL);
            p.setColor(0x4d46a5ff); c.drawCircle(cx, cy, 7 * u, p);
            p.setColor(0xffffffff); c.drawCircle(cx, cy, 4 * u, p);
            p.setColor(0xff2f6fd0); c.drawCircle(cx, cy, 2.5f * u, p);
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
