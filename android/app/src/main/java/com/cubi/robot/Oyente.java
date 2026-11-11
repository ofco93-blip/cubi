package com.cubi.robot;

import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

// Con "Acceso a notificaciones" Android deja controlar la música de otras apps (Spotify, YouTube Music), ver la
// indicación de navegación de Google Maps / Waze y saber que llegó un WhatsApp. De WhatsApp no se lee nada: solo que
// llegó uno, para la reacción. Lo demás se ignora.
public class Oyente extends NotificationListenerService {
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String app = sbn.getPackageName();
        if (CubiService.yo == null) return;
        Notification n = sbn.getNotification();
        if (app.equals("com.whatsapp") || app.equals("com.whatsapp.w4b")) {
            // el resumen del grupo y los avisos fijos ("buscando mensajes nuevos") no son mensajes
            if ((n.flags & (Notification.FLAG_GROUP_SUMMARY | Notification.FLAG_ONGOING_EVENT)) != 0) return;
            if (Notification.CATEGORY_CALL.equals(n.category)) return;
            CubiService.yo.whatsapp();
            return;
        }
        if (!(app.equals("com.google.android.apps.maps") || app.equals("com.waze"))) return;
        Bundle x = n.extras;
        if (!CubiService.yo.navActiva()) return;
        StringBuilder t = new StringBuilder();
        for (String k : new String[]{Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT,
                Notification.EXTRA_SUB_TEXT, Notification.EXTRA_INFO_TEXT}) t.append(texto(x, k)).append(' ');
        CharSequence[] lineas = x.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lineas != null) for (CharSequence l : lineas) t.append(l).append(' ');
        if (n.tickerText != null) t.append(n.tickerText);
        CubiService.yo.indicacion(t.toString(), ladoDelDibujo(n));
    }

    // Google Maps no escribe "gira a la izquierda": manda el giro como un dibujo de flecha (el ícono grande).
    // Se mira hacia dónde cae la punta (la parte de arriba del dibujo) respecto al palo (la parte de abajo).
    // -1 izquierda, 1 derecha, 2 derecho (la flecha va hacia arriba), 0 no se sabe (sin dibujo).
    // ponytail: heurística a ojo; si falla con algún giro, el Capturador guarda el texto y se afina con ejemplos reales
    private int ladoDelDibujo(Notification n) {
        try {
            Icon ic = n.getLargeIcon();
            if (ic == null) return 0;
            Drawable d = ic.loadDrawable(this);
            if (d == null) return 0;
            final int T = 48;
            Bitmap b = Bitmap.createBitmap(T, T, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, T, T);
            d.draw(new Canvas(b));
            return lado(b);
        } catch (Throwable e) {
            return 0;
        }
    }

    static int lado(Bitmap b) {
        final int T = b.getWidth(), H = b.getHeight(), fondo = b.getPixel(0, 0);
        double sa = 0, na = 0, sb = 0, nb = 0;
        for (int y = 0; y < H; y++) for (int xx = 0; xx < T; xx++) {
            int p = b.getPixel(xx, y);
            if ((p >>> 24) < 128) continue;                       // transparente
            if ((fondo >>> 24) >= 128 && Math.abs(((p >> 16) & 255) - ((fondo >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((fondo >> 8) & 255))
                    + Math.abs((p & 255) - (fondo & 255)) < 90) continue;   // del color del fondo
            if (y < H * 0.4) { sa += xx; na++; } else if (y >= H * 0.6) { sb += xx; nb++; }
        }
        if (na < 6 || nb < 6) return 0;
        double dif = (sa / na - sb / nb) / T;
        return dif < -0.1 ? -1 : dif > 0.1 ? 1 : 2;
    }

    private static String texto(Bundle x, String k) {
        CharSequence c = x.getCharSequence(k);
        return c == null ? "" : c.toString();
    }
}
