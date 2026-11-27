package com.cubi.robot;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;

import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager;

// A este servicio le manda Google cada giro de su navegación (CubiGoogle lo inscribe cuando el navegador queda listo).
// Solo recibe y se lo pasa a CubiGoogle.giro(); no hace nada más.
public class CubiGiros extends Service {
    private final TurnByTurnManager giros = TurnByTurnManager.createInstance();
    private final Messenger buzon = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message m) {
            if (m.what != TurnByTurnManager.MSG_NAV_INFO) return;
            try { CubiGoogle.giro(giros.readNavInfoFromBundle(m.getData())); } catch (Throwable t) { }
        }
    });
    @Override public IBinder onBind(Intent i) { return buzon.getBinder(); }
}
