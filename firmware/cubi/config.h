// ===== Cubi — ajustes =====
#pragma once

// --- WiFi: la red de la casa se configura desde la app (queda guardada en el robot).
// Estas dos son de respaldo si no hay ninguna guardada.
#define WIFI1_SSID "MiCelular"
#define WIFI1_PASS "clave123"
#define WIFI2_SSID "WiFiCasa"
#define WIFI2_PASS "clave456"

// Red propia del robot (siempre activa): conéctate y abre http://192.168.4.1
#define AP_SSID "Cubi"
#define AP_PASS "12345678"          // mínimo 8 caracteres
#define NOMBRE_MDNS "cubi"          // http://cubi.local

// --- Pantalla ---
#define PANTALLA 0                  // 0 = auto, 1 = SH8601 (original), 2 = CO5300 (V2)
#define BRILLO_INICIAL 200          // 0..255
#define ROTACION_INICIAL 3          // 3 = montada de cabeza (carro), 1 = normal. Se cambia desde la app.

// --- Energía (chip AXP2101) ---
#define APAGAR_SIN_USB_SEG 60       // con batería: si se quita el USB (apagas el carro) se apaga solo tras N s.
                                    // Prende solo al volver el USB. 0 = nunca se apaga solo.

// --- Sonido (parlante de la placa) ---
#define VOLUMEN_INICIAL 70          // 0..100
#define GANANCIA_MIC 4              // 0..7 (sube si no reacciona a la música, baja si reacciona a todo)

// --- Detección de música (perillas: mira los valores en http://IP/audio mientras suena) ---
#define BEAT_K          1.45f       // qué tanto debe saltar el bajo para contar un golpe
#define SONIDO_K        3.0f        // nivel sobre el ruido de fondo para considerar que "hay sonido"
#define BAJO_ALTO       0.38f       // proporción de bajos que se considera "bajo fuerte" (electrónica/reggaetón)
#define AGUDO_ALTO      0.22f       // proporción de agudos que se considera "brillante" (rock)

// --- Canto (lipsync). Se ajustan en vivo desde la app; estos son los valores de fábrica ---
#define UMBRAL_VOZ      0.35f       // 0..1: desde qué "parecido a voz" empieza a cantar (baja = canta más fácil)
#define GANANCIA_BOCA   1.3f        // qué tanto abre la boca

// --- Movimiento del carro ---
// Voltear la pantalla 180° (ROTACION 1 <-> 3) NO cambia estos signos: el eje adelante es el mismo.
#define ADELANTE_SIGNO  1           // si al FRENAR se pone feliz en vez de asustarse, pon -1
#define GIRO_SIGNO      1           // si en curva mira al lado contrario, pon -1
#define UMBRAL_FRENO    0.35f
#define UMBRAL_ACEL     0.30f
#define UMBRAL_BACHE    0.50f
#define GIRO_MAREO      70.0f
#define MINUTOS_SUENO   5

#define DEBUG_IMU 0
