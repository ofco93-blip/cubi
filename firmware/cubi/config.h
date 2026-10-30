// ===== Cubi — ajustes =====
#pragma once

// --- Bluetooth: así aparece el robot para la app ---
#define NOMBRE_BLE "Cubi"

// --- Pantalla ---
#define PANTALLA 0                  // 0 = auto, 1 = SH8601 (original), 2 = CO5300 (V2)
#define BRILLO_INICIAL 200          // 0..255
#define ROTACION_INICIAL 3          // 3 = montada de cabeza (carro), 1 = normal. Se cambia desde la app.

// --- Energía y batería (chip AXP2101) ---
// Funciona con la batería sin USB. Siempre se apaga solo si la batería baja del 5 % (y prende al conectar el USB).
#define APAGAR_SIN_USB_SEG 60       // cuando el carro corta el USB, se apaga a los N s (si está activado en la app)

// --- Sonido (parlante de la placa) ---
#define VOLUMEN_INICIAL 70          // 0..100
#define GANANCIA_MIC 4              // 0..7 (sube si no reacciona a la música, baja si reacciona a todo)

// --- Detección de música (se ven los valores en vivo en la app, pestaña Música) ---
#define BEAT_K          1.45f       // qué tanto debe saltar el bajo para contar un golpe
#define SONIDO_K        3.0f        // nivel sobre el ruido de fondo para considerar que "hay sonido"
#define BAJO_ALTO       0.38f       // proporción de bajos que se considera "bajo fuerte" (electrónica/reggaetón)
#define AGUDO_ALTO      0.22f       // proporción de agudos que se considera "brillante" (rock)
#define REG_MIN         0.35f       // 0..1: qué tan parejo debe repetirse el golpe para ser música (ruido ~0,1-0,25)

// --- Canto (lipsync). Se ajustan en vivo desde la app; estos son los valores de fábrica ---
#define UMBRAL_VOZ      0.35f       // 0..1: desde qué "parecido a voz" empieza a cantar (baja = canta más fácil)
#define GANANCIA_BOCA   1.3f        // qué tanto abre la boca

// --- Movimiento del carro ---
// Voltear la pantalla 180° (ROTACION 1 <-> 3) NO cambia estos signos: el eje adelante es el mismo.
#define ADELANTE_SIGNO  1           // si al FRENAR se pone feliz en vez de asustarse, pon -1
#define GIRO_SIGNO      1           // si en curva mira al lado contrario, pon -1
#define UMBRAL_FRENO    0.25f       // g sostenidos medio segundo para estrellarse contra el vidrio (se ajusta en la app)
#define UMBRAL_ACEL     0.25f
#define UMBRAL_BACHE    0.35f
#define GIRO_MAREO      70.0f
#define MINUTOS_SUENO   5
#define MINUTOS_APAGAR  10   // sin movimiento, sonido ni toque: apaga la pantalla (cuida el OLED)

#define DEBUG_IMU 0
