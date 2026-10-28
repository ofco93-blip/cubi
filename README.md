# Cubi

Robot de escritorio con cara en una pantalla AMOLED. Canta cuando escucha una canción con voz, siente la música con efectos cuando solo suenan instrumentos y se maneja desde una app Android.

Placa: Waveshare ESP32-S3-Touch-AMOLED-1.8 (V1 con SH8601 o V2 con CO5300, se detecta sola).

## Qué hace

- **Canta**: el micrófono de la placa separa lo que parece voz (tonal, en 280–3000 Hz y que sube y baja como sílabas) y mueve la boca con el volumen. Si el tono es grave la boca se pone redonda; si es agudo, ancha. Si sostiene una nota fuerte, cierra los ojos.
- **Siente la música**: sin voz, cierra los ojos felices y se mueve con el golpe del bajo. Suelta anillos, chispas y notas. Cada estilo (balada, tropical, reggaetón, electrónica, rock) tiene sus colores y su baile.
- **Emociones**: normal, feliz, enojado, triste, sorpresa, enamorado, sueño, miedo y mareado. También reacciona al moverlo y al tocar la pantalla.

## App Android

1. En la página del proyecto entra a **Releases**, abre la última versión y descarga **Cubi.apk** desde el celular.
2. Ábrelo e instálalo (Android pide permitir "instalar apps de esta fuente" la primera vez).
3. Conecta el celular a la red **Cubi** (clave `12345678`) o a la misma WiFi de la casa, y abre la app.

En la pestaña **Música** se ve en vivo si está cantando y se ajusta la sensibilidad. En **Ajustes** se pone la WiFi de la casa y se actualiza el robot.

## Actualizar el robot

- **Por WiFi**: descarga `cubi.ino.bin` de la última versión y súbelo desde la app (Ajustes › Actualizar el robot).
- **Por cable**: abre `firmware/cubi` en Arduino IDE con la placa *Waveshare ESP32-S3-Touch-AMOLED-1.8*, PSRAM activada y USB CDC On Boot activado. Librerías: GFX Library for Arduino 1.6.9, arduinoFFT 2.0.4 y XPowersLib 0.3.3.

Cada cambio en `main` compila la app y el firmware en GitHub Actions y publica una versión nueva con los dos archivos.

## Carpetas

- `firmware/cubi`: programa del robot. Los valores de fábrica están en `config.h`.
- `android`: app. La interfaz está en `app/src/main/assets/index.html`; la parte Android solo la muestra.
