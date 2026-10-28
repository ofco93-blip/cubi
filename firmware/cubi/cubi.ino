// Cubi v2 — Waveshare ESP32-S3-Touch-AMOLED-1.8 (V1 y V2)
// Caras nuevas, canta con la música (lipsync por el micrófono), siente la música con efectos,
// sonidos, energía y batería (AXP2101), reacciones al movimiento, app Android por Bluetooth y actualización por Bluetooth.
#include <Arduino_GFX_Library.h>
#include <Wire.h>
#include <Update.h>
#include <Preferences.h>
#include <ESP_I2S.h>
#include <arduinoFFT.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>
#define XPOWERS_CHIP_AXP2101
#include <XPowersLib.h>
#include "es8311.h"
#include "config.h"

#define VERSION "2.0"

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
float umbralVoz = UMBRAL_VOZ, ganBoca = GANANCIA_BOCA, sensK = SONIDO_K;
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
// Voz: energía en 280-3000 Hz que sea tonal (no ruido de platillos) y que suba y baje como las sílabas.
// ponytail: reglas a mano, se calibran desde la app. Si se quedan cortas: separar la voz con un modelo TinyML.
// =====================================================================================
float vR[NF], vI[NF];
ArduinoFFT<float> FFT(vR, vI, NF, SR);
volatile uint8_t estilo = NADA;
volatile uint32_t beatCnt = 0, hiCnt = 0;
volatile bool aCanta = false;
volatile float aNivel = 0, aVoz = 0, aBoca = 0, aAncho = 0.5f, aBajoP = 0, aBpm = 0, aReg = 0, aBajo = 0, aMedio = 0, aAgudo = 0, aRms = 0, aPiso = 0;
const char *EST_NOM[N_EST] = { "nada", "voz", "balada", "tropical", "reggaeton", "electronica", "rock" };

uint8_t clasificar(float bpm, float reg, float bajo, float medio, float agudo) {
  if (reg < 0.4f) return bajo < 0.22f && medio > 0.35f ? VOZ : BALADA;  // sin golpe claro
  if (bpm < 84) return BALADA;
  if (bajo > BAJO_ALTO && bpm >= 118 && bpm <= 140) return ELECTRO;
  if (agudo > AGUDO_ALTO && bajo < BAJO_ALTO) return ROCK;
  if (bajo > BAJO_ALTO - 0.05f && bpm <= 108) return REGGAETON;
  if (bpm >= 140) return ROCK;
  return TROPICAL;  // salsa, cumbia, vallenato, merengue...
}

float suma(int a, int b) { float s = 0; for (int i = a; i <= b; i++) s += vR[i]; return s; }

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
  vozS += ((hay ? (0.5f * fracN + 0.5f * tonN) * modN : 0) - vozS) * 0.12f;  // ~250 ms
  if (!canta && vozS > umbralVoz) { if (++sube >= 4) canta = true; } else sube = 0;  // 130 ms seguidos para arrancar
  if (canta && vozS < umbralVoz * 0.7f) canta = false;

  // boca: envolvente de la banda de voz con control automático de nivel (dB)
  vMax = max(db, vMax - 0.02f); vMin = min(db, vMin + 0.03f);
  float o = constrain((db - vMin - 3) / max(6.f, vMax - vMin - 3), 0.f, 1.f);
  o = min(1.f, powf(o, 1.4f) * ganBoca);
  float t = canta ? o : 0;
  boca += (t - boca) * (t > boca ? 0.7f : 0.35f);
  float hz = ev > 1 ? cw / ev * 31.25f : 0;   // centro de la voz: grave = boca redonda (o/u), agudo = ancha (e/i)
  ancho += (constrain((hz - 650) / 1300, 0.f, 1.f) - ancho) * 0.3f;

  hAvg += (eh - hAvg) * 0.05f;
  if (hay && eh > hAvg * 2.2f) hiCnt++;  // platillo / hi-hat: chispas
  aVoz = vozS; aCanta = canta; aBoca = boca; aAncho = ancho;
}

void tareaAudio(void *) {
  float bAvg = 0, bPrev = 0, piso = 30, pico = 300, fB = 0, fM = 0, fA = 0;
  uint32_t ultBeat = 0, tEval = 0, tSon = 0, mudoHasta = 0, paHasta = 0;
  uint16_t ibi[12]; int nIbi = 0, iIbi = 0; uint8_t cand = NADA, candN = 0;
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
    pico = max(rms, pico * 0.998f);
    aNivel = hay ? constrain(rms / pico, 0.f, 1.f) : 0;
    aRms = rms; aPiso = piso;
    analizarVoz(hay);

    bAvg += (b - bAvg) * 0.04f;
    aBajoP = hay ? constrain((b - bAvg) / (bAvg + 1), 0.f, 1.f) : 0;
    if (hay && b > bAvg * BEAT_K && b > bPrev * 1.1f && now - ultBeat > 300) {
      if (ultBeat && now - ultBeat < 2000) { ibi[iIbi] = now - ultBeat; iIbi = (iIbi + 1) % 12; nIbi = min(nIbi + 1, 12); }
      ultBeat = now; beatCnt++;
    }
    bPrev = b;
    if (hay) { fB += (b / tot - fB) * 0.03f; fM += (md / tot - fM) * 0.03f; fA += (hi / tot - fA) * 0.03f; tSon = now; }

    if (now - tEval < 2000) continue;
    tEval = now;
    if (now - tSon > 3000) { estilo = NADA; nIbi = 0; candN = 0; continue; }
    uint16_t o[12]; memcpy(o, ibi, sizeof(o));
    std::sort(o, o + nIbi);
    float med = nIbi ? o[nIbi / 2] : 0, bpm = med ? 60000.f / med : 0, reg = 0;
    while (bpm && bpm < 70) bpm *= 2;
    while (bpm > 180) bpm /= 2;
    for (int i = 0; i < nIbi; i++) if (fabsf(o[i] - med) < med * 0.18f) reg += 1.f / nIbi;
    if (nIbi < 4 || now - ultBeat > 2500) reg = 0;
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
bool emoForzada = false;  // pedida desde la app o tocando: le gana a la música; las del movimiento no
uint8_t anim = NINGUNA; uint32_t animT0 = 0, animProx = 8000, animPaso = 0;
float bostezo = 0, abreDer = 1;
uint8_t asis = NO_ASIS; uint32_t asisHasta = 0, asisT0 = 0;
void ponerAsistente(uint8_t e, uint32_t ms) { asis = e; asisT0 = millis(); asisHasta = asisT0 + ms; emoHasta = 0; }
void setEmo(Emo e, uint32_t ms) { if (e != emo) sonar(SONIDO_EMO[e]); emo = e; emoHasta = ms ? millis() + ms : 0; emoForzada = false; }

Modo modo = NORMAL;
uint32_t modoDesde = 0, modoHasta = 0, proxRing = 0;
void setModo(Modo m, uint32_t ms) { modo = m; modoDesde = millis(); modoHasta = ms ? millis() + ms : 0; }

void evento(const String &t) {
  if (t == "whatsapp") { if (modo == NORMAL || modo == WHATSAPP) { setModo(WHATSAPP, 3500); sonar(S_WHATSAPP); } }
  else if (t == "llamada") { setModo(RING, 60000); proxRing = 0; }
  else if (t == "encurso") setModo(ENLLAMADA, 2UL * 3600000UL);
  else if (t == "colgar") { setModo(NORMAL, 0); sonar(S_COLGAR); setEmo(FELIZ, 1200); }
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

// Barra gruesa con puntas redondas (cejas, ojos cerrados)
void barra(float x0, float y0, float x1, float y1, float t, uint16_t c) {
  float dx = x1 - x0, dy = y1 - y0, L = sqrtf(dx * dx + dy * dy);
  if (L < 1) { cv->fillCircle(x0, y0, t / 2, c); return; }
  float nx = -dy / L * t / 2, ny = dx / L * t / 2;
  cv->fillTriangle(x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, c);
  cv->fillTriangle(x0 + nx, y0 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny, c);
  cv->fillCircle(x0, y0, t / 2, c); cv->fillCircle(x1, y1, t / 2, c);
}

// Ojo: halo, cuerpo con degradado, brillos y párpados. lado: -1 izquierdo, +1 derecho. k: tamaño extra por perspectiva.
void ojo(float cx, float cy, int lado, float abre, float k) {
  float w = cur.ew * U * escala * sqx * k * (1 + (1 - abre) * 0.12f), h = cur.eh * U * escala * sqy * k * abre;
  if (h < 8 * U) { barra(cx - w * 0.38f, cy, cx + w * 0.38f, cy, 8 * U, c565(cur.c1)); return; }
  float r = min(cur.er * U * escala * k, min(w, h) / 2), m = 9 * U;
  C3 halo = mix(fondoC, cur.c2, 0.22f);
  formaOjo(cx, cy, w, h, r, lado, m, halo, fondoC, false);   // halo
  formaOjo(cx, cy, w, h, r, lado, 0, halo, halo, true);      // ojo con degradado
  float top = cy - h / 2;  // brillos, solo si quedan por debajo del párpado
  float gx[2] = { cx - w * 0.22f, cx - w * 0.05f }, gy[2] = { top + h * 0.22f, top + h * 0.37f }, gr[2] = { w * 0.11f, w * 0.05f };
  for (int i = 0; i < 2; i++)
    if (cur.arco < 0.4f && gy[i] - gr[i] > yParpado(gx[i], cx, cy, w, h, lado)) cv->fillCircle(gx[i], gy[i], gr[i], 0xFFFF);
}

// Altura del párpado de arriba en x: baja recto (sueño) y se inclina (enojo: baja por dentro; tristeza: por fuera)
float yParpado(float x, float cx, float cy, float w, float h, int lado) {
  float base = cy - h / 2 + cur.lid * h, yi = base + max(0.f, cur.ang) * h * 0.5f, yo = base + max(0.f, -cur.ang) * h * 0.45f;
  float xo = cx + lado * w / 2, xi = cx - lado * w / 2;
  return yo + (yi - yo) * (x - xo) / (xi - xo);
}

// Línea horizontal con los bordes suavizados (mezcla el pixel del borde con el color de atrás)
void tramo(float xs, float xe, int y, C3 c, C3 bg) {
  int a = ceilf(xs), b = floorf(xe);
  if (b < a) { cv->drawPixel(int(xs), y, c565(mix(bg, c, xe - xs))); return; }
  if (b > a) cv->drawFastHLine(a, y, b - a, c565(c));
  if (a - xs > 0.02f) cv->drawPixel(a - 1, y, c565(mix(bg, c, a - xs)));
  if (xe - b > 0.02f) cv->drawPixel(b, y, c565(mix(bg, c, xe - b)));
}

// Rectángulo redondeado del ojo, agrandado e píxeles, recortado por los dos párpados (sin pintar encima del fondo).
void formaOjo(float cx, float cy, float w, float h, float r, int lado, float e, C3 col, C3 bg, bool degradado) {
  float top = cy - h / 2 - e, bot = cy + h / 2 + e, R = r + e, x0 = cx - w / 2 - e, x1 = cx + w / 2 + e;
  float ey = cy + h / 2 + h * 0.25f, erx = w * 0.78f - e, ery = cur.arco * h * 1.05f - e;  // arco de abajo: ojos felices ^ ^
  bool arco = cur.arco > 0.02f && ery > 1 && erx > 1;
  float xo = cx + lado * w / 2, xi = cx - lado * w / 2;
  float yo = yParpado(xo, cx, cy, w, h, lado) - e, pend = (yParpado(xi, cx, cy, w, h, lado) - e - yo) / (xi - xo);
  for (int y = ceilf(top); y <= bot; y++) {
    float d = 0, off = 0;
    if (y < top + R) d = top + R - y; else if (y > bot - R) d = y - (bot - R);
    if (d > 0) off = R - sqrtf(max(0.f, R * R - d * d));
    float xs = x0 + off, xe = x1 - off;
    if (fabsf(pend) < 1e-4f) { if (y < yo) continue; }   // se ve solo lo que queda debajo del párpado
    else if (pend > 0) xe = min(xe, xo + (y - yo) / pend); else xs = max(xs, xo + (y - yo) / pend);
    if (xe <= xs) continue;
    if (degradado) col = mix(cur.c1, cur.c2, constrain((y - top) / h, 0.f, 1.f));
    float dy = (y - ey) / ery;
    if (arco && fabsf(dy) < 1) {
      float half = erx * sqrtf(1 - dy * dy), e1 = min(xe, cx - half), s2 = max(xs, cx + half);
      if (e1 > xs) tramo(xs, e1, y, col, e1 < xe ? fondoC : bg);
      if (xe > s2) tramo(s2, xe, y, col, s2 > xs ? fondoC : bg);
    } else tramo(xs, xe, y, col, bg);
  }
}

void ceja(float cx, float top, int lado) {
  if (cur.ceja < 0.05f) return;
  float bw = cur.ew * U * 0.85f, y = top - (26 + cur.cejaY) * U, k = cur.cejaAng * 15 * U;
  barra(cx + lado * bw / 2, y - k, cx - lado * bw / 2, y + k, 12 * U, tenue(cur.c1, cur.ceja));
}

void corazon(float cx, float cy, float s, uint16_t c) {
  cv->fillCircle(cx - s / 2, cy - s / 4, s / 2, c);
  cv->fillCircle(cx + s / 2, cy - s / 4, s / 2, c);
  cv->fillTriangle(cx - s, cy - s / 8, cx + s, cy - s / 8, cx, cy + s, c);
}
void espiral(float cx, float cy, float r, float rot, uint16_t c) {
  for (int k = 0; k < 4; k++) { float rr = r * (1 - k * 0.24f); cv->fillArc(cx, cy, rr, rr - r * 0.12f, rot + k * 95, rot + k * 95 + 250, c); }
}
void gota(float cx, float cy, float s, uint16_t c) {
  cv->fillCircle(cx, cy, s, c);
  cv->fillTriangle(cx - s, cy - s * 0.2f, cx + s, cy - s * 0.2f, cx, cy - s * 2.2f, c);
}
void notaMusical(float x, float y, uint16_t c) {
  cv->fillCircle(x, y, 9, c); cv->fillRect(x + 6, y - 34, 4, 34, c); cv->fillTriangle(x + 10, y - 34, x + 24, y - 24, x + 10, y - 20, c);
}
void estrella(float x, float y, float s, uint16_t c) {
  cv->fillTriangle(x - s, y, x + s, y, x, y - s * 0.22f, c); cv->fillTriangle(x - s, y, x + s, y, x, y + s * 0.22f, c);
  cv->fillTriangle(x, y - s, x, y + s, x - s * 0.22f, y, c); cv->fillTriangle(x, y - s, x, y + s, x + s * 0.22f, y, c);
}

// Boca: canta/habla con la voz (abre con el volumen, redonda u ancha según el tono); quieta según la emoción.
void boca(float cx, float my) {
  uint16_t col = c565(cur.c1), oscuro = c565(hx(0x2A0614));
  if (hablaV > 0.05f) {
    float a = bocaV;
    if (a < 0.06f) { cv->fillArc(cx, my - 14 * U, 20 * U, 14 * U, 30, 150, col); return; }  // entre sílabas
    float mw = (24 + 16 * anchoV + 6 * a) * U, mh = (8 + 44 * a * (1.1f - 0.35f * anchoV)) * U, t = 6 * U;
    cv->fillEllipse(cx, my, mw, mh, col);
    cv->fillEllipse(cx, my, mw - t, max(2.f, mh - t), oscuro);
    return;
  }
  if (cur.boca > 1.5f || bostezo > 0.05f) {  // "o" (sorpresa) o bostezo
    float b = bostezo;
    cv->fillEllipse(cx, my, (15 + 10 * b) * U, (19 + 26 * b) * U, col); cv->fillEllipse(cx, my, (9 + 10 * b) * U, (13 + 26 * b) * U, oscuro);
  }
  else if (cur.boca > 0.3f) cv->fillArc(cx, my - 30 * U, 34 * U, 26 * U, 35, 145, col);
  else if (cur.boca < -0.3f) cv->fillArc(cx, my + 30 * U, 34 * U, 26 * U, 215, 325, col);
}

void telefono(float cx, float cy, bool desc, uint32_t now) {
  uint16_t rojo = c565(hx(0xE0413A)), crema = c565(hx(0xFFE9C7)), osc = c565(hx(0x8A1F1A));
  cv->fillRoundRect(cx - 62, cy - 8, 124, 66, 18, rojo);
  cv->fillCircle(cx, cy + 25, 25, crema);
  for (int i = 0; i < 8; i++) { float a = i * 0.7f + 0.6f; cv->fillCircle(cx + cosf(a) * 16, cy + 25 + sinf(a) * 16, 4, osc); }
  cv->fillCircle(cx, cy + 25, 6, osc);
  if (!desc) {
    float hop = (modo == RING) ? fabsf(sinf(now * 0.03f)) * 10 : 0;
    cv->fillRoundRect(cx - 72, cy - 32 - hop, 144, 22, 11, rojo);
    cv->fillRoundRect(cx - 80, cy - 40 - hop, 38, 30, 12, rojo);
    cv->fillRoundRect(cx + 42, cy - 40 - hop, 38, 30, 12, rojo);
  } else cv->fillRoundRect(cx - 40, cy - 18, 80, 12, 6, osc);
}

void cable(float x0, float y0, float x1, float y1, uint32_t now) {
  uint16_t c = c565(hx(0xE0413A));
  float dx = x1 - x0, dy = y1 - y0, L = sqrtf(dx * dx + dy * dy);
  float nx = -dy / L, ny = dx / L, ph = now * 0.012f, px = x0, py = y0;
  for (int i = 1; i <= 90; i++) {
    float t = i / 90.f, sag = sinf(t * PI) * 30, amp = 9 * sinf(t * PI);
    float x = x0 + dx * t + nx * amp * sinf(t * 38 + ph), y = y0 + dy * t + ny * amp * sinf(t * 38 + ph) + sag;
    for (int k = -1; k <= 1; k++) cv->drawLine(px, py + k, x, y + k, c);
    px = x; py = y;
  }
}

void burbujaChat(float cx, float cy) {
  uint16_t verde = c565(hx(0x25D366));
  cv->fillCircle(cx, cy, 34, verde);
  cv->fillTriangle(cx - 30, cy + 14, cx - 14, cy + 30, cx - 40, cy + 40, verde);
  for (int i = -1; i <= 1; i++) cv->fillCircle(cx + i * 14, cy, 5, 0xFFFF);
}

// Golpe de la música: cada estilo baila distinto, y todos sueltan un anillo y una nota
void enGolpe(uint32_t now) {
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
  if (now < demoHasta) {  // simulación para probar sin música (desde la app o por serie)
    float t = (now - demoT0) / 1000.f;
    est = TROPICAL; libre = true;
    voz = demoCanto && fmodf(t, 4.f) < 3.2f;
    bIn = voz ? constrain(0.55f * sinf(t * 8.5f) + 0.35f * sinf(t * 3.1f) + 0.25f, 0.f, 1.f) : 0;
    aIn = 0.5f + 0.5f * sinf(t * 1.3f);
    uint32_t b = (now - demoT0) / 476; if (b != demoBeat) { demoBeat = b; enGolpe(now); }
  }
  bool musica = est >= BALADA;
  if (asis && now > asisHasta) asis = NO_ASIS;
  if (asis) { musica = false; est = NADA; libre = true; despertar(now); }  // el asistente le gana a todo
  // solo mueve la boca con canciones, o cuando el asistente (Gemini) le está hablando a uno
  if (!libre || !(musica || asis == HABLA)) voz = false;
  if (asis == HABLA && !voz) {  // si el micrófono no alcanza a oír a Gemini, habla igual (sílabas inventadas)
    float t = (now - asisT0) / 1000.f;
    voz = true; bIn = constrain((0.5f * sinf(t * 9.3f) + 0.3f * sinf(t * 3.7f) + 0.3f) * ganBoca / GANANCIA_BOCA, 0.f, 1.f); aIn = 0.5f + 0.4f * sinf(t * 1.7f);
  }
  static uint32_t cantaHasta = 0;  // la cara de cantar aguanta 1,5 s entre frases: no salta canta/baila a cada respiro
  if (voz) cantaHasta = now + 1500;
  bool canta = voz || now < cantaHasta;
  if (musica) despertar(now);
  if (beatCnt != beatVisto) { beatVisto = beatCnt; if (musica) enGolpe(now); }
  if (hiCnt != hiVisto) { hiVisto = hiCnt; if (musica) chispa(now); }
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

// Mientras se actualiza: anillo de progreso y porcentaje (sin cara, para que se note que no hay que apagarlo)
void pantallaOta() {
  float p = otaTotal ? float(otaHecho) / otaTotal : 1;
  C3 c = hx(0x3CD6E8);
  cv->fillArc(W / 2, H / 2, 120 * U, 106 * U, 0, 360, tenue(c, 0.2f));
  if (p > 0.005f) cv->fillArc(W / 2, H / 2, 120 * U, 106 * U, -90, -90 + 360 * p, c565(c));
  char t[8]; snprintf(t, sizeof t, "%d%%", int(p * 100));
  cv->setTextColor(0xFFFF); cv->setTextSize(5);
  cv->setCursor(W / 2 - strlen(t) * 15, H / 2 - 18); cv->print(t);
  cv->setTextColor(c565(c)); cv->setTextSize(2);
  cv->setCursor(W / 2 - 72, H / 2 + 30); cv->print("Actualizando");
}

void dibujar(uint32_t now) {
  cv->fillScreen(0x0000);
  if (otaTotal || reinicioEn) { pantallaOta(); return; }
  float abre = 1;
  if (now - blinkT0 < 180) abre = max(0.f, 1 - sinf(PI * (now - blinkT0) / 180.f));
  float sx = 0, sy = 0;
  if (caraAct == ENOJADO && est != ROCK) { sx = random(-4, 5); sy = random(-3, 4); }
  if (caraAct == MIEDO) sx = random(-2, 3);
  float cy = eyeCy + cur.dy * U + ly * U * escala + bounce + sy;
  float sep = 100 * U * escala, cx = W / 2 + lx * U * escala + sx;

  // fondo: brillo que late con el bajo, anillos en cada golpe y chispas con los platillos
  fondoC = NEGRO;
  if (fondo > 0.02f) {
    fondoC = mix(NEGRO, cur.c2, 0.05f + 0.13f * fondo);
    cv->fillCircle(W / 2, cy + 20 * U, (150 + 40 * fondo) * U, c565(fondoC));
  }
  for (auto &a : anillos) if (a.t0) {
    float t = (now - a.t0) / 800.f;
    if (t >= 1) { a.t0 = 0; continue; }
    float r = (100 + t * 320) * U; uint16_t col = tenue(a.c, 0.7f * (1 - t));
    for (int d = 0; d < 4; d++) cv->drawCircle(W / 2, cy, r + d, col);
  }
  for (auto &ch : chispas) if (ch.t0) {
    float t = (now - ch.t0) / 600.f;
    if (t >= 1) { ch.t0 = 0; continue; }
    estrella(ch.x, ch.y - t * 20 * U, 14 * U * (1 - t * 0.6f), tenue(ch.c, 1 - t));
  }
  for (auto &n : notas) if (n.t0) {
    float t = (now - n.t0) / 1000.f;
    if (t > 2.2f) { n.t0 = 0; continue; }
    notaMusical(n.x + 12 * sinf(t * 4), n.y - t * 70 * n.v, tenue(n.c, 1 - t / 2.2f));
  }

  if (caraAct == ENAMORADO) {
    float s = (46 + 6 * sinf(now * 0.012f)) * U * (1 + pulso);
    uint16_t c = c565(mix(cur.c1, cur.c2, 0.5f));
    corazon(cx - sep, cy, s, c); corazon(cx + sep, cy, s, c);
  } else if (caraAct == MAREADO) {
    espiral(cx - sep, cy, 52 * U, giro, c565(cur.c1)); espiral(cx + sep, cy, 52 * U, giro, c565(cur.c1));
  } else {
    // perspectiva: el ojo hacia donde mira se ve un poco más grande
    ojo(cx - sep, cy, -1, abre, 1 - lx * 0.0022f); ojo(cx + sep, cy, +1, abre * abreDer, 1 + lx * 0.0022f);
  }
  float top = cy - cur.eh * U * escala / 2;
  ceja(cx - sep, top, -1); ceja(cx + sep, top, +1);
  boca(cx, cy + cur.eh * U * escala / 2 + 46 * U);

  if (caraAct == TRISTE) gota(cx - sep - 30 * U, cy + 60 * U + (now / 12) % 90, 9 * U, c565(hx(0x6FB7FF)));
  if (caraAct == SUENO) {
    cv->setTextColor(c565(hx(0xB9A8FF))); cv->setTextSize(3);
    int z = (now / 600) % 3;
    for (int i = 0; i <= z; i++) { cv->setCursor(W / 2 + 120 + i * 22, 70 - i * 26); cv->print("z"); }
  }
  if (modo == WHATSAPP) burbujaChat(W - 70, 70 + 8 * sinf(now * 0.015f));
  if (modo == RING) {
    float sh = sinf(now * 0.09f) * 6;
    telefono(W / 2 + sh, 250, false, now);
    if ((now / 250) % 2) {
      uint16_t c = c565(hx(0xFFD25A));
      cv->fillArc(W / 2 - 95 + sh, 230, 26, 22, 140, 220, c);
      cv->fillArc(W / 2 + 95 + sh, 230, 26, 22, -40, 40, c);
    }
  }
  if (modo == ENLLAMADA) {
    telefono(W / 2 - 80, 260, true, now);
    float hx1 = W / 2 + 120, hy1 = 205 + 6 * sinf(now * 0.004f);
    cable(W / 2 - 80 + 58, 270, hx1 - 10, hy1 + 18, now);
    cv->fillRoundRect(hx1 - 18, hy1 - 50, 36, 110, 16, c565(hx(0xE0413A)));
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
  lon += (l - lon) * 0.1f;
  vert = v;
  yaw = dot(gyr, g) * GIRO_SIGNO;
  curvaLook = constrain(-yaw * 1.5f, -60.f, 60.f);

  if (sqrtf(dot(d, d)) > 0.06f || gmag > 6) despertar(now);
  if (emoBase != SUENO && now - lastMotion > MINUTOS_SUENO * 60000UL && modo == NORMAL) { emoBase = SUENO; setEmo(SUENO, 0); }

  // Frenón y arranque: deben durar 0,3 s (un hueco no cuenta) y le ganan a la música un ratico.
  static uint32_t frenaDesde = 0, aceleraDesde = 0;
  frenaDesde = lon < -UMBRAL_FRENO ? (frenaDesde ? frenaDesde : now) : 0;
  aceleraDesde = lon > UMBRAL_ACEL ? (aceleraDesde ? aceleraDesde : now) : 0;
  if (modo == NORMAL && !asis && now > cdEvento) {
    if (frenaDesde && now - frenaDesde > 300) { setEmo(MIEDO, 1800); emoForzada = true; bounce = 18; cdEvento = now + 4000; }
    else if (aceleraDesde && now - aceleraDesde > 300) { setEmo(FELIZ, 1500); emoForzada = true; cdEvento = now + 4000; }
    else if (fabsf(vert) > UMBRAL_BACHE) {  // hueco: brinca siempre; la cara y el sonido solo si no hay música
      bounce = -30; cdEvento = now + 1500;
      if (estilo < BALADA) { setEmo(SORPRESA, 700); sonar(S_BACHE); }
    }
  }
  if (fabsf(yaw) > GIRO_MAREO) {
    if (!mareoDesde) mareoDesde = now;
    if (now - mareoDesde > 1000 && modo == NORMAL && !asis && emo != MAREADO) { setEmo(MAREADO, 2500); emoForzada = true; }
  } else mareoDesde = 0;
#if DEBUG_IMU
  static uint32_t dbg; if (now - dbg > 300) { dbg = now; Serial.printf("lon=%.2f vert=%.2f giro=%.0f\n", lon, vert, yaw); }
#endif
}

// ---------- Táctil ----------
uint32_t tDown = 0, nextDemo = 0; bool tPrev = false; int demoIdx = 0;
void touchUpdate(uint32_t now) {
  uint8_t n;
  bool t = tpAddr && i2cR(tpAddr, 0x02, &n, 1) && (n & 0x0F) && (n & 0x0F) < 3;
  if (t && !tPrev) { tDown = now; nextDemo = now + 1500; despertar(now); }
  if (t && now > nextDemo) { demoIdx = (demoIdx + 1) % N_EMO; setEmo(Emo(demoIdx), 3000); emoForzada = true; nextDemo = now + 1200; }
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
    setEmo(FELIZ, 2000); sonar(S_RISA); bounce = -20;
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
  if (pmu.isVbusIn() || !pmu.isBatteryConnect()) { sinUsb = 0; return; }
  if (!sinUsb) sinUsb = now;
  bool vacia = pmu.getBatteryPercent() < 5;
  if (vacia || (APAGAR_SIN_USB_SEG && now - sinUsb > APAGAR_SIN_USB_SEG * 1000UL)) {
    // Se apaga del todo para cuidar la batería; el AXP2101 lo vuelve a prender al conectar el USB (o con el botón PWR).
    emoBase = SUENO; setEmo(SUENO, 0); sonar(S_SUENO);
    for (uint32_t t0 = millis(); millis() - t0 < 2500;) { animar(millis()); dibujar(millis()); cv->flush(); delay(30); }
    panel->setBrightness(0);
    pmu.shutdown();
  }
}

void guardar() {
  prefs.putUChar("rot", rotacion); prefs.putUChar("vol", volumen); prefs.putBool("son", sonidoOn); prefs.putBool("mus", musicaOn);
  prefs.putFloat("uv", umbralVoz); prefs.putFloat("gb", ganBoca); prefs.putFloat("sk", sensK); prefs.putUChar("mg", micGan); prefs.putChar("adl", adelante);
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
  if (ruta == "/demo") { despertar(millis()); emoHasta = 0; demo(p.get("t") != "baile"); return "ok"; }
  if (ruta == "/brillo") { brillo = constrain(p.get("v").toInt(), 0, 255); panel->setBrightness(brillo); return "ok"; }
  if (ruta == "/ajuste") {
    if (p.has("vol")) { volumen = constrain(p.get("vol").toInt(), 0, 100); if (es) es8311_voice_volume_set(es, volumen, NULL); sonar(S_WHATSAPP); }
    if (p.has("son")) sonidoOn = p.get("son") == "1";
    if (p.has("mus")) musicaOn = p.get("mus") == "1";
    if (p.has("adl")) adelante = -adelante;  // al frenar se pone feliz: se invierte
    if (p.has("rot")) { rotacion = rotacion == 3 ? 1 : 3; cv->setRotation(rotacion); }
    if (p.has("uv")) umbralVoz = constrain(p.get("uv").toFloat(), 0.05f, 0.95f);
    if (p.has("gb")) ganBoca = constrain(p.get("gb").toFloat(), 0.3f, 3.f);
    if (p.has("sk")) sensK = constrain(p.get("sk").toFloat(), 1.3f, 8.f);
    if (p.has("mg")) { micGan = constrain(p.get("mg").toInt(), 0, 7); if (es) es8311_microphone_gain_set(es, (es8311_mic_gain_t)micGan); }
    guardar(); return "ok";
  }
  if (ruta == "/estado") {
    int bat = pmuOk && pmu.isBatteryConnect() ? pmu.getBatteryPercent() : -1;
    return String("{\"emo\":\"") + EMO_NOM[emo] + "\",\"modo\":" + modo + ",\"estado\":" + estado +
      ",\"estilo\":\"" + EST_NOM[estilo] + "\",\"bpm\":" + int(aBpm) + ",\"bat\":" + bat + ",\"usb\":" + (pmuOk && pmu.isVbusIn() ? 1 : 0) +
      ",\"carga\":" + (pmuOk && pmu.isCharging() ? 1 : 0) + ",\"vol\":" + volumen + ",\"son\":" + sonidoOn + ",\"mus\":" + musicaOn +
      ",\"bri\":" + brillo + ",\"ver\":\"" VERSION "\"" + ",\"uv\":" + String(umbralVoz, 2) + ",\"gb\":" + String(ganBoca, 2) +
      ",\"sk\":" + String(sensK, 1) + ",\"mg\":" + micGan + ",\"adl\":" + int(adelante) + "}";
  }
  if (ruta == "/audio") {  // en vivo, para ver y calibrar el canto desde la app
    char b[300];
    snprintf(b, sizeof b, "{\"estado\":%u,\"estilo\":\"%s\",\"bpm\":%.0f,\"nivel\":%.2f,\"voz\":%.2f,\"canta\":%d,\"boca\":%.2f,\"ancho\":%.2f,"
      "\"bajo\":%.2f,\"rms\":%.0f,\"piso\":%.0f,\"golpes\":%lu}",
      estado, EST_NOM[estilo], aBpm, aNivel, aVoz, aCanta ? 1 : 0, aBoca, aAncho, aBajoP, aRms, aPiso, (unsigned long)beatCnt);
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
  sonidoOn = prefs.getBool("son", true); musicaOn = prefs.getBool("mus", true);
  umbralVoz = prefs.getFloat("uv", UMBRAL_VOZ); ganBoca = prefs.getFloat("gb", GANANCIA_BOCA);
  sensK = prefs.getFloat("sk", SONIDO_K); micGan = prefs.getUChar("mg", GANANCIA_MIC); adelante = prefs.getChar("adl", ADELANTE_SIGNO);

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
  // El movimiento se calcula en pasos fijos de 33 ms (las animaciones están afinadas para ese ritmo)
  // y se dibuja lo más seguido que dé la pantalla (~15 cuadros/s): así todo va a la velocidad correcta.
  // ponytail: dibujar + enviar tardan ~65 ms; más cuadros pedirían dibujar en la orientación nativa del panel.
  static uint32_t tSim;
  if (now - tSim >= 33) {
    uint32_t t0 = micros();
    if (now - tSim > 200) tSim = now - 33;  // tras una pausa larga no intenta ponerse al día
    while (now - tSim >= 33) { tSim += 33; animar(tSim); }
    tAnim = micros() - t0;
    dibujar(now); tDib = micros() - t0 - tAnim;
    cv->flush(); tFlush = micros() - t0 - tAnim - tDib;
    frameUs = micros() - t0;
  }
}
