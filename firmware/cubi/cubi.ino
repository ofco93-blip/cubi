// Cubi v2 — Waveshare ESP32-S3-Touch-AMOLED-1.8 (V1 y V2)
// Caras con el motor de Isa (ojos cápsula, manitos y accesorios de cada ritmo), canta con la música (lipsync por el micrófono),
// sonidos, energía y batería (AXP2101), reacciones al movimiento, app Android por Bluetooth y actualización por Bluetooth.
#include <Arduino_GFX_Library.h>
#include <Wire.h>
#include <Update.h>
#include <Preferences.h>
#include <ESP_I2S.h>
#include <arduinoFFT.h>
#include "tempo.h"
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>
#define XPOWERS_CHIP_AXP2101
#include <XPowersLib.h>
#include "es8311.h"
#include "config.h"
#include "caras.h"

#include "version.h"  // VERSION_FW: el número de compilación de GitHub (0 = compilado a mano)
#define VERSION "2.0." VERSION_FW_TXT

// ---------- Tipos (antes de cualquier función por los prototipos de Arduino) ----------
struct C3 { float r, g, b; };
enum Emo { NEUTRAL, FELIZ, ENOJADO, TRISTE, SORPRESA, ENAMORADO, SUENO, MIEDO, MAREADO, N_EMO };
enum { CANTA = N_EMO, SIENTE, REPOSO, N_CARAS };  // caras de la música y del reposo (no se piden desde la app)
enum { NINGUNA, MIRAR, GUINO, BOSTEZO, SALTITO, CABECEO, N_ANIM };  // animaciones al azar en reposo
enum { NO_ASIS, ESCUCHA, HABLA };  // asistente del celular (Gemini): escuchando a uno o respondiendo
enum Modo { NORMAL, WHATSAPP, RING, ENLLAMADA };
enum Estilo { NADA, VOZ, BALADA, TROPICAL, REGGAETON, ELECTRO, ROCK, N_EST };
struct V { float x, y, z; };
struct Nota { uint16_t f0, f1, ms; uint8_t onda, vol; };  // onda: 0 seno 1 cuadrada 2 ruido 3 triangular 4 timbre 5 vibrato
// Medidas de la cara en píxeles para una pantalla de 368 de alto (U escala a otras pantallas).
struct Cara {
  float ew, eh, er;            // ojo: ancho, alto, radio de las esquinas
  float lid, ang;              // párpado de arriba: cuánto baja (0..1) e inclinación (+ enojo, - tristeza)
  float arco;                  // párpado de abajo en arco: ojos felices ^ ^ (0..1)
  float ceja, cejaAng, cejaY;  // cejas: cuánto se ven (0..1), inclinación (+ enojo, - preocupación), altura extra
  float boca;                  // boca quieta: 1 sonrisa, -1 triste, 2 "o", 0 sin boca
  float dy;                    // altura de los ojos
  C3 c1, c2;                   // color del ojo de arriba hacia abajo
};
const int N_FLOAT = 11;  // floats al inicio de Cara (se animan en bloque)
struct Params {  // argumentos de una orden de la app
  String k[8], v[8]; int n = 0;
  void add(const String &a, const String &b) { if (n < 8) { k[n] = a; v[n++] = b; } }
  bool has(const char *x) const { for (int i = 0; i < n; i++) if (k[i] == x) return true; return false; }
  String get(const char *x) const { for (int i = 0; i < n; i++) if (k[i] == x) return v[i]; return ""; }
};

// ---------- Pines ----------
static const int P_SDA = 15, P_SCL = 14;
static const int P_CS = 12, P_SCK = 11, P_D0 = 4, P_D1 = 5, P_D2 = 6, P_D3 = 7;
static const int P_PA = 46, I2S_MCK = 16, I2S_BCK = 9, I2S_DI = 10, I2S_WS = 45, I2S_DO = 8;
static const int W = 448, H = 368;
static const float U = H / 368.f;
static const uint8_t TCA9554 = 0x20;

Arduino_DataBus *bus;
Arduino_OLED *panel;
Arduino_Canvas *cv;
uint8_t tpAddr = 0, imuAddr = 0;
Preferences prefs;
XPowersPMU pmu;
bool pmuOk = false;
uint8_t pwrOn = 0, pwrOff = 0;  // por qué prendió / se apagó la última vez (diagnóstico)
I2SClass i2s;
es8311_handle_t es = nullptr;

uint8_t rotacion = ROTACION_INICIAL, volumen = VOLUMEN_INICIAL, micGan = GANANCIA_MIC;
bool sonidoOn = true, musicaOn = true;
bool musicaCel = true;  // true: baila solo cuando el celular reproduce (y usa la letra y el instrumento que manda);
                        // false: solo con lo que oye su micrófono, sin mirar el celular
float umbralVoz = UMBRAL_VOZ, ganBoca = GANANCIA_BOCA, sensK = SONIDO_K, umbralFreno = UMBRAL_FRENO;
volatile uint32_t otaTotal = 0, otaHecho = 0;  // actualización por Bluetooth en curso (bytes)
volatile bool otaError = false;
uint32_t reinicioEn = 0;

// ---------- I2C ----------
bool i2cHay(uint8_t a) { Wire.beginTransmission(a); return Wire.endTransmission() == 0; }
void i2cW(uint8_t a, uint8_t r, uint8_t v) { Wire.beginTransmission(a); Wire.write(r); Wire.write(v); Wire.endTransmission(); }
bool i2cR(uint8_t a, uint8_t r, uint8_t *b, uint8_t n) {
  Wire.beginTransmission(a); Wire.write(r);
  if (Wire.endTransmission(false)) return false;
  if (Wire.requestFrom(a, n) != n) return false;
  for (int i = 0; i < n; i++) b[i] = Wire.read();
  return true;
}

// ---------- Color ----------
const C3 NEGRO = { 0, 0, 0 };
C3 hx(uint32_t c) { return { float((c >> 16) & 255), float((c >> 8) & 255), float(c & 255) }; }
uint16_t c565(C3 c) { return ((uint16_t(c.r) & 0xF8) << 8) | ((uint16_t(c.g) & 0xFC) << 3) | (uint16_t(c.b) >> 3); }
C3 mix(C3 a, C3 b, float t) { return { a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t }; }
uint16_t tenue(C3 c, float t) { return c565(mix(NEGRO, c, constrain(t, 0.f, 1.f))); }
C3 hsv(float h) {  // tono 0..1, saturación y brillo al máximo
  h = (h - floorf(h)) * 6; float f = h - int(h), q = 255 * (1 - f), t = 255 * f;
  switch (int(h)) { case 0: return { 255, t, 0 }; case 1: return { q, 255, 0 }; case 2: return { 0, 255, t };
    case 3: return { 0, q, 255 }; case 4: return { t, 0, 255 }; default: return { 255, 0, q }; }
}

// =====================================================================================
// SONIDOS (sintetizados, sin archivos: estilo robot/BMO)
// =====================================================================================
const Nota S_ARRANQUE[] = { { 400, 800, 80, 0, 170 }, { 0, 0, 30, 0, 0 }, { 800, 1200, 80, 0, 170 }, { 0, 0, 20, 0, 0 }, { 1200, 1700, 160, 0, 200 }, { 0, 0, 0, 0, 0 } };
const Nota S_WHATSAPP[] = { { 880, 880, 70, 3, 220 }, { 0, 0, 40, 0, 0 }, { 1320, 1320, 120, 3, 220 }, { 0, 0, 0, 0, 0 } };
const Nota S_RING[] = { { 1000, 1250, 1100, 4, 150 }, { 0, 0, 350, 0, 0 }, { 1000, 1250, 1100, 4, 150 }, { 0, 0, 0, 0, 0 } };
const Nota S_COLGAR[] = { { 1200, 600, 160, 0, 200 }, { 0, 0, 0, 0, 0 } };
const Nota S_FELIZ[] = { { 660, 660, 80, 0, 190 }, { 880, 880, 80, 0, 190 }, { 1100, 1320, 140, 0, 200 }, { 0, 0, 0, 0, 0 } };
const Nota S_RISA[] = { { 600, 900, 60, 0, 190 }, { 0, 0, 30, 0, 0 }, { 700, 1000, 60, 0, 190 }, { 0, 0, 30, 0, 0 }, { 800, 1250, 90, 0, 200 }, { 0, 0, 0, 0, 0 } };
const Nota S_ENOJADO[] = { { 180, 140, 250, 1, 110 }, { 0, 0, 40, 0, 0 }, { 170, 110, 320, 1, 120 }, { 0, 0, 0, 0, 0 } };
const Nota S_TRISTE[] = { { 700, 380, 600, 5, 170 }, { 0, 0, 0, 0, 0 } };
const Nota S_SORPRESA[] = { { 300, 1500, 180, 0, 200 }, { 0, 0, 0, 0, 0 } };
const Nota S_ENAMORADO[] = { { 880, 1100, 120, 0, 180 }, { 0, 0, 60, 0, 0 }, { 880, 1320, 260, 5, 190 }, { 0, 0, 0, 0, 0 } };
const Nota S_SUENO[] = { { 90, 60, 700, 2, 70 }, { 0, 0, 300, 0, 0 }, { 70, 50, 500, 2, 45 }, { 0, 0, 0, 0, 0 } };
const Nota S_MIEDO[] = { { 900, 300, 320, 5, 190 }, { 0, 0, 0, 0, 0 } };
const Nota S_MAREADO[] = { { 500, 900, 200, 5, 160 }, { 900, 500, 200, 5, 160 }, { 500, 900, 200, 5, 160 }, { 0, 0, 0, 0, 0 } };
const Nota S_BACHE[] = { { 250, 700, 110, 5, 190 }, { 0, 0, 0, 0, 0 } };
const Nota S_KAME[] = { { 0, 0, 400, 0, 0 }, { 180, 900, 2200, 5, 150 }, { 900, 120, 900, 2, 220 }, { 0, 0, 0, 0, 0 } };
const Nota S_PUM[] = { { 320, 220, 70, 1, 150 }, { 0, 0, 60, 0, 0 }, { 260, 180, 70, 1, 150 }, { 0, 0, 0, 0, 0 } };  // clic-clac: la carga
const Nota S_CUENTA[] = { { 880, 880, 120, 0, 200 }, { 0, 0, 880, 0, 0 }, { 880, 880, 120, 0, 200 }, { 0, 0, 880, 0, 0 },
                          { 1320, 1320, 160, 0, 210 }, { 0, 0, 840, 0, 0 }, { 1400, 150, 140, 2, 230 }, { 0, 0, 120, 0, 0 }, { 600, 900, 60, 0, 190 }, { 0, 0, 30, 0, 0 },
                       { 700, 1000, 60, 0, 190 }, { 0, 0, 30, 0, 0 }, { 800, 1250, 90, 0, 200 }, { 0, 0, 0, 0, 0 } };
const Nota S_ESCUCHA[] = { { 700, 700, 70, 0, 170 }, { 0, 0, 30, 0, 0 }, { 1050, 1050, 110, 0, 190 }, { 0, 0, 0, 0, 0 } };
const Nota *SONIDO_EMO[N_EMO] = { nullptr, S_FELIZ, S_ENOJADO, S_TRISTE, S_SORPRESA, S_ENAMORADO, S_SUENO, S_MIEDO, S_MAREADO };
struct SonNom { const char *n; const Nota *s; };
const SonNom SONIDOS[] = { { "arranque", S_ARRANQUE }, { "whatsapp", S_WHATSAPP }, { "ring", S_RING }, { "colgar", S_COLGAR }, { "feliz", S_FELIZ },
  { "risa", S_RISA }, { "enojado", S_ENOJADO }, { "triste", S_TRISTE }, { "sorpresa", S_SORPRESA }, { "enamorado", S_ENAMORADO },
  { "sueno", S_SUENO }, { "miedo", S_MIEDO }, { "mareado", S_MAREADO }, { "bache", S_BACHE } };

volatile const Nota *sPend = nullptr;
volatile bool sonando = false;
void sonar(const Nota *s) { if (s && sonidoOn) sPend = s; }

const int SR = 16000, NF = 512;  // 32 ms por bloque, 31.25 Hz por casilla de la FFT
int16_t rxBuf[NF * 2], txBuf[NF * 2];

// Llena txBuf con el sonido en curso. Devuelve true si está sonando.
bool sintetizar() {
  static const Nota *seq = nullptr; static int idx; static uint32_t k, len; static float ph; static uint32_t rng = 1;
  if (sPend) { seq = (const Nota *)sPend; sPend = nullptr; idx = 0; k = 0; len = seq[0].ms * 16; }
  if (!seq) { memset(txBuf, 0, sizeof(txBuf)); return false; }
  for (int i = 0; i < NF; i++) {
    float s = 0;
    if (seq) {
      const Nota &no = seq[idx];
      if (no.f0) {
        float f = no.f0 + (no.f1 - no.f0) * float(k) / len;
        if (no.onda == 4) f = ((k / 400) & 1) ? no.f1 : no.f0;  // timbre de teléfono antiguo (25 ms)
        if (no.onda == 5) f *= 1 + 0.06f * sinf(k * 0.0047f);    // vibrato ~12 Hz
        ph += f / SR; ph -= floorf(ph);
        switch (no.onda) {
          case 1: case 4: s = ph < 0.5f ? 0.7f : -0.7f; break;
          case 2: rng = rng * 1664525 + 1013904223; s = (int32_t(rng) >> 16) / 32768.f * (0.5f + 0.5f * sinf(2 * PI * ph)); break;
          case 3: s = 4 * fabsf(ph - 0.5f) - 1; break;
          default: s = sinf(2 * PI * ph);
        }
        s *= min(1.f, min(k / 80.f, (len - k) / 240.f)) * no.vol / 255.f;  // ataque 5 ms, salida 15 ms
      }
      if (++k >= len) { idx++; k = 0; if (!seq[idx].ms) seq = nullptr; else len = seq[idx].ms * 16; }
    }
    txBuf[2 * i] = txBuf[2 * i + 1] = int16_t(s * 14000);
  }
  return true;
}

// =====================================================================================
// MÚSICA Y CANTO: micrófono -> FFT -> golpes (beat), BPM, estilo y voz para mover la boca.
// Golpes: bajo vs. promedio móvil (idea de WLED sound-reactive).
// Tempo y "¿es música?": autocorrelación de los ataques (tempo.h), aguanta ritmos sincopados.
// Voz: energía en 280-3000 Hz que sea tonal (no ruido de platillos) y que suba y baje como las sílabas.
// ponytail: reglas a mano, se calibran desde la app. Si se quedan cortas: separar la voz con un modelo TinyML.
// =====================================================================================
float vR[NF], vI[NF];
ArduinoFFT<float> FFT(vR, vI, NF, SR);
volatile uint8_t estilo = NADA;
volatile uint32_t beatCnt = 0, hiCnt = 0, aplausoCnt = 0, tSonido = 0;  // tSonido: última vez que oyó algo (lo despierta)
volatile bool aCanta = false;
volatile float aNivel = 0, aVoz = 0, aBoca = 0, aBocaCruda = 0, aAncho = 0.5f, aBajoP = 0, aBpm = 0, aReg = 0, aBajo = 0, aMedio = 0, aAgudo = 0, aRms = 0, aPiso = 0;
const char *EST_NOM[N_EST] = { "nada", "voz", "balada", "tropical", "reggaeton", "electronica", "rock" };

uint8_t clasificar(float bpm, float reg, float bajo, float medio, float agudo) {
  // sin golpe parejo no es música (motor, viento, aire acondicionado): antes eso contaba como balada y bailaba en reposo
  if (reg < REG_MIN) return bajo < 0.22f && medio > 0.35f ? VOZ : NADA;
  if (bpm < 84) return BALADA;
  if (bajo > BAJO_ALTO && bpm >= 118 && bpm <= 140) return ELECTRO;
  if (agudo > AGUDO_ALTO && bajo < BAJO_ALTO) return ROCK;
  if (bajo > BAJO_ALTO - 0.05f && bpm <= 108) return REGGAETON;
  if (bpm >= 140) return ROCK;
  return TROPICAL;  // salsa, cumbia, vallenato, merengue...
}

float suma(int a, int b) { float s = 0; for (int i = a; i <= b; i++) s += vR[i]; return s; }

extern uint8_t asis;  // si Gemini está hablando (lo avisa el celular)
extern bool celPlay;  // el celular dice que suena música
// Voz y boca a partir de la FFT del bloque (vR ya tiene magnitudes). Corre en la tarea de audio.
void analizarVoz(bool hay) {
  static float vMod = 0, vPrev = 0, vozS = 0, vMax = 60, vMin = 60, boca = 0, ancho = 0.5f, hAvg = 0;
  static bool canta = false; static uint8_t sube = 0;
  float eb = 0, ev = 0, eh = 0, cw = 0, lg = 0, ar = 0;
  for (int k = 2; k <= 6; k++) eb += vR[k] * vR[k];
  for (int k = 9; k <= 96; k++) { float m = vR[k], e = m * m; ev += e; cw += e * k; lg += logf(m + 1); ar += m; }
  for (int k = 112; k < NF / 2; k++) eh += vR[k] * vR[k];
  const float nv = 88;
  float plano = expf(lg / nv) / (ar / nv + 1);   // 0 = tonal (voz, instrumentos)  1 = ruido (platillos)
  float frac = ev / (eb + ev + eh + 1);          // cuánto de lo que suena está en la banda de la voz
  float db = 10 * log10f(ev + 1);
  vMod += (fabsf(db - vPrev) - vMod) * 0.15f; vPrev = db;  // sube y baja como sílabas
  float fracN = constrain((frac - 0.30f) / 0.40f, 0.f, 1.f), tonN = constrain((0.62f - plano) / 0.32f, 0.f, 1.f);
  float modN = constrain((vMod - 0.6f) / 2.4f, 0.f, 1.f);
  static float rs = 0, base = 0.3f;
  rs += (frac - rs) * 0.3f;
  base += (rs - base) * (rs < base ? 0.05f : 0.003f);  // baja rápido, sube en ~10 s: lo que suena sin voz
  float vozMus = hay && (estilo >= BALADA || celPlay) ? constrain((rs - base) / fmaxf(0.06f, base * 0.5f), 0.f, 1.f) * (0.5f + 0.5f * modN) : 0;
  vozS += ((hay ? fmaxf((0.5f * fracN + 0.5f * tonN) * modN, vozMus) : 0) - vozS) * 0.25f;  // ~120 ms
  if (!canta && vozS > umbralVoz) { if (++sube >= 2) canta = true; } else sube = 0;  // 64 ms seguidos para arrancar
  if (canta && vozS < umbralVoz * 0.7f) canta = false;

  // Vocal (como uLipSync, pero con formantes porque el español tiene 5 vocales claras): se busca F1 y F2 en el
  // espectro suavizado. F1 alto = boca abierta (a), F1 bajo = cerrada (i, u). F2 alto = estirada (i, e), bajo = redonda (o, u).
  static float abre = 0.8f;
  if (hay && (canta || asis == HABLA)) {
    auto suave = [](int k) { return vR[k - 2] + vR[k - 1] + vR[k] + vR[k + 1] + vR[k + 2]; };
    int k1 = 9; float m1 = 0;
    for (int k = 9; k <= 30; k++) { float v = suave(k); if (v > m1) { m1 = v; k1 = k; } }   // 280-940 Hz
    int k2 = max(k1 + 8, 28); float m2 = 0;
    for (int k = k2; k <= 80; k++) { float v = suave(k); if (v > m2) { m2 = v; k2 = k; } }  // hasta 2,5 kHz
    float f1 = k1 * 31.25f, f2 = k2 * 31.25f;
    abre += (constrain((f1 - 280) / 470, 0.f, 1.f) * 0.55f + 0.45f - abre) * 0.5f;
    ancho += (constrain((f2 - 900) / 1300, 0.f, 1.f) - ancho) * 0.5f;
  }

  // boca: envolvente de la banda de voz con control automático de nivel (dB), por la vocal
  vMax = max(db, vMax - 0.02f); vMin = min(db, vMin + 0.03f);
  float o = constrain((db - vMin - 3) / max(6.f, vMax - vMin - 3), 0.f, 1.f);
  o = min(1.f, powf(o, 1.4f) * ganBoca) * abre;
  static float cruda = 0;   // sin esperar a "¿es voz?": para cuando el celular ya avisó que Gemini habla
  float tc = hay ? o : 0;
  cruda += (tc - cruda) * (tc > cruda ? 0.85f : 0.45f);
  float t = canta ? o : 0;
  boca += (t - boca) * (t > boca ? 0.85f : 0.45f);

  hAvg += (eh - hAvg) * 0.05f;
  if (hay && eh > hAvg * 2.2f) hiCnt++;  // platillo / hi-hat: chispas
  aVoz = vozS; aCanta = canta; aBoca = boca; aAncho = ancho; aBocaCruda = cruda;
}

void tareaAudio(void *) {
  float bAvg = 0, bPrev = 0, piso = 30, pico = 300, fB = 0, fM = 0, fA = 0;
  uint32_t ultBeat = 0, tEval = 0, tSon = 0, mudoHasta = 0, paHasta = 0;
  uint8_t cand = NADA, candN = 0;
  static Tempo tempo; static float lPrev[128];
  float rPrev = 0, aplauso = 0;
  for (;;) {
    size_t n = i2s.readBytes((char *)rxBuf, sizeof(rxBuf));
    bool s = sintetizar();
    i2s.write((uint8_t *)txBuf, sizeof(txBuf));
    uint32_t now = millis();
    sonando = s;
    if (s) { mudoHasta = now + 250; paHasta = now + 400; digitalWrite(P_PA, HIGH); }
    else if (now > paHasta) digitalWrite(P_PA, LOW);  // apaga el amplificador: sin soplido
    if (n < sizeof(rxBuf) || now < mudoHasta || !musicaOn) {
      if (!musicaOn) { estilo = NADA; aCanta = false; aBoca = 0; }
      continue;
    }

    float m = 0;
    for (int i = 0; i < NF; i++) { vR[i] = (rxBuf[2 * i] + rxBuf[2 * i + 1]) * 0.5f; m += vR[i]; }
    m /= NF; float rms = 0;
    for (int i = 0; i < NF; i++) { vR[i] -= m; rms += vR[i] * vR[i]; vI[i] = 0; }
    rms = sqrtf(rms / NF);
    FFT.windowing(FFTWindow::Hamming, FFTDirection::Forward);
    FFT.compute(FFTDirection::Forward);
    FFT.complexToMagnitude();
    float b = suma(2, 5), md = suma(17, 64), hi = suma(65, 255), tot = b + suma(6, 16) + md + hi + 1;  // 62-156 Hz / -500 / -2k / -8k

    piso = rms < piso ? piso * 0.9f + rms * 0.1f : piso * 1.0004f;  // ruido de fondo: baja rápido, sube lento
    bool hay = rms > piso * sensK && rms > 40;
    // el ritmo no depende de los controles de la app: usa su propio umbral fijo sobre el ruido de fondo
    bool suena = rms > piso * 1.8f && rms > 30;
    pico = max(rms, pico * 0.998f);
    aNivel = suena ? constrain(rms / pico, 0.f, 1.f) : 0;
    aRms = rms; aPiso = piso;
    if (hay) tSonido = now;
    // aplauso / golpe seco (idea de Lumo): salta de golpe sobre el fondo y se apaga en el bloque siguiente; la voz no se apaga así
    if (aplauso) { if (rms < aplauso * 0.5f) aplausoCnt++; aplauso = 0; }
    else if (hay && rms > piso * 10 && rms > rPrev * 4) aplauso = rms;
    rPrev = rms;
    // ataques para el tempo: cuánto subió el espectro (en log) desde el bloque anterior, 62 Hz a 4 kHz
    float flujo = 0;
    for (int k = 2; k < 128; k++) { float l = logf(1 + vR[k]); flujo += max(0.f, l - lPrev[k]); lPrev[k] = l; }
    tempo.poner(suena ? flujo : 0);
    analizarVoz(hay);

    bAvg += (b - bAvg) * 0.04f;
    aBajoP = hay ? constrain((b - bAvg) / (bAvg + 1), 0.f, 1.f) : 0;
    // golpe: un ataque del bombo o un salto fuerte del espectro (caja, aplauso, rasgueo); el reloj de golpes en pintar()
    // solo se corrige con los que caen cerca del pulso, así los falsos no lo desordenan
    static float fAvg = 0, fPrev = 0;
    fAvg += (flujo - fAvg) * 0.05f;
    bool golpe = (b > bAvg * BEAT_K && b > bPrev * 1.1f) || (flujo > fAvg * 1.7f && flujo > fPrev);
    if (suena && golpe && now - ultBeat > 250) { ultBeat = now; beatCnt++; }
    fPrev = flujo;
    bPrev = b;
    if (hay) { fB += (b / tot - fB) * 0.03f; fM += (md / tot - fM) * 0.03f; fA += (hi / tot - fA) * 0.03f; tSon = now; }

    if (now - tEval < 2000) continue;
    tEval = now;
    if (now - tSon > 3000) { estilo = NADA; candN = 0; continue; }
    float bpm, reg;
    tempo.medir(bpm, reg);
    while (bpm && bpm < 70) bpm *= 2;
    while (bpm > 180) bpm /= 2;
    aBpm = bpm; aReg = reg; aBajo = fB; aMedio = fM; aAgudo = fA;
    uint8_t e = clasificar(bpm, reg, fB, fM, fA);
    if (e == cand) candN++; else { cand = e; candN = 1; }
    // Entrar a la música: 4 s. Cambiar de estilo: 6 s. Salir de la música mientras sigue sonando algo: 10 s
    // (un verso suave sin golpe no la saca). Con silencio sale a los 3 s (arriba).
    uint8_t pide = estilo < BALADA ? 2 : e >= BALADA ? 3 : 5;
    if (candN >= pide) estilo = e;
  }
}

void audioInit() {
  pinMode(P_PA, OUTPUT); digitalWrite(P_PA, LOW);
  i2s.setPins(I2S_BCK, I2S_WS, I2S_DO, I2S_DI, I2S_MCK);
  if (!i2s.begin(I2S_MODE_STD, SR, I2S_DATA_BIT_WIDTH_16BIT, I2S_SLOT_MODE_STEREO, I2S_STD_SLOT_BOTH)) { Serial.println("I2S fallo"); return; }
  es = es8311_create(0, ES8311_ADDRRES_0);
  es8311_clock_config_t clk = { false, false, true, SR * 256, SR };
  if (!es || es8311_init(es, &clk, ES8311_RESOLUTION_16, ES8311_RESOLUTION_16) != ESP_OK) { Serial.println("ES8311 no responde"); return; }
  es8311_sample_frequency_config(es, clk.mclk_frequency, clk.sample_frequency);
  es8311_microphone_config(es, false);
  es8311_microphone_gain_set(es, (es8311_mic_gain_t)micGan);
  es8311_voice_volume_set(es, volumen, NULL);
  xTaskCreatePinnedToCore(tareaAudio, "audio", 8192, nullptr, 2, nullptr, 0);
}

// =====================================================================================
// CARAS
// =====================================================================================
const char *EMO_NOM[N_EMO] = { "neutral", "feliz", "enojado", "triste", "sorpresa", "enamorado", "sueno", "miedo", "mareado" };
Cara PRESET[N_CARAS] = {
  //  ew   eh   er   lid    ang   arco  ceja  cAng  cY  boca  dy
  {  92, 128,  40, 0.00,  0.00, 0.00, 0.0,  0.0,  0,  0,     0, hx(0x7CF7FF), hx(0x2E8BFF) },  // neutral
  {  96, 120,  44, 0.00,  0.00, 0.55, 0.0,  0.0,  0,  1,    -4, hx(0xFFE66B), hx(0xFF9F1C) },  // feliz
  {  98, 112,  30, 0.10,  0.80, 0.00, 1.0,  0.9,  0, -0.6,   8, hx(0xFF6A3D), hx(0xC1121F) },  // enojado
  {  88, 118,  40, 0.06, -0.60, 0.00, 0.8, -0.7,  0, -1,    16, hx(0x9CC9FF), hx(0x4361EE) },  // triste
  { 108, 150,  54, 0.00,  0.00, 0.00, 1.0,  0.0, 14,  2,   -10, hx(0xFFFFFF), hx(0x9BF6FF) },  // sorpresa
  {  92, 124,  44, 0.00,  0.00, 0.35, 0.0,  0.0,  0,  1,     0, hx(0xFF8FCF), hx(0xFF2E7E) },  // enamorado
  {  92, 128,  40, 0.62,  0.00, 0.00, 0.0,  0.0,  0,  0,    14, hx(0xC3B5FF), hx(0x6A4CFF) },  // sueño
  {  76, 112,  36, 0.00, -0.30, 0.00, 1.0, -0.6, 10, -0.6,   6, hx(0xEDEBFF), hx(0x9D8DF1) },  // miedo
  {  92, 120,  46, 0.00,  0.00, 0.00, 0.0,  0.0,  0, -0.5,   0, hx(0xC6FF6B), hx(0x2DC653) },  // mareado
  {  96, 126,  44, 0.00,  0.00, 0.20, 0.7, -0.3,  6,  0,    -6, hx(0xFFB86B), hx(0xFF4D8D) },  // canta
  {  96, 120,  44, 0.00,  0.00, 0.90, 0.0,  0.0,  0,  1,     0, hx(0x7CF7FF), hx(0x2E8BFF) },  // siente la música
  {  94, 118,  42, 0.46,  0.00, 0.08, 0.0,  0.0,  0,  0,     8, hx(0x6FE3F0), hx(0x2E6BFF) },  // reposo: medio dormido
};
const uint16_t DUR_ANIM[N_ANIM] = { 0, 2600, 1100, 2200, 1100, 2600 };
Cara cur = PRESET[REPOSO], tgt;
int caraAct = REPOSO;
Emo emo = NEUTRAL, emoBase = NEUTRAL;
uint32_t emoHasta = 0;
bool emoForzada = false;
bool hayMusica = false, cantando = false;  // lo que animar() decide y las caras usan
extern volatile bool bleOn;
bool celPlay = false; uint8_t estPlay = TROPICAL;
bool celVoz = false; uint32_t celVozHasta = 0;
int celRitmo = -1;  // el instrumento que se destaca en la canción (lo dice Haiku por el título): se toca ese  // el celular sabe por la letra cuándo canta el artista (caduca si deja de avisar)  // el celular avisa play/pausa; estPlay = el último estilo que se oyó
uint32_t tGolpe = 0;  // pedida desde la app o tocando: le gana a la música; las del movimiento no
uint8_t anim = NINGUNA; uint32_t animT0 = 0, animProx = 8000, animPaso = 0;
float bostezo = 0, abreDer = 1;
uint8_t asis = NO_ASIS; uint32_t asisHasta = 0, asisT0 = 0;
void ponerAsistente(uint8_t e, uint32_t ms) { asis = e; asisT0 = millis(); asisHasta = asisT0 + ms; emoHasta = 0; }
void setEmo(Emo e, uint32_t ms) { if (e != emo) sonar(SONIDO_EMO[e]); emo = e; emoHasta = ms ? millis() + ms : 0; emoForzada = false; }

Modo modo = NORMAL;
uint32_t modoDesde = 0, modoHasta = 0, proxRing = 0;
void setModo(Modo m, uint32_t ms) { modo = m; modoDesde = millis(); modoHasta = ms ? millis() + ms : 0; }

// Reacción de caricatura (caras.cpp) con su sonido
const Nota *sonidoReac(caras::Reac r) {
  switch (r) {
    case caras::FRENAZO: return S_MIEDO;
    case caras::ACELERON: case caras::ARRANQUE: case caras::SALUDO: return S_FELIZ;
    case caras::BACHE: return S_BACHE;
    case caras::MAREO: return S_MAREADO;
    case caras::RISA: case caras::CARICIA: return S_RISA;
    case caras::SORPRESA: case caras::CURIOSA: return S_SORPRESA;
    case caras::AMOR: return S_ENAMORADO;
    case caras::WHATSAPP: return S_WHATSAPP;
    case caras::LLUVIA: case caras::FRIO: return S_TRISTE;
    case caras::KAME: return S_KAME;
    case caras::PUM: return S_PUM;
    default: return nullptr;
  }
}
void reac(caras::Reac r) { caras::reaccion(r); sonar(sonidoReac(r)); }

void evento(const String &t) {
  if (t == "whatsapp") { if (modo == NORMAL || modo == WHATSAPP) { setModo(WHATSAPP, 6000); reac(caras::WHATSAPP); } }
  else if (t == "llamada") { setModo(RING, 60000); proxRing = 0; caras::reaccion(caras::LLAMADA); }
  else if (t == "encurso") setModo(ENLLAMADA, 2UL * 3600000UL);
  else if (t == "colgar") { setModo(NORMAL, 0); sonar(S_COLGAR); caras::reaccion(caras::RISA); }
}

float lx = 0, ly = 0, vlx = 0, vly = 0, tlx = 0, tly = 0, bounce = 0, escala = 1, pulso = 0, eyeCy = H / 2, sqx = 1, sqy = 1;
C3 fondoC = { 0, 0, 0 };  // color detrás de los ojos (para suavizar sus bordes)
uint32_t frameUs = 0, tAnim = 0, tDib = 0, tFlush = 0;
float bocaV = 0, anchoV = 0.5f, hablaV = 0, fondo = 0, giro = 0, tono = 0;
uint32_t nextSacc = 0, nextBlink = 0, blinkT0 = 0, lastMotion = 0, fuerteDesde = 0;
uint32_t demoT0 = 0, demoHasta = 0, demoBeat = 0; bool demoCanto = false;
float curvaLook = 0;
uint8_t brillo = BRILLO_INICIAL;
uint8_t est = NADA, estado = 0;  // estado: 0 silencio, 1 habla, 2 música, 3 canta
uint32_t beatVisto = 0, hiVisto = 0; int lado = 1;
struct Notita { float x, y, v; uint32_t t0; C3 c; } notas[6];
struct Anillo { uint32_t t0; C3 c; } anillos[4];
struct Chispa { float x, y; uint32_t t0; C3 c; } chispas[10];

void demo(bool canto) { demoT0 = millis(); demoHasta = demoT0 + 9000; demoCanto = canto; demoBeat = 0; }

// Golpe de la música: cada estilo baila distinto, y todos sueltan un anillo y una nota
void enGolpe(uint32_t now) {
  tGolpe = now;
  lado = -lado;
  switch (est) {
    case ROCK: bounce = -38 * U; break;                                 // cabeceo metalero
    case ELECTRO: pulso = 0.16f; tono += 0.13f; break;                  // pulso + cambio de color neón
    case REGGAETON: tlx = 42 * lado; bounce = -10 * U; break;           // perreo de lado a lado
    case TROPICAL: tlx = 30 * lado; bounce = -18 * U; break;            // pasito de cumbia/salsa
    case BALADA: pulso = 0.05f; break;
    default: return;
  }
  sqy = 1.10f; sqx = 0.93f; fondo = 1;
  for (auto &a : anillos) if (!a.t0 || now - a.t0 > 800) { a = { now, cur.c1 }; break; }
  for (auto &n : notas) if (!n.t0) { n = { float(random(30, W - 30)), float(H - 20), float(random(15, 30)) / 10, now, cur.c1 }; break; }
}

void chispa(uint32_t now) {
  for (auto &c : chispas) if (!c.t0 || now - c.t0 > 600) {
    float a = random(0, 628) / 100.f, d = random(150, 210) * U;
    c = { W / 2 + cosf(a) * d * 1.2f, eyeCy + sinf(a) * d * 0.8f, now, mix(cur.c1, hx(0xFFFFFF), 0.5f) };
    return;
  }
}

void animar(uint32_t now) {
  if (emoHasta && now > emoHasta) { emo = emoBase; emoHasta = 0; }
  if (modoHasta && now > modoHasta) { modo = NORMAL; modoHasta = 0; }
  if (modo == RING && now > proxRing) { sonar(S_RING); proxRing = now + 4000; }

  // la música manda, salvo una cara pedida desde la app (o tocando) y los avisos del celular
  bool libre = modo == NORMAL && !(emoHasta && emoForzada);
  bool voz = aCanta; float bIn = aBoca, aIn = aAncho;
  est = libre ? estilo : NADA;
  if (estilo >= BALADA) estPlay = estilo;
  if (musicaCel) est = libre && celPlay && bleOn ? (estilo >= BALADA ? estilo : estPlay) : NADA;   // espera al celular; el oído pone el estilo
  if (now < demoHasta) {  // simulación para probar sin música (desde la app o por serie)
    float t = (now - demoT0) / 1000.f;
    est = TROPICAL; libre = true;
    voz = demoCanto && fmodf(t, 4.f) < 3.2f;
    bIn = voz ? constrain(0.55f * sinf(t * 8.5f) + 0.35f * sinf(t * 3.1f) + 0.25f, 0.f, 1.f) : 0;
    aIn = 0.5f + 0.5f * sinf(t * 1.3f);
    uint32_t b = (now - demoT0) / 476; if (b != demoBeat) { demoBeat = b; enGolpe(now); }
  }
  bool musica = est >= BALADA;
  if (musica && now >= demoHasta) {
    // con la letra canta justo cuando canta el artista; sin letra canta mientras suena la música (con respiros cada
    // tantos segundos), salvo que el oído ya esté oyendo la voz. La boca sigue lo que oye y unas sílabas inventadas.
    float t = now / 1000.f;
    if (musicaCel && now < celVozHasta) voz = celVoz;
    else if (!voz) voz = aNivel > 0.12f && fmodf(t, 5.f) < 4.f;
    bIn = voz ? fmaxf(aBocaCruda, 0.6f * constrain(0.5f * sinf(t * 9.3f) + 0.3f * sinf(t * 3.7f) + 0.3f, 0.f, 1.f)) : 0;
  }
  if (asis && now > asisHasta) asis = NO_ASIS;
  if (asis) { musica = false; est = NADA; libre = true; despertar(now); }  // el asistente le gana a todo
  // solo mueve la boca con canciones, o cuando el asistente (Gemini) le está hablando a uno
  if (!libre || !(musica || asis == HABLA)) voz = false;
  static uint32_t oyeGemini = 0;  // Gemini habla: la boca sigue lo que oye el micrófono, sin esperar a confirmar que es voz
  if (asis == HABLA && aBocaCruda > 0.08f) oyeGemini = now + 1500;
  if (asis == HABLA && now < oyeGemini) { voz = true; bIn = aBocaCruda; }
  else if (asis == HABLA) {  // si el micrófono no alcanza a oír a Gemini (audífonos), habla igual (sílabas inventadas)
    float t = (now - asisT0) / 1000.f;
    voz = true; bIn = constrain((0.5f * sinf(t * 9.3f) + 0.3f * sinf(t * 3.7f) + 0.3f) * ganBoca / GANANCIA_BOCA, 0.f, 1.f); aIn = 0.5f + 0.4f * sinf(t * 1.7f);
  }
  static uint32_t cantaHasta = 0;  // la cara de cantar aguanta 1,5 s entre frases: no salta canta/baila a cada respiro
  if (voz) cantaHasta = now + 1500;
  bool canta = voz || now < cantaHasta;
  hayMusica = musica; cantando = canta;
  if (musica) despertar(now);
  if (beatCnt != beatVisto) { beatVisto = beatCnt; if (musica) enGolpe(now); }
  if (hiCnt != hiVisto) { hiVisto = hiCnt; if (musica) chispa(now); }
  static uint32_t aplausoVisto = 0, proxSusto = 0;  // un aplauso o un golpe seco lo asusta (sin música, cada 15 s máximo)
  if (aplausoCnt != aplausoVisto) {
    aplausoVisto = aplausoCnt;
    if (!musica && !asis && modo == NORMAL && now > proxSusto && caras::reaccionActual() == caras::NINGUNA) { reac(caras::SORPRESA); proxSusto = now + 15000; }
  }
  estado = voz ? (musica ? 3 : 1) : (musica ? 2 : 0);

  int c = emo;
  escala = 1; float cyBase = H / 2;
  if (modo == WHATSAPP) { c = FELIZ; tlx = 40; tly = -25; }
  if (modo == RING) { c = SORPRESA; escala = 0.62f; cyBase = 92; tlx = 0; tly = 25; }
  if (modo == ENLLAMADA) { c = FELIZ; escala = 0.62f; cyBase = 92; tlx = 30 + 15 * sinf(now * 0.012f); tly = 22; }
  if (libre && musica) c = canta ? CANTA : (est == ROCK ? ENOJADO : SIENTE);
  if (asis) c = asis == HABLA ? CANTA : SORPRESA;

  // reposo: medio dormido, y de vez en cuando una animación al azar
  uint32_t ta = now - animT0;
  bool reposo = libre && !musica && !asis && emo == NEUTRAL && !emoHasta;
  if (!reposo || ta > DUR_ANIM[anim]) anim = NINGUNA;
  if (reposo) {
    c = REPOSO;
    if (now > animProx) { anim = random(1, N_ANIM); animT0 = now; ta = 0; animPaso = 0; animProx = now + random(7000, 16000); }
    if (anim == MIRAR || anim == GUINO) c = NEUTRAL;
    if (anim == SALTITO) c = FELIZ;
  }
  tgt = PRESET[c];
  bostezo = 0; abreDer = 1;
  // Gemini: colores de Gemini (azul a violeta) para que se note de lejos
  if (asis) { tgt.c1 = hx(0x8AB4F8); tgt.c2 = hx(0xA142F4); }
  if (asis == ESCUCHA) {  // "parando la oreja": ojos grandes, cabeza ladeada, asiente "ajá" y suelta un anillo por segundo
    float t = (now - asisT0) * 0.001f;
    tgt.eh *= 1.1f; tgt.boca = 0; tgt.cejaY = 10; tgt.ang = 0.18f;
    tlx = 26; tly = -14 + 6 * sinf(t * 5.2f);
    uint32_t paso = (now - asisT0) / 1000;
    if (paso != animPaso) { animPaso = paso; sqy = 1.08f; sqx = 0.95f; for (auto &a : anillos) if (!a.t0) { a = { now, tgt.c1 }; break; } }
  }
  if (asis == HABLA) { tgt.arco = 0.25f; tgt.cejaY = 4 + 8 * bocaV; tlx = 0; tly = -6; }
  switch (anim) {
    case MIRAR: if (ta / 650 != animPaso) { animPaso = ta / 650; tlx = random(-55, 56); tly = random(-28, 20); } break;  // mira a todos lados
    case GUINO: if (ta > 300 && ta < 650) abreDer = 0.05f; tgt.boca = 1; break;
    case BOSTEZO: { float s = sinf(PI * ta / DUR_ANIM[BOSTEZO]); tgt.lid = 0.45f + 0.4f * s; tgt.boca = 2; bostezo = s; break; }
    case SALTITO: if (ta / 380 != animPaso && ta < 800) { animPaso = ta / 380; bounce = -26 * U; sqy = 0.88f; sqx = 1.1f; } break;
    case CABECEO:  // se va quedando dormido... y se despierta de golpe
      if (ta < 1700) tgt.lid = 0.46f + 0.4f * ta / 1700.f;
      else { tgt.lid = 0; if (!animPaso) { animPaso = 1; sqy = 1.15f; sqx = 0.9f; bounce = -14 * U; } }
      break;
  }
  if (musica) {
    if (est == TROPICAL) { tgt.c1 = hx(0xFFD23F); tgt.c2 = hx(0xFF6B00); }
    if (est == REGGAETON) { tgt.c1 = hx(0xFF4FD8); tgt.c2 = hx(0x7B2CFF); if (c == SIENTE) { tgt.arco = 0; tgt.lid = 0.36f; } }  // mirada "chimba"
    if (est == BALADA) { tgt.c1 = hx(0xFF9ECF); tgt.c2 = hx(0xFF3D7F); }
    if (est == ELECTRO) { tgt.c1 = hsv(tono); tgt.c2 = hsv(tono + 0.35f); }
    if (est == ROCK) { tgt.arco = 0; tgt.boca = 0; }  // cabeceo con cejas de rockero
  }
  if (c == CANTA && !asis) {  // canta con sentimiento: si sostiene una nota fuerte, cierra los ojos y sube las cejas
    if (bocaV > 0.65f) { if (!fuerteDesde) fuerteDesde = now; } else fuerteDesde = 0;
    if (fuerteDesde && now - fuerteDesde > 350) { tgt.arco = 0.85f; tgt.cejaY = 16; tgt.cejaAng = -0.5f; }
  }
  if (voz || hablaV > 0.05f) cyBase -= 24 * U; else if (fabsf(tgt.boca) > 0.3f && c != REPOSO) cyBase -= 14 * U;
  if (c != caraAct) { sqy = 1.12f; sqx = 0.9f; }  // al cambiar de cara: estirón elástico
  caraAct = c;

  const float k = 0.22f;
  float *pc = &cur.ew, *pt = &tgt.ew;
  for (int i = 0; i < N_FLOAT; i++) pc[i] += (pt[i] - pc[i]) * k;
  float kc = est == ELECTRO ? 0.5f : k;
  cur.c1 = mix(cur.c1, tgt.c1, kc); cur.c2 = mix(cur.c2, tgt.c2, kc);
  eyeCy += (cyBase - eyeCy) * k;
  pulso *= 0.85f; escala *= 1 + pulso;
  if (!musica) escala *= 1 + 0.012f * sinf(now * 0.0021f);  // respira
  bocaV += ((voz ? bIn : 0) - bocaV) * 0.6f;
  anchoV += (aIn - anchoV) * 0.3f;
  hablaV += ((voz ? 1.f : 0.f) - hablaV) * 0.25f;
  fondo = max(fondo * 0.86f, musica ? float(aBajoP) : 0.f);
  sqx += (1 - sqx) * 0.16f; sqy += (1 - sqy) * 0.16f;
  if (c == MAREADO) giro += 9;

  if (modo == NORMAL && !asis) {
    if (musica && fabsf(curvaLook) > 15) { tlx = curvaLook; tly = 0; }  // en curva mira hacia la curva, aun bailando
    else if (est == BALADA) { tlx = 25 * sinf(now * 0.0015f); tly = 0; }
    else if (est >= TROPICAL) tly = 0;  // el baile mueve la mirada en enGolpe()
    else if (fabsf(curvaLook) > 8) { tlx = curvaLook; tly = 0; }
    else if (anim != MIRAR && now > nextSacc) {
      float nx = random(-45, 46), ny = random(-25, 26);
      if (c == REPOSO) { nx *= 0.5f; ny *= 0.4f; nextSacc = now + random(1800, 3000); }  // en reposo se mueve poco y despacio
      if (c == SUENO) nx = ny = 0;
      if (voz) ny = random(-12, 4);  // al hablar mira más al frente
      if (fabsf(nx - tlx) > 55 && random(3) == 0) blinkT0 = now;  // en miradas largas a veces parpadea
      tlx = nx; tly = ny;
      if (c != REPOSO) nextSacc = now + random(1200, 4000);
    }
  }
  // mirada con resorte: llega rápido y se pasa un poquito
  vlx = (vlx + (tlx - lx) * 0.2f) * 0.6f; vly = (vly + (tly - ly) * 0.2f) * 0.6f;
  lx += vlx; ly += vly;
  bounce *= 0.8f;
  if (now > nextBlink && c != SUENO) { blinkT0 = now; nextBlink = now + random(2000, 6000); }
}

// =====================================================================================
// CARAS EN PANTALLA: el motor de Isa (caras.cpp) con lo que Cubi siente
// =====================================================================================
uint16_t *tmpPanel = nullptr;
int ritmoFijo = -1;  // un baile pedido desde la app para probarlo

// Cada estilo que detecta el micrófono tiene su familia de bailes; elige uno al empezar la música y no lo cambia a mitad
uint8_t elegirRitmo(uint32_t now) {
  using namespace caras;
  static const uint8_t BAL[] = { R_DISFRUTA, R_PIANO, R_JAZZ, R_GUITARRA, R_CORAZON }, TRO[] = { R_TROPICAL, R_TROPICAL, R_GUITARRA },
    REG[] = { R_DJ, R_KPOP, R_DJ }, ELE[] = { R_ELECTRO, R_DJ, R_BARRAS }, ROC[] = { R_ROCK, R_ROCK, R_GROOVE };   // la batería ya no por defecto
  static uint8_t r = R_KPOP, estR = NADA;
  if (ritmoFijo >= 0) return ritmoFijo;
  // con el celular en play: el instrumento que dijo Haiku; si no se sabe, no improvisa: micrófono y ojos cerrados
  if (musicaCel && celPlay) return celRitmo >= 0 ? celRitmo : R_KPOP;
  if (est < BALADA) estR = NADA;
  if (musicaCel && celPlay && estR != NADA) return r;  // con el celular en play no cambia de baile a mitad de la canción
  if (est >= BALADA && est != estR) {
    const uint8_t *f = BAL; int n = 5;
    if (est == TROPICAL) { f = TRO; n = 3; } else if (est == REGGAETON) { f = REG; n = 3; }
    else if (est == ELECTRO) { f = ELE; n = 3; } else if (est == ROCK) { f = ROC; n = 3; }
    int i = random(n);
    if (f[i] == r) i = (i + 1) % n;  // que no repita el mismo baile
    r = f[i]; estR = est;
  }
  return r;
}

// ---------- Panel que baja desde arriba: batería, Bluetooth, versión y botones ----------
extern volatile bool bleOn;
bool panelAbierto = false;
int climaT = 0; String climaE;  // lo último que contó el celular del clima
uint32_t panelHasta = 0;
const float PANEL_BY = 300;                       // fila de botones (en lo que se ve, 448 x 368)
float panelBx(int i) { return dib::W / 2 + (i - 1) * 146; }

void dibujarPanel(uint32_t now) {
  using dib::Col; using dib::figura; using dib::solido; using dib::dCaja; using dib::texto; using dib::BLANCO;
  static uint32_t tLee = 0; static int bat = -1; static bool carga = false, usb = false;
  if (pmuOk && (!tLee || now - tLee > 1000)) { tLee = now; bat = pmu.getBatteryPercent(); carga = pmu.isCharging(); usb = pmu.isVbusIn(); }
  const Col gris{170, 180, 200}, celeste{120, 190, 255};
  // pila: contorno, puntita y relleno según la carga
  const float bx = W / 2 - 40, by = 70, nivel = bat < 0 ? 0 : bat / 100.f;
  Col c = bat > 50 ? Col{90, 220, 120} : bat > 20 ? Col{255, 200, 60} : Col{255, 90, 80};
  figura(bx - 70, by - 36, bx + 80, by + 36, solido(Col{200, 205, 220}), [=](float px, float py) {
    return fminf(fabsf(dCaja(px, py, bx, by, 64, 32, 12)) - 3.5f, dCaja(px, py, bx + 70, by, 6, 12, 3));
  });
  float w = 112 * nivel;
  if (w > 2) figura(bx - 56, by - 22, bx - 56 + w, by + 22, solido(c), [=](float px, float py) { return dCaja(px, py, bx - 56 + w / 2, by, w / 2, 22, 7); });
  char s[16];
  if (bat < 0) snprintf(s, sizeof(s), "?"); else snprintf(s, sizeof(s), "%d%%", bat);
  texto(s, bx + 150, by, 44, BLANCO);
  texto(usb ? (carga ? "Cargando" : "Conectado al USB") : "Con batería", W / 2, by + 62, 24, gris);
  texto(bleOn ? "Celular conectado" : "Sin celular", W / 2, 172, 24, bleOn ? celeste : gris);
  texto("Cubi v" VERSION, W / 2, 206, 18, Col{110, 120, 140});
  if (climaE.length()) {
    char c[40]; snprintf(c, sizeof(c), "%s, %d grados", climaE.c_str(), climaT); c[0] = toupper(c[0]);
    texto(c, W / 2, 238, 22, celeste);
  }
  // botones
  const char *nombre[3] = {"Sonido", "Brillo", "Gemini"};
  char valor[3][12];
  snprintf(valor[0], 12, sonidoOn ? "sí" : "no");
  snprintf(valor[1], 12, "%d%%", brillo * 100 / 255);
  snprintf(valor[2], 12, "hablar");
  for (int i = 0; i < 3; i++) {
    float x = panelBx(i);
    figura(x - 66, PANEL_BY - 42, x + 66, PANEL_BY + 42, solido(Col{40, 46, 64}), [=](float px, float py) { return dCaja(px, py, x, PANEL_BY, 64, 40, 18); });
    texto(nombre[i], x, PANEL_BY - 13, 22, BLANCO);
    texto(valor[i], x, PANEL_BY + 17, 20, celeste);
  }
  texto("Desliza hacia arriba para cerrar", W / 2, H - 14, 14, Col{90, 100, 120});
}

// toque con el panel abierto: un botón o, fuera de ellos, cerrar
void tocarPanel(int x, int y, uint32_t now) {
  panelHasta = now + 8000;
  for (int i = 0; i < 3; i++) {
    if (abs(x - (int)panelBx(i)) > 68 || abs(y - (int)PANEL_BY) > 46) continue;
    if (i == 0) { sonidoOn = !sonidoOn; guardar(); sonar(S_ESCUCHA); }
    else if (i == 1) { brillo = brillo < 100 ? 150 : brillo < 200 ? 255 : 60; panel->setBrightness(brillo); }
    else { panelAbierto = false; llamarAsistente(); }
    return;
  }
  panelAbierto = false;
}

void pintar(uint32_t now) {
  static uint32_t antes = now, proxGesto = 30000;
  float t = now / 1000.f, dt = min(0.1f, (now - antes) / 1000.f);
  antes = now;
  if (now > demoHasta) ritmoFijo = -1;
  caras::Base b = caras::REPOSO;
  if (modo == ENLLAMADA) b = caras::TELEFONO;
  else if (asis == HABLA) b = caras::HABLA;
  else if (asis == ESCUCHA) b = caras::ESCUCHA;
  else if (hayMusica) b = caras::CANTA;
  else if (emoBase == SUENO) b = caras::DUERME;
  caras::base(b);
  caras::Entradas en;
  en.boca = bocaV; en.ancho = anchoV; en.canta = cantando; en.nivel = aNivel; en.curva = curvaLook / 60; en.voz = aVoz;
  if (b == caras::CANTA) {
    float bpm = now < demoHasta ? 126 : aBpm > 60 ? aBpm : 110;
    static float fase = 0; static uint32_t antesF = now, golpeVisto = 0; static uint8_t fuera = 0;
    fase += (now - antesF) * bpm / 60000.f; antesF = now;
    if (tGolpe != golpeVisto) {           // oyó un golpe: si cae cerca del pulso lo acerca; si no, quizá es un contratiempo
      golpeVisto = tGolpe;
      float err = fase - roundf(fase);
      if (fabsf(err) < 0.3f) { fase -= 0.4f * err; fuera = 0; }
      else if (++fuera >= 4) { fase -= err; fuera = 0; }   // 4 golpes seguidos fuera del pulso: se re-engancha a ellos
    }
    fase = fmodf(fase, 1000.f);
    en.bpm = bpm; en.pulso = fase - floorf(fase); en.ritmo = elegirRitmo(now);
  }
  bool libreR = caras::reaccionActual() == caras::NINGUNA;
  if (b == caras::REPOSO && libreR && now > proxGesto) {  // en reposo, de vez en cuando un guiño o cara de curiosa
    reac(random(2) ? caras::GUINO : caras::CURIOSA); proxGesto = now + random(25000, 50000);
  }
  if (modo == RING && libreR) caras::reaccion(caras::LLAMADA);

  uint16_t *fb = cv->getFramebuffer();
  dib::fb = fb; dib::giro = rotacion == 3;
  dib::empezar();
  if (panelAbierto && now > panelHasta) panelAbierto = false;
  if (otaTotal || reinicioEn) caras::progreso(t, otaTotal ? float(otaHecho) / otaTotal : 1);
  else if (panelAbierto) dibujarPanel(now);
  else caras::cuadro(t, dt, en);
  static bool saludo = true;  // al prender saluda (después del primer cuadro: así arranca con el reloj de las caras al día)
  if (saludo) { saludo = false; reac(caras::SALUDO); }
  // solo se manda al panel lo que cambió (este cuadro y el anterior)
  dib::Rect r = dib::terminar();
  int w = r.x1 - r.x0, h = r.y1 - r.y0;
  if (w <= 0 || h <= 0) return;
  if (w == dib::RW) panel->draw16bitRGBBitmap(0, r.y0, fb + (uint32_t)r.y0 * dib::RW, dib::RW, h);
  else {
    for (int f = 0; f < h; f++) memcpy(tmpPanel + (uint32_t)f * w, fb + (uint32_t)(r.y0 + f) * dib::RW + r.x0, w * 2);
    panel->draw16bitRGBBitmap(r.x0, r.y0, tmpPanel, w, h);
  }
}

// ---------- IMU QMI8658 ----------
V acc{ 0, 0, 1 }, gyr{ 0, 0, 0 }, gLP{ 0, 0, 1 };
float lon = 0, vert = 0, yaw = 0;
int8_t adelante = ADELANTE_SIGNO;  // se invierte desde la app si al frenar se pone feliz
uint32_t cdEvento = 0, mareoDesde = 0, lastImu = 0;
float dot(V a, V b) { return a.x * b.x + a.y * b.y + a.z * b.z; }

bool imuLeer() {
  uint8_t b[12];
  if (!imuAddr || !i2cR(imuAddr, 0x35, b, 12)) return false;
  int16_t v[6];
  for (int i = 0; i < 6; i++) v[i] = int16_t(b[2 * i] | (b[2 * i + 1] << 8));
  acc = { v[0] / 8192.f, v[1] / 8192.f, v[2] / 8192.f };
  gyr = { v[3] / 64.f, v[4] / 64.f, v[5] / 64.f };
  return true;
}

void imuInit() {
  for (uint8_t a : { 0x6B, 0x6A }) { uint8_t id; if (i2cR(a, 0x00, &id, 1) && id == 0x05) { imuAddr = a; break; } }
  if (!imuAddr) { Serial.println("IMU no encontrada"); return; }
  i2cW(imuAddr, 0x02, 0x40); i2cW(imuAddr, 0x03, 0x15); i2cW(imuAddr, 0x04, 0x55); i2cW(imuAddr, 0x08, 0x03);
  delay(50);
  V s{ 0, 0, 0 };
  for (int i = 0; i < 50; i++) { imuLeer(); s.x += acc.x; s.y += acc.y; s.z += acc.z; delay(5); }
  gLP = { s.x / 50, s.y / 50, s.z / 50 };
}

void despertar(uint32_t now) {
  lastMotion = now;
  if (emoBase == SUENO) { emoBase = NEUTRAL; setEmo(SORPRESA, 900); }
}

void imuUpdate(uint32_t now) {
  if (now - lastImu < 10 || !imuLeer()) return;
  lastImu = now;
  float gmag = sqrtf(dot(gyr, gyr));
  // ponytail: gravedad por filtro lento (~8 s); en aceleraciones muy largas se "come" la señal. Fusión completa si hace falta.
  if (gmag < 20) { gLP.x += (acc.x - gLP.x) * 0.0012f; gLP.y += (acc.y - gLP.y) * 0.0012f; gLP.z += (acc.z - gLP.z) * 0.0012f; }
  float gn = sqrtf(dot(gLP, gLP)); V g{ gLP.x / gn, gLP.y / gn, gLP.z / gn };
  V d{ acc.x - gLP.x, acc.y - gLP.y, acc.z - gLP.z };
  float v = dot(d, g);
  V hz{ d.x - v * g.x, d.y - v * g.y, d.z - v * g.z };
  V f{ -g.z * g.x, -g.z * g.y, 1 - g.z * g.z };
  float fn = sqrtf(dot(f, f));
  float l = fn > 0.3f ? dot(hz, f) / fn * adelante : 0;
  // la vibración (motor, carretera, su propio parlante) sube y baja muy rápido: se promedia ~0,4 s y se va;
  // un frenón de verdad dura más que eso
  lon += (l - lon) * 0.025f;
  vert += (v - vert) * 0.3f;
  yaw = dot(gyr, g) * GIRO_SIGNO;
  curvaLook = constrain(-yaw * 1.5f, -60.f, 60.f);

  if (sqrtf(dot(d, d)) > 0.06f || gmag > 6) despertar(now);
  if (emoBase != SUENO && now - lastMotion > MINUTOS_SUENO * 60000UL && modo == NORMAL) { emoBase = SUENO; setEmo(SUENO, 0); }

  // Frenón y arranque: deben durar 0,3 s (un hueco no cuenta) y le ganan a la música un ratico.
  static uint32_t frenaDesde = 0, aceleraDesde = 0;
  frenaDesde = lon < -umbralFreno ? (frenaDesde ? frenaDesde : now) : 0;
  aceleraDesde = lon > UMBRAL_ACEL ? (aceleraDesde ? aceleraDesde : now) : 0;
  // mientras suena su parlante (y un ratico después) la caja vibra: no cuenta como movimiento
  static uint32_t parlanteHasta = 0;
  if (sonando) parlanteHasta = now + 700;
  if (modo == NORMAL && !asis && now > cdEvento && now > parlanteHasta) {
    static uint32_t ultimoEvento = 0;
    // frenazo: medio segundo frenando duro, y no más de uno cada 20 s (el vidrio rajado cansa)
    static uint32_t ultimoFrenazo = 0;
    if (frenaDesde && now - frenaDesde > 500 && (!ultimoFrenazo || now - ultimoFrenazo > 20000)) { reac(caras::FRENAZO); cdEvento = now + 4500; ultimoEvento = ultimoFrenazo = now; }
    else if (aceleraDesde && now - aceleraDesde > 300) {  // arrancar después de un buen rato quieto: entra "a toda velocidad"
      reac(now - ultimoEvento > 60000 ? caras::ARRANQUE : caras::ACELERON); cdEvento = now + 4000; ultimoEvento = now;
    } else if (fabsf(vert) > UMBRAL_BACHE && estilo < BALADA) { reac(caras::BACHE); cdEvento = now + 3000; ultimoEvento = now; }  // con música no la corta
  }
  // curva larga (sin música): mira y se inclina hacia la curva
  static uint32_t curvaDesde = 0;
  if (fabsf(yaw) > 20 && fabsf(yaw) < GIRO_MAREO) {
    if (!curvaDesde) curvaDesde = now;
    if (now - curvaDesde > 1200 && now > cdEvento && estilo < BALADA && !asis && modo == NORMAL && caras::reaccionActual() == caras::NINGUNA) {
      reac(yaw > 0 ? caras::GIRO_IZQ : caras::GIRO_DER); cdEvento = now + 6000;
    }
  } else curvaDesde = 0;
  if (fabsf(yaw) > GIRO_MAREO) {
    if (!mareoDesde) mareoDesde = now;
    if (now - mareoDesde > 1000 && modo == NORMAL && !asis && caras::reaccionActual() != caras::MAREO) reac(caras::MAREO);
  } else mareoDesde = 0;
#if DEBUG_IMU
  static uint32_t dbg; if (now - dbg > 300) { dbg = now; Serial.printf("lon=%.2f vert=%.2f giro=%.0f\n", lon, vert, yaw); }
#endif
}

// ---------- Táctil ----------
uint32_t tDown = 0, nextDemo = 0; bool tPrev = false; int demoIdx = 0;
void touchUpdate(uint32_t now) {
  uint8_t b[5];
  bool t = tpAddr && i2cR(tpAddr, 0x02, b, 5) && (b[0] & 0x0F) && (b[0] & 0x0F) < 3;
  // dónde: el táctil da el punto en el panel vertical (368 x 448); se pasa a lo que se ve (acostado, 448 x 368)
  static int x0, y0, x, y; static bool arrastre;
  if (t) {
    int px = ((b[1] & 0x0F) << 8) | b[2], py = ((b[3] & 0x0F) << 8) | b[4];
    x = rotacion == 3 ? 447 - py : py; y = rotacion == 3 ? px : 367 - px;
  }
  if (t && !tPrev) { tDown = now; nextDemo = now + 1500; despertar(now); x0 = x; y0 = y; arrastre = false; }
  if (t && (abs(x - x0) > 40 || abs(y - y0) > 40)) arrastre = true;
  if (t && now > nextDemo && !arrastre && !panelAbierto) { reac(caras::CARICIA); nextDemo = now + 3000; }  // dejar el dedo: caricia
  // deslizar desde arriba hacia abajo abre el panel; hacia arriba lo cierra
  if (!t && tPrev && arrastre) {
    if (!panelAbierto && y0 < 120 && y - y0 > 90) { panelAbierto = true; panelHasta = now + 8000; }
    else if (panelAbierto && y0 - y > 90) panelAbierto = false;
    tPrev = t;
    return;
  }
  if (!t && tPrev && panelAbierto) { tocarPanel(x0, y0, now); tPrev = t; return; }
  // toque corto: se ríe. Dos toques seguidos: llama al asistente del celular (Gemini).
  // El primer toque espera 350 ms para saber si viene el segundo.
  static uint32_t toque1 = 0;
  if (!t && tPrev && now - tDown < 600) {
    if (toque1 && now - toque1 < 350) { toque1 = 0; llamarAsistente(); }
    else toque1 = now;
  }
  if (toque1 && now - toque1 >= 350) {
    toque1 = 0;
    if (modo == RING || modo == WHATSAPP) setModo(NORMAL, 0);
    static uint32_t toques[4];  // el 4.º toque en 5 s ya no le da risa: se enoja (idea de Lumo)
    memmove(toques, toques + 1, sizeof(toques) - sizeof(toques[0])); toques[3] = now;
    if (toques[0] && now - toques[0] < 5000) { setEmo(ENOJADO, 2500); memset(toques, 0, sizeof(toques)); }
    else reac(emoBase == SUENO ? caras::SALUDO : caras::RISA);
  }
  tPrev = t;
}

// ---------- Energía (AXP2101) ----------
void energiaInit() {
  i2cR(0x34, 0x20, &pwrOn, 1); i2cR(0x34, 0x21, &pwrOff, 1);  // leer ANTES de tocar nada: causa del último apagado
  pmuOk = pmu.begin(Wire, AXP2101_SLAVE_ADDRESS, P_SDA, P_SCL);
  if (!pmuOk) { Serial.println("AXP2101 no encontrado"); return; }
  pmu.setPowerKeyPressOffTime(XPOWERS_POWEROFF_10S);  // que no se apague por un toque largo accidental
  pmu.disableTSPinMeasure();                           // sin termistor: si no, puede negarse a cargar la batería
  pmu.setChargeTargetVoltage(XPOWERS_AXP2101_CHG_VOL_4V2);
  pmu.setChargerConstantCurr(XPOWERS_AXP2101_CHG_CUR_500MA);
  pmu.setSysPowerDownVoltage(3000);
  pmu.enableBattDetection(); pmu.enableBattVoltageMeasure(); pmu.enableVbusVoltageMeasure();
  Serial.printf("PMU ok  encendido=0x%02X  apagado_anterior=0x%02X  bat=%d%%\n", pwrOn, pwrOff, pmu.getBatteryPercent());
}

void energiaUpdate(uint32_t now) {
  static uint32_t t, sinUsb;
  if (!pmuOk || otaTotal || now - t < 1000) return;
  t = now;
  static int8_t usbAntes = -1;  // al conectarlo "come" energía feliz; al desconectarlo se sorprende (idea de Lumo)
  bool usb = pmu.isVbusIn();
  if (usbAntes >= 0 && usb != usbAntes && modo == NORMAL && !asis) { despertar(now); reac(usb ? caras::AMOR : caras::SORPRESA); }
  usbAntes = usb;
  if (usb || !pmu.isBatteryConnect()) { sinUsb = 0; return; }
  static uint32_t avisoBat = 0;
  if (pmu.getBatteryPercent() < 15 && (!avisoBat || now - avisoBat > 600000)) { avisoBat = now; reac(caras::BATERIA); }
  if (!sinUsb) sinUsb = now;
  bool vacia = pmu.getBatteryPercent() < 5;
  if (vacia || (APAGAR_SIN_USB_SEG && now - sinUsb > APAGAR_SIN_USB_SEG * 1000UL)) {
    // Se apaga del todo para cuidar la batería; el AXP2101 lo vuelve a prender al conectar el USB (o con el botón PWR).
    emoBase = SUENO; setEmo(SUENO, 0); sonar(S_SUENO);
    for (uint32_t t0 = millis(); millis() - t0 < 2500;) { animar(millis()); pintar(millis()); delay(30); }
    panel->setBrightness(0);
    pmu.shutdown();
  }
}

void guardar() {
  prefs.putUChar("rot", rotacion); prefs.putUChar("vol", volumen); prefs.putBool("son", sonidoOn); prefs.putBool("mus", musicaOn); prefs.putBool("mc", musicaCel);
  prefs.putFloat("uv", umbralVoz); prefs.putFloat("gb", ganBoca); prefs.putFloat("sk", sensK); prefs.putUChar("mg", micGan); prefs.putChar("adl", adelante); prefs.putFloat("fr", umbralFreno);
}

// ---------- Órdenes de la app: "ruta?clave=valor&..." -> texto o JSON ----------
String urlDec(const String &s) {
  String o;
  for (int i = 0; i < s.length(); i++) {
    char c = s[i];
    if (c == '+') o += ' ';
    else if (c == '%' && i + 2 < s.length()) { o += char(strtol(s.substring(i + 1, i + 3).c_str(), nullptr, 16)); i += 2; }
    else o += c;
  }
  return o;
}

String atender(const String &ruta, const Params &p, int &cod) {
  cod = 200;
  if (otaTotal && !ruta.startsWith("/ota")) { cod = 409; return "actualizando"; }  // durante la actualización no se toca nada
  if (ruta == "/r") {  // una reacción de caricatura por su nombre (saludo, frenazo, amor, lluvia...)
    String n = p.get("n");
    for (int i = 1; i < caras::N_REAC; i++) if (n == caras::NOMBRE_REAC[i]) { despertar(millis()); reac(caras::Reac(i)); return "ok"; }
    cod = 400; return "reaccion desconocida";
  }
  if (ruta == "/e") {
    String n = p.get("n");
    for (int i = 0; i < N_EMO; i++) if (n == EMO_NOM[i]) { despertar(millis()); emoBase = NEUTRAL; demoHasta = 0; setEmo(Emo(i), 5000); emoForzada = true; return "ok"; }
    cod = 400; return "emocion desconocida";
  }
  if (ruta == "/evento") { despertar(millis()); evento(p.get("tipo")); return "ok"; }
  if (ruta == "/sonar") {
    String n = p.get("n");
    for (auto &s : SONIDOS) if (n == s.n) { sonar(s.s); return "ok"; }
    cod = 400; return "sonido desconocido";
  }
  if (ruta == "/asistente") {  // el celular avisa en qué va Gemini: escucha, habla o fin
    String e = p.get("estado");
    if (e == "escucha") ponerAsistente(ESCUCHA, 15000);
    else if (e == "habla") ponerAsistente(HABLA, 60000);
    else ponerAsistente(NO_ASIS, 0);
    return "ok";
  }
  if (ruta == "/demo") {  // probar sin música; r = un baile (jazz, dj, piano...)
    despertar(millis()); emoHasta = 0; demo(p.get("t") != "baile");
    ritmoFijo = -1;
    for (int i = 0; i < caras::N_RITMO; i++) if (p.get("r") == caras::NOMBRE_RITMO[i]) ritmoFijo = i;
    return "ok";
  }
  if (ruta == "/pum") { despertar(millis()); caras::cuentaPum(); sonar(S_CUENTA); return "ok"; }   // 3, 2, 1, ¡pum! y risa
  if (ruta == "/musica") {  // el celular avisa play o pausa (con play baila de una) y, por la letra, si el artista canta
    if (p.has("play")) { celPlay = p.get("play") == "1"; if (!celPlay) { estilo = NADA; celVozHasta = 0; } }
    if (p.has("voz")) { celVoz = p.get("voz") == "1"; celVozHasta = millis() + 6000; }
    if (p.has("ritmo")) { celRitmo = -1; for (int i = 0; i < caras::N_RITMO; i++) if (p.get("ritmo") == caras::NOMBRE_RITMO[i]) celRitmo = i; }
    if (p.has("play") && !celPlay) celRitmo = -1;
    return "ok";
  }
  if (ruta == "/clima") {  // el celular cuenta el clima (cada 20 min): reacciona cuando cambia
    int t = p.get("t").toInt(); String e = p.get("e"); bool dia = p.get("d") == "1";
    caras::Reac r = caras::NINGUNA;
    if (e == "lluvia" || e == "tormenta") r = caras::LLUVIA;
    else if (e == "nieve" || t <= 12) r = caras::FRIO;
    else if (t >= 30) r = caras::CALOR;
    else if (e == "despejado" && dia) r = caras::SOL;
    static caras::Reac antes = caras::N_REAC;
    if (p.get("ver") == "1") {                     // se lo preguntaron a Cubi: animación del clima y los grados en pantalla
      despertar(millis());
      if (r == caras::NINGUNA) r = caras::CURIOSA;  // nublado y templado: se asoma a mirar
      reac(r); caras::mostrarGrados(t, caras::duracion(r));
    } else if ((r != antes || r == caras::LLUVIA) && r != caras::NINGUNA && modo == NORMAL && !asis) reac(r);   // mientras llueva, cada vez
    antes = r; climaT = t; climaE = e;
    return "ok";
  }
  if (ruta == "/brillo") { despertar(millis()); brillo = constrain(p.get("v").toInt(), 0, 255); panel->setBrightness(brillo); return "ok"; }
  if (ruta == "/ajuste") {
    if (p.has("vol")) { volumen = constrain(p.get("vol").toInt(), 0, 100); if (es) es8311_voice_volume_set(es, volumen, NULL); sonar(S_WHATSAPP); }
    if (p.has("son")) sonidoOn = p.get("son") == "1";
    if (p.has("mus")) musicaOn = p.get("mus") == "1";
    if (p.has("mc")) musicaCel = p.get("mc") == "1";
    if (p.has("fr")) umbralFreno = constrain(p.get("fr").toFloat(), 0.15f, 0.9f);
    if (p.has("adl")) adelante = -adelante;  // al frenar se pone feliz: se invierte
    if (p.has("rot")) { rotacion = rotacion == 3 ? 1 : 3; cv->setRotation(rotacion); memset(cv->getFramebuffer(), 0, 368 * 448 * 2); dib::todo(); }
    if (p.has("uv")) umbralVoz = constrain(p.get("uv").toFloat(), 0.05f, 0.95f);
    if (p.has("gb")) ganBoca = constrain(p.get("gb").toFloat(), 0.3f, 3.f);
    if (p.has("sk")) sensK = constrain(p.get("sk").toFloat(), 1.3f, 8.f);
    if (p.has("paq")) { caras::paquete(p.get("paq").toInt()); prefs.putUChar("paq", p.get("paq").toInt()); dib::todo(); }
    if (p.has("mg")) { micGan = constrain(p.get("mg").toInt(), 0, 7); if (es) es8311_microphone_gain_set(es, (es8311_mic_gain_t)micGan); }
    guardar(); return "ok";
  }
  if (ruta == "/estado") {
    int bat = pmuOk && pmu.isBatteryConnect() ? pmu.getBatteryPercent() : -1;
    return String("{\"emo\":\"") + EMO_NOM[emo] + "\",\"modo\":" + modo + ",\"estado\":" + estado +
      ",\"estilo\":\"" + EST_NOM[estilo] + "\",\"bpm\":" + int(aBpm) + ",\"bat\":" + bat + ",\"usb\":" + (pmuOk && pmu.isVbusIn() ? 1 : 0) +
      ",\"carga\":" + (pmuOk && pmu.isCharging() ? 1 : 0) + ",\"vol\":" + volumen + ",\"son\":" + sonidoOn + ",\"mus\":" + musicaOn + ",\"mc\":" + musicaCel +
      ",\"bri\":" + brillo + ",\"ver\":\"" VERSION "\"" + ",\"uv\":" + String(umbralVoz, 2) + ",\"gb\":" + String(ganBoca, 2) +
      ",\"sk\":" + String(sensK, 1) + ",\"mg\":" + micGan + ",\"adl\":" + int(adelante) + ",\"fr\":" + String(umbralFreno, 2) + ",\"fw\":" + VERSION_FW +
      ",\"cps\":" + String(1000000UL / max((uint32_t)33000, frameUs)) + ",\"paq\":" + prefs.getUChar("paq", 0) + "}";  // cuadros por segundo del último cuadro
  }
  if (ruta == "/audio") {  // en vivo, para ver y calibrar el canto desde la app
    char b[300];
    snprintf(b, sizeof b, "{\"estado\":%u,\"estilo\":\"%s\",\"bpm\":%.0f,\"nivel\":%.2f,\"voz\":%.2f,\"canta\":%d,\"boca\":%.2f,\"ancho\":%.2f,"
      "\"bajo\":%.2f,\"rms\":%.0f,\"piso\":%.0f,\"golpes\":%lu,\"reg\":%.2f}",
      estado, EST_NOM[estilo], aBpm, aNivel, aVoz, cantando ? 1 : 0, bocaV, aAncho, aBajoP, aRms, aPiso, (unsigned long)beatCnt, aReg);
    return b;
  }
  // Actualización por Bluetooth: inicio (tamaño y MD5), los datos llegan por la característica OTA, y fin (verifica y reinicia).
  if (ruta == "/ota/inicio") {
    uint32_t tam = p.get("tam").toInt();
    if (tam < 100000 || !Update.begin(tam)) { cod = 400; return String("no se puede actualizar: ") + Update.errorString(); }
    if (p.has("md5")) Update.setMD5(p.get("md5").c_str());
    otaHecho = 0; otaError = false; otaTotal = tam;
    return "ok";
  }
  if (ruta == "/ota/fin") {
    uint32_t tam = otaTotal, hecho = otaHecho;
    otaTotal = 0;
    if (otaError || hecho != tam) { Update.abort(); cod = 400; return String("llegaron ") + hecho + " de " + tam + " bytes"; }
    if (!Update.end()) { cod = 400; return String("el archivo no pasó la verificación: ") + Update.errorString(); }
    reinicioEn = millis() + 1500;  // da tiempo de mandar la respuesta
    return "ok";
  }
  if (ruta == "/ota/cancelar") { if (otaTotal) { otaTotal = 0; Update.abort(); } return "ok"; }
  cod = 404; return "no existe";
}

// ---------- Bluetooth (BLE): la app se conecta sin quitarle el internet al celular ----------
// Estilo "UART de Nordic": la app escribe "id|ruta?args" en RX y el robot responde "id|respuesta\n" por TX (en pedazos).
// La característica OTA recibe el firmware en pedazos, cada uno con confirmación (no se pierde ninguno).
#define BLE_SRV "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
#define BLE_RX  "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
#define BLE_TX  "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
#define BLE_OTA "6e400004-b5a3-f393-e0a9-e50e24dcca9e"
struct OrdenBle { char t[240]; };
QueueHandle_t bleCola;
BLECharacteristic *bleTx;
volatile bool bleOn = false;

class BleRx : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {  // corre en la tarea del Bluetooth: solo encola, loop() atiende
    OrdenBle o{}; String v = c->getValue();
    strlcpy(o.t, v.c_str(), sizeof o.t);
    xQueueSend(bleCola, &o, 0);
  }
};
class BleOta : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {  // escribe directo en la memoria: llegan en orden, uno por uno
    if (!otaTotal || otaError) return;
    size_t n = c->getLength();
    if (Update.write(c->getData(), n) != n) otaError = true;
    otaHecho += n;
  }
};
class BleSrv : public BLEServerCallbacks {
  void onConnect(BLEServer *) override { bleOn = true; }
  void onDisconnect(BLEServer *s) override {
    bleOn = false;
    if (otaTotal) { otaTotal = 0; Update.abort(); }  // se cortó a mitad: sigue con la versión que tenía
    s->startAdvertising();
  }
};

void bleInit() {
  bleCola = xQueueCreate(8, sizeof(OrdenBle));
  BLEDevice::init(NOMBRE_BLE);
  BLEDevice::setMTU(247);
  BLEServer *s = BLEDevice::createServer();
  s->setCallbacks(new BleSrv());
  BLEService *sv = s->createService(BLE_SRV);
  BLECharacteristic *rx = sv->createCharacteristic(BLE_RX, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  rx->setCallbacks(new BleRx());
  bleTx = sv->createCharacteristic(BLE_TX, BLECharacteristic::PROPERTY_NOTIFY);
  bleTx->addDescriptor(new BLE2902());
  BLECharacteristic *ota = sv->createCharacteristic(BLE_OTA, BLECharacteristic::PROPERTY_WRITE);
  ota->setCallbacks(new BleOta());
  sv->start();
  BLEAdvertising *a = BLEDevice::getAdvertising();
  a->addServiceUUID(BLE_SRV);
  a->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.printf("BLE listo: %s  %s\n", NOMBRE_BLE, BLEDevice::getAddress().toString().c_str());
}

// Aviso que Cubi le manda al celular sin que se lo pidan: "ev|nombre"
void bleEvento(const char *nombre) {
  if (!bleOn) return;
  String m = String("ev|") + nombre + "\n";
  bleTx->setValue((uint8_t *)m.c_str(), m.length());
  bleTx->notify();
}

// Doble toque o "Hola Cubi": cara de escuchar y le pide al celular que abra el asistente
void llamarAsistente() {
  despertar(millis());
  ponerAsistente(ESCUCHA, 15000);
  sonar(S_ESCUCHA);
  bleEvento("asistente");
}

void bleAtender() {
  OrdenBle o;
  while (xQueueReceive(bleCola, &o, 0)) {
    String t = o.t;
    int bar = t.indexOf('|'), q = t.indexOf('?');
    String id = t.substring(0, bar), ruta = q < 0 ? t.substring(bar + 1) : t.substring(bar + 1, q);
    Params p;
    if (q >= 0) {
      String qs = t.substring(q + 1);
      while (qs.length()) {
        int amp = qs.indexOf('&');
        String par = amp < 0 ? qs : qs.substring(0, amp);
        int eq = par.indexOf('=');
        p.add(urlDec(eq < 0 ? par : par.substring(0, eq)), eq < 0 ? String() : urlDec(par.substring(eq + 1)));
        qs = amp < 0 ? String() : qs.substring(amp + 1);
      }
    }
    int cod; String r = atender(ruta, p, cod);
    String msg = id + "|" + (cod == 200 ? "" : "!") + r + "\n";
    for (int i = 0; bleOn && i < msg.length(); i += 180) {
      String parte = msg.substring(i, i + 180);
      bleTx->setValue((uint8_t *)parte.c_str(), parte.length());
      bleTx->notify();
      delay(4);
    }
  }
}

// ---------- Depuración por el cable USB ----------
// c = captura de pantalla (FB + ancho, alto + RGB565 crudo), 0..8 = emoción, s = simular canto, m = simular baile, t = tiempo por cuadro
void serialDebug() {
  while (Serial.available()) {
    char ch = Serial.read();
    if (ch == 'c') {
      uint16_t dim[2] = { 368, 448 };
      Serial.write("FB"); Serial.write((uint8_t *)dim, 4); Serial.write((uint8_t *)cv->getFramebuffer(), 368 * 448 * 2); Serial.flush();
    } else if (ch >= '0' && ch <= '8') { emoBase = NEUTRAL; demoHasta = 0; setEmo(Emo(ch - '0'), 8000); emoForzada = true; }
    else if (ch == 's' || ch == 'm') { emoHasta = 0; demo(ch == 's'); }
    else if (ch == 't') Serial.printf("cuadro %lu us (animar %lu, dibujar %lu, flush %lu)  ble=%d\n", (unsigned long)frameUs,
      (unsigned long)tAnim, (unsigned long)tDib, (unsigned long)tFlush, (int)bleOn);
  }
}

// ---------- Setup / loop ----------
void setup() {
  Serial.begin(115200);
  Wire.begin(P_SDA, P_SCL, 400000);
  energiaInit();
  prefs.begin("robot");
  rotacion = prefs.getUChar("rot", ROTACION_INICIAL); volumen = prefs.getUChar("vol", VOLUMEN_INICIAL);
  sonidoOn = prefs.getBool("son", true); musicaOn = prefs.getBool("mus", true); musicaCel = prefs.getBool("mc", true);
  caras::paquete(prefs.getUChar("paq", caras::CUBI_AZUL));
  umbralVoz = prefs.getFloat("uv", UMBRAL_VOZ); ganBoca = prefs.getFloat("gb", GANANCIA_BOCA);
  sensK = prefs.getFloat("sk", SONIDO_K); micGan = prefs.getUChar("mg", GANANCIA_MIC); adelante = prefs.getChar("adl", ADELANTE_SIGNO); umbralFreno = prefs.getFloat("fr", UMBRAL_FRENO);

  // Reset de pantalla y táctil por el expansor TCA9554 (pines 0..2), misma secuencia que el firmware de fábrica V2.
  // Con pulsos cortos (20 ms) el panel puede quedar trabado tras regrabar: ver github.com/waveshareteam/ESP32-S3-Touch-AMOLED-1.8/issues/12
  i2cW(TCA9554, 0x01, 0x07); i2cW(TCA9554, 0x03, 0xF8); delay(100);
  i2cW(TCA9554, 0x01, 0x00); delay(300);
  i2cW(TCA9554, 0x01, 0x07); delay(100);
  if (i2cHay(0x38)) tpAddr = 0x38; else if (i2cHay(0x15)) tpAddr = 0x15;
  int pant = PANTALLA ? PANTALLA : (tpAddr == 0x15 ? 2 : 1);
  Serial.printf("Cubi v" VERSION "  tactil=0x%02X  pantalla=%s\n", tpAddr, pant == 2 ? "CO5300" : "SH8601");

  bus = new Arduino_ESP32QSPI(P_CS, P_SCK, P_D0, P_D1, P_D2, P_D3);
  if (pant == 2) panel = new Arduino_CO5300(bus, -1, 0, 368, 448);
  else panel = new Arduino_SH8601(bus, -1, 0, 368, 448);
  cv = new Arduino_Canvas(368, 448, panel, 0, 0, rotacion);
  if (!cv->begin()) Serial.println("ERROR pantalla (sin PSRAM? activa OPI PSRAM)");
  // Borra TODA la memoria del panel: hay una franja fuera de los 368x448 que nunca se dibuja
  // y mostraba basura (la "línea verde" de abajo)
  panel->startWrite();
  panel->writeAddrWindow(0, 0, 480, 480);
  bus->writeRepeat(0x0000, 480UL * 480UL);
  panel->endWrite();
  panel->setBrightness(brillo);
  cv->fillScreen(0); cv->flush();
  tmpPanel = (uint16_t *)ps_malloc(368 * 448 * 2);
  caras::iniciar(esp_random());

  imuInit();
  lastMotion = millis();
  audioInit();
  sonar(S_ARRANQUE);
  bleInit();  // sin WiFi: todo va por Bluetooth y la batería dura mucho más
}

void loop() {
  uint32_t now = millis();
  serialDebug();
  bleAtender();
  if (reinicioEn && now > reinicioEn) ESP.restart();
  imuUpdate(now);
  touchUpdate(now);
  energiaUpdate(now);
  // Pantalla apagada tras MINUTOS_APAGAR sin nada; la despierta moverlo, tocarlo, un sonido o que se conecte el celular
  static bool bleAntes = false, pantallaOff = false;
  if (bleOn && !bleAntes) despertar(now);
  bleAntes = bleOn;
  uint32_t oyo = tSonido;
  if (now - oyo < 1000 && oyo > lastMotion) despertar(now);
  bool apagar = now - lastMotion > MINUTOS_APAGAR * 60000UL && modo == NORMAL && !asis && !otaTotal && !reinicioEn;
  if (apagar != pantallaOff) {
    pantallaOff = apagar;
    panel->setBrightness(apagar ? 0 : brillo);
    if (!apagar) dib::todo();  // al prender se manda la pantalla entera
  }
  if (pantallaOff) return;     // apagada no dibuja (ahorra batería); el audio, el movimiento y el Bluetooth siguen
  // El movimiento se calcula en pasos fijos de 33 ms (las animaciones están afinadas para ese ritmo)
  // y se dibuja lo más seguido que dé la pantalla (~15 cuadros/s): así todo va a la velocidad correcta.
  // ponytail: dibujar + enviar tardan ~65 ms; más cuadros pedirían dibujar en la orientación nativa del panel.
  static uint32_t tSim;
  if (now - tSim >= 33) {
    uint32_t t0 = micros();
    if (now - tSim > 200) tSim = now - 33;  // tras una pausa larga no intenta ponerse al día
    while (now - tSim >= 33) { tSim += 33; animar(tSim); }
    tAnim = micros() - t0;
    pintar(now); tDib = micros() - t0 - tAnim;  // dibuja y manda solo lo que cambió
    tFlush = 0;
    frameUs = micros() - t0;
  }
}
