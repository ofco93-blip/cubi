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
        private final Rect zona = new Rect();
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
            boolean sola = prefs.getBoolean("carroSola", false);
            return new NavigationTemplate.Builder().setActionStrip(new ActionStrip.Builder()
                    .addAction(tecla(R.drawable.carro_antes, -1)).addAction(tecla(sonaba ? R.drawable.carro_pausa : R.drawable.carro_play, 0)).addAction(tecla(R.drawable.carro_sigue, 1))
                    .addAction(new Action.Builder()
                    .setTitle(sola ? "Con mapa" : "Solo carita")
                    .setOnClickListener(() -> { prefs.edit().putBoolean("carroSola", !sola).apply(); invalidate(); }).build()).build()).build();
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
                lienzo.dibujar(c, a, CubiService.yo, prefs.getBoolean("carroSola", false), prefs.getInt("ojos", 0xffffff));
            } finally {
                s.unlockCanvasAndPost(c);
            }
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

        void dibujar(Canvas c, Rect a, CubiService s, boolean sola, int ojos) {
            long ahora = System.currentTimeMillis();
            c.drawColor(0xff000000);
            boolean musica = s != null && !s.cancionTitulo.isEmpty(), suena = musica && s.musicaPlay;
            String cancion = !musica ? "" : "♪ " + s.cancionTitulo + (s.cancionArtista.isEmpty() ? "" : " · " + s.cancionArtista);
            boolean nav = s != null && !s.navDir.isEmpty() && ahora - s.navCuando < 120000;
            boolean aLoAncho = a.width() >= a.height() * 1.5f;
            float u = Math.min(a.height(), a.width() * (aLoAncho ? 0.5f : 1f)) / 100f;
            if (sola) {
                cara(c, a.centerX(), a.centerY() - (musica ? 5 * u : 0), 60 * u, ojos, suena, ahora);
                if (musica) texto(c, cancion, a.centerX(), a.bottom - 9 * u, 5.5f * u, GRIS, false, Paint.Align.CENTER, a.width() - 12 * u);
                return;
            }
            // la carita a un lado (arriba si la pantalla es angosta) y el panel con lo demás
            float corte = aLoAncho ? a.left + a.width() * 0.38f : a.top + a.height() * 0.36f, m = 3 * u;
            if (aLoAncho) { cara(c, (a.left + corte) / 2, a.centerY(), 46 * u, ojos, suena, ahora); r.set(corte, a.top + m, a.right - m, a.bottom - m); }
            else { cara(c, a.centerX(), (a.top + corte) / 2, Math.min(40 * u, (corte - a.top) * 1.1f), ojos, suena, ahora); r.set(a.left + m, corte, a.right - m, a.bottom - m); }
            float x0 = r.left, y0 = r.top, x1 = r.right, y1 = r.bottom, w = x1 - x0;
            recorte.rewind(); recorte.addRoundRect(r, 5 * u, 5 * u, Path.Direction.CW);
            c.save(); c.clipPath(recorte);
            p.setStyle(Paint.Style.FILL); p.setColor(PANEL); c.drawRect(r, p);
            boolean conMapa = nav && s.mapaTramos != null && s.navMapa();
            if (conMapa) mapa(c, s, x0 + w / 2, y0 + (y1 - y0) * 0.62f, w / 300f, u);
            float pie = musica ? 22 * u : 0;
            if (nav) {
                // el giro: sobre el mapa va en una tarjeta arriba a la izquierda; sin mapa, grande y al centro
                boolean llego = s.navDir.equals("llegamos");
                String dist = llego ? "Llegaste" : metros(s.navMetros), calle = llego ? "" : s.navCalle;
                float k = conMapa ? 1f : 1.5f, fx = x0 + 15 * u * k, fy = conMapa ? y0 + 17 * u : (y0 + y1 - pie) / 2;
                if (conMapa) { p.setColor(0xd905070c); r.set(x0 + 3 * u, y0 + 3 * u, x0 + Math.min(w - 3 * u, 78 * u), y0 + 31 * u); c.drawRoundRect(r, 4 * u, 4 * u, p); }
                if (!llego) flecha(c, fx, fy, 9 * u * k, s.navDir, s.navForma);
                float tx = llego ? x0 + 8 * u * k : fx + 15 * u * k, tw = (conMapa ? x0 + 76 * u : x1 - 4 * u) - tx;
                texto(c, dist, tx, fy - (calle.isEmpty() ? -4 * u * k : 1 * u * k), 12 * u * k, TINTA, true, Paint.Align.LEFT, tw);
                if (!calle.isEmpty()) texto(c, calle, tx, fy + 8 * u * k, 5.5f * u * k, GRIS, false, Paint.Align.LEFT, tw);
            } else {
                // sin ruta: la hora y, si hay GPS, la velocidad
                java.util.Calendar cal = java.util.Calendar.getInstance();
                float cy = (y0 + y1 - pie) / 2;
                texto(c, String.format(Locale.US, "%d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)), x0 + w / 2, cy + 4 * u, 28 * u, TINTA, true, Paint.Align.CENTER, w);
                if (s != null && s.kmh >= 3) texto(c, Math.round(s.kmh) + " km/h", x0 + w / 2, cy + 17 * u, 7 * u, GRIS, false, Paint.Align.CENTER, w);
                if (s == null) texto(c, "Abre la app de Cubi en el celular", x0 + w / 2, cy + 20 * u, 5.5f * u, GRIS, false, Paint.Align.CENTER, w - 8 * u);
            }
            if (nav && s.kmh >= 0) {
                float vx = x1 - 5 * u, vy = musica ? y1 - pie - 10 * u : y1 - 12 * u;
                texto(c, String.valueOf(Math.round(s.kmh)), vx, vy, 14 * u, TINTA, true, Paint.Align.RIGHT, 40 * u);
                texto(c, "KM/H", vx, vy + 6 * u, 4 * u, GRIS, false, Paint.Align.RIGHT, 40 * u);
            }
            if (musica) {
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
