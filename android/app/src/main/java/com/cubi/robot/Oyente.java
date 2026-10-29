package com.cubi.robot;

import android.service.notification.NotificationListenerService;

// No lee notificaciones: existe porque Android solo deja controlar la música de otras apps (Spotify, YouTube Music)
// a quien tiene "Acceso a notificaciones". Así "Cubi, pon salsa" busca y además reproduce.
public class Oyente extends NotificationListenerService {
}
