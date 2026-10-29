package com.cubi.robot;

import android.app.Notification;
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
        CubiService.yo.indicacion(texto(x, Notification.EXTRA_TITLE) + " " + texto(x, Notification.EXTRA_TEXT)
                + " " + texto(x, Notification.EXTRA_BIG_TEXT));
    }

    private static String texto(Bundle x, String k) {
        CharSequence c = x.getCharSequence(k);
        return c == null ? "" : c.toString();
    }
}
