package com.cubi.instalador;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

// Un botón: baja la última versión de Fermata Auto de su página oficial y la instala con la marca de Play Store,
// para que Android Auto la muestre sin más aplicaciones ni pasos.
public class Inicio extends Activity {
    static final String VERSIONES = "https://api.github.com/repos/AndreyPavlenko/Fermata/releases/latest";
    TextView estado;
    ProgressBar barra;
    Button boton;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int m = (int) (28 * getResources().getDisplayMetrics().density);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setPadding(m, m, m, m);
        TextView titulo = new TextView(this);
        titulo.setText("Fermata para Android Auto");
        titulo.setTextSize(26);
        titulo.setGravity(Gravity.CENTER);
        estado = new TextView(this);
        estado.setText("Baja Fermata Auto de su página oficial y lo instala de forma que Android Auto lo muestre.\n\nSon unos 150 MB: mejor con wifi.");
        estado.setTextSize(16);
        estado.setGravity(Gravity.CENTER);
        estado.setPadding(0, m * 2 / 3, 0, m);
        barra = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        barra.setMax(100);
        barra.setVisibility(View.INVISIBLE);
        boton = new Button(this);
        boton.setText("Instalar Fermata");
        boton.setTextSize(18);
        boton.setOnClickListener(v -> empezar());
        TextView pie = new TextView(this);
        pie.setText("Fermata Auto es de Andrey Pavlenko (github.com/AndreyPavlenko/Fermata). Esta app no lo modifica: solo lo descarga y lo instala.");
        pie.setTextSize(12);
        pie.setGravity(Gravity.CENTER);
        pie.setPadding(0, m, 0, 0);
        l.addView(titulo);
        l.addView(estado);
        l.addView(barra, new LinearLayout.LayoutParams(-1, -2));
        l.addView(boton);
        l.addView(pie);
        setContentView(l);
    }

    void empezar() {
        // Android pide este permiso una vez por app; al volver se toca el botón otra vez
        if (!getPackageManager().canRequestPackageInstalls()) {
            decir("Activa \"Permitir de esta fuente\", vuelve y toca Instalar otra vez.", -1);
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        boton.setEnabled(false);
        new Thread(() -> {
            try {
                decir("Buscando la última versión…", 0);
                JSONObject v = new JSONObject(new String(leer(abrir(VERSIONES)), "UTF-8"));
                String url = elegir(v.getJSONArray("assets"));
                File dir = new File(getCacheDir(), "apk");
                dir.mkdirs();
                File f = new File(dir, "fermata.apk");
                HttpURLConnection c = abrir(url);
                long total = c.getContentLengthLong(), van = 0;
                try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(f)) {
                    byte[] buf = new byte[1 << 16];
                    int n, antes = -1;
                    while ((n = in.read(buf)) > 0) {
                        o.write(buf, 0, n);
                        van += n;
                        int p = total > 0 ? (int) (van * 100 / total) : 0;
                        if (p != antes) decir("Descargando Fermata " + v.optString("tag_name") + "… " + p + " %", antes = p);
                    }
                }
                if (total > 0 && van != total) throw new Exception("la descarga quedó incompleta");
                decir("Listo. Toca Instalar (o Actualizar) en la ventana de Android.\n\nDespués abre Android Auto: Fermata sale en la lista de apps.", 100);
                Uri u = androidx.core.content.FileProvider.getUriForFile(this, "com.cubi.instalador.archivos", f);
                startActivity(new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, "com.android.vending"));
            } catch (Exception e) {
                decir("No se pudo: " + e.getMessage() + "\n\nRevisa el internet y toca Instalar otra vez.", -1);
            }
            runOnUiThread(() -> boton.setEnabled(true));
        }).start();
    }

    // el APK de Fermata Auto para celulares (arm64); si cambian los nombres, el primero que sea de Auto
    static String elegir(JSONArray a) throws Exception {
        String otro = null;
        for (int i = 0; i < a.length(); i++) {
            String n = a.getJSONObject(i).getString("name"), u = a.getJSONObject(i).getString("browser_download_url");
            if (!n.endsWith(".apk") || !n.contains("auto")) continue;
            if (n.contains("arm64")) return u;
            if (otro == null) otro = u;
        }
        if (otro == null) throw new Exception("la versión no trae el APK de Fermata Auto");
        return otro;
    }

    static HttpURLConnection abrir(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        if (c.getResponseCode() != 200) throw new Exception("el servidor respondió " + c.getResponseCode());
        return c;
    }

    static byte[] leer(HttpURLConnection c) throws Exception {
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    // p: avance de 0 a 100; -1 esconde la barra
    void decir(String t, int p) {
        runOnUiThread(() -> {
            estado.setText(t);
            barra.setVisibility(p < 0 ? View.INVISIBLE : View.VISIBLE);
            if (p >= 0) barra.setProgress(p);
        });
    }
}
