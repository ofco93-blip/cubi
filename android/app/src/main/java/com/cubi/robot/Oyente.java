package com.cubi.robot;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

// Con "Acceso a notificaciones" Android deja controlar la música de otras apps (Spotify, YouTube Music) y ver la
// indicación de navegación de Google Maps / Waze. Solo se miran esas dos apps de navegación; lo demás se ignora.
public class Oyente extends NotificationListenerService {
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        String app = sbn.getPackageName();
        if (CubiService.yo == null || !(app.equals("com.google.android.apps.maps") || app.equals("com.waze"))) return;
        Bundle x = sbn.getNotification().extras;
        CubiService.yo.indicacion(texto(x, Notification.EXTRA_TITLE) + " " + texto(x, Notification.EXTRA_TEXT)
                + " " + texto(x, Notification.EXTRA_BIG_TEXT));
    }

    private static String texto(Bundle x, String k) {
        CharSequence c = x.getCharSequence(k);
        return c == null ? "" : c.toString();
    }
}
