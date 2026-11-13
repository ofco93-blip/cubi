// el dibujo es casi todo cuentas con decimales: más rápido que el -Os de Arduino
#pragma GCC optimize("O3", "fast-math")
#include "caras.h"

namespace caras {
using namespace dib;

const char *NOMBRE_BASE[N_BASE] = {"reposo", "escucha", "habla", "canta", "piensa", "duerme", "telefono"};
const char *NOMBRE_REAC[N_REAC] = {"", "saludo", "bache", "frenazo", "aceleron", "arranque", "izq", "der", "caricia",
                                   "whatsapp", "llamada", "calor", "risa", "sorpresa", "amor", "mareo", "maps_izq", "maps_der",
                                   "llegamos", "sol", "lluvia", "frio", "bateria", "guino", "curiosa", "kamehameha", "confeti", "maps_recto", "cinturon", "sonrojo", "enojo", "papas", "bebida", "consola", "leemapa", "cono", "crispetas", "feliz", "triste", "sueno", "ppt_piedra", "ppt_papel", "ppt_tijera"};
static const float DURA[N_REAC] = {0, 2.6f, 2.4f, 4.0f, 2.8f, 2.8f, 2.4f, 2.4f, 2.6f, 6.0f, 6.0f, 7.5f, 3.0f, 2.0f, 4.0f,
                                   3.2f, 4.5f, 4.5f, 4.0f, 7.0f, 7.0f, 5.0f, 4.0f, 1.9f, 2.4f, 5.0f, 20.0f, 4.5f, 5.6f, 4.6f, 4.4f, 5.0f, 5.0f, 5.0f, 4.5f, 5.6f, 5.6f, 3.6f, 5.0f, 5.5f, 5.0f, 5.0f, 5.0f};
static bool esMaps(Reac r) { return r == MAPS_IZQ || r == MAPS_DER || r == MAPS_RECTO; }   // las indicaciones de Maps / Waze
float duracion(Reac r) { return DURA[r]; }
const char *NOMBRE_RITMO[N_RITMO] = {"pop", "urbano", "electro", "tropical", "kpop", "corazon", "groove", "guitarra", "disfruta", "rock",
                                     "jazz", "dj", "piano", "barras", "manos", "acordeon", "vela"};

// ---------------------------------------------------------------- medidas (ojos grandes y redondos, como Copi)
constexpr float CX = W / 2.f, EY = 166, EX = 88, EW = 104, EH = 164, MY = 296, MIRA = 50;

struct Tono { Col a, b; };
enum { AZUL, ROJO, ROSA, ORO, NARANJA, MORADO, HIELO, VERDE, T_PANCHO, T_LINO, T_MOCHI };   // los tres últimos: el color propio de cada cara nueva
static const Tono TONOS[] = {
    {{185, 245, 255}, {40, 125, 255}},  {{255, 190, 140}, {235, 35, 45}},   {{255, 205, 230}, {255, 70, 150}},
    {{255, 245, 170}, {245, 150, 15}},  {{255, 230, 130}, {255, 95, 35}},   {{225, 200, 255}, {130, 70, 255}},
    {{235, 250, 255}, {120, 200, 255}}, {{200, 255, 210}, {40, 200, 120}},
    {{255, 255, 255}, {226, 232, 248}}, {{255, 255, 255}, {226, 232, 248}}, {{255, 255, 255}, {226, 232, 248}},   // las caras nuevas: ojos blancos
};

struct Ojo { float x, y, w, h, tapa, incl, feliz, alfa; };
struct Rostro {
  Ojo L, R;
  Col a, b;            // degradado de arriba y de abajo
  float boca;          // < 0.05 = sin boca
  float bx, by, bAncho;
  float mejillas;      // rubor 0..1
  float corazon;       // 0..1: ojos de corazón
};
static_assert(sizeof(Rostro) % sizeof(float) == 0, "Rostro debe ser solo floats");

static Rostro ahora, meta;
const char *NOMBRE_PAQ[N_PAQ] = {"Cubi azul", "Cubi", "Nova sólida", "Kira sólida", "Colores", "Nova", "Kira", "Pixel", "Pancho", "Lino", "Mochi"};
static uint8_t elPaquete = CUBI_AZUL;
static int manoEstilo = 3, tonoBase = AZUL;   // las manos y el color de base de la cara puesta
// Quedan cuatro caras: la minimalista y las tres nuevas. Las viejas ya no se ofrecen: si quedó guardada una, cae en la minimalista.
// ponytail: el código de las viejas sigue ahí sin usarse; se puede borrar cuando las cuatro estén asentadas
void paquete(uint8_t p) {
  elPaquete = p >= CARICATURA && p < N_PAQ ? p : MINIMALISTA;
  manoEstilo = elPaquete == CARICATURA ? 0 : elPaquete == TRAZO ? 1 : elPaquete == BOLITA ? 2 : 3;
  tonoBase = elPaquete == TRAZO ? T_LINO : elPaquete == BOLITA ? T_MOCHI : T_PANCHO;
  dib::sinFondo();
}
uint8_t paqueteAhora() { return elPaquete; }
static float elTamano = 1.2f;  // la cara más grande (con el acrílico encima se ve aún más): 1 = como antes
void tamano(float z) { elTamano = lim(z, 1.f, 1.5f); }
static Base laBase = REPOSO;
static Reac laReac = NINGUNA;
static float reacDesde = -1, tAhora = 0, baseDesde = 0, curvaAhora = 0;
static float gritoAhora = 0;  // 0..1: qué tan grande abre la boca en la parte fuerte (sigue cada sílaba, por niveles)
static float gritoZona = 0;
static float bailaAhora = 0;
static float emoAhora = 0;    // 0..1: la nota sostenida, suavizada (ojos cerrados y las dos manos en el micrófono)
static float rockeroAhora = 0;  // 0..1: la eléctrica suena fuerte: ojos apretados y se muerde el labio, como un roquero  // 0..1: la canción es bailable (tempo de baile y suena con fuerza): baila y usa la mano libre   // 0..1: está en una parte fuerte de la canción (sube rápido, baja en ~1 s)
static int gradosN = 0; static float gradosDesde = -1, gradosHasta = -1; static bool gradosM = false;
static char calleTxt[32] = "";
void mostrarCalle(const char *c) { snprintf(calleTxt, sizeof calleTxt, "%s", c ? c : ""); }
static int giroForma = 0;
static bool mapaDeDia = false; static int mapaKmh = -1;   // el tablero de Maps: de día va claro; la velocidad que manda el celular (-1 = no hay)
void mapaDia(bool dia) { mapaDeDia = dia; }
void mapaVel(int kmh) { mapaKmh = kmh; }
void mostrarGiro(int forma) { giroForma = forma; }
void mostrarGrados(int g, float seg, bool metros) { gradosN = g; gradosM = metros; gradosDesde = tAhora; gradosHasta = tAhora + seg; }

// ---------------------------------------------------------------- azar
static uint32_t semilla = 12345;
static float azar() { semilla ^= semilla << 13; semilla ^= semilla >> 17; semilla ^= semilla << 5; return (semilla & 0xFFFFFF) / 16777216.f; }
static float hash(int i, int k) { float s = sinf(i * 127.1f + k * 311.7f) * 43758.5453f; return s - floorf(s); }

static inline float suave(float x) { x = lim(x, 0, 1); return x * x * (3 - 2 * x); }
static inline float tramo(float u, float a, float b) { return suave((u - a) / (b - a)); }
static inline float campana(float u, float a, float b) { return (u <= a || u >= b) ? 0 : sinf((u - a) / (b - a) * PI); }

// El cinturón, por tiempos: 0 mira la correa en el hombro · 0,5 sube la mano y la agarra · 1,0 la cruza (con comba) ·
// 2,2 toma impulso · 2,45 ¡clic! (destello, chispas, la correa se tensa y vibra) · 3,4 suelta, pulgar arriba y guiño.
// cuánto falta para que un brazo (y lo que carga) termine de subir desde abajo de la pantalla; al final de la reacción vuelve a bajar
static float subeDe(float u, float dura, float t0 = 0.1f) { return 200 * (1 - tramo(u, t0, t0 + 0.7f)) + 200 * tramo(u, dura - 0.6f, dura); }
static float cinturonQ(float u) {            // por dónde va la lengüeta: 0 en el hombro, 1 dentro de la hebilla
  if (u < 1.0f) return 0;
  if (u < 2.2f) return 0.9f * suave((u - 1.0f) / 1.2f);
  if (u < 2.33f) return 0.9f - 0.07f * suave((u - 2.2f) / 0.13f);     // se devuelve un poquito: toma impulso
  if (u < 2.45f) return 0.83f + 0.17f * suave((u - 2.33f) / 0.12f);   // y la mete de un golpe
  return 1;
}
static Rostro neutro(int tono = AZUL) {
  Rostro r;
  // Todas vienen blancas y toman el color que se escoja en la app: Cubi y Pancho en ojos y boca (sus efectos siguen a color); Lino y Mochi en todo.
  if (((elPaquete == CARICATURA || elPaquete == MINIMALISTA) && tono != ROJO) || tono == AZUL) tono = tonoBase;
  r.L = {CX - EX, EY, EW, EH, 0, 0, 0, 1};
  r.R = {CX + EX, EY, EW, EH, 0, 0, 0, 1};
  r.a = TONOS[tono].a; r.b = TONOS[tono].b;
  if (tono == T_PANCHO) { r.a = dib::sinColor(r.a); r.b = dib::sinColor(r.b); }   // Cubi y Pancho: ojos y boca del color escogido (blanco de fábrica)
  r.boca = 0; r.bx = CX; r.by = MY; r.bAncho = 1; r.mejillas = 0; r.corazon = 0;
  {  // ojitos de píldora parada, más chicos (como el robot del tablero)
    r.L.x = CX - EX * 0.8f; r.R.x = CX + EX * 0.8f; r.L.w = r.R.w = EW * 0.6f; r.L.h = r.R.h = EH * 0.72f;
    r.by = MY - 34;
    if (elPaquete == CARICATURA) { r.L.x = CX - EX * 0.86f; r.R.x = CX + EX * 0.86f; r.L.w = r.R.w = EW * 0.8f; r.L.h = r.R.h = EH * 0.56f; }   // anchos y bajitos
    if (elPaquete == BOLITA) { r.L.w = r.R.w = EW * 0.5f; r.L.h = r.R.h = EH * 0.46f; }                                                      // chiquitos
  }
  return r;
}
// mirar a un lado (lx, ly en -1..1): el ojo del lado al que mira crece un poquito
static void mirar(Rostro &r, float lx, float ly) {
  r.L.x += lx * MIRA; r.R.x += lx * MIRA; r.L.y += ly * 30; r.R.y += ly * 30;
  float g = 0.06f * lx;
  r.L.w *= 1 - g; r.L.h *= 1 - g; r.R.w *= 1 + g; r.R.h *= 1 + g;
  r.bx += lx * 24;
}
static void escalar(Rostro &r, float sw, float sh) { r.L.w *= sw; r.R.w *= sw; r.L.h *= sh; r.R.h *= sh; }
static void mover(Rostro &r, float dx, float dy) { r.L.x += dx; r.R.x += dx; r.L.y += dy; r.R.y += dy; r.bx += dx; r.by += dy; }
static void tapas(Rostro &r, float t) { r.L.tapa = fmaxf(r.L.tapa, t); r.R.tapa = fmaxf(r.R.tapa, t); }
static void feliz(Rostro &r, float f) { r.L.feliz = r.R.feliz = f; }

// ---------------------------------------------------------------- parpadeo y mirada del reposo
static float proxParpadeo = 2, parpadeoEn = -10; static bool doble = false;
static float parpadeo(float t) {
  if (t > proxParpadeo) {
    parpadeoEn = t; proxParpadeo = t + 2.5f + azar() * 3.0f;
    doble = azar() < 0.2f;
  }
  float p = t - parpadeoEn;
  float v = campana(p, 0, 0.16f);
  if (doble) v = fmaxf(v, campana(p, 0.24f, 0.40f));
  return v;
}
// plan de miradas: (hacia dónde, cuánto se queda)
static const float PLAN[][3] = {{0, 0, 2.4f}, {-1, 0, 1.4f}, {0, 0, 1.8f}, {1, 0, 1.4f}, {0, 0, 2.2f},
                                {-0.6f, -0.5f, 1.2f}, {0, 0, 1.6f}, {0.7f, 0.3f, 1.1f}};
constexpr int N_PLAN = sizeof(PLAN) / sizeof(PLAN[0]);
static void mirada(float t, float &lx, float &ly) {
  float total = 0; for (auto &p : PLAN) total += p[2];
  float m = fmodf(t, total), acc = 0;
  for (int i = 0; i < N_PLAN; i++) {
    if (m < acc + PLAN[i][2]) {
      const float *a = PLAN[(i + N_PLAN - 1) % N_PLAN], *b = PLAN[i];
      float k = suave((m - acc) / 0.3f);          // 0,3 s para llegar
      lx = a[0] + (b[0] - a[0]) * k; ly = a[1] + (b[1] - a[1]) * k;
      return;
    }
    acc += PLAN[i][2];
  }
  lx = ly = 0;
}

// ---------------------------------------------------------------- piezas de dibujo
static Pintura tonoP(const Rostro &r, float y0, float y1, float halo, float alfa = 1) {
  Pintura p; p.arriba = r.a; p.abajo = r.b; p.y0 = y0; p.y1 = y1; p.halo = halo; p.haloA = 0.33f; p.haloCol = r.b; p.alfa = alfa;
  return p;
}

// Robot minimalista: blanco puro con su brillo (en AMOLED el negro de alrededor le da el volumen)
template <typename F>
static void volumen(float x0, float y0, float x1, float y1, float alfa, F d) {
  figura(x0, y0, x1, y1, solido(BLANCO, alfa, 12), d);
}
// máximo suave: donde se cruzan dos bordes queda una curva en vez de una punta
static inline float smax(float a, float b, float k) { float h = lim(0.5f - 0.5f * (b - a) / k, 0, 1); return b + (a - b) * h + k * h * (1 - h); }
// distancia a un arco de circunferencia (de a0 a a1, en radianes; y hacia abajo)
static float dArco(float px, float py, float cx, float cy, float R, float a0, float a1) {
  float dx = px - cx, dy = py - cy, mid = (a0 + a1) / 2, half = (a1 - a0) / 2;
  float da = remainderf(atan2f(dy, dx) - mid, 2 * PI);
  if (fabsf(da) <= half) return fabsf(sqrtf(dx * dx + dy * dy) - R);
  float a = mid + (da > 0 ? half : -half), ex = cx + cosf(a) * R - px, ey = cy + sinf(a) * R - py;
  return sqrtf(ex * ex + ey * ey);
}
// píldora: lados rectos y las puntas redondas (parada o acostada)
static float dPildora(float px, float py, float x, float y, float hw, float hh) { return dCaja(px, py, x, y, hw, hh, fminf(hw, hh)); }


static void ojo(const Rostro &r, const Ojo &o, float lado) {
  if (o.alfa < 0.02f || o.w < 2 || o.h < 2) return;
  float top = fminf(o.tapa, 0.98f);
  if (top >= 0.97f) return;
  const float x = o.x, y = o.y, hw = o.w / 2, hh = o.h / 2, rad = fminf(o.w, o.h) * 0.46f;
  const float y0 = y - hh + top * o.h, tn = tanf(o.incl) * lado, co = cosf(o.incl);
  const float fz = o.feliz, cy = y + o.h * (0.95f - 0.55f * fz), crx = o.w * 0.78f, cry = o.h * 0.62f;
  if (r.corazon > 0.5f) {
    float s = fminf(o.w, o.h) * 0.55f * (0.9f + 0.1f * r.corazon);
    auto cor = [=](float px, float py) { return dCorazon(px, py, x, y, s); };
    figura(x - s * 1.3f, y - s * 1.3f, x + s * 1.3f, y + s * 1.1f, tonoP(r, y - s, y + s, 20, o.alfa), cor);
    return;
  }
  {   // al entrecerrar se aplasta (no se corta) y todas las puntas quedan redondas
    const float hh2 = fmaxf(hh * (1 - top), 4.f), y2 = y + hh - hh2, cy2 = y2 + 2 * hh2 * (0.95f - 0.55f * fz), cry2 = 2 * hh2 * 0.62f;
    const float yl = y2 - hh2 + fabsf(tn) * hw;
    if (fz > 0.5f) {   // feliz: un arco de trazo grueso, como el robot del tablero (no una píldora recortada)
      const float R = hw * 1.1f, cyA = y + hh * 0.35f, gr = elPaquete == TRAZO ? 4.2f : elPaquete == CARICATURA ? 8 : 6.5f;
      figura(x - R - 9, cyA - R - 9, x + R + 9, cyA + 9, tonoP(r, cyA - R, cyA, 10, o.alfa),
             [=](float px, float py) { return dArco(px, py, x, cyA, R, -PI * 0.86f, -PI * 0.14f) - gr; });
      return;
    }
    if (elPaquete >= CARICATURA) {   // las caras nuevas: la misma mecánica (se aplasta al cerrar, cejas, media luna) con otra forma
      const int pq = elPaquete; const float gi = 0.16f * lado;
      figura(x - hw - 5, y2 - hh2 - 5, x + hw + 5, y2 + hh2 + 5, tonoP(r, y2 - hh2, y2 + hh2, 12, o.alfa), [=](float px, float py) {
        float d;
        if (pq == CARICATURA)        // pan de molde: arriba bien redondo, abajo casi cuadrado
          d = fminf(dCaja(px, py, x, y2 - hh2 * 0.12f, hw, hh2 * 0.88f, fminf(hw, hh2 * 0.88f) * 0.84f), dCaja(px, py, x, y2 + hh2 * 0.3f, hw, hh2 * 0.7f, fminf(hw, hh2 * 0.7f) * 0.36f));
        else if (pq == BOLITA) {     // huevito inclinado hacia adentro
          const float c = cosf(gi), s2 = sinf(gi), u2 = (px - x) * c + (py - y2) * s2, v2 = -(px - x) * s2 + (py - y2) * c;
          d = dElipse(u2, v2, 0, 0, hw, hh2);
        } else d = dPildora(px, py, x, y2, hw, hh2);
        if (o.incl != 0) d = smax(d, (yl + tn * (px - x) - py) * co, 10);
        if (fz > 0.01f) d = smax(d, -dElipse(px, py, x, cy2, crx, cry2), 8);
        if (pq == TRAZO && hh2 > 8) d = fabsf(d + 3.4f) - 3.4f;   // de línea: solo el contorno (cerrado queda una rayita)
        return d;
      });
      return;
    }
    // con los colores de Lumi azul (como Copi), no en blanco
    figura(x - hw, y2 - hh2, x + hw, y2 + hh2, tonoP(r, y2 - hh2, y2 + hh2, 14, o.alfa), [=](float px, float py) {
      float d = dPildora(px, py, x, y2, hw, hh2);
      if (o.incl != 0) d = smax(d, (yl + tn * (px - x) - py) * co, 10);
      if (fz > 0.01f) d = smax(d, -dElipse(px, py, x, cy2, crx, cry2), 8);
      return d;
    });
    return;
  }
}

static void boca(const Rostro &r) {
  float op = r.boca;
  float x = r.bx, y = r.by;
  if (op < 0.05f) return;
  float w = (26 + 14 * op) * r.bAncho, h = 16 + 62 * op;
  if (elPaquete >= CARICATURA) {   // las caras nuevas: sonrisa abierta, óvalo de línea o bolita
    const int pq = elPaquete;
    figura(x - w * 0.85f - 3, y - h / 2 - 3, x + w * 0.85f + 3, y + h / 2 + 3, tonoP(r, y - h / 2, y + h / 2, 10), [=](float px, float py) {
      if (pq == CARICATURA) return dElipse(px, py, x, y, w * 0.62f, h * 0.5f);   // Pancho: óvalo casi redondo
      if (pq == TRAZO) return fabsf(dCaja(px, py, x, y, w / 2, h / 2, fminf(w, h) / 2) + 2.6f) - 2.6f;
      return dElipse(px, py, x, y, w * 0.46f, h * 0.44f);
    });
    return;
  }
  // minimalista: la misma boca de Lumi azul (como Copi), que se ve mejor que la boquita blanca
  figura(x - w / 2, y - h / 2, x + w / 2, y + h / 2, tonoP(r, y - h / 2, y + h / 2, 12),
         [=](float px, float py) { return dCaja(px, py, x, y, w / 2, h / 2, fminf(w, h) / 2); });
}

static void chispa(float x, float y, float s, float alfa, const Col &c = {255, 245, 190}) {
  figura(x - s, y - s, x + s, y + s, solido(c, alfa, 10), [=](float px, float py) {
    return fminf(dElipse(px, py, x, y, s, s * 0.2f), dElipse(px, py, x, y, s * 0.2f, s));
  });
}
static void linea(float ax, float ay, float bx, float by, float g, const Col &c, float alfa, float halo = 0) {
  figura(fminf(ax, bx) - g, fminf(ay, by) - g, fmaxf(ax, bx) + g, fmaxf(ay, by) + g, solido(c, alfa, halo),
         [=](float px, float py) { return dSegmento(px, py, ax, ay, bx, by, g); });
}
static void circulo(float x, float y, float r, const Col &c, float alfa, float halo = 0) {
  figura(x - r, y - r, x + r, y + r, solido(c, alfa, halo), [=](float px, float py) { return dCirculo(px, py, x, y, r); });
}
static void gota(float x, float y, float s, float alfa) {
  Pintura p; p.arriba = {235, 250, 255}; p.abajo = {120, 200, 255}; p.y0 = y - s * 1.6f; p.y1 = y + s; p.halo = 8; p.haloCol = p.abajo; p.alfa = alfa;
  figura(x - s, y - s * 1.8f, x + s, y + s, p, [=](float px, float py) { return dGota(px, py, x, y, s); });
}
static void corazonito(float x, float y, float s, float alfa) {
  Pintura p; p.arriba = TONOS[ROSA].a; p.abajo = TONOS[ROSA].b; p.y0 = y - s; p.y1 = y + s; p.halo = 10; p.haloCol = p.abajo; p.alfa = alfa;
  figura(x - s * 1.3f, y - s * 1.3f, x + s * 1.3f, y + s * 1.1f, p, [=](float px, float py) { return dCorazon(px, py, x, y, s); });
}
// arco fino (ojos cerrados): media sonrisa ‿ (abajo) o ∩ (arriba), con puntas redondas
static void arco(const Rostro &r, float x, float y, float w, bool arriba, float alfa) {
  const int N = 7;
  float R = w * 0.55f, a0 = arriba ? PI * 1.2f : PI * 0.2f, a1 = arriba ? PI * 1.8f : PI * 0.8f;
  float cy = arriba ? y + R * 0.6f : y - R * 0.6f;
  float px_[N + 1], py_[N + 1];
  for (int i = 0; i <= N; i++) { float a = a0 + (a1 - a0) * i / N; px_[i] = x + cosf(a) * R; py_[i] = cy + sinf(a) * R; }
  float g = 6;
  figura(x - R, y - R * 0.5f, x + R, y + R * 0.5f, tonoP(r, y - 10, y + 10, 16, alfa), [&](float px, float py) {
    float d = 1e9f;
    for (int i = 0; i < N; i++) d = fminf(d, dSegmento(px, py, px_[i], py_[i], px_[i + 1], py_[i + 1], g));
    return d;
  });
}
static void polvo(float u, float alfa) {
  Col c{200, 225, 255};
  for (int i = 0; i < 7; i++) {
    float s = (i - 3) / 3.f;
    float x = CX + s * 150 + s * u * 90, y = H - 40 - u * 30 - fabsf(s) * 10;
    circulo(x, y, 6 + u * 14 + hash(i, 7) * 6, c, alfa * (1 - u) * 0.55f, 12);
  }
}
// grieta del vidrio (frenazo) desde un punto, crece con k
static void grieta(float x, float y, float k, float alfa) {
  if (alfa < 0.02f) return;
  static const float RAMAS[][4] = {{-150, -20, -0.25f, 1}, {-90, 70, 0.3f, 1}, {30, 95, -0.4f, 1}, {120, 40, 0.2f, 1},
                                   {95, -60, -0.3f, 1}, {-30, -80, 0.35f, 1}};
  for (auto &b : RAMAS) {
    float mx = x + b[0] * 0.5f * k + b[1] * b[2] * 0.4f * k, my = y + b[1] * 0.5f * k - b[0] * b[2] * 0.4f * k;
    float ex = x + b[0] * k, ey = y + b[1] * k;
    linea(x, y, mx, my, 2.2f, BLANCO, alfa, 6);
    linea(mx, my, ex, ey, 1.6f, BLANCO, alfa * 0.9f, 4);
  }
  circulo(x, y, 6, BLANCO, alfa, 10);
}

// cápsula con un extremo más grueso (para la campana de la trompeta y el mango del micrófono)
static float dCapsula2(float px, float py, float ax, float ay, float ra, float bx, float by, float rb) {
  px -= ax; py -= ay; bx -= ax; by -= ay;
  float h = bx * bx + by * by;
  float qx = fabsf((px * by - py * bx) / h), qy = (px * bx + py * by) / h;
  float b = ra - rb, cx = sqrtf(fmaxf(h - b * b, 1e-3f)), cy = b;
  float k = cx * qy - cy * qx, m = cx * qx + cy * qy, n = qx * qx + qy * qy;
  if (k < 0) return sqrtf(h * n) - ra;
  if (k > cx) return sqrtf(h * (n + 1 - 2 * qy)) - rb;
  return m - ra;
}
// marco girado: coordenadas locales de un instrumento
struct Marco {
  float x, y, c, s, e;                                   // e = escala
  float lx(float px, float py) const { return ((px - x) * c + (py - y) * s) / e; }
  float ly(float px, float py) const { return (-(px - x) * s + (py - y) * c) / e; }
  // caja en pantalla de un rectángulo local
  void caja(float a0, float b0, float a1, float b1, float &x0, float &y0, float &x1, float &y1) const {
    x0 = y0 = 1e9f; x1 = y1 = -1e9f;
    const float A[4] = {a0, a1, a1, a0}, B[4] = {b0, b0, b1, b1};
    for (int i = 0; i < 4; i++) {
      float wx = x + (A[i] * c - B[i] * s) * e, wy = y + (A[i] * s + B[i] * c) * e;
      x0 = fminf(x0, wx); x1 = fmaxf(x1, wx); y0 = fminf(y0, wy); y1 = fmaxf(y1, wy);
    }
  }
};
static Marco marco(float x, float y, float ang, float e = 1) { return {x, y, cosf(ang), sinf(ang), e}; }
template <typename F>
static void pieza(const Marco &m, float a0, float b0, float a1, float b1, const Pintura &p, F d) {
  float x0, y0, x1, y1; m.caja(a0, b0, a1, b1, x0, y0, x1, y1);
  figura(x0, y0, x1, y1, p, [&](float px, float py) { return d(m.lx(px, py), m.ly(px, py)) * m.e; });
}
static Pintura degr(int tono, float y0, float y1, float halo = 12, float alfa = 1) {
  Pintura p; p.arriba = TONOS[tono].a; p.abajo = TONOS[tono].b; p.y0 = y0; p.y1 = y1; p.halo = halo; p.haloCol = TONOS[tono].b; p.alfa = alfa;
  return p;
}
// rayas que salen del centro (entrar a toda velocidad)
static void tunel(float u, float alfa) {
  Col c{200, 235, 255};
  for (int i = 0; i < 12; i++) {
    float a = hash(i, 1) * 2 * PI, p = fmodf(u * (1.4f + hash(i, 2)) + hash(i, 3), 1.f);
    float r0 = 60 + p * p * 300, len = 15 + p * 80, ca = cosf(a), sa = sinf(a);
    linea(CX + ca * r0, EY + sa * r0 * 0.85f, CX + ca * (r0 + len), EY + sa * (r0 + len) * 0.85f, 1.5f + 2.5f * p, c, alfa * campana(p, 0, 1));
  }
}

// ---------------------------------------------------------------- accesorios de la música (los de Lumi, en grande)
static const Col BL{246, 244, 252}, NEG{8, 8, 14}, BL_ABAJO{200, 212, 238};
static const Col COLORES[5] = {{255, 110, 150}, {255, 196, 60}, {110, 190, 255}, {130, 230, 150}, {190, 140, 255}};
static Pintura plana(const Col &c, float y0, float y1, float osc = 0.75f) {
  Pintura p; p.arriba = c; p.abajo = {c.r * osc, c.g * osc, c.b * osc}; p.y0 = y0; p.y1 = y1; return p;
}

static void notaColor(float x, float y, float s, const Col &c, float alfa) {
  figura(x - s * 0.6f, y - s * 0.5f, x + s * 0.6f, y + s * 0.5f, solido(c, alfa, 8), [=](float px, float py) { return dElipse(px, py, x, y, s * 0.55f, s * 0.45f); });
  linea(x + s * 0.5f, y, x + s * 0.5f, y - s * 1.8f, s * 0.11f, c, alfa);
  linea(x + s * 0.5f, y - s * 1.8f, x + s * 1.15f, y - s * 1.35f, s * 0.11f, c, alfa);
}
// gorra roja de lado (urbano)
static void gorra(float x, float y, float ang, float e = 1) {
  Marco m = marco(x, y, ang, e);
  Col roja{230, 60, 70}, osc{160, 30, 40};
  pieza(m, 0, -16, 190, 10, plana(osc, y - 16, y + 10), [](float a, float b) { return dCaja(a, b, 120, -3, 72, 10, 10); });
  pieza(m, -158, -112, 158, 6, plana(roja, y - 112, y + 6, 0.82f), [](float a, float b) { return fmaxf(dElipse(a, b, 0, 0, 156, 108), b - 2); });
  pieza(m, -60, -100, 60, 4, solido(osc, 0.5f), [](float a, float b) { return fmaxf(fabsf(a) - 2.5f, fmaxf(dElipse(a, b, 0, 0, 156, 108), b - 2)); });
  pieza(m, -12, -124, 12, -100, plana(osc, y - 124, y - 100), [](float a, float b) { return dCirculo(a, b, 0, -110, 11); });
}

// ---------------------------------------------------------------- teléfonos
// cable en espiral entre dos puntos (curva con un punto de control)
static void cable(float x0, float y0, float cx, float cy, float x1, float y1, int vueltas, float ancho, const Col &c, float alfa) {
  const int N = vueltas * 6;
  float ax = x0, ay = y0;
  for (int i = 1; i <= N; i++) {
    float t = (float)i / N, it = 1 - t;
    float px = it * it * x0 + 2 * it * t * cx + t * t * x1, py = it * it * y0 + 2 * it * t * cy + t * t * y1;
    float dx = 2 * it * (cx - x0) + 2 * t * (x1 - cx), dy = 2 * it * (cy - y0) + 2 * t * (y1 - cy), n = sqrtf(dx * dx + dy * dy) + 1e-3f;
    float o = sinf(t * vueltas * 2 * PI) * ancho;
    px += -dy / n * o; py += dx / n * o;
    linea(ax, ay, px, py, 2.2f, c, alfa);
    ax = px; ay = py;
  }
}
// auricular: barra con la parte de oír y la de hablar (local: a lo largo del eje y)
static void auricular(float x, float y, float ang, float largo, float alfa) {
  Marco m = marco(x, y, ang);
  pieza(m, -26, -largo - 18, 14, largo + 18, degr(ROJO, y - largo, y + largo, 12, alfa), [=](float a, float b) {
    float barra = dCaja(a, b, 0, 0, 8, largo, 8);
    float oir = dCaja(a, b, -8, -largo + 4, 16, 13, 9), hablar = dCaja(a, b, -8, largo - 4, 16, 13, 9);
    return fminf(barra, fminf(oir, hablar));
  });
}
// teléfono viejo de mesa con disco y cable en espiral
// en el paquete blanco algunos íconos conservan su color (WhatsApp, flechas, pila, teléfono)
struct ConColor { bool b; ConColor() : b(dib::blanco) { dib::blanco = false; } ~ConColor() { dib::blanco = b; } };
// logo de WhatsApp: globo blanco con el teléfono adentro sobre círculo verde
static void logoWhatsapp(float x, float y, float R, float alfa) {
  ConColor cc;
  Col verde{37, 211, 102};
  figura(x - R, y - R, x + R, y + R, solido(verde, alfa, 16), [=](float px, float py) { return dCirculo(px, py, x, y, R); });
  float r = R * 0.68f, g = R * 0.075f;
  figura(x - R, y - R, x + R, y + R, solido(BLANCO, alfa), [=](float px, float py) {
    float globo = fminf(dCirculo(px, py, x, y, r), dSegmento(px, py, x - r * 0.62f, y + r * 0.62f, x - r * 0.98f, y + r * 1.0f, g * 1.6f));
    return fabsf(globo) - g;
  });
  float rc = r * 0.5f, cx = x + r * 0.04f, cy = y - r * 0.04f;
  figura(x - r, y - r, x + r, y + r, solido(BLANCO, alfa), [=](float px, float py) {
    float d = 1e9f, ax = 0, ay = 0;
    for (int i = 0; i <= 8; i++) {                                   // lomo curvo
      float a = (65 + 140 * i / 8.f) * PI / 180, bx = cx + cosf(a) * rc, by = cy + sinf(a) * rc;
      if (i) d = fminf(d, dSegmento(px, py, ax, ay, bx, by, g * 1.15f));
      ax = bx; ay = by;
    }
    float a0 = 65 * PI / 180, a1 = 205 * PI / 180;                   // las dos puntas, más gruesas
    d = fminf(d, dCirculo(px, py, cx + cosf(a0) * rc, cy + sinf(a0) * rc, g * 2.0f));
    d = fminf(d, dCirculo(px, py, cx + cosf(a1) * rc, cy + sinf(a1) * rc, g * 2.0f));
    return d;
  });
}

// ---------------------------------------------------------------- piezas de clima, Maps y demás
static float dTriangulo(float px, float py, float ax, float ay, float bx, float by, float cx, float cy) {
  // distancia a un triángulo (aprox. buena en el borde): el mayor de los tres semiplanos
  auto lado = [&](float x0, float y0, float x1, float y1) {
    float ex = x1 - x0, ey = y1 - y0, n = sqrtf(ex * ex + ey * ey) + 1e-6f;
    return ((px - x0) * ey - (py - y0) * ex) / n;
  };
  float d = fmaxf(fmaxf(lado(ax, ay, bx, by), lado(bx, by, cx, cy)), lado(cx, cy, ax, ay));
  // según el orden de las puntas el signo sale al revés: adentro tiene que dar negativo
  float qx = (ax + bx + cx) / 3, qy = (ay + by + cy) / 3;
  float ex = bx - ax, ey = by - ay;
  bool alReves = ((qx - ax) * ey - (qy - ay) * ex) > 0;
  if (alReves) d = fmaxf(fmaxf(-lado(ax, ay, bx, by), -lado(bx, by, cx, cy)), -lado(cx, cy, ax, ay));
  return d;
}
static void espiral(float x, float y, float giro, float alfa) {
  const int N = 26;
  float ax = x, ay = y;
  for (int i = 1; i <= N; i++) {
    float a = giro + i * 0.42f, r = 3 + i * 1.75f;
    float bx = x + cosf(a) * r, by = y + sinf(a) * r * 1.1f;
    linea(ax, ay, bx, by, 4, TONOS[MORADO].a, alfa, i == N ? 10 : 0);
    ax = bx; ay = by;
  }
}
static void copo(float x, float y, float r, float giro, float alfa) {
  for (int i = 0; i < 3; i++) {
    float a = giro + i * PI / 3, c = cosf(a) * r, s = sinf(a) * r;
    linea(x - c, y - s, x + c, y + s, 2.2f, TONOS[HIELO].a, alfa, i == 0 ? 8 : 0);
  }
}
static void pilita(float x, float y, float nivel, bool parpadea, float alfa) {
  ConColor cc;
  Col borde{235, 240, 250}, rojo = TONOS[ROJO].b;
  figura(x - 52, y - 28, x + 60, y + 28, solido(borde, alfa), [=](float px, float py) {
    return fminf(fabsf(dCaja(px, py, x, y, 48, 24, 8)) - 3.5f, dCaja(px, py, x + 54, y, 5, 10, 3));
  });
  if (parpadea) {
    float w = 84 * nivel;
    figura(x - 42, y - 18, x - 42 + w, y + 18, solido(rojo, alfa, 12), [=](float px, float py) { return dCaja(px, py, x - 42 + w / 2, y, w / 2, 17, 4); });
  }
}
static void confeti(float u, float alfa) {
  for (int i = 0; i < 22; i++) {
    float p = fmodf(u * (0.35f + 0.2f * hash(i, 4)) + hash(i, 5), 1.f);
    float x = hash(i, 6) * W + sinf(u * 3 + i) * 12, y = -10 + p * (H + 20);
    Marco m = marco(x, y, u * 4 * (hash(i, 7) - 0.5f) + i);
    pieza(m, -6, -4, 6, 4, solido(COLORES[i % 5], alfa * campana(p, 0, 1) * 1.4f), [](float a, float b) { return dCaja(a, b, 0, 0, 6, 3.5f, 1); });
  }
}


// ---------------------------------------------------------------- accesorios nuevos de Cubi (mismo estilo: contorno negro y degradado)
static const Col dorado{255, 196, 60};

// ---------------------------------------------------------------- caras de fondo
static float pulsosVistos = 0, ultimoPulso = 1;

// ---------------------------------------------------------------- Robot minimalista: los bailes
// Los ojos no se transforman: son los de siempre, cabecean con el golpe, se mecen y miran el instrumento.
// El baile lo hace el instrumento (extrasMini) y la boquita, que solo abre cuando el artista canta.
// Micrófono con drama: lo pasa de una mano a la otra y, cuando el artista calla después de cantar (o en pleno coro),
// se lo acerca a quien lo mira para que cante también
static float ofreceAhora = 0;      // 0..1: el micrófono va hacia la pantalla ("¡canta tú!")
static float dosAhora = 0;         // 0..1: canta con sentimiento, el micrófono al centro agarrado con las dos manos
static float golpeAhora = 0;       // 0..1: la mano libre golpea el aire con cada golpe (una mesa que no se ve)
static bool lanzaMic = false;      // este pase es por el aire: lo lanza girando y lo atrapa con la otra mano
static float ladoMic = 1;          // -1 = en la mano izquierda .. 1 = en la derecha (en el pase va por el medio)
static float nivR = 0, nivL = 0;   // qué tan fuerte suena: ahora mismo y en los últimos segundos
static float tensionAhora = 0;     // 0..1: la música se calló de golpe (el silencio antes del coro): se queda quieto y tenso
static float explotaAhora = 0;     // 1 -> 0: la música entró de golpe (el coro, o arranca la canción): explota
static float veniaDesde = -100;    // cuándo empezó la venia del final de la canción
static float sonoSeg = 0, ultCantaT = -100;   // cuánto lleva sonando; último cuadro en que estaba cantando
static int ladoCanta = 1;          // hacia qué lado canta esta frase: cambia con cada respiro, como respondiéndose
static float guinoHasta = -1;      // acaba de pasarse el micrófono: te guiña el ojo
static int manoPaso = 0;           // qué hace la mano libre: 0 puño (sin voz), 1 saluda (canta), 2 apunta al cielo (nota alta)
// Nada va por turnos ni en bucle: cada gesto sale de lo que suena en ese momento y solo uno a la vez.
//   canta suave o nota larga  -> el micrófono al centro con las dos manos
//   suena duro                -> la mano libre golpea el aire
//   la música entra de golpe  -> lanza el micrófono a la otra mano
//   respiro entre dos frases  -> lo pasa a la otra mano por debajo
//   el artista calla          -> te lo ofrece: "¡canta tú!"
// ponytail: los umbrales son a ojo sobre aNivel (0..1); se afinan mirando canciones reales en el robot
static void dramaMicro(const Entradas &e, float dt) {
  static float cantoSeg = 0, calladoSeg = 0, ofreceHasta = -1, ofreceDesde = -1, proxOfrece = 4, proxCoro = 14;
  static float proxLanza = 0, proxPase = 0, proxExplota = 0, suaveSeg = 0, mudoSeg = 0, tensoSeg = 0, manoDesde = 0;
  static int ladoMeta = 1;
  static bool porSilencio = false, respiro = false, duro = false, pasando = false;
  const bool esMic = e.ritmo == R_KPOP;
  ultCantaT = tAhora;
  nivR += (e.nivel - nivR) * lim(dt * 1.5f, 0, 1); nivL += (e.nivel - nivL) * lim(dt * 0.25f, 0, 1);   // despacio: un golpe fuerte suelto no cambia nada
  bool acabaDeRespirar = false;
  static float ladoDesde = 0;
  if (e.canta) { if (respiro) { if (tAhora - ladoDesde > 6) { ladoCanta = -ladoCanta; ladoDesde = tAhora; } respiro = false; } cantoSeg += dt; calladoSeg = 0; }
  else {
    calladoSeg += dt;
    if (calladoSeg > 0.3f && cantoSeg > 1 && !respiro) { respiro = true; acabaDeRespirar = true; }
    if (calladoSeg > 5) cantoSeg = 0;
  }
  // final de la canción: sonó un buen rato y se calló del todo -> venia
  if (e.nivel > 0.06f) { sonoSeg += dt; mudoSeg = 0; }
  else { mudoSeg += dt; if (mudoSeg > 1.2f) { if (sonoSeg > 15) veniaDesde = tAhora; sonoSeg = 0; } }
  // la caída: se calla de golpe (tenso) y luego entra con todo (explota)
  tensoSeg = nivR < 0.45f * nivL && nivL > 0.25f ? tensoSeg + dt : 0;
  tensionAhora += ((tensoSeg > 0.4f && mudoSeg < 1.2f ? 1.f : 0.f) - tensionAhora) * lim(dt * 8, 0, 1);
  bool entra = false;
  if (nivR - nivL > 0.3f && e.nivel > 0.55f && tAhora > proxExplota) { entra = true; explotaAhora = 1; proxExplota = tAhora + 20; }
  explotaAhora = fmaxf(0, explotaAhora - dt / 1.2f);

  if (esMic && tAhora > proxOfrece && tAhora > ofreceHasta) {
    const bool silencio = !e.canta && calladoSeg > 0.45f && cantoSeg > 2 && mudoSeg < 0.2f;          // calló: "¡tu turno!"
    const bool coro = e.canta && cantoSeg > proxCoro && gritoZona > 0.5f;          // lleva rato cantando duro: "¡todos!"
    if (silencio || coro) {
      porSilencio = silencio;
      ofreceDesde = tAhora; ofreceHasta = tAhora + (silencio ? 3.f : 240.f / fmaxf(e.bpm, 60));   // el coro: 4 golpes
      proxOfrece = ofreceHasta + 25 + 20 * azar(); proxCoro = 12 + 12 * azar(); cantoSeg = 0;
    }
  }
  // si el artista vuelve a cantar, Cubi recoge el micrófono para seguir
  if (porSilencio && tAhora < ofreceHasta && e.canta && tAhora - ofreceDesde > 0.8f) ofreceHasta = tAhora;
  if (mudoSeg > 0.6f && tAhora < ofreceHasta) ofreceHasta = tAhora;   // se acabo la musica: no es un turno, es el final
  const bool ofrece = esMic && tAhora < ofreceHasta;
  ofreceAhora += ((ofrece ? 1.f : 0.f) - ofreceAhora) * lim(dt * 8, 0, 1);

  const bool quieto = esMic && !ofrece && fabsf(ladoMic) > 0.95f;   // el micrófono está en una mano, sin nada más pasando
  // canta suave un rato -> dos manos; se suelta cuando sube o se calla
  // cada postura aguanta al menos 6 s antes de cambiar: no salta de una a otra con cada subida del volumen
  static float posturaDesde = -10, duroSeg = 0; static bool dosOn = false;
  const bool puedeCambiar = tAhora - posturaDesde > 6;
  if (e.canta && nivR < 0.4f) suaveSeg += dt; else if (nivR > 0.55f || calladoSeg > 1.5f) suaveSeg = 0;
  if (puedeCambiar && (suaveSeg > 2.5f) != dosOn) { dosOn = !dosOn; posturaDesde = tAhora; }
  const bool dos = quieto && dosOn;
  // suena duro -> golpea el aire (con margen para que no entre y salga a cada rato)
  duroSeg = nivR > 0.75f || gritoZona > 0.5f ? duroSeg + dt : nivR < 0.6f && gritoZona < 0.3f ? 0 : duroSeg;
  if (puedeCambiar && (duroSeg > 1.5f) != duro) { duro = !duro; posturaDesde = tAhora; }
  const bool golpe = quieto && !dos && duro && (e.canta || bailaAhora > 0.05f);
  dosAhora += ((dos ? 1.f : 0.f) - dosAhora) * lim(dt * 6, 0, 1);
  golpeAhora += ((golpe ? 1.f : 0.f) - golpeAhora) * lim(dt * 6, 0, 1);
  // entra la música de golpe -> lo lanza; respiro entre frases -> lo pasa por debajo
  if (quieto && dosAhora < 0.3f) {
    if (entra && tAhora > proxLanza) { ladoMeta = -ladoMeta; lanzaMic = true; proxLanza = tAhora + 20; proxPase = tAhora + 10; }
    else if (acabaDeRespirar && tAhora > proxPase && tAhora < proxOfrece) { ladoMeta = -ladoMeta; lanzaMic = false; proxPase = tAhora + 14 + 10 * azar(); pasando = true; }
  }
  // la mano libre: según la voz, y aguanta al menos segundo y medio para no temblar entre una y otra
  // sin voz: puño con el golpe; canta: saluda; canta con ganas: te señala; nota alta: apunta al cielo
  const int quiere = !e.canta ? 0 : (e.boca > 0.6f || e.voz > 0.8f) ? 2 : nivR > 0.55f ? 4 : 1;
  if (quiere != manoPaso && tAhora - manoDesde > 5) { manoPaso = quiere; manoDesde = tAhora; }
  const float paso = dt * fmaxf(e.bpm, 60) / 60 * (lanzaMic ? 1.2f : 2);   // lanzado tarda más: se ve volar
  ladoMic = ladoMic < ladoMeta ? fminf(ladoMic + paso, ladoMeta) : fmaxf(ladoMic - paso, ladoMeta);
  if (pasando && ladoMic == ladoMeta) { pasando = false; guinoHasta = tAhora + 0.8f; }   // lo recibió: guiño
}
// la venia del final: cierra los ojitos y baja la cabeza
static bool venia(Rostro &r) {
  const float u = tAhora - veniaDesde;
  if (u < 0 || u > 2.6f) return false;
  const float k = tramo(u, 0, 0.5f) * (1 - tramo(u, 1.9f, 2.6f));
  feliz(r, 1); mover(r, 0, 48 * k); escalar(r, 1, 1 - 0.12f * k); r.boca = 0;
  return true;
}

static void bailaMini(Rostro &r, const Entradas &e, float g, float vaiven, float bl) {
  bool lento = e.ritmo == R_CORAZON || e.ritmo == R_JAZZ || e.ritmo == R_PIANO || e.ritmo == R_DISFRUTA || e.ritmo == R_GUITARRA || e.ritmo == R_VELA;
  // lenta y suave: movimientos largos y chiquitos; movida y fuerte: cortos y marcados. En el silencio tenso, quieto.
  const float E = lim(0.5f * lim((e.bpm - 70) / 70, 0, 1) + 0.5f * lim(nivL * 1.4f, 0, 1), 0, 1);
  g *= (0.55f + 0.75f * E) * (1 - tensionAhora); vaiven *= 1 - tensionAhora;
  mover(r, (lento ? 12 : 6) * vaiven, -18 + (lento ? 5 : 10) * g);
  if (E < 0.35f) {                                    // balada: se mece largo y entrecierra los ojos
    const float S = (1 - E / 0.35f) * (1 - tensionAhora);
    mover(r, 12 * S * sinf((pulsosVistos + e.pulso) * PI * 0.25f), 0); bl = fmaxf(bl, 0.3f * S);
  }
  mirar(r, 0, 0.2f); tapas(r, bl);
  // canción bailable (tempo de baile y suena con fuerza, sostenido unos segundos): además de tocar, baila: se mece más,
  // rebota con el golpe y se ladea de un lado al otro
  static float tB = 0, nivelB = 0;
  const float dtB = lim(tAhora - tB, 0, 0.1f);
  tB = tAhora;
  nivelB += (e.nivel - nivelB) * lim(dtB * 0.6f, 0, 1);
  const bool bailable = e.bpm >= 92 && e.bpm <= 145 && nivelB > 0.32f;
  bailaAhora += ((bailable ? 1.f : 0.f) - bailaAhora) * lim(dtB * 0.8f, 0, 1);
  const bool rock = (e.ritmo == R_ROCK || e.ritmo == R_GROOVE) && nivelB > 0.5f;
  rockeroAhora += ((rock ? 1.f : 0.f) - rockeroAhora) * lim(dtB * 1.5f, 0, 1);   // los ojos se ajustan al final
  if (bailaAhora > 0.01f) {
    const float B = bailaAhora;
    mover(r, 16 * B * vaiven, -10 * B * g);
    r.L.incl += 0.12f * B * vaiven; r.R.incl += 0.12f * B * vaiven;
  }
  dramaMicro(e, dtB);
  {   // cada 8 golpes un pasito distinto, al azar: que no baile siempre igual
    const float f2 = pulsosVistos + e.pulso, A = lento ? 0.5f : 1;
    const int frase = (int)(f2 / 16);                    // cada 16 golpes, no cada 8: que no cambie a cada rato
    int pasito = (int)(hash(frase, 21) * 6);
    if (pasito == (int)(hash(frase - 1, 21) * 6)) pasito = (pasito + 1 + (int)(hash(frase, 22) * 4)) % 6;   // nunca el mismo dos veces seguidas
    switch (pasito) {
      case 1: mover(r, 0, -8 * A * fabsf(sinf(f2 * PI * 2))); break;                          // doble rebote
      case 2: mover(r, 10 * A * cosf(f2 * PI * 0.5f), 7 * A * sinf(f2 * PI * 0.5f)); break;   // la cabeza en círculo
      case 3: {                                                                               // se inclina a un lado y aguanta
        const float d = ((int)f2 / 2) % 2 ? 1.f : -1.f;
        mover(r, 14 * A * d, 0); r.L.incl += 0.1f * d; r.R.incl += 0.1f * d;
        break;
      }
      case 4: escalar(r, 1 + 0.07f * A * g, 1 + 0.07f * A * g); break;                        // late con el golpe
      case 5: mover(r, 0, 6 * A * sinf(f2 * PI * 0.25f)); r.L.y -= 5 * A * vaiven; r.R.y += 5 * A * vaiven; break;   // sube un ojo y baja el otro
      default: break;
    }
  }
  if (e.canta) {                                      // cantando: cada 4 golpes una expresión distinta, en orden al azar
    const float f = pulsosVistos + e.pulso;
    const int barra = (int)(f / 8);                      // cada 8 golpes
    int ex = (int)(hash(barra, 9) * 10);
    if (ex == (int)(hash(barra - 1, 9) * 10)) ex = (ex + 1 + (int)(hash(barra, 10) * 8)) % 10;   // nunca la misma dos veces seguidas
    if (ex == 7 && hash(barra, 11) > 0.35f) ex = 0;    // los corazones salen solo de vez en cuando
    r.L.incl = r.R.incl = 0.06f * sinf(f * PI * 0.25f);   // ladea la cabeza suave con la música
    switch (ex) {
      case 0: mover(r, 0, 6 * g); break;                                                    // ojos abiertos, rebotando
      case 1: feliz(r, 1); mover(r, 0, 8 * g); break;                                       // felices ^^
      case 2: mirar(r, 0, -0.6f); escalar(r, 1.06f, 1.06f); break;                          // le canta al cielo
      case 3: mirar(r, 0.55f * sinf(f * PI * 0.5f), 0); break;                              // al público, de lado a lado
      case 4: {                                                                              // guiño (un ojo y luego el otro)
        Ojo &o = barra % 2 ? r.L : r.R;
        o.feliz = 1; o.tapa = 0; mirar(r, barra % 2 ? 0.3f : -0.3f, 0);
        break;
      }
      case 5: escalar(r, 1.18f, 1.0f + 0.12f * e.boca); break;                              // asombro: ojos grandes con la voz
      case 6: r.L.incl = 0.32f; r.R.incl = -0.05f; r.L.y -= 6; mirar(r, 0.25f, 0); break;   // pícaro: una ceja arriba
      case 8: tapas(r, 0.8f); mirar(r, 0, -0.3f); mover(r, 8 * sinf(f * PI * 0.25f), -6); break;   // sentido: ojos casi cerrados, se mece
      case 9: escalar(r, 0.94f, 0.9f); r.L.incl = r.R.incl = 0.16f; mover(r, 0, 10 * g); break;    // intenso: mirada fija al público, cabecea
      default: r.corazon = 1; escalar(r, 1.05f, 1.05f); mover(r, 0, 6 * g); break;         // ojitos de corazón
    }
    static float ls = 1;                                // cada frase la canta hacia un lado, como respondiéndose
    ls += (ladoCanta - ls) * lim(dtB * 5, 0, 1);
    mover(r, 9 * ls, 0); r.L.incl += 0.05f * ls; r.R.incl += 0.05f * ls;
  } else if (e.ritmo == R_KPOP) { feliz(r, 1); mover(r, 0, 8 * g); }   // micrófono sin voz: ojos cerrados, "tun, tun"
  // "¡aaah!": en la parte fuerte de la canción (voz muy fuerte o la barra rosada al tope) la boca crece, pero sigue
  // abriendo y cerrando con cada sílaba por niveles: no se queda pegada abierta. Los ojos se vuelven chiquitos.
  static float tAntes = 0;
  const float dt = lim(tAhora - tAntes, 0, 0.1f);
  tAntes = tAhora;
  // tiene que sostenerse un cuarto de segundo: si no, cada sílaba fuerte la prendía un poquito y los ojos quedaban
  // medio cerrados todo el tiempo
  static float alto = 0;
  const bool fuerte = e.canta && (e.boca > 0.78f || e.voz > 0.85f);
  alto = fuerte ? alto + dt : 0;
  const bool zona = alto > 0.25f;
  gritoZona += ((zona ? 1.f : 0.f) - gritoZona) * lim(dt * (zona ? 4 : 1.2f), 0, 1);
  // cuánto abre ahora: dos golpecitos de boca por cada golpe de la música, mezclados con lo que oye; nunca baja de un
  // tercio del tamaño grande, así abre y cierra mucho sin salirse del rango grande
  const float silaba = 0.5f - 0.5f * cosf(e.pulso * 4 * PI);
  const float meta = e.canta ? gritoZona * (0.3f + 0.7f * (0.55f * silaba + 0.45f * tramo(e.boca, 0.4f, 1.0f))) : 0;
  gritoAhora += (meta - gritoAhora) * lim(dt * 14, 0, 1);
  if (gritoZona > 0.01f) {                             // en la parte fuerte cierra los ojitos (felices) para que quepa la boca;
    const float Z = gritoZona;                          // los ojos no se mueven de su sitio y el instrumento sigue tocando
    if (Z > 0.4f) feliz(r, 1);                          // cerrados del todo o normales: nada de medio cerrados
    escalar(r, 1 - 0.15f * Z, 1);
    r.by -= 22 * Z;                                     // la boca sube un poquito, hacia los ojos
  }
  if (e.ritmo == R_KPOP && fabsf(ladoMic) < 0.95f) mirar(r, 0.5f * ladoMic, lanzaMic ? 0.4f - 1.1f * (1 - ladoMic * ladoMic) : 0.4f);
  if (dosAhora > 0.5f) feliz(r, 1);
  r.by -= 26 * dosAhora;                              // y la boca sube para que quepa el micrófono debajo   // con las dos manos: ojos cerrados, con sentimiento
  if (golpeAhora > 0.01f) {                           // golpea el aire: cejas bravas y se sacude con cada golpe
    const float G = golpeAhora;
    r.L.incl = r.R.incl = fmaxf(r.L.incl, 0.2f * G); escalar(r, 1, 1 - 0.14f * G * g); mover(r, 0, 12 * G * g);
  }   // en el pase sigue el micrófono con los ojos
  if (ofreceAhora > 0.01f) {                          // "¡canta tú!": se calla, pone el oído y ladea la cabeza hacia la mano que escucha
    const float O = ofreceAhora, sg = ladoMic >= 0 ? 1 : -1;
    r.L.feliz *= 1 - O; r.R.feliz *= 1 - O; r.corazon *= 1 - O;
    r.L.tapa *= 1 - O; r.R.tapa *= 1 - O;
    mirar(r, -0.28f * sg * O, 0.05f * O);             // mira de reojo hacia el lado del oído
    mover(r, 8 * sg * O, -6 * O + 5 * O * g);         // le hace campo a la mano del oído y asiente con el golpe
    r.L.incl = r.R.incl = r.L.incl * (1 - O) - 0.13f * sg * O;
  }
  if (tAhora < guinoHasta) {                          // guiña el ojo del lado donde quedó el micrófono, el otro bien abierto
    Ojo &o = ladoMic > 0 ? r.R : r.L, &q = ladoMic > 0 ? r.L : r.R;
    o.feliz = 1; o.tapa = 0; q.feliz = 0; q.tapa = 0; r.corazon = 0;
  }
  if (tensionAhora > 0.01f) {                         // silencio antes del coro: ojos bien abiertos, quieto, tiembla apenas
    const float T = tensionAhora;
    r.L.feliz *= 1 - T; r.R.feliz *= 1 - T; r.corazon *= 1 - T; r.L.tapa *= 1 - T; r.R.tapa *= 1 - T;
    escalar(r, 1 - 0.05f * T, 1 + 0.12f * T); mover(r, 1.5f * T * sinf(tAhora * 40), 0);
  }
  if (explotaAhora > 0.01f) {                         // entró con todo: crece, salta y se alegra
    const float X = explotaAhora;
    escalar(r, 1 + 0.22f * X, 1 + 0.22f * X); mover(r, 0, -18 * X);
    if (X > 0.4f) feliz(r, 1);
  }
  venia(r);
  if (rockeroAhora > 0.01f) {                         // roquero: ojos apretados, cejas bravas, cabecea duro con el golpe
    const float K = rockeroAhora;
    tapas(r, 0.7f * K); r.L.incl = r.R.incl = 0.25f * K;
    r.L.feliz *= 1 - K; r.R.feliz *= 1 - K;
    mover(r, 0, 14 * K * g);
  }
}

static void anillo(float x, float y, float R, float grosor, float alfa) {
  figura(x - R - grosor, y - R - grosor, x + R + grosor, y + R + grosor, solido(BL, alfa),
         [=](float px, float py) { return fabsf(dCirculo(px, py, x, y, R)) - grosor; });
}

// ---------------------------------------------------------------- instrumentos de línea (Robot minimalista)
// Como en el robot del tablero: el instrumento es un dibujo de línea blanca y lo toca una manito blanca rellena.
static inline float smin(float a, float b, float k) { return -smax(-a, -b, k); }
static void aPantalla(const Marco &m, float a, float b, float &x, float &y) { x = m.x + (a * m.c - b * m.s) * m.e; y = m.y + (a * m.s + b * m.c) * m.e; }
static void arcoTrazo(float x, float y, float R, float a0, float a1, float g, float alfa) {
  figura(x - R - g, y - R - g, x + R + g, y + R + g, solido(BL, alfa), [=](float px, float py) { return dArco(px, py, x, y, R, a0, a1) - g; });
}
// manito: palma con pulgar; "apunta" saca el índice, si no es un puño que agarra. ang = hacia dónde van los dedos
// manitos: cada cara tiene la suya. 0 guante gordito de tres dedos (Caricatura), 1 de pura línea (Trazo), 2 manopla lisa (Bolita),
// 3 la de siempre (minimalista)
void estiloManos(int e) { manoEstilo = e; }
static void manoBlanca(float x, float y, float ang, float e, bool apunta, float alfa = 1) {
  Marco m = marco(x, y, ang, e);
  const int est = manoEstilo;
  auto d = [=](float a, float b) {
    if (est == 3) {                                                                        // la de siempre
      float p = dElipse(a, b, 0, 0, 19, 15);
      p = smin(p, apunta ? dSegmento(a, b, 8, -5, 34, -5, 6.5f) : dSegmento(a, b, 6, -3, 16, -3, 11), 5);
      return smin(p, dSegmento(a, b, 2, 9, 14, 13, 5.5f), 4);
    }
    // puño de caricatura: palma cuadradita y redonda, los dedos doblados asoman al frente como tres lomitos y el pulgar
    // abraza por un lado. Si apunta, el índice sale derecho. En Mochi (2) todo se funde en una manopla lisa.
    const float k = est == 2 ? 6 : est == 1 ? 3.5f : 1.6f;
    float p, dedos, pulgar;
    if (apunta) {
      p = dCaja(a, b, 0, 1, 13, 12, 10);
      dedos = fminf(dSegmento(a, b, 6, -8, 35, -8, 5.6f), fminf(dCirculo(a, b, 14, 0.5f, 5.6f), dCirculo(a, b, 12.5f, 8, 5.2f)));
      pulgar = dSegmento(a, b, -7, 10, 4, 16, 4.8f);
    } else {
      p = dCaja(a, b, 2, -1, 15, 12.5f, 10.5f);
      dedos = fminf(dCirculo(a, b, 15.5f, -8.6f, 5.6f), fminf(dCirculo(a, b, 17.8f, -1, 6), dCirculo(a, b, 15.5f, 6.6f, 5.6f)));
      pulgar = dSegmento(a, b, -6, 9.5f, 6, 15.5f, 5);
    }
    return smin(smin(p, dedos, k), pulgar, est == 2 ? 3 : 2);
  };
  auto rayas = [=](float a, float b) {                                                     // dos rayitas cortas entre los dedos
    if (apunta) return fminf(dSegmento(a, b, 12, -3.3f, 19, -3.3f, 1.2f), dSegmento(a, b, 11, 4.6f, 17.5f, 4.6f, 1.2f));
    return fminf(dSegmento(a, b, 14, -4.9f, 20.5f, -4.9f, 1.2f), dSegmento(a, b, 14, 2.9f, 20.5f, 2.9f, 1.2f));
  };
  pieza(m, -27, -25, 45, 27, solido(NEG, alfa), [=](float a, float b) { return d(a, b) - 4; });   // borde negro: la separa de lo que toca
  if (est == 1) {                                                                          // Lino: solo el contorno y las rayitas, como los instrumentos
    pieza(m, -24, -22, 42, 25, solido(NEG, alfa), d);
    pieza(m, -24, -22, 42, 25, solido(BL, alfa), [=](float a, float b) { return fabsf(d(a, b)) - 1.8f; });   // el trazo va sobre el borde; sin rayitas, que en línea se veían como huellas
    return;
  }
  pieza(m, -24, -22, 42, 25, solido(BL, alfa), d);
  if (est == 0) pieza(m, 8, -8, 24, 8, solido(NEG, 0.9f * alfa), rayas);
}
// la mano del piedra, papel o tijera: grande, de frente y con los dedos hacia arriba. forma: 0 piedra, 1 papel, 2 tijera
static void manoJuego(float x, float y, float e, int forma, float alfa, float ang = 0) {
  Marco m = marco(x, y, ang, e);
  auto d = [=](float a, float b) {
    float p = dCaja(a, b, 0, 8, 22, 20, 12);                                                     // la palma
    if (forma == 0) {                                                                            // piedra: los nudillos arriba y el pulgar cruzado
      for (int i = 0; i < 4; i++) p = smin(p, dCirculo(a, b, -15 + i * 10, -10, 7.5f), 3);
      return smin(p, dSegmento(a, b, -20, 12, -3, 5, 6.5f), 2);
    }
    if (forma == 1) {                                                                            // papel: los cuatro dedos estirados y el pulgar abierto
      static const float ALTO[4] = {-40, -48, -46, -37};
      for (int i = 0; i < 4; i++) p = smin(p, dSegmento(a, b, -15 + i * 10, -6, (-15 + i * 10) * 1.3f, ALTO[i], 6.2f), 3);
      return smin(p, dSegmento(a, b, -20, 14, -37, -4, 6.5f), 3);
    }
    p = smin(p, dSegmento(a, b, -11, -6, -22, -48, 6.5f), 3);                                    // tijera: índice y medio en V
    p = smin(p, dSegmento(a, b, 0, -6, 9, -48, 6.5f), 3);
    p = smin(p, fminf(dCirculo(a, b, 11, -9, 7), dCirculo(a, b, 19, -3, 6.2f)), 3);              // los otros dos, doblados
    return smin(p, dSegmento(a, b, -20, 12, -2, 7, 6.2f), 2);                                    // el pulgar los sujeta
  };
  pieza(m, -46, -60, 34, 36, solido(NEG, alfa), [=](float a, float b) { return d(a, b) - 4; });
  pieza(m, -42, -56, 30, 32, solido(BL, alfa), d);
  if (forma != 1) pieza(m, -22, 0, 0, 16, solido(NEG, 0.85f * alfa), [=](float a, float b) { return dSegmento(a, b, -17, 5.5f, -4, 0.5f, 1.1f); });   // la rayita del pulgar
}

// disco de vinilo de línea: borde, surcos que giran y el centro
static void disco(float x, float y, float R, float giro, float alfa) {
  circulo(x, y, R, Col{40, 40, 50}, alfa);                                      // el vinilo, oscuro
  anillo(x, y, R, 1.6f, alfa);
  for (int k = 0; k < 2; k++) {                                                 // los brillos que giran
    float a0 = giro + k * PI;
    arcoTrazo(x, y, R * 0.74f, a0, a0 + 1.3f, 1.8f, 0.7f * alfa);
    arcoTrazo(x, y, R * 0.56f, a0 + 0.8f, a0 + 1.9f, 1.8f, 0.7f * alfa);
  }
  circulo(x, y, R * 0.36f, Col{255, 110, 150}, alfa);                           // la etiqueta
  linea(x, y, x + cosf(giro) * R * 0.3f, y + sinf(giro) * R * 0.3f, 1.6f, Col{255, 220, 230}, alfa);
  circulo(x, y, R * 0.07f, Col{20, 20, 26}, alfa);
}
// guitarra rellena y de color, acostada sobre el eje del marco (cuerpo en 0, mástil hacia +a). vibra = cuánto vibran las cuerdas
static void guitarraLinea(float x, float y, float ang, float e, bool electrica, float vibra, float alfa) {
  Marco m = marco(x, y, ang, e);
  const float n0 = electrica ? 32 : 60;
  const Col cu = electrica ? Col{228, 54, 66} : Col{226, 152, 78}, bo = electrica ? Col{150, 22, 36} : Col{152, 92, 42}, mad{124, 84, 56};
  auto cuerpo = [=](float a, float b) {
    if (!electrica) return smin(dCirculo(a, b, 0, 0, 34), dCirculo(a, b, 40, 0, 25), 12);   // acústica: dos curvas y la cintura
    float d = smin(dElipse(a, b, 0, 0, 36, 26), dSegmento(a, b, 8, -12, 34, -30, 9), 8);    // eléctrica: dos cuernos
    return smin(d, dSegmento(a, b, 8, 12, 28, 26, 8), 8);
  };
  pieza(m, n0 - 4, -9, 134, 9, solido(mad, alfa), [=](float a, float b) { return dCaja(a, b, (n0 + 130) / 2, 0, (130 - n0) / 2, 6.5f, 3); });   // el mástil
  for (int i = 0; i < 5; i++) { const float fa = n0 + 12 + i * 13; pieza(m, fa - 2, -7, fa + 2, 7, solido(Col{214, 196, 160}, 0.8f * alfa), [=](float a, float b) { return dCaja(a, b, fa, 0, 0.7f, 5.5f, 0.5f); }); }   // trastes
  pieza(m, 126, -14, 162, 14, solido(electrica ? cu : mad, alfa), [=](float a, float b) { return dCaja(a, b, 144, electrica ? -3 : 0, 13, 9, 4); });   // la cabeza
  pieza(m, -42, -46, 72, 42, solido(cu, alfa), cuerpo);
  pieza(m, -42, -46, 72, 42, solido(bo, alfa), [=](float a, float b) { return fabsf(cuerpo(a, b) + 1.8f) - 1.8f; });                         // el borde del cuerpo
  if (electrica) for (int i = 0; i < 2; i++) {                       // pastillas
    float pa = -12 + i * 14;
    pieza(m, pa - 6, -15, pa + 6, 15, solido(Col{232, 232, 240}, alfa), [=](float a, float b) { return dCaja(a, b, pa, 0, 3.4f, 11, 2); });
  } else {
    pieza(m, 8, -14, 36, 14, solido(Col{54, 32, 20}, alfa), [=](float a, float b) { return dCirculo(a, b, 22, 0, 10); });                    // la boca
    pieza(m, 8, -14, 36, 14, solido(bo, alfa), [=](float a, float b) { return fabsf(dCirculo(a, b, 22, 0, 11.5f)) - 1.2f; });
  }
  pieza(m, -20, -9, -12, 9, solido(BL, alfa), [=](float a, float b) { return dSegmento(a, b, -16, -7, -16, 7, 2.2f); });               // puente
  for (int i = -1; i <= 1; i += 2) {                                  // cuerdas
    float yy = i * 2.4f;
    pieza(m, -18, -12, 134, 12, solido(BL, 0.8f * alfa), [=](float a, float b) {
      float v = vibra * sinf(a * 0.35f + tAhora * 70) * fmaxf(0.f, 1 - fabsf(a - 30) / 90);
      return fmaxf(fabsf(b - yy - v) - 0.9f, fabsf(a - 58) - 74);
    });
  }
}
// saxofón dorado, de pie: boquilla arriba, campana abajo que sube a la derecha
static void saxoLinea(float x, float y, float ang, float e, float alfa) {
  Marco m = marco(x, y, ang, e);
  auto tubo = [=](float a, float b) { return smin(dCapsula2(a, b, -6, -70, 5, 0, 22, 13), dCapsula2(a, b, 6, 36, 13, 34, 4, 19), 10); };
  pieza(m, -24, -80, 58, 54, solido(Col{246, 196, 74}, alfa), tubo);
  pieza(m, -24, -80, 58, 54, solido(Col{182, 124, 34}, alfa), [=](float a, float b) { return fabsf(tubo(a, b) + 1.6f) - 1.6f; });
  pieza(m, -24, -92, -2, -64, solido(Col{214, 214, 224}, alfa), [=](float a, float b) { return dSegmento(a, b, -6, -70, -18, -86, 3); });    // boquilla
  for (int i = 0; i < 3; i++) { float kx, ky; aPantalla(m, -3 + i * 1.5f, -40 + i * 18, kx, ky); circulo(kx, ky, 3.5f * e, Col{255, 244, 200}, alfa); }   // llaves
}
// micrófono de mano relleno: cabeza gris con rejilla en (x, y) y el mango hacia ang
static void microLinea(float x, float y, float ang, float e, float alfa, float largo = 1) {   // largo < 1: mango corto, apunta a quien mira
  Marco m = marco(x, y, ang, e);
  const Col gris{184, 186, 196}, osc{104, 106, 120}, mango{138, 140, 154};
  pieza(m, 12, -10, 66, 10, solido(mango, alfa), [=](float a, float b) { return dCapsula2(a, b, 17, 0, 8, 17 + 43 * largo, 0, 5.5f + 2.5f * (1 - largo)); });
  pieza(m, -20, -20, 20, 20, solido(gris, alfa), [=](float a, float b) { return dCirculo(a, b, 0, 0, 16.5f); });
  pieza(m, -16, -16, 16, 16, solido(osc, 0.85f * alfa), [=](float a, float b) {                                     // la rejilla
    return fmaxf(dCirculo(a, b, 0, 0, 14.5f), fminf(fabsf(fmodf(a + b + 70, 7.f) - 3.5f), fabsf(fmodf(a - b + 70, 7.f) - 3.5f)) - 0.7f); });
  pieza(m, 12, -10, 20, 10, solido(osc, alfa), [=](float a, float b) { return dCaja(a, b, 16, 0, 2.4f, 8.5f, 1.5f); });   // el aro
}
// teléfono: ícono verde redondo con el auricular blanco; tiembla mientras timbra y suelta ondas a los lados
static void telefonoIcono(float x, float y, float R, float u, float alfa) {
  ConColor cc;
  const Col verde{52, 199, 89};
  float lat = fmodf(u, 1.2f), timbre = lat < 0.6f ? 1 : 0, vib = timbre * sinf(u * 55) * 0.2f;
  for (int lado = -1; lado <= 1; lado += 2) for (int i = 0; i < 2; i++) {
    float Ra = R + 16 + 14 * i, a0 = lado > 0 ? -0.6f : PI - 0.6f;
    figura(x - Ra - 4, y - Ra - 4, x + Ra + 4, y + Ra + 4, solido(verde, alfa * timbre * (0.9f - 0.35f * i), 6),
           [=](float px, float py) { return dArco(px, py, x, y, Ra, a0, a0 + 1.2f) - 3; });
  }
  float s = 1 + 0.06f * timbre * fabsf(sinf(u * 20));
  figura(x - R * s, y - R * s, x + R * s, y + R * s, solido(verde, alfa, 14), [=](float px, float py) { return dCirculo(px, py, x, y, R * s); });
  Marco m = marco(x, y, -0.8f + vib, R / 40);
  pieza(m, -30, -30, 30, 30, solido(BLANCO, alfa), [](float a, float b) {
    float d = dArco(a, b, 8, 0, 19, PI * 0.62f, PI * 1.38f) - 6;                              // el mango curvo
    d = fminf(d, dCaja(a, b, 6, 18, 9, 6.5f, 5.5f));                                            // las dos puntas
    return fminf(d, dCaja(a, b, 6, -18, 9, 6.5f, 5.5f));
  });
}

// la mano libre, a la izquierda de la cara: 0 puño arriba con el golpe, 1 saluda de lado a lado, 2 apunta al cielo
// lado: -1 a la izquierda de la cara (como siempre), 1 a la derecha (cuando el micrófono está en la otra mano)
static void manoLibre(const Entradas &e, int n, float g, int paso, float a, int lado = -1) {
  const Ojo &o = lado < 0 ? ahora.L : ahora.R;
  const float f = n + e.pulso, x0 = o.x + lado * (o.w / 2 + 40), y0 = o.y + o.h / 2 + 34;
  float dx = 0, y = y0, ang = -PI / 2, esc = 1.1f;
  bool apunta = false;
  if (paso == 0) { y = y0 - 50 * (1 - g); ang = -PI / 2 - 0.2f; }                       // puño que sube y baja
  else if (paso == 1) { ang = -PI / 2 + 0.6f * sinf(f * PI); dx = 10 * sinf(f * PI); y = y0 - 40; }   // saludo
  else if (paso == 3) {                                                                 // golpea el aire: sube despacio y cae de golpe
    const float p = e.pulso, alto = p < 0.7f ? suave(p / 0.7f) : 1 - (p - 0.7f) * (p - 0.7f) / 0.09f, k = expf(-p * 9);
    y = y0 + 26 - 78 * alto; ang = 0.15f; dx = 14;
    for (int s2 = -1; s2 <= 1; s2 += 2)                                                // rayitas del golpe (no hay mesa)
      linea(x0 - lado * 14 + s2 * 30, y0 + 46, x0 - lado * 14 + s2 * (44 + 10 * (1 - k)), y0 + 54, 2.2f, BL, k * fminf(1.f, a * 1.6f));
  }
  else if (paso == 4) { apunta = true; ang = 0.95f; y = y0 - 14 + 8 * g; dx = 14; esc = 1.15f + 0.4f * g; }   // te señala: el dedo hacia ti, crece con el golpe
  else { apunta = true; ang = -PI / 2 + 0.25f; y = y0 - 30 - 30 * (1 - g); dx = 8; }   // apunta arriba
  if (lado > 0) { dx = -dx; ang = PI - ang; }                                            // al otro lado, en espejo
  manoBlanca(x0 + dx, y, ang, esc, apunta, fminf(1.f, a * 1.6f));   // blanca sólida y más grande: que se vea el paso
}

// Los instrumentos van abajo: crecen con la cara pero un poco menos y anclados al borde de abajo, para no salirse de la
// pantalla. El micrófono y la mano libre siguen pegados a la cara (con su mismo tamaño).
// cuándo cambió de ritmo (o empezó a cantar): el instrumento y el fondo entran de nuevo con su transición
static uint8_t ritmoAntes = 255, ritmoSale = 255; static float cambioEn = -10;   // ritmoSale: el instrumento que se está yendo (255 = ninguno)
static void cambioRitmo(const Entradas &e, float u) {
  if (u < 0.05f) { ritmoAntes = e.ritmo; ritmoSale = 255; cambioEn = tAhora; }                 // empieza a cantar: solo entra
  else if (e.ritmo != ritmoAntes) { ritmoSale = tAhora - cambioEn > 0.8f ? ritmoAntes : 255; ritmoAntes = e.ritmo; cambioEn = tAhora; }
}
struct ZoomInstrumento {
  float z, x, y; bool activo;
  explicit ZoomInstrumento(bool a) : z(dib::zoom), x(dib::zx), y(dib::zy), activo(a) {
    if (a) { dib::zoom = 1 + (z - 1) * 0.6f; dib::zx = CX; dib::zy = H; }
  }
  void soltar() { if (activo) { dib::zoom = z; dib::zx = x; dib::zy = y; activo = false; } }
  ~ZoomInstrumento() { soltar(); }
};

static void extrasMini(float u, const Entradas &e0) {
  // El instrumento y las manos entran subiendo desde abajo, al empezar a cantar y cada vez que cambia de instrumento
  // (por ejemplo del micrófono a la guitarra cuando se acaba la voz). En el cambio, el que estaba baja primero.
  cambioRitmo(e0, u);
  Entradas e = e0;
  const float dc = tAhora - cambioEn; const bool seVa = ritmoSale != 255 && dc < 0.25f;
  if (seVa) e.ritmo = ritmoSale;
  struct Sube { ~Sube() { dib::sube = 0; } } sb;
  dib::sube = seVa ? 230 * tramo(dc, 0, 0.25f) : 230 * (1 - tramo(dc - (ritmoSale != 255 ? 0.25f : 0), 0, 0.55f));
  ZoomInstrumento zi(e.ritmo != R_KPOP);
  int n = (int)pulsosVistos;
  float g = expf(-e.pulso * 6), f = n + e.pulso, vaiven = sinf(f * PI);
  // velocidad por niveles según qué tan fuerte suena: suave 1 toque por golpe, normal 2, fuerte 4 (con un margen para
  // que no salte de un nivel a otro a cada rato)
  static float nivelS = 0.5f;
  static int veces = 2;
  nivelS += (e.nivel - nivelS) * 0.03f;
  if (veces == 1 && nivelS > 0.4f) veces = 2;
  else if (veces == 2 && nivelS < 0.3f) veces = 1;
  else if (veces == 2 && nivelS > 0.75f) veces = 4;
  else if (veces == 4 && nivelS < 0.65f) veces = 2;
  const float fv = e.pulso * veces, gv = expf(-fmodf(fv, 1.f) * 6);   // cada toque, a esa velocidad
  const int nv = n * veces + (int)fv;                                  // cuántos toques van
  switch (e.ritmo) {
    case R_ROCK: case R_GROOVE: {                       // guitarra eléctrica: la manito rasguea a la velocidad del nivel
      float gx = CX - 62 + 18 * vaiven, gy = H - 36, ang = -0.22f - 0.08f * gv + 0.07f * vaiven, hx, hy;   // se mece de lado a lado   // abajo y acostada: el mástil pasa debajo de la boquita
      guitarraLinea(gx, gy, ang, 1, true, 4 * gv, 1);
      aPantalla(marco(gx, gy, ang, 1), 0, -14 + 26 * (1 - gv), hx, hy);
      manoBlanca(hx, hy, ang + 1.9f, 0.85f, false);
      aPantalla(marco(gx, gy, ang, 1), 96 + 14 * sinf(f * PI * 0.5f), 12, hx, hy);   // la otra mano en el mástil
      manoBlanca(hx, hy, ang - 1.5f, 0.8f, false);
      break;
    }
    case R_GUITARRA: case R_DISFRUTA: {                 // guitarra acústica: rasguea a la velocidad del nivel
      float rasga = gv, gx = CX - 70 + 18 * vaiven, gy = H - 38, ang = -0.2f + 0.07f * vaiven, hx, hy;   // se mece de lado a lado
      guitarraLinea(gx, gy, ang, 1.05f, false, 3 * rasga, 1);
      aPantalla(marco(gx, gy, ang, 1.05f), 22, -16 + 26 * (1 - rasga), hx, hy);
      manoBlanca(hx, hy, ang + 1.9f, 0.85f, false);
      aPantalla(marco(gx, gy, ang, 1.05f), 104 + 10 * sinf(f * PI * 0.5f), 12, hx, hy);   // la otra mano en el mástil
      manoBlanca(hx, hy, ang - 1.5f, 0.8f, false);
      break;
    }
    case R_TROPICAL:                                    // maracas en las manos: se turnan, suben, giran y dejan rayitas al sacudir
      for (int s2 = -1; s2 <= 1; s2 += 2) {
        const float sh = (nv + (s2 > 0)) % 2 ? gv : 0.25f * gv;                       // 1 = le toca a esta
        const float x = CX + s2 * (132 - 16 * sh), y = EY + 104 - 58 * sh;
        const float ang = s2 * (0.3f + 0.55f * sh) + 0.4f * sh * sinf(u * 45), hx = x + sinf(ang) * 46, hy = y - cosf(ang) * 46;
        linea(x, y, hx, hy, 4.5f, BL, 1);
        figura(hx - 22, hy - 22, hx + 22, hy + 22, solido(s2 < 0 ? Col{240, 84, 96} : Col{255, 190, 64}, 1), [=](float px, float py) { return dElipse(px, py, hx, hy, 17, 21); });
        figura(hx - 22, hy - 6, hx + 22, hy + 6, solido(BL, 0.9f), [=](float px, float py) { return fmaxf(dElipse(px, py, hx, hy, 17, 21), fabsf(py - hy) - 2.2f); });   // la franja
        manoBlanca(x, y + 4, -PI / 2, 0.75f, false);
        const float af = s2 > 0 ? -0.5f : PI - 0.5f;                                   // rayitas hacia afuera
        for (int i = 0; i < 2; i++) arcoTrazo(hx, hy, 32 + 11 * i, af - 0.45f, af + 0.45f, 2, sh * (1 - 0.4f * i));
      }
      break;
    case R_JAZZ: {                                      // saxofón con la manito: se mece de lado a lado, más rápido en lo fuerte
      const float vs = sinf((n * (veces > 1 ? 2 : 1) + e.pulso * (veces > 1 ? 2 : 1)) * PI);
      const float sxx = W - 110 + 26 * vs, syy = H - 50 - 6 * fabsf(vs), ang = 0.2f * vs, se = 0.95f;
      float hx, hy;
      saxoLinea(sxx, syy, ang, se, 1);
      aPantalla(marco(sxx, syy, ang, se), -4, -28, hx, hy); manoBlanca(hx, hy, -0.3f + ang, 0.75f, false);
      break;
    }
    case R_DJ: case R_ELECTRO: case R_BARRAS: {         // disco a la derecha (la boquita queda libre) y la manito que lo raya
      float scratch = (n % 4 >= 2) ? sinf(f * PI * veces) : 0, x = CX + 122 + 20 * vaiven, y = H - 66 - 8 * gv;   // se mece con el golpe
      disco(x, y, 48, u * 4 + 0.9f * scratch, 1);
      manoBlanca(x - 44 + 10 * scratch, y - 30 + 6 * scratch, 0.45f, 1.15f, true);
      zi.soltar();                                      // la otra mano va con la cara
      if (bailaAhora > 0.05f) manoLibre(e, n, gv, 0, bailaAhora);   // la otra mano: puño arriba con el golpe
      break;
    }
    case R_KPOP: {                                      // micrófono de línea frente a la boquita, con la manito
      // lado: en qué mano está (en el pase va por debajo de la boquita). O: cuánto se lo acerca a quien mira.
      const float lado = ladoMic, O = suave(ofreceAhora), pase = 1 - fabsf(lado);
      const int sg = lado >= 0 ? 1 : -1;
      // lo mueve al cantar, más rápido en lo fuerte; en el "¡aaah!" se corre para que quepa la boca grande
      const float Em = lim(0.5f * lim((e.bpm - 70) / 70, 0, 1) + 0.5f * lim(nivL * 1.4f, 0, 1), 0, 1);   // lenta y suave 0 .. movida y fuerte 1
      const float a0 = 0.95f + (0.06f + 0.22f * Em) * sinf((n + e.pulso) * PI * 0.5f * veces) - 0.2f * ahora.boca;
      float ang = PI / 2 - lado * (PI / 2 - a0);
      const float vuelo = lanzaMic ? 1 - lado * lado : 0, D = suave(dosAhora) * (1 - O);   // vuelo: qué tan alto va el lanzado
      const bool enVuelo = vuelo > 0.08f;
      if (lanzaMic) ang += (lado + 1) * PI;              // lanzado: una sola vuelta en el aire
      float mx = ahora.bx + lado * (56 + 46 * gritoAhora), my = ahora.by + 4 + (lanzaMic ? -135 * vuelo : 36 * suave(pase)), esc = 1.3f, hx, hy;
      my -= (3 + 14 * Em) * g * (1 - vuelo); mx += lado * 9 * Em * vaiven * (1 - vuelo);   // marca el golpe: poquito en baladas, mucho en lo movido
      mx += (ahora.bx - mx) * D; my += (ahora.by + 62 - my) * D; ang += (PI / 2 - ang) * D;   // dos manos: al centro, de pie
      // "¡canta tú!": el micrófono se queda en su mano, con la cabeza hacia afuera, y lo empuja hacia el público con cada
      // golpe (crece = va hacia adelante)
      mx += (ahora.bx + sg * 80 - mx) * O; my += (ahora.by + 14 - 10 * g - my) * O; ang += (PI / 2 + sg * 0.18f - ang) * O;
      esc *= 1 + O * (0.08f + 0.2f * g);                 // apenas crece: lo que cambia es que se voltea hacia quien mira
      const float largo = 1 - 0.62f * O;                 // de frente el mango casi no se ve
      microLinea(mx, my, ang, esc, 1, largo);
      if (enVuelo) {                                    // va por el aire: las dos manos abajo, esperándolo
        for (int s2 = -1; s2 <= 1; s2 += 2) manoBlanca(ahora.bx + s2 * 88, ahora.by + 49 - 18 * vuelo, -PI / 2 - s2 * 0.3f, 0.85f, false);
        break;
      }
      aPantalla(marco(mx, my, ang, esc), (42 - 16 * D) * (1 - O) + 36 * O, 0, hx, hy);   // a dos manos agarra mas arriba: que quepan en la pantalla
      manoBlanca(hx, hy, ang - sg * PI / 2, 0.85f, false);
      const float dosM = fmaxf(D, O < 0.05f ? lim(emoAhora * 2 - 0.6f, 0, 1) : 0);
      if (dosM > 0.05f) {                               // con sentimiento o nota larga: el micrófono con las dos manos
        aPantalla(marco(mx, my, ang, esc), 64 - 20 * D, 0, hx, hy);
        manoBlanca(hx, hy, D > 0.5f ? ang + sg * PI / 2 : ang - sg * (PI / 2 + 0.25f), 0.85f, false, dosM);
      } else if (O > 0.3f) {                            // la otra mano, al oído: "¡no los oigo!"
        const Ojo &o = sg > 0 ? ahora.L : ahora.R;
        manoBlanca(o.x - sg * (o.w / 2 + 16), o.y - 2 + 5 * g, -PI / 2 - sg * 0.4f, 1.2f, false, O);
      }
      else if (pase < 0.2f && (e.canta || bailaAhora > 0.05f)) manoLibre(e, n, gv, golpeAhora > 0.3f ? 3 : manoPaso, fmaxf(bailaAhora, 0.6f), -sg);   // la otra mano baila
      break;
    }
    case R_MANOS: {                                     // canta con las manos: se turnan con el golpe y las abre en lo fuerte
      const float drama = fmaxf(emoAhora, gritoZona);   // nota larga o parte fuerte: las dos arriba y abiertas
      for (int s2 = -1; s2 <= 1; s2 += 2) {             // abajo, a los lados de la boquita: no tapan los ojos ni se salen
        const float toca = (n % 2 == (s2 > 0)) ? gv : 0.3f * gv;
        const float x = CX + s2 * (128 + 8 * sinf(f * PI * 0.5f) + 22 * drama);
        const float y = H - 62 - (22 + 30 * lim(nivR * 1.3f, 0, 1)) * toca - 30 * drama + 5 * drama * sinf(u * 30);
        manoBlanca(x, y, -PI / 2 + s2 * (0.45f - 0.5f * toca + 0.4f * drama), 1.1f, false);
      }
      break;
    }
    case R_VELA: {                                      // vela en la mano: la mece despacio y la levanta en lo fuerte
      const float lento = sinf(f * PI * 0.5f), sube = 18 * lim(fmaxf(emoAhora, gritoZona), 0, 1);
      const float x = CX + 176 + 12 * lento, y = H - 54 - sube, tilt = 0.12f * lento;
      const float tx = x + sinf(tilt) * 44, ty = y - cosf(tilt) * 44;                 // la punta de la vela
      Marco m = marco(x, y, tilt, 1);
      pieza(m, -14, -48, 14, 6, solido(BL, 1), [=](float a, float b) { return fabsf(dCaja(a, b, 0, -21, 8, 21, 4)) - 2.4f; });   // la vela
      pieza(m, -3, -56, 3, -42, solido(BL, 1), [=](float a, float b) { return dSegmento(a, b, 0, -44, 0, -51, 1.4f); });        // el pabilo
      const float fs = 12 * (1 + 0.12f * sinf(u * 23) + 0.25f * gv), fx = tx - 5 * lento + 2 * sinf(u * 17), fy = ty - 12 - fs;   // la llama se queda atrás al mecer
      figura(fx - fs, fy - fs * 1.9f, fx + fs, fy + fs * 1.1f, solido(BL, 1, 10), [=](float px, float py) { return dGota(px, py, fx, fy, fs); });
      anillo(fx, fy - 4, fs + 12 + 4 * gv, 1.6f, 0.35f + 0.25f * gv);                 // el resplandor
      manoBlanca(x, y + 6, tilt - PI / 2, 0.85f, false);
      zi.soltar();
      if (e.canta) manoLibre(e, n, gv, emoAhora > 0.3f || gritoZona > 0.5f ? 2 : 1, 1);   // la otra mano: saluda lento, al cielo en lo fuerte
      break;
    }
    case R_ACORDEON: {                                  // acordeón: el fuelle abre y cierra con la música
      const float ap = 28 + 20 * sinf(f * PI * 0.5f) + 5 * gv, ay = H - 50;
      figura(CX - ap - 4, ay - 30, CX + ap + 4, ay + 30, solido(Col{240, 228, 200}, 1), [=](float qx, float qy) { return dCaja(qx, qy, CX, ay, ap + 2, 27, 3); });   // el fuelle
      for (int i = 0; i < 7; i++) { const float x = CX + (i - 3) * ap / 3.2f; linea(x, ay - 27, x, ay + 27, 1.3f, i % 2 ? Col{150, 40, 50} : Col{176, 160, 128}, 1); }   // pliegues
      for (int s2 = -1; s2 <= 1; s2 += 2) {
        const float x = CX + s2 * (ap + 16);
        figura(x - 20, ay - 40, x + 20, ay + 40, solido(Col{206, 40, 56}, 1), [=](float qx, float qy) { return dCaja(qx, qy, x, ay, 15, 35, 6); });                 // las cajas rojas
        figura(x - 20, ay - 40, x + 20, ay + 40, solido(Col{250, 214, 110}, 1), [=](float qx, float qy) { return fabsf(dCaja(qx, qy, x, ay, 15, 35, 6) + 2.4f) - 1; });   // filete dorado
        manoBlanca(x + s2 * 24, ay + 4 - (s2 > 0 ? 8 * gv : 0), s2 > 0 ? PI : 0, 0.7f, false);
      }
      for (int i = 0; i < 3; i++) circulo(CX + ap + 16, ay - 18 + i * 18, 3.4f, (nv + i) % 3 == 0 ? Col{255, 255, 255} : Col{255, 190, 190}, 1);   // botones
      for (int i = 0; i < 4; i++) linea(CX - ap - 22, ay - 20 + i * 13, CX - ap - 10, ay - 20 + i * 13, 1.6f, Col{255, 236, 236}, 1);               // las teclas del otro lado
      break;
    }
    case R_URBANO: {                                    // el raperito
      zi.soltar();                                      // todo va pegado a la cara
      const Ojo &oL = ahora.L, &oR = ahora.R;
      const float bx = ahora.bx, by = ahora.by, top = fminf(oL.y - oL.h / 2 + oL.h * oL.tapa, oR.y - oR.h / 2 + oR.h * oR.tapa);
      gorra((oL.x + oR.x) / 2 - 10, top - 14, -0.1f - 0.03f * gv, 0.72f);                   // la gorra, ladeada y calada hasta los ojos
      {                                                                                    // la cadena de oro con su medalla, que salta con el golpe
        const float cy = by + 2, R = 50, my = cy + R + 5 * gv, mxx = bx + 6 * vaiven;
        figura(bx - R - 4, cy, bx + R + 4, cy + R + 6, solido(Col{250, 200, 70}), [=](float px, float py) { return dArco(px, py, bx, cy, R, 0.22f * PI, 0.78f * PI) - 2.4f; });
        circulo(mxx, my, 11, Col{250, 200, 70}, 1); circulo(mxx, my, 7, Col{190, 130, 30}, 1); chispa(mxx + 6, my - 7, 5 + 4 * gv, 0.9f);
      }
      const float mx = bx + 46 + 5 * vaiven, my2 = by + 6 - 5 * gv;                        // el micrófono pegado a la boca
      microLinea(mx, my2, 0.95f, 1.05f, 1);
      manoBlanca(mx + 26, my2 + 36, PI + 0.95f, 1.05f, false);
      // la otra mano marca las barras: apunta y baja con cada golpe, cambiando de altura
      manoBlanca(oL.x - oL.w / 2 - 44 + 12 * gv, oL.y + 46 + 26 * (nv % 2) - 22 * gv, -0.35f + 0.5f * gv, 1.15f, true);
      break;
    }
    default:                                            // ecualizador: barritas blancas que saltan con la música
      for (int i = 0; i < 9; i++) {
        const float x = CX + (i - 4) * 36, a = 0.45f + 0.55f * fabsf(sinf(u * (2.6f + 0.37f * i) + i * 1.7f));
        const float h = 14 + 70 * lim(e.nivel * 1.4f, 0.15f, 1) * a * (0.65f + 0.35f * gv);
        figura(x - 12, H - 14 - h, x + 12, H - 10, solido(COLORES[i % 5], 0.95f), [=](float px, float py) { return dCaja(px, py, x, H - 12 - h / 2, 10, h / 2, 10); });
      }
      break;
  }
}

static Rostro deBase(float t, const Entradas &e, float &vel) {
  Rostro r = neutro(AZUL);
  float lx = 0, ly = 0, bl = parpadeo(t);
  float respira = 1 + 0.018f * sinf(t * 2.1f);
  escalar(r, 1, respira);
  vel = 7;
  switch (laBase) {
    case REPOSO:
      // Cubi: en reposo con ojitos de sueño que respiran, y la cara se pasea despacio (que no se queme el OLED)
      mirada(t, lx, ly); mirar(r, 0.5f * lx, 0.5f * ly);
      {
        // cada 7 s, a veces, un gesto suelto y distinto (curiosea, bosteza, se alegra, piensa, sospecha, se sacude):
        // que se vea vivo aunque no pase nada
        float tapa = 0.4f + 0.06f * sinf(t * 0.8f);
        const int gi = (int)(t / 7);
        const float gu = t - gi * 7, k = tramo(gu, 0.3f, 0.7f) * (1 - tramo(gu, 2.1f, 2.6f));
        const float d = hash(gi, 6) < 0.5f ? -1.f : 1.f;
        if (k > 0.01f) switch ((int)(hash(gi, 5) * 10)) {
          case 4: tapa *= 1 - k; escalar(r, 1 + 0.08f * k, 1 + 0.08f * k); r.L.incl = r.R.incl = 0.16f * k * d; mirar(r, 0.6f * k * d, 0); break;   // curiosea
          case 5: tapa += (1 - tapa) * k; mover(r, 0, -6 * k); r.boca = 0.5f * k; r.bAncho = 0.7f; break;                     // bosteza
          case 6: if (k > 0.5f) feliz(r, 1); mover(r, 0, -9 * k * fabsf(sinf(gu * 9))); break;                                                     // se alegra
          case 7: tapa *= 1 - 0.8f * k; mirar(r, 0.45f * k * d, -0.7f * k); break;                                                                // piensa
          case 8: tapa += (0.66f - tapa) * k; mirar(r, 0.9f * k * sinf((gu - 0.3f) * 2.6f) * d, 0); break;                                        // sospecha
          case 9: tapa *= 1 - k; mover(r, 9 * k * sinf(gu * 15), 0); break;                                                                       // se sacude
          default: break;
        }
        tapas(r, fmaxf(bl, tapa));
      }
      mover(r, 26 * sinf(t * 0.051f), 14 * sinf(t * 0.033f + 1));
      vel = 3;
      {                                 // se acabó la música después de un buen rato: venia
        if (t - ultCantaT < 0.25f && sonoSeg > 15 && t - veniaDesde > 5) { veniaDesde = t; sonoSeg = 0; }
        if (venia(r)) vel = 9;
      }
      break;
    case ESCUCHA:
      escalar(r, 1.08f, 1.08f); r.L.y -= 6; r.R.y += 6; mirar(r, 0.25f, -0.1f); tapas(r, bl * 0.9f);
      mover(r, -40, 0);   // deja campo para la mano en el oído
      break;
    case HABLA:
      r.boca = e.boca; r.bAncho = 0.75f + 0.6f * e.ancho; mover(r, 0, -5 * e.boca); tapas(r, bl);
      mirar(r, 0.15f * sinf(t * 0.7f), 0);
      vel = 14;
      break;
    case CANTA: {
      if (e.pulso < ultimoPulso - 0.5f) pulsosVistos += 1;    // pasó un golpe
      ultimoPulso = e.pulso;
      int n = (int)pulsosVistos;
      float g = expf(-e.pulso * 6), k = powf(1 - e.pulso, 3);
      float vaiven = sinf((n + e.pulso) * PI);               // un golpe hacia cada lado
      float abre = 1;   // cuánto abre la boca este baile cuando hay voz (0 = no canta)
      bailaMini(r, e, g, vaiven, bl);
      if (e.ritmo == R_URBANO) {              // el raperito: mirada tranquila debajo de la gorra, cabecea con el golpe
        escalar(r, 0.92f, 0.8f); mover(r, 0, 30 + 9 * g); r.L.feliz = r.R.feliz = 0; r.L.tapa = r.R.tapa = 0.36f; r.L.incl = r.R.incl = 0.06f;
      }
      // con voz la boca sigue al cantante (entre sílabas se cierra); sin voz no hay boca: solo baila o toca
      // minimalista: la boca sigue la voz completa, como en la app (sin achicarla por baile)
      // bailando o cantando nunca con cara de preocupado: la inclinación negativa son cejas tristes
      r.L.incl = fmaxf(r.L.incl, 0); r.R.incl = fmaxf(r.R.incl, 0);
      r.boca = e.canta && abre > 0 ? fmaxf(e.boca, 0.07f) : 0; r.bAncho = 0.75f + 0.6f * e.ancho;
      if (ofreceAhora > 0.3f) r.boca = 0;       // le pasó el micrófono a quien mira: se calla y escucha
      {   // nota sostenida (un "aaah" con la misma vocal): emocion crece con lo que dura, y entre más dura más drama
        static float tE = 0; const float dE = lim(t - tE, 0, 0.1f); tE = t;
        emoAhora += ((e.canta ? e.emocion : 0) - emoAhora) * lim(dE * 6, 0, 1);
        if (emoAhora > 0.3f) { feliz(r, 1); mirar(r, 0, -0.35f - 0.3f * emoAhora); mover(r, 0, -8 - 14 * emoAhora); }   // ojos cerrados, al cielo
        if (emoAhora > 0.6f) {                                // ya es largo: tiembla de emoción (vibrato) y crece
          const float v = (emoAhora - 0.6f) * 2.5f;
          mover(r, 3 * v * sinf(t * 38), 0); escalar(r, 1 + 0.08f * v, 1 + 0.08f * v);
        }
      }
      vel = 16;
      break;
    }
    case PIENSA:
      mirar(r, -0.5f, -0.6f); tapas(r, fmaxf(0.15f, bl));
      break;
    case DUERME:
      tapas(r, 1);
      r.L.alfa = r.R.alfa = 0.6f;
      mover(r, 26 * sinf(t * 0.051f), 14 * sinf(t * 0.033f + 1));   // también dormido se pasea
      vel = 4;
      break;
    case TELEFONO:                                            // hablando por teléfono: mira al lado del auricular
      mover(r, -30, 0); mirar(r, 0.35f, 0); tapas(r, bl);
      r.boca = e.boca; r.bAncho = 0.75f + 0.6f * e.ancho; vel = 14;
      break;
    default: break;
  }
  return r;
}

// La pantalla se ilumina por el borde según la emoción, muy difuminado: MARCO = todo el contorno, ABAJO = sube desde abajo,
// ARRIBA = baja desde arriba. Va por detrás de la cara y pegada al borde (sin zoom). Siempre de color, en todas las caras.
enum { MARCO, ABAJO, ARRIBA, OVALO_LUZ };   // OVALO_LUZ: viñeta ovalada, la más suave (no se le ve la forma de la pantalla)
static void luzBorde(int donde, const Col &c, float alfa, float ancho, float radio = 44) {
  if (alfa < 0.03f) return;
  ConColor cc; dib::SinZoom sz;
  if (donde == MARCO) resplandor(0, 0, W, H, c, alfa, ancho, [=](float px, float py) { return fabsf(dCaja(px, py, W / 2.f, H / 2.f, W / 2.f - 5, H / 2.f - 5, radio)); });   // un contorno redondeado que brilla hacia adentro y hacia afuera
  else if (donde == OVALO_LUZ) resplandor(0, 0, W, H, c, alfa, ancho, [](float px, float py) {
    const float ax = (px - W / 2.f) / (W * 0.6f), ay = (py - H / 2.f) / (H * 0.6f);
    return fmaxf(0, (1 - sqrtf(ax * ax + ay * ay)) * H * 0.6f); });
  else if (donde == ABAJO) resplandor(0, H, W, H, c, alfa, ancho, [=](float, float py) { return ancho * (1 - sqrtf(fmaxf(0, 1 - (H - py) / ancho))); });
  else resplandor(0, 0, W, 0, c, alfa, ancho, [=](float, float py) { return ancho * (1 - sqrtf(fmaxf(0, 1 - py / ancho))); });
}

static void haloEscucha(float u, const Entradas &e) {
  ConColor cc;
  static const Col TONOS_HALO[3] = {{60, 220, 255}, {170, 110, 255}, {255, 100, 200}};   // turquesa, violeta, rosado
  const float h = fmodf(u * 0.35f, 3.f);
  const int i = (int)h;
  const Col &c0 = TONOS_HALO[i], &c1 = TONOS_HALO[(i + 1) % 3];
  const float k = h - i;
  const Col c{uint8_t(c0.r + (c1.r - c0.r) * k), uint8_t(c0.g + (c1.g - c0.g) * k), uint8_t(c0.b + (c1.b - c0.b) * k)};
  const float entra = tramo(u, 0, 0.4f), late = 0.55f + 0.25f * sinf(u * 4) + 0.6f * e.nivel;
  for (int s = -1; s <= 1; s += 2) {                    // un aro brillante alrededor de cada ojo
    const Ojo &o = s < 0 ? ahora.L : ahora.R;
    const float hw = o.w / 2 + 12 + 4 * e.nivel, hh = o.h / 2 + 12 + 4 * e.nivel;
    figura(o.x - hw - 30, o.y - hh - 30, o.x + hw + 30, o.y + hh + 30, solido(c, entra * fminf(late, 1.f), 26),
           [=](float px, float py) { return fabsf(dPildora(px, py, o.x, o.y, hw, hh)) - 3; });
  }
  {                                                     // la mano de bocina en el oído y las ondas que le van llegando
    const Ojo &o = ahora.R;
    const float ox = o.x + o.w / 2 + 46, oy = o.y + 2;
    for (int j = 0; j < 3; j++) {
      const float p = fmodf(u * 0.9f + j / 3.f, 1.f), R = 22 + 44 * (1 - p);
      figura(ox, oy - R - 6, ox + R + 6, oy + R + 6, solido(c, entra * 0.85f * campana(p, 0, 1), 8),
             [=](float px, float py) { return dArco(px, py, ox, oy, R, -0.62f, 0.62f) - 2.4f - 1.2f * e.nivel; });
    }
    manoBlanca(o.x + o.w / 2 + 34, o.y + 6 - 3 * e.nivel + 200 * (1 - tramo(u, 0, 0.6f)), -PI / 2 + 0.4f, 1.1f, false, 1);
  }
}

// El ambiente de cuando canta: discreto y siempre cambiando. Un par de luces chicas que se pasean por el borde y van pasando
// de un color a otro (cada ritmo con su paleta y su manera), y una notita de vez en cuando. Lo oscuro va opaco sobre el negro: barato.
static void ambienteCanta(float u, const Entradas &e) {
  cambioRitmo(e, u);
  if (e.ritmo == R_VELA) return;                       // a oscuras: la luz es la vela
  ConColor cc; dib::SinZoom sz;
  const float entra = tramo(tAhora - cambioEn, 0, 0.9f), g = expf(-e.pulso * 6);
  // la paleta del ritmo: los colores se van turnando, fundiéndose de uno al siguiente
  static const Col PAL[][3] = {
      {{255, 110, 150}, {110, 190, 255}, {190, 140, 255}},   // 0 pop: rosado, azul, lila
      {{255, 50, 50}, {255, 140, 40}, {255, 60, 140}},       // 1 rock: rojo, naranja, fucsia
      {{255, 170, 60}, {255, 120, 40}, {255, 210, 110}},     // 2 guitarra: ámbares de fogata
      {{60, 90, 255}, {150, 90, 255}, {40, 170, 255}},       // 3 jazz: azules y violeta
      {{255, 60, 200}, {40, 220, 255}, {150, 255, 90}},      // 4 dj: magenta, cian, lima
      {{255, 130, 50}, {255, 200, 60}, {255, 90, 110}},      // 5 tropical: atardecer
      {{140, 70, 255}, {90, 240, 130}, {255, 80, 180}},      // 6 urbano: violeta, verde, fucsia
      {{90, 110, 255}, {200, 210, 255}, {160, 120, 255}},    // 7 piano: noche
      {{255, 130, 200}, {120, 220, 255}, {255, 240, 130}},   // 8 kpop: pastel
      {{255, 210, 40}, {60, 100, 255}, {240, 50, 60}},       // 9 acordeón: amarillo, azul, rojo
  };
  int pal = 0, modo = 0;                               // modo: 0 luces que se pasean, 1 focos finos del techo, 2 rayos (nada de tintes de borde: en el robot frenaban el baile)
  switch (e.ritmo) {
    case R_ROCK: case R_GROOVE: pal = 1; modo = 1; break;
    case R_GUITARRA: case R_DISFRUTA: pal = 2; break;
    case R_JAZZ: pal = 3; break;
    case R_DJ: case R_ELECTRO: case R_BARRAS: pal = 4; modo = 2; break;
    case R_TROPICAL: pal = 5; break;
    case R_URBANO: pal = 6; break;
    case R_KPOP: pal = 8; break;
    case R_ACORDEON: pal = 9; break;
    default: break;
  }
  auto color = [&](float desfase) {                    // el color de ahora: cada ~3 s pasa al siguiente de la paleta
    const float t = u * 0.33f + desfase; const int i = (int)t % 3;
    return mezcla(PAL[pal][i], PAL[pal][(i + 1) % 3], suave(t - (int)t));
  };
  const Col c0 = color(0), c1 = color(1.5f);
  auto osc = [&](const Col &c, float k) { return Col{c.r * k * entra, c.g * k * entra, c.b * k * entra}; };
  if (modo == 0) {                                     // dos luces chicas que dan la vuelta por el borde, una frente a la otra
    for (int j = 0; j < 2; j++) {
      const float a = u * 0.4f + j * PI + 0.5f * sinf(u * 0.7f + j);
      circulo(CX + cosf(a) * (W / 2.f - 6), H / 2.f + sinf(a) * (H / 2.f - 6), (34 + 6 * j) * entra * (1 + 0.1f * g), osc(j ? c1 : c0, 0.3f), 1);
    }
  } else if (modo == 1) {                              // dos focos finos que barren desde el techo
    for (int s2 = -1; s2 <= 1; s2 += 2) {
      const float ax = CX + s2 * 150, bx = CX + s2 * (60 + 110 * sinf(u * 1.3f + s2 * 0.8f));
      figura(0, 0, W, H, solido(osc(s2 < 0 ? c0 : c1, 0.26f + 0.08f * g)), [=](float px, float py) { return dTriangulo(px, py, ax, -12, bx - 22, H + 10, bx + 22, H + 10); });
    }
  } else if (modo == 2) {                              // dos rayos que barren y cambian de color
    for (int i = 0; i < 2; i++)
      linea(CX + (i ? 60 : -60), -6, CX + 240 * sinf(u * 1.1f + i * 2.3f), H + 6, 1.8f, i ? c1 : c0, (0.4f + 0.3f * g) * entra);
  }
  {                                                    // una notita de vez en cuando (una sola a la vez, cada 4 s, alternando lado)
    const float ciclo = u / 4.f; const int k = (int)ciclo; const float q = (ciclo - k) / 0.55f;
    if (q < 1) notaColor((k % 2 ? W - 60 : 56) + 9 * sinf(q * 6), 190 - 80 * q, 12, c1, entra * campana(q, 0, 1) * 0.95f);
  }
}

static void extrasBase(float t, const Entradas &e, bool delante) {
  float u = t - baseDesde;
  if (laBase == ESCUCHA) { if (delante) haloEscucha(u, e); return; }
  if (!delante) switch (laBase) {            // la luz del borde de cada estado
    case HABLA: luzBorde(MARCO, Col{80, 230, 196}, tramo(u, 0, 0.3f) * (0.15f + 0.5f * lim(e.boca * 1.5f, 0, 1)), 56); break;   // verde agua: late con la voz
    case PIENSA: luzBorde(MARCO, Col{255, 190, 60}, tramo(u, 0, 0.3f) * (0.3f + 0.15f * sinf(u * 3)), 56); break;                // ámbar: dándole vueltas
    case TELEFONO: luzBorde(MARCO, Col{60, 224, 120}, tramo(u, 0, 0.3f) * (0.2f + 0.4f * lim(e.boca * 1.5f, 0, 1)), 56); break;  // verde llamada
    // ponytail: cantando no lleva luz: medido en el PC, el baile salía ~30 % más lento; ponerla si algún día sobra tiempo por cuadro
    default: break;
  }
  if (!delante && laBase != CANTA) return;
  if (laBase == CANTA) { if (delante) extrasMini(u, e); else ambienteCanta(u, e); return; }
  switch (laBase) {
    case PIENSA:
      for (int i = 0; i < 3; i++) {
        float a = u * 3 + i * 2.094f;
        circulo(CX + 150 + cosf(a) * 18, 60 + sinf(a) * 18, 7, TONOS[AZUL].a, 0.5f + 0.5f * sinf(a), 6);
      }
      break;
    case TELEFONO: {
      float x = ahora.R.x + 92, y = EY + 50 - 6 * ahora.boca;
      cable(x - 6, y + 82, x + 30, y + 150, x - 10, H + 30, 7, 7, TONOS[ROJO].b, 1);
      auricular(x, y, 0.12f, 62, 1);
      break;
    }
    case DUERME:
      arco(ahora, ahora.L.x, ahora.L.y + 20, EW, false, 0.7f);
      arco(ahora, ahora.R.x, ahora.R.y + 20, EW, false, 0.7f);
      for (int i = 0; i < 3; i++) {
        float p = fmodf(u * 0.25f + i / 3.f, 1.f);
        dib::texto("z", CX + 130 + p * 70, 150 - p * 120, 26 + p * 30, TONOS[AZUL].a, campana(p, 0, 1) * 0.8f);
      }
      break;
    default: break;
  }
}

// ---------------------------------------------------------------- lluvia sobre el vidrio de la pantalla
// Caen gotas en el vidrio (algunas escurren), se va empañando y pasa el limpiaparabrisas: ida y vuelta.
static const float LP_Y = H + 30, LP_L = 470;   // pivote del limpiaparabrisas (abajo, fuera de la pantalla) y su largo
static float limpiaAng(float u) {               // ángulo desde la vertical: -1,45 = acostado a la izquierda
  if (u < 3.4f) return -1.45f;
  if (u < 4.2f) return -1.45f + 2.9f * suave((u - 3.4f) / 0.8f);
  if (u < 5.0f) return 1.45f - 2.9f * suave((u - 4.2f) / 0.8f);
  return -1.45f;
}
static void lluviaVidrio(float u) {
  const float limpio = u < 3.4f ? -1.45f : u < 4.2f ? limpiaAng(u) : 1.45f, fin = tramo(u, 6.4f, 7.0f);
  float emp = 0.42f * tramo(u, 0.6f, 3.0f) * (1 - fin);   // empañado: gris claro donde no ha pasado el limpiaparabrisas
  if (emp > 0.01f && limpio < 1.44f) figura(0, 0, W, H, solido(Col{150, 162, 178}, emp), [=](float px, float py) {
    float dx = px - CX, dy = LP_Y - py;
    return (limpio - atan2f(dx, dy)) * sqrtf(dx * dx + dy * dy);
  });
  for (int i = 0; i < 28; i++) {                           // gotas
    float x = 20 + hash(i, 21) * (W - 40), y0 = 16 + hash(i, 22) * (H - 50), t0 = 0.15f + hash(i, 23) * 2.8f, sz = 3 + 5 * hash(i, 24);
    bool corre = hash(i, 25) < 0.35f;
    float t = u - t0;
    if (u >= 3.4f && atan2f(x - CX, LP_Y - y0) < limpio)   // la barrió el limpiaparabrisas: vuelve a caer un rato después
      t = u - 4.6f - t0 * 0.6f;
    if (t < 0) continue;
    float y = y0 + (corre ? fminf(70.f, t * t * 18) : 0), al = fminf(1.f, t * 6) * (1 - fin) * 0.85f;
    if (corre && y > y0 + 4) linea(x, y0, x, y - sz * 0.5f, sz * 0.35f, Col{200, 215, 235}, al * 0.35f);   // estela
    circulo(x, y, sz, Col{170, 190, 215}, al * 0.55f, 3);
    circulo(x - sz * 0.35f, y - sz * 0.35f, sz * 0.3f, BLANCO, al);                                         // brillito
  }
  if (u > 3.2f && u < 5.2f) {                               // limpiaparabrisas
    float ang = limpiaAng(u), c = sinf(ang), k = -cosf(ang);
    linea(CX + c * 40, LP_Y + k * 40, CX + c * LP_L, LP_Y + k * LP_L, 5, Col{70, 74, 84}, 1);            // brazo
    linea(CX + c * 120, LP_Y + k * 120, CX + c * LP_L, LP_Y + k * LP_L, 7, Col{25, 26, 30}, 1);          // escobilla de caucho
    linea(CX + c * 120, LP_Y + k * 120, CX + c * LP_L, LP_Y + k * LP_L, 2, Col{150, 155, 165}, 1);       // su brillo
  }
}

// ---------------------------------------------------------------- sol: rayo que barre el vidrio, destello del parabrisas y gafas
// El destello sale del sol (arriba a la derecha) y cruza la pantalla; cuando el carro gira, el sol se corre y el destello con él.
static void solDestello(float u, float a) {
  ConColor cc;
  SinZoom sz;                                                         // el sol va en la esquina: sin zoom
  const float sx = W - 46 - curvaAhora * 150, sy = 30;
  figura(sx - 70, sy - 70, sx + 70, sy + 70, solido(Col{255, 214, 90}, a, 46), [=](float px, float py) { return dCirculo(px, py, sx, sy, 22 + 2 * sinf(u * 3)); });
  for (int k = 0; k < 8; k++) {                                      // rayitos que giran despacio
    float g = u * 0.4f + k * PI / 4, r0 = 32, r1 = 46 + 6 * sinf(u * 4 + k);
    linea(sx + cosf(g) * r0, sy + sinf(g) * r0, sx + cosf(g) * r1, sy + sinf(g) * r1, 3, Col{255, 220, 120}, a * 0.8f);
  }
  static const float T[5] = {0.42f, 0.7f, 1.0f, 1.32f, 1.7f}, RR[5] = {9, 22, 13, 32, 18}, AA[5] = {0.3f, 0.16f, 0.26f, 0.12f, 0.2f};
  static const Col CC[5] = {{255, 236, 170}, {255, 190, 120}, {190, 230, 255}, {255, 220, 150}, {200, 255, 210}};
  for (int k = 0; k < 5; k++) {                                      // circulitos del destello en fila, hacia el centro
    float x = sx + (CX - sx) * T[k], y = sy + (EY - sy) * T[k];
    if (k % 2) figura(x - RR[k] - 3, y - RR[k] - 3, x + RR[k] + 3, y + RR[k] + 3, solido(CC[k], a * AA[k] * 1.6f, 4),
                      [=](float px, float py) { return fabsf(dCirculo(px, py, x, y, RR[k])) - 2; });
    else circulo(x, y, RR[k], CC[k], a * AA[k], 6);
  }
}
static void gafasSol(float dy, float brillo, float alfa) {
  const Ojo &oL = ahora.L, &oR = ahora.R;
  const float y = (oL.y + oR.y) / 2 + dy, hw = fmaxf(oL.w, oR.w) / 2 + 16, hh = fminf(fmaxf(fmaxf(oL.h, oR.h) / 2 + 8, 26.f), hw * 0.85f);
  for (int s2 = -1; s2 <= 1; s2 += 2) {
    const float x = s2 < 0 ? oL.x : oR.x;
    figura(x - hw - 4, y - hh - 4, x + hw + 4, y + hh + 4, solido(Col{16, 18, 26}, alfa), [=](float px, float py) { return dCaja(px, py, x, y, hw, hh, hh * 0.8f); });
    figura(x - hw - 6, y - hh - 6, x + hw + 6, y + hh + 6, solido(BLANCO, alfa, 6), [=](float px, float py) { return fabsf(dCaja(px, py, x, y, hw, hh, hh * 0.8f)) - 3; });
    if (brillo > 0 && brillo < 1) {                                  // el brillo que cruza el lente
      const float gx = x - hw + 2 * hw * brillo;
      figura(gx - 30, y - hh, gx + 30, y + hh, solido(BLANCO, 0.75f * alfa), [=](float px, float py) {
        return fmaxf(dCaja(px, py, x, y, hw - 5, hh - 5, hh * 0.7f), fabsf((px - gx) + (py - y) * 0.5f) - 5);
      });
    }
    linea(x - s2 * hw, y - hh * 0.35f, x - s2 * (hw + 26), y - hh * 0.6f, 4, BLANCO, alfa);   // patitas
  }
  linea(oL.x + hw, y - hh * 0.4f, oR.x - hw, y - hh * 0.4f, 4, BLANCO, alfa);               // puente
}
static void solHistoria(float u) {
  const float fin = 1 - tramo(u, 6.4f, 7.0f);
  float b = campana(u, 0.15f, 1.5f);                                  // 1) un rayo de luz barre el vidrio y lo deslumbra
  if (b > 0.01f) {
    const float bx = -120 + (W + 240) * tramo(u, 0.15f, 1.5f), c = cosf(0.5f), sn = sinf(0.5f);
    ConColor cc;
    figura(0, 0, W, H, solido(Col{255, 238, 196}, 0.38f * b, 70), [=](float px, float py) { return fabsf((px - bx) * c + (py - EY) * sn) - 26; });
  }
  solDestello(u, tramo(u, 0.1f, 0.5f) * fin * (u < 3.6f ? 1 : 0.6f));
  if (u > 1.5f && u < 3.9f) {                                         // 2) manito de visera sobre los ojos
    const float entra = tramo(u, 1.5f, 1.95f) * (1 - tramo(u, 3.45f, 3.85f));
    const float y = fminf(ahora.L.y - ahora.L.h / 2, ahora.R.y - ahora.R.h / 2) - 4, x = ahora.R.x + 12;
    manoBlanca(W + 90 - (W + 90 - x) * entra, y - 30 * (1 - entra) + 3 * sinf(u * 3), PI + 0.1f, 2.1f, false);
  }
  if (u > 3.4f) {                                                     // 3) caen las gafas, rebotan, brillan y se quedan
    const float p = tramo(u, 3.4f, 3.95f);
    float dy = -280 * (1 - p) * (1 - p);
    if (u > 3.95f) dy = -16 * fabsf(sinf((u - 3.95f) * PI * 2.6f)) * expf(-(u - 3.95f) * 6);
    gafasSol(dy, tramo(u, 4.3f, 4.85f), fin);
    const Ojo &o = ahora.R;
    float ch = campana(u, 4.6f, 5.3f);
    if (ch > 0.01f) chispa(o.x + o.w / 2 + 22, o.y - o.h / 2 - 8, 10 + 14 * ch, ch, BLANCO);
  }
}

// ---------------------------------------------------------------- pistola de confeti: apunta al copiloto, cuenta 3 2 1 y ¡pum!
// Apunta hasta que el celular termina de decir "¡Prepárate, ...!" y manda cuentaPum(); si no llega, cuenta solo a los 7 s.
static float pumCuenta = -1;
void cuentaPum() { if (laReac == PUM && pumCuenta < 0) pumCuenta = tAhora - reacDesde; }
static float pumK(float u) {   // segundos desde que empezó la cuenta (negativo: todavía apuntando)
  if (pumCuenta < 0 && u > 7) pumCuenta = 7;
  return pumCuenta < 0 ? -1 : u - pumCuenta;
}
// La pistola apuntando de frente, a los de atrás (Cubi va arriba de la pantalla, mirando hacia la cabina): se ve la boca del
// cañón grande al centro, el cuerpo detrás y la mano abajo. El disparo viene hacia uno: destello, aro y confeti que se abre.
static void pistolaFrente(float u) {
  ConColor cc;
  const Col cuerpo{90, 220, 210}, cuerpoO{52, 160, 156}, metal{235, 240, 250}, punta{255, 80, 120}, rayo{255, 120, 170};
  const float k = pumK(u), entra = tramo(u, 0.2f, 0.7f), fin = k < 0 ? 1 : 1 - tramo(k, 4.6f, 5.4f);
  const float tiro = k - 3.0f, retro = tiro > 0 ? expf(-tiro * 8) : 0, baja = k < 0 ? 0 : tramo(k, 3.8f, 4.4f);
  const float carga = k >= 0 && k < 3 ? fmodf(k, 1.f) : 0;           // la luz de adentro se prende con cada número
  const float x = CX + 3 * sinf(u * 1.7f), y = H - 108 + 240 * (1 - entra) + 3 * sinf(u * 2.1f) - 26 * retro + 220 * baja, e = 1 - 0.12f * retro;
  circulo(x + 2, y + 36 * e, 40 * e, cuerpoO, fin);                                                             // la parte de atrás del cuerpo
  figura(x - 16, y + 30, x + 30, y + 116, solido(metal, fin), [=](float px, float py) { return dCapsula2(px, py, x + 4, y + 52 * e, 15 * e, x + 10, y + 98 * e, 13 * e); });   // el mango
  circulo(x, y + 16 * e, 44 * e, cuerpo, fin, 6);
  circulo(x, y + 5 * e, 37 * e, metal, fin); circulo(x, y + 5 * e, 32 * e, cuerpo, fin);                        // un aro del cañón
  figura(x - 10, y - 58 * e, x + 10, y - 24 * e, solido(metal, fin), [=](float px, float py) { return dTriangulo(px, py, x - 7 * e, y - 26 * e, x + 7 * e, y - 26 * e, x, y - 46 * e); });   // la mira
  circulo(x, y, 27 * e, metal, fin); circulo(x, y, 20 * e, Col{24, 30, 44}, fin);                               // la boca del cañón
  circulo(x, y, (6 + 7 * carga) * e, punta, fin, 10 + 18 * carga);
  manoBlanca(x + 8, y + 86 * e, -PI / 2 + 0.1f, 1.3f, false, fin);
  if (k >= 0 && k < 3) {                                              // 3 · 2 · 1 arriba
    const float f = k - floorf(k);
    const char c[2] = {char('3' - (int)k), 0};
    SinZoom sz;
    dib::texto(c, CX, 52, 70 + 30 * (1 - tramo(f, 0, 0.25f)), BLANCO, 1 - tramo(f, 0.8f, 1));
  }
  if (tiro > 0 && tiro < 0.5f) {                                      // ¡pum!: el destello llena la pantalla y un aro rosado crece hacia uno
    const float t = tiro / 0.5f, Ra = 30 + 430 * t;
    figura(0, 0, W, H, solido(Col{255, 230, 240}, 0.8f * (1 - t)), [](float, float) { return -1.f; });
    figura(x - Ra - 16, y - Ra - 16, x + Ra + 16, y + Ra + 16, solido(rayo, 1 - t), [=](float px, float py) { return fabsf(dCirculo(px, py, x, y, Ra)) - 3 - 9 * (1 - t); });
  }
  if (tiro > 0.05f && tiro < 1.8f) {                                  // el confeti sale hacia uno: se abre desde la boca y crece
    const float t2 = tiro - 0.05f, al = 1 - tramo(t2, 1.1f, 1.7f);
    for (int i = 0; i < 30; i++) {
      const float an = 6.283f * hash(i, 1), d = (90 + 260 * hash(i, 2)) * t2 * (1 + t2);
      Marco p2 = marco(x + cosf(an) * d, y + sinf(an) * d * 0.85f + 120 * t2 * t2, t2 * 10 * (hash(i, 3) - 0.5f) + i, 1 + 2.2f * t2);
      pieza(p2, -7, -5, 7, 5, solido(COLORES[i % 5], al), [](float a, float b) { return dCaja(a, b, 0, 0, 6, 3.5f, 1); });
    }
  }
  if (tiro > 0.3f && tiro < 1.8f) for (int i = 0; i < 3; i++) {       // el cañón queda echando humito
    const float p = (tiro - 0.3f) / 1.5f + i * 0.15f;
    if (p < 1) circulo(x + 6 * sinf(p * 7 + i), y - 10 - 50 * p, 6 + 10 * p, Col{220, 224, 236}, 0.6f * fin * (1 - p));
  }
  if (tiro > 0.4f) confeti(tiro - 0.4f, tramo(tiro, 0.4f, 0.7f) * (1 - tramo(k, 4.8f, 5.5f)));
}
// pistola láser de juguete de lado, en una mano, apuntando al copiloto (a la derecha): rayo y ¡confeti!
static void pistolaConfeti(float u) {
  if (giroForma == 1) { pistolaFrente(u); return; }   // "mata al de atrás": apunta de frente (el celular lo manda con f=1)
  ConColor cc;
  const Col cuerpo{90, 220, 210}, metal{235, 240, 250}, punta{255, 80, 120}, rayo{255, 120, 170};
  const float k = pumK(u), entra = tramo(u, 0.2f, 0.7f), fin = k < 0 ? 1 : 1 - tramo(k, 4.6f, 5.4f);
  const float tiro = k - 3.0f, retro = tiro > 0 ? expf(-tiro * 8) : 0, baja = k < 0 ? 0 : tramo(k, 3.8f, 4.4f);
  const float x = CX - 104 - 16 * retro, y = H - 98 + 240 * (1 - entra) + 3 * sinf(u * 2.1f) * (1 - baja) + 30 * baja;   // entra subiendo desde abajo
  const float ang = -0.06f - 0.3f * retro + 0.6f * baja;              // nivelada: Cubi va a la altura de la cara del copiloto
  Marco m = marco(x, y, ang, 1.4f);
  const float carga = k >= 0 && k < 3 ? fmodf(k, 1.f) : 0;           // la punta se prende con cada número
  pieza(m, -12, 4, 26, 52, solido(metal, fin, 4), [](float a, float b) {                       // mango
    return dCapsula2(a, b, 6, 10, 9, -2, 42, 8);
  });
  pieza(m, -18, -24, 104, 24, solido(cuerpo, fin, 6), [](float a, float b) {                   // cuerpo y cañón
    return fminf(dCapsula2(a, b, 0, 0, 13, 52, 0, 17), dCapsula2(a, b, 52, 0, 9, 94, 0, 12));   // en perspectiva: más gruesa hacia la punta, que viene hacia uno
  });
  for (int i = 0; i < 3; i++) {                                       // aros del cañón
    const float ax = 18 + i * 14;
    pieza(m, ax - 6, -26, ax + 6, 26, solido(metal, fin), [=](float a, float b) { return dCaja(a, b, ax, 0, 3, 16 + i * 2.2f, 3); });
  }
  float px, py;
  aPantalla(m, 98, 0, px, py);                                        // la punta que brilla
  pieza(m, 88, -16, 108, 16, solido(metal, fin), [](float a, float b) { return dElipse(a, b, 98, 0, 7.5f, 13.5f); });      // la boca del cañón, de frente
  pieza(m, 90, -12, 106, 12, solido(Col{24, 30, 44}, fin), [](float a, float b) { return dElipse(a, b, 98.5f, 0, 4.6f, 9.5f); });
  circulo(px, py, 4 + 4 * carga, punta, fin, 8 + 16 * carga);                                                          // adentro se va prendiendo
  float hx, hy;
  aPantalla(m, 2, 30, hx, hy);
  manoBlanca(hx, hy, ang + 0.35f, 1.2f, false, fin);                  // una sola mano en el mango
  if (tiro < 0 && entra > 0.9f) {                                     // la mira láser: una raya punteada roja hasta el copiloto
    const float dx = cosf(ang), dy = sinf(ang);
    for (int i = 0; i < 7; i++) { const float d0 = 16 + i * 26 + fmodf(u * 40, 26.f); linea(px + dx * d0, py + dy * d0, px + dx * (d0 + 10), py + dy * (d0 + 10), 1.4f, Col{255, 70, 90}, 0.7f * fin); }
  }
  if (tiro > 0.3f && tiro < 1.8f) for (int i = 0; i < 3; i++) {       // el cañón queda echando humito
    const float p = (tiro - 0.3f) / 1.5f + i * 0.15f;
    if (p < 1) circulo(px + 6 * sinf(p * 7 + i), py - 8 - 50 * p, 6 + 10 * p, Col{220, 224, 236}, 0.6f * fin * (1 - p));
  }
  if (k >= 0 && k < 3) {                                              // 3 · 2 · 1 arriba
    const float f = k - floorf(k);
    const char c[2] = {char('3' - (int)k), 0};
    SinZoom sz;                                                       // los números arriba, sin zoom
    dib::texto(c, CX, 52, 70 + 30 * (1 - tramo(f, 0, 0.25f)), BLANCO, 1 - tramo(f, 0.8f, 1));
  }
  if (tiro > 0 && tiro < 0.45f) {                                     // ¡pum!: el rayo cruza hasta el copiloto
    const float a2 = 1 - tramo(tiro, 0.25f, 0.45f), dx = cosf(ang), dy = sinf(ang), largo = (W + 40 - px) / fmaxf(dx, 0.3f);
    const float ex = px + dx * largo, ey = py + dy * largo;
    linea(px, py, ex, ey, 9, rayo, 0.8f * a2, 18);
    linea(px, py, ex, ey, 3, BLANCO, a2);
  }
  if (tiro > 0.1f && tiro < 1.8f) {                                   // el confeti revienta donde está el copiloto
    const float t2 = tiro - 0.1f, al = 1 - tramo(t2, 1.1f, 1.7f), bx = W - 30, by = py + sinf(ang) * (W - 30 - px);
    for (int i = 0; i < 30; i++) {
      const float an = PI * (0.55f + 0.9f * hash(i, 1)), v = 120 + 260 * hash(i, 2);   // sale hacia atrás y hacia arriba
      Marco p2 = marco(bx + cosf(an) * v * t2, by + sinf(an) * v * t2 * 0.9f - 120 * t2 + 260 * t2 * t2, t2 * 10 * (hash(i, 3) - 0.5f) + i, 1.2f);
      pieza(p2, -7, -5, 7, 5, solido(COLORES[i % 5], al), [](float a, float b) { return dCaja(a, b, 0, 0, 6, 3.5f, 1); });
    }
    const float fl = 1 - tramo(t2, 0, 0.3f);
    if (fl > 0.01f) chispa(bx, by, 30 * fl + 10, fl, Col{255, 240, 160});
  }
  if (tiro > 0.4f) confeti(tiro - 0.4f, tramo(tiro, 0.4f, 0.7f) * (1 - tramo(k, 4.8f, 5.5f)));   // y llueve confeti
}

// ---------------------------------------------------------------- kamehameha: carga la bola entre las manos y te la lanza
static void kame(float u) {
  ConColor cc;
  const Col azul{110, 190, 255};
  const float entra = tramo(u, 0.1f, 0.5f), fin = 1 - tramo(u, 4.4f, 5.0f);
  const float carga = tramo(u, 0.4f, 2.6f), sale = u - 2.6f;
  const float by = H - 104;
  const float R = (10 + 38 * carga) * (1 + 0.08f * sinf(u * 37));
  if (sale < 0) {
    // carga: junta las manos atrás, a un costado, una encima de la otra con la bola en medio; mientras carga se las lleva
    // más atrás (se ven más chicas, más lejos) y al final las trae de golpe al frente, grandes, para lanzar
    const float lanza = tramo(u, 2.3f, 2.6f), atras = carga * (1 - lanza), sb = subeDe(u, 9, 0.05f);
    const float e = 0.95f - 0.22f * atras + 0.65f * lanza, Rr = R * e;
    const float bx = CX + (100 + 30 * atras) * (1 - lanza) + 3 * carga * sinf(u * 80), by2 = by - 26 * (1 - lanza) + sb;
    for (int i = 0; i < 8; i++) {                                     // la energía llega de todos lados
      const float q = fmodf(u * 1.6f + i / 8.f, 1.f), g = i * 0.785f + u * 2.2f, d0 = Rr + 16 + 80 * (1 - q), d1 = d0 + 14;
      linea(bx + cosf(g) * d0, by2 + sinf(g) * d0 * 0.8f, bx + cosf(g) * d1, by2 + sinf(g) * d1 * 0.8f, 1.8f, azul, entra * carga * campana(q, 0, 1));
    }
    if (Rr > 1) { circulo(bx, by2, Rr + 12, azul, 0.6f * entra, 22); circulo(bx, by2, Rr, BLANCO, entra, 10); circulo(bx - Rr * 0.3f, by2 - Rr * 0.3f, Rr * 0.22f, Col{210, 236, 255}, 0.9f * entra); }
    if (carga > 0.3f) for (int i = 0; i < 3; i++) {                   // chispas eléctricas que saltan de la bola
      const int sem = (int)(u * 18) * 7 + i; const float g = hash(sem, 21) * 6.283f;
      float x0 = bx + cosf(g) * Rr, y0 = by2 + sinf(g) * Rr;
      for (int k2 = 0; k2 < 3; k2++) {
        const float x1 = x0 + cosf(g) * 12 + (hash(sem, 22 + k2) - 0.5f) * 15, y1 = y0 + sinf(g) * 12 + (hash(sem, 25 + k2) - 0.5f) * 15;
        linea(x0, y0, x1, y1, 1.6f, Col{220, 240, 255}, entra * carga);
        x0 = x1; y0 = y1;
      }
    }
    const float a0 = entra * (1 - lanza), a1 = entra * lanza;
    if (a0 > 0.02f) {                                                 // atrás: una mano encima y otra debajo de la bola, con los dedos hacia el frente
      manoBlanca(bx + 10 * e, by2 - Rr - 12 * e, PI + 0.4f, 1.25f * e, false, a0);
      manoBlanca(bx + 10 * e, by2 + Rr + 12 * e, PI - 0.4f, 1.25f * e, false, a0);
    }
    if (a1 > 0.02f) {                                                 // al frente: las dos palmas empujando
      manoBlanca(bx - Rr - 30, by2 + 8, 0.15f, 1.25f, false, a1);
      manoBlanca(bx + Rr + 30, by2 + 8, PI - 0.15f, 1.25f, false, a1);
    }
    return;
  }
  // ¡HA!: la bola sale hacia uno, crece hasta llenar la pantalla y deja anillos y rayas de velocidad
  const float fl = campana(sale, 0, 0.5f);
  if (fl > 0.01f) figura(0, 0, W, H, solido(azul, 0.35f * fl), [](float, float) { return -1.f; });
  const float g = tramo(sale, 0, 0.45f), cy = by + (EY - by) * g, Rg = R * 1.6f + 480 * g * g, a = 1 - tramo(sale, 0.5f, 1.6f);
  if (a > 0.01f) { circulo(CX, cy, Rg + 30, azul, 0.8f * a, 40); circulo(CX, cy, Rg * 0.7f, BLANCO, a, 20); }
  for (int i = 0; i < 2; i++) {
    const float q = tramo(sale, 0.1f + i * 0.25f, 1.1f + i * 0.25f), Ra = 40 + 420 * q;
    if (q > 0 && q < 1) figura(CX - Ra - 8, cy - Ra - 8, CX + Ra + 8, cy + Ra + 8, solido(azul, 0.8f * (1 - q), 10),
                               [=](float px, float py) { return fabsf(dCirculo(px, py, CX, cy, Ra)) - 4; });
  }
  const float lineas = 1 - tramo(sale, 1.2f, 1.8f);
  if (lineas > 0.01f) for (int i = 0; i < 14; i++) {
    const float ang = i * 0.4488f + 0.2f, q = fmodf(sale * 2.2f + hash(i, 3), 1.f), r0 = 80 + 260 * q, r1 = r0 + 70;
    linea(CX + cosf(ang) * r0, cy + sinf(ang) * r0, CX + cosf(ang) * r1, cy + sinf(ang) * r1, 4, BLANCO, lineas * campana(q, 0, 1));
  }
  const float m = fin * (1 - tramo(sale, 0.4f, 0.9f)), sep = 70 + 60 * tramo(sale, 0, 0.3f);   // las manos se abren al soltarla
  if (m > 0.01f) { manoBlanca(CX - sep, by + 8, 0.15f, 1.25f, false, m); manoBlanca(CX + sep, by + 8, PI - 0.15f, 1.25f, false, m); }
  if (sale > 0.9f && sale < 2.1f) for (int i = 0; i < 4; i++) {       // después del rayo le humean las manos
    const float p = (sale - 0.9f) / 1.2f + i * 0.12f;
    if (p < 1) circulo(CX + (i % 2 ? 1 : -1) * (120 + 12 * i), by - 10 - 60 * p, 7 + 12 * p, Col{214, 226, 240}, 0.6f * fin * (1 - p));
  }
}

// ---------------------------------------------------------------- reacciones (anticipación, acción, consecuencia, remate)
static Rostro deReac(float u, const Entradas &e, float &vel) {
  Rostro r = neutro(AZUL);
  vel = 1000;        // las de caricatura van "de golpe"
  switch (laReac) {
    case SALUDO: {
      float abre = tramo(u, 0.05f, 0.35f);
      float s = 0.6f + 0.55f * abre - 0.15f * tramo(u, 0.35f, 0.6f);
      escalar(r, s, s); tapas(r, 1 - abre);
      if (u > 0.6f) { feliz(r, tramo(u, 0.6f, 0.8f)); mover(r, 0, -12 * campana(u, 0.8f, 1.2f)); r.boca = 0.25f; }
      break;
    }
    case BACHE: {                                                    // sale disparada, cae, ¡plaf!, rebota y tiembla
      float sx = 1, sy = 1, dy = 0;
      if (u < 0.12f) { float k = campana(u, 0, 0.24f); sx = 1 + 0.15f * k; sy = 1 - 0.22f * k; dy = 18 * k; }
      else if (u < 0.6f) { float q = (u - 0.12f) / 0.48f; dy = -340 * (1 - (1 - q) * (1 - q)); sx = 0.8f; sy = 1.32f; }
      else if (u < 0.8f) dy = -420;                                  // afuera de la pantalla
      else if (u < 1.05f) { float q = (u - 0.8f) / 0.25f; dy = -340 * (1 - q * q); sx = 0.84f; sy = 1.3f; }
      else if (u < 1.24f) { float k = campana(u, 1.05f, 1.24f); sx = 1 + 0.55f * k; sy = 1 - 0.55f * k; dy = 45 * k; mover(r, 8 * sinf(u * 130) * k, 0); }
      else if (u < 1.64f) { float k = sinf((u - 1.24f) / 0.4f * PI); dy = -75 * k; sy = 1 + 0.1f * k; sx = 1 - 0.06f * k; }
      else if (u < 1.78f) { float k = campana(u, 1.64f, 1.78f); sx = 1 + 0.22f * k; sy = 1 - 0.25f * k; dy = 20 * k; }
      else { float q = u - 1.78f, g = expf(-q * 5) * sinf(q * 32); sx = 1 - 0.08f * g; sy = 1 + 0.1f * g; }
      escalar(r, sx, sy); mover(r, 0, dy);
      break;
    }
    case RISA: {                                                     // carcajada: se echa para atrás, se sacude y se dobla de la risa
      const float k = 1 - tramo(u, 2.6f, 3.0f), dobla = campana(u, 1.3f, 2.5f);
      feliz(r, 1);
      mover(r, 9 * sinf(u * 3.4f), -12 * campana(u, 0, 0.9f) + 14 * dobla + 6 * sinf(u * 44) * k);
      r.boca = (0.7f + 0.25f * fabsf(sinf(u * 14))) * k + 0.15f * (1 - k); r.bAncho = 1.9f;
      break;
    }
    case FRENAZO: {                                                  // se estampa contra el vidrio, lo raja y se escurre
      float aplasta = 0, baja = 0;
      if (u < 0.14f) aplasta = suave(u / 0.14f);
      else if (u < 2.7f) { aplasta = 1; baja = tramo(u, 1.5f, 2.7f); }
      if (u < 2.7f) {
        escalar(r, 1 + 0.75f * aplasta - 0.12f * baja, 1 + 0.35f * aplasta + 0.08f * baja);
        r.L.x -= 34 * aplasta; r.R.x += 34 * aplasta;
        mover(r, 0, 12 * aplasta + 190 * baja);
        if (u > 0.14f && u < 1.5f) mover(r, 2.5f * sinf(u * 95), 2 * cosf(u * 83));
        r.boca = u > 0.1f ? 0.5f : 0;
      } else if (u < 3.15f) {                                        // vuelve de un brinco
        float q = (u - 2.7f) / 0.45f, s = sinf(q * PI);
        mover(r, 0, 202 * (1 - q) - 40 * s); escalar(r, 1 - 0.1f * s, 1 + 0.15f * s);
      } else {                                                       // sacude la cabeza y queda aliviada
        float q = u - 3.15f;
        mover(r, 18 * expf(-q * 4) * sinf(q * 26), 0); feliz(r, 0.9f * tramo(u, 3.4f, 3.6f));
      }
      break;
    }
    case ACELERON:
    case ARRANQUE: {                                                 // la fuerza la aplasta hacia atrás
      float empuje = tramo(u, 0, 0.15f) * (1 - tramo(u, 1.5f, 1.8f));
      escalar(r, 1 - 0.22f * empuje, 1 - 0.3f * empuje); mover(r, 0, -6 * empuje);
      if (empuje > 0.5f) mover(r, 2 * sinf(u * 80), 1.5f * cosf(u * 70));
      if (laReac == ARRANQUE) tapas(r, 0.6f * (1 - tramo(u, 0, 0.2f)));
      if (u > 1.6f) { feliz(r, 0.85f * tramo(u, 1.6f, 1.8f)); mover(r, 0, -8 * campana(u, 1.8f, 2.3f)); }
      break;
    }
    case GIRO_IZQ:
    case GIRO_DER: {
      // caricatura: amaga, sale disparada, se estrella contra el borde y queda pegada temblando
      float lado = laReac == GIRO_IZQ ? -1 : 1;
      if (u < 0.12f) {                                               // amague hacia el otro lado
        float k = campana(u, 0, 0.24f);
        mover(r, -lado * 18 * k, 0); escalar(r, 0.9f, 1.08f);
      } else if (u < 0.3f) {                                         // vuela estirada
        float k = suave((u - 0.12f) / 0.18f);
        mover(r, lado * 190 * k, 0); escalar(r, 1 + 0.6f * k, 1 - 0.25f * k);
      } else {                                                       // pegada al borde, aplastada
        float p = u - 0.3f;
        float tiembla = expf(-p * 4.5f) * sinf(p * 38);              // gelatina que se va calmando
        float ancho = EW * (0.42f + 0.12f * tiembla), alto = EH * (1.32f - 0.14f * tiembla);
        float resbala = 26 * tramo(u, 1.1f, 2.4f);                  // se escurre un poquito
        Ojo &a = lado > 0 ? r.R : r.L, &b = lado > 0 ? r.L : r.R;   // a = la que choca primero
        a.w = ancho; a.h = alto; a.x = lado > 0 ? W - ancho / 2 - 3 : ancho / 2 + 3; a.y = EY + resbala;
        float ancho2 = EW * (0.55f + 0.1f * tiembla);
        b.w = ancho2; b.h = EH * (1.2f - 0.1f * tiembla); b.x = a.x - lado * (ancho / 2 + ancho2 / 2 + 6); b.y = EY + resbala * 0.8f;
        r.bx = b.x - lado * 10; r.by = MY + resbala;
        r.boca = 0.3f * expf(-p * 2);
      }
      break;
    }
    case CARICIA:
      feliz(r, tramo(u, 0, 0.15f));
      mover(r, 0, -6 * fabsf(sinf(u * 6))); r.boca = 0.18f;
      break;
    case WHATSAPP: {
      float pop = campana(u, 0, 0.35f);
      escalar(r, 0.82f + 0.15f * pop, 0.82f + 0.15f * pop); mover(r, 0, 74); mirar(r, 0.1f * sinf(u * 2), -0.5f);
      vel = 30;
      break;
    }
    case LLAMADA: {                                                  // timbra: se asusta y mira el teléfono
      float pop = campana(u, 0, 0.3f);
      escalar(r, 0.78f + 0.2f * pop, 0.78f + 0.2f * pop); mover(r, 0, 46); mirar(r, 0, -0.5f);
      if (fmodf(u, 1.2f) < 0.6f) mover(r, 3 * sinf(u * 50), 0);
      vel = 30;
      break;
    }
    case CALOR: {
      Rostro c = neutro(NARANJA); r = c;
      float cansa = tramo(u, 0, 0.6f) * (1 - tramo(u, 7.0f, 7.5f));
      tapas(r, 0.38f * cansa); r.L.incl = r.R.incl = 0.18f * cansa;
      mover(r, 0, 8 * cansa + 3 * sinf(u * 3));
      { tapas(r, 0.92f * cansa); r.L.alfa = r.R.alfa = 1 - tramo(cansa, 0.75f, 1); }   // se le cierran, y quedan                // los ojos son dos rayitas caídas (se dibujan aparte)
      r.boca = (0.2f + 0.12f * sinf(u * 9)) * cansa;               // jadea, nada de boca brava
      vel = 20;
      break;
    }
    case CINTURON: {
      const float k = tramo(u, 0, 0.3f) * (1 - tramo(u, 5.1f, 5.6f)), q = cinturonQ(u), listo = tramo(u, 2.45f, 2.6f);
      // sigue la lengüeta con la mirada: del hombro (arriba a la derecha) a la hebilla (abajo a la izquierda)
      mirar(r, k * (1 - listo) * (0.4f - 0.75f * q), k * (1 - listo) * (0.15f + 0.45f * q));
      escalar(r, 1 - 0.2f * k, 1 - 0.2f * k); mover(r, -9 * k * q * (1 - listo), -34 * k + 5 * k * q * (1 - listo));          // se inclina con el jalón
      const float pop = campana(u, 2.45f, 2.8f);
      escalar(r, 1 + 0.08f * pop, 1 + 0.1f * pop); mover(r, 0, -9 * pop);            // brinquito con el clic
      feliz(r, listo * (1 - tramo(u, 3.3f, 3.6f)));
      if (u > 3.6f && u < 5.0f) {                                                    // guiño de "listo, vámonos"
        const float g = tramo(u, 3.6f, 3.75f) * (1 - tramo(u, 4.7f, 5.0f));
        r.R.feliz = g; r.L.incl = -0.06f * g; mover(r, 0, -4 * g);
      }
      vel = 30;
      break;
    }
    case SONROJO: {                                                  // se sorprende, se pone rojo, se tapa, espía con un ojo y se vuelve a esconder
      const float fin = 1 - tramo(u, 4.1f, 4.6f), k = tramo(u, 0, 0.25f) * fin, pena = tramo(u, 0.7f, 1.0f) * fin, espia = campana(u, 2.5f, 3.3f);
      escalar(r, 1 + 0.12f * k * (1 - pena), 1 + 0.14f * k * (1 - pena));           // primero abre los ojos: "¿yo?"
      feliz(r, pena);
      if (espia > 0.3f) { r.R.feliz = 0; r.R.tapa = 0.3f; }                         // espía con uno
      mover(r, 7 * pena * sinf(u * 4.2f), -8 * k + 6 * pena); mirar(r, 0.25f * espia, 0);
      r.L.incl = r.R.incl = fmaxf(0, 0.06f * pena * sinf(u * 4.2f));
      r.boca = 0.09f * pena; r.bAncho = 0.8f;
      vel = 30;
      break;
    }
    case ENOJO: {                                                    // se va llenando de rabia, aprieta los dientes, estalla y resopla
      Rostro c = neutro(ROJO); r = c;
      { const Rostro n0 = neutro(AZUL); const float tinta = tramo(u, 0.1f, 1.5f) * (1 - tramo(u, 3.6f, 4.4f)); r.a = mezcla(n0.a, c.a, tinta); r.b = mezcla(n0.b, c.b, tinta); }
      const float k = tramo(u, 0, 0.3f) * (1 - tramo(u, 3.9f, 4.4f)), carga = tramo(u, 0.2f, 2.0f), pum = campana(u, 2.0f, 2.5f), calma = tramo(u, 2.9f, 3.6f);
      tapas(r, (0.22f + 0.2f * carga) * k * (1 - 0.5f * calma)); r.L.incl = r.R.incl = (0.22f + 0.2f * carga) * k * (1 - 0.6f * calma);
      escalar(r, 1 + 0.12f * pum, 1 - 0.1f * carga * (1 - calma) + 0.12f * pum);
      mover(r, (2 + 3.5f * carga) * k * (1 - calma) * sinf(u * 52), 8 * k - 14 * pum);            // tiembla cada vez más
      r.boca = k * (pum > 0.05f ? 0.5f * pum : 0); r.bAncho = 1.5f;   // la boca brava se dibuja aparte (un arco hacia abajo)
      vel = 30;
      break;
    }
    case PAPAS: {                                                    // come papas: abre cuando llega la papa y mastica contento
      const float k = tramo(u, 0, 0.3f) * (1 - tramo(u, 4.5f, 5.0f)), p = fmodf(u, 1.5f) / 1.5f;      // cada 1,5 s una papa
      mover(r, 0, -22 * k); mirar(r, -0.18f * k, 0.25f * k * (1 - p));
      feliz(r, k * tramo(p, 0.55f, 0.7f));                                                            // cierra los ojos al saborear
      r.boca = k * (p < 0.5f ? 0.45f * tramo(p, 0.25f, 0.5f) : 0.12f + 0.1f * fabsf(sinf(u * 14)));   // abre... y mastica
      vel = 30;
      break;
    }
    case BEBIDA: {                                                   // toma con pitillo: ojos relajados y la boquita chiquita
      const float k = tramo(u, 0, 0.3f) * (1 - tramo(u, 4.5f, 5.0f));
      mover(r, 0, -40 * k + 3 * k * sinf(u * 5)); tapas(r, 0.22f * k); mirar(r, 0, 0.3f * k);
      r.boca = 0.1f * k; r.bAncho = 0.7f;
      vel = 30;
      break;
    }
    case CONSOLA: {                                                  // juega: concentrado, se inclina con la jugada
      const float k = tramo(u, 0, 0.3f) * (1 - tramo(u, 4.5f, 5.0f)), lado = sinf(u * 2.6f);
      mover(r, 9 * k * lado, -36 * k); mirar(r, 0.12f * k * lado, 0.55f * k);
      tapas(r, 0.18f * k); r.L.incl = r.R.incl = 0.12f * k + 0.08f * k * lado;
      vel = 30;
      break;
    }
    case LEEMAPA: {                                                  // lee un mapa de papel: los ojos van de un lado a otro por encima
      const float k = tramo(u, 0, 0.3f) * (1 - tramo(u, 4.0f, 4.5f));
      escalar(r, 1 - 0.2f * k, 1 - 0.2f * k); mover(r, 0, -50 * k); mirar(r, 0.4f * k * sinf(u * 2.2f), 0.5f * k);
      vel = 30;
      break;
    }
    case CONO: {                                                     // lame un cono de helado: se lo acerca, lengüetazo, y saborea con los ojos cerrados
      const float k = tramo(u, 0, 0.3f) * (1 - tramo(u, 5.1f, 5.6f)), p = fmodf(u, 1.7f) / 1.7f, cerca = tramo(p, 0.15f, 0.4f) * (1 - tramo(p, 0.62f, 0.8f));
      escalar(r, 1 - 0.2f * k, 1 - 0.2f * k); mover(r, 0, -58 * k + 4 * k * cerca);
      mirar(r, 0.12f * k * (1 - cerca), 0.5f * k * (1 - tramo(p, 0.6f, 0.75f)));
      feliz(r, k * tramo(p, 0.62f, 0.75f));                                          // mmm...
      r.boca = k * (0.1f + 0.2f * cerca); r.bAncho = 0.9f;
      vel = 30;
      break;
    }
    case CRISPETAS: {                                                // se lanza crispetas a la boca: la sigue con los ojos, la atrapa y mastica (la tercera le pega en la frente)
      const float k = tramo(u, 0, 0.3f) * (1 - tramo(u, 5.1f, 5.6f)), p = fmodf(u, 1.4f) / 1.4f, q = lim((p - 0.2f) / 0.35f, 0, 1);
      const bool falla = (int)(u / 1.4f) % 3 == 2, vuela = p > 0.2f && p < 0.55f;
      mover(r, 0, -30 * k);
      if (vuela) mirar(r, -0.3f * k * (1 - q), k * (0.3f * q - 0.55f * sinf(q * PI)));
      if (falla) {                                                                   // ¡ay!: cierra los ojos con el golpe
        if (p > 0.55f) { tapas(r, 0.85f * (1 - tramo(p, 0.7f, 0.95f))); mover(r, 0, 7 * campana(p, 0.55f, 0.75f)); }
        r.boca = k * 0.45f * tramo(p, 0.3f, 0.5f) * (1 - tramo(p, 0.75f, 0.95f)); r.bAncho = 0.9f;
      } else {
        feliz(r, k * tramo(p, 0.6f, 0.7f) * (1 - tramo(p, 0.92f, 1)));
        r.boca = k * (p < 0.55f ? 0.5f * tramo(p, 0.3f, 0.5f) : 0.12f + 0.1f * fabsf(sinf(u * 15)));
      }
      vel = 30;
      break;
    }
    case FELIZ: {                                                    // feliz: brinca con las manos arriba
      const float k = tramo(u, 0, 0.2f) * (1 - tramo(u, 3.2f, 3.6f)), br = fabsf(sinf(u * 6.5f));
      feliz(r, k); mover(r, 6 * k * sinf(u * 3.25f), -8 * k - 16 * k * br); escalar(r, 1 + 0.05f * k * br, 1 + 0.05f * k * (1 - br));
      r.boca = 0.36f * k + 0.1f * k * br; r.bAncho = 1.7f;
      vel = 30;
      break;
    }
    case TRISTE: {                                                   // triste: baja la mirada, puchero, se le salen las lágrimas y al final se las seca
      const float k = tramo(u, 0, 0.6f) * (1 - tramo(u, 4.5f, 5.0f)), seca = campana(u, 3.3f, 4.3f);
      tapas(r, 0.3f * k); r.L.incl = r.R.incl = -0.3f * k;                            // cejas caídas
      mirar(r, -0.1f * k, 0.45f * k); mover(r, 0, 22 * k + 2 * k * sinf(u * 2)); escalar(r, 0.94f, 0.88f);
      if (seca > 0.3f) r.L.tapa = 0.9f;
      r.boca = 0.09f * k; r.bAncho = 1.3f; r.bx += 1.6f * k * sinf(u * 40);          // le tiembla la boquita
      vel = 20;
      break;
    }
    case SUENO: {                                                    // le da sueño: se le caen los párpados, bosteza grande y cabecea
      const float k = tramo(u, 0, 0.5f) * (1 - tramo(u, 5.0f, 5.5f)), bos = campana(u, 1.0f, 3.0f);
      const float cab = u > 3.2f && u < 5.0f ? powf(fmodf(u - 3.2f, 0.9f) / 0.9f, 2) : 0;   // se va cayendo... y se despierta de un brinco
      tapas(r, fminf(0.9f, k * (0.45f + 0.35f * bos + 0.45f * cab)));
      mover(r, 0, -10 * bos * k + 20 * cab * k); escalar(r, 1, 1 + 0.06f * bos);
      r.L.incl = r.R.incl = -0.08f * k;
      r.boca = k * 0.75f * bos; r.bAncho = 0.75f;
      vel = 25;
      break;
    }
    case PPT_PIEDRA: case PPT_PAPEL: case PPT_TIJERA: {              // piedra, papel o tijera: cuenta con el puño, ¡y saca la suya!
      const float k = tramo(u, 0, 0.3f) * (1 - tramo(u, 4.5f, 5.0f)), sale = tramo(u, 2.3f, 2.45f), pop = campana(u, 2.3f, 2.8f);
      const float bate = u > 0.5f && u < 2.3f ? fabsf(sinf((u - 0.5f) / 0.6f * PI)) : 0;         // tres sacudidas
      escalar(r, (1 - 0.22f * k) * (1 + 0.1f * pop), (1 - 0.22f * k) * (1 + 0.12f * pop)); mover(r, 0, -62 * k - 6 * bate - 8 * pop);
      mirar(r, 0, k * (0.55f - 0.25f * bate) * (1 - sale));                                      // mira el puño mientras cuenta
      if (sale < 0.5f) tapas(r, 0.18f * k); else feliz(r, tramo(u, 2.9f, 3.1f) * (1 - tramo(u, 4.2f, 4.5f)));
      r.boca = k * (sale > 0.5f ? 0.3f : 0.1f + 0.12f * bate); r.bAncho = sale > 0.5f ? 1.5f : 0.9f;
      vel = 30;
      break;
    }
    case SORPRESA: {
      float pop = tramo(u, 0, 0.1f) * (1 - tramo(u, 1.6f, 2.0f));
      escalar(r, 1 + 0.25f * pop, 1 + 0.28f * pop); mover(r, 0, -10 * pop);
      r.boca = 0.55f * pop; r.bAncho = 0.95f;
      break;
    }
    case AMOR: {
      Rostro c = neutro(ROSA); r = c;
      r.corazon = 1; float late = campana(fmodf(u, 0.8f), 0, 0.25f);
      escalar(r, 1 + 0.12f * late, 1 + 0.12f * late);
      r.boca = 0.15f;
      vel = 40;
      break;
    }
    case MAREO:                                                      // ojos en espiral y la cara se tambalea
      r.L.alfa = r.R.alfa = 0;
      mover(r, 18 * sinf(u * 5), 6 * sinf(u * 7));
      vel = 30;
      break;
    case MAPS_RECTO:                                                 // sigue derecho: se asoma por encima del volante
    case MAPS_IZQ:
    case MAPS_DER: {                                                 // mira hacia donde hay que girar
      r.boca = 0; vel = 20;   // la cara no se dibuja: va la pantalla de tablero
      break;
    }
    case LLEGAMOS:                                                   // feliz, salta y llueve confeti
      feliz(r, 1); mover(r, 0, -16 * fabsf(sinf(u * 6)));
      r.boca = 0.3f;
      break;
    case SOL: {                                                      // el sol lo deslumbra, se hace visera con la mano,
      Rostro c = neutro(ORO); r = c;                                 // le caen unas gafas oscuras y queda relajado
      float desl = tramo(u, 0.3f, 0.7f) * (1 - tramo(u, 3.5f, 3.9f));
      tapas(r, 0.62f * desl * (1 - 0.45f * tramo(u, 1.8f, 2.2f)));
      escalar(r, 1 - 0.1f * desl, 1 - 0.1f * desl); mover(r, 0, 12 * desl);
      if (u > 1.8f && u < 3.5f) mirar(r, -0.55f, -0.15f);           // con la visera mira a lo lejos
      if (u > 3.7f) {                                                // con gafas: más bajitos para que quepan detrás, y se mece
        escalar(r, 1, 0.6f);
        mover(r, 8 * sinf((u - 3.7f) * 2.4f), 4 * sinf((u - 3.7f) * 4.8f));
      }
      vel = 14;
      break;
    }
    case LLUVIA:                                                     // mira las gotas, entrecierra con el empañado,
      if (u < 3.3f) {                                                // sigue al limpiaparabrisas y se alegra al ver limpio
        mirar(r, 0.3f * sinf(u * 1.7f), -0.3f);
        tapas(r, fmaxf(parpadeo(u), 0.3f * tramo(u, 1.5f, 3.0f)));
      } else if (u < 5.0f) mirar(r, limpiaAng(u) / 1.45f * 0.9f, -0.1f);
      else { feliz(r, tramo(u, 5.0f, 5.3f)); mover(r, 0, -6 * campana(u, 5.0f, 5.8f)); }
      vel = 12;
      break;
    case FRIO: {
      Rostro c = neutro(HIELO); r = c;
      escalar(r, 0.9f, 0.88f); mover(r, 3.5f * sinf(u * 70), 4);      // tirita
      tapas(r, 0.2f);
      r.boca = 0;   // la boca es una rayita ondulada (va aparte)
      vel = 40;
      break;
    }
    case BATERIA:                                                    // cansada, se le cierran los ojos
      tapas(r, 0.45f + 0.15f * sinf(u * 1.5f)); r.L.incl = r.R.incl = -0.12f;
      escalar(r, 0.9f, 0.86f); mover(r, 0, 54);
      vel = 8;
      break;
    case GUINO: {                                                    // guiña, sonríe de lado y suelta una estrella
      const float g = tramo(u, 0.15f, 0.3f) * (1 - tramo(u, 1.3f, 1.5f));
      if (g > 0.5f) { r.R.feliz = 1; r.R.y -= 6; }
      r.L.incl = -0.05f * g; mover(r, 5 * g, -5 * campana(u, 0.1f, 1.4f));
      r.boca = 0.12f * g; r.bAncho = 1.5f; r.bx += 10 * g;
      vel = 25;
      break;
    }
    case KAME: {                                                     // concentra energía en las manos y te la lanza
      float conc = tramo(u, 0, 0.3f), carga = tramo(u, 0.4f, 2.6f), rayo = tramo(u, 2.6f, 2.75f) * (1 - tramo(u, 3.9f, 4.4f));
      float orgullo = tramo(u, 4.2f, 4.5f) * (1 - tramo(u, 4.7f, 5.0f));
      tapas(r, (0.3f * conc + 0.35f * rayo) * (1 - orgullo)); r.L.incl = r.R.incl = 0.2f * conc * (1 - orgullo);
      { const float lz = tramo(u, 2.3f, 2.6f); mirar(r, 0.5f * conc * (1 - lz), 0.4f * conc * (1 - rayo)); mover(r, -24 * conc * (1 - lz), 0); }   // mira la bola, atrás a un costado; al soltarla, al frente
      escalar(r, (1 - 0.1f * conc) * (1 - 0.12f * rayo), (1 - 0.22f * conc) * (1 - 0.12f * rayo));
      mover(r, 4 * carga * (1 - rayo) * sinf(u * 90), -46 * conc - 12 * rayo);
      feliz(r, orgullo);
      r.boca = 0.8f * rayo; r.bAncho = 1.2f;
      vel = 40;
      break;
    }
    case PUM: {                                                      // te apunta de frente con cara de malo, cuenta 3 2 1,
      float k = pumK(u), malo = tramo(u, 0, 0.4f) * (k < 3 ? 1 : 1 - tramo(k, 3.0f, 3.2f));   // ¡pum! y se muere de la risa
      float risa = k < 0 ? 0 : tramo(k, 3.05f, 3.3f) * (1 - tramo(k, 5.2f, 5.6f));
      tapas(r, 0.4f * malo); r.L.incl = r.R.incl = 0.22f * malo;
      mirar(r, (giroForma == 1 ? 0 : 0.75f) * malo, 0.1f * malo);     // mira al copiloto, a quien le apunta (o derecho, a los de atrás)
      mover(r, 3 * sinf(u * 2.3f) * malo, (giroForma == 1 ? -46 : -14) * malo);
      if (k > 3.0f && k < 3.15f) escalar(r, 1.08f, 0.9f);           // el culatazo
      feliz(r, risa); mover(r, 0, 7 * sinf(u * 40) * risa);
      r.boca = risa * (0.6f + 0.3f * fabsf(sinf(u * 14))); r.bAncho = 1.7f;
      vel = 30;
      break;
    }
    case CURIOSA: {                                                  // ladea la cabeza y abre un ojo más que el otro
      float k = tramo(u, 0, 0.3f) * (1 - tramo(u, 2.0f, 2.4f));
      r.L.y += 14 * k; r.R.y -= 14 * k; r.R.w *= 1 + 0.12f * k; r.R.h *= 1 + 0.12f * k; r.L.h *= 1 - 0.1f * k;
      mirar(r, 0.35f * k, -0.3f * k); r.L.tapa = 0.25f * k;
      vel = 14;
      break;
    }
    default: break;
  }
  return r;
}

// ---------------------------------------------------------------- mapa: las calles de alrededor y la ruta (las manda el celular)
// Las calles llegan en metros desde un punto de origen, de a 3 m (un byte por coordenada: 128 = el origen), y la posición
// llega aparte cada segundo. Hay dos juegos: uno se dibuja mientras el otro se carga, y al terminar se cambian.
// Clases: 0 calle, 1 avenida, 2 la ruta.
namespace {
constexpr int MAX_VIAS = 160, MAX_PUNTOS = 900;
constexpr float MAPA_M = 3;                                           // metros por unidad
struct Calles { uint8_t x[MAX_PUNTOS], y[MAX_PUNTOS]; uint16_t ini[MAX_VIAS + 1]; uint8_t clase[MAX_VIAS]; int nv = 0, np = 0; };
Calles callesA, callesB, *callesHay = &callesA, *callesCarga = &callesB;
float mapaT = -100, mpx = 0, mpy = 0, mrumbo = 0, mtx = 0, mty = 0, mtr = 0, mapaTU = 0;
bool mapaSalta = true;
int hexv(char c) { return c <= '9' ? c - '0' : (c | 32) - 'a' + 10; }
}
// w: tramos separados por punto; cada uno es su clase ('0', '1' o '2') y luego 4 letras hex por punto (x, y)
void mapaVias(const char *w, bool primero, bool fin) {
  Calles &c = *callesCarga;
  if (primero) { c.nv = 0; c.np = 0; }
  while (*w && c.nv < MAX_VIAS) {
    c.clase[c.nv] = lim(*w++ - '0', 0, 2); c.ini[c.nv] = c.np;
    while (*w && *w != '.') {
      if (!w[1] || !w[2] || !w[3]) { w += strlen(w); break; }
      if (c.np < MAX_PUNTOS) { c.x[c.np] = hexv(w[0]) * 16 + hexv(w[1]); c.y[c.np] = hexv(w[2]) * 16 + hexv(w[3]); c.np++; }
      w += 4;
    }
    if (*w == '.') w++;
    if (c.np - c.ini[c.nv] >= 2) c.nv++; else c.np = c.ini[c.nv];   // un tramo de un solo punto no se dibuja
  }
  c.ini[c.nv] = c.np;
  if (fin) { Calles *t = callesHay; callesHay = callesCarga; callesCarga = t; mapaSalta = true; }   // cambió el origen: la posición salta
}
// dónde va el carro: metros al este y al norte del origen, y el rumbo en grados (0 = norte, a la derecha crece)
void mapaPos(float x, float y, float rumbo) {
  mtx = x; mty = y; mtr = rumbo;
  if (mapaSalta || tAhora - mapaT > 8) { mpx = x; mpy = y; mrumbo = rumbo; mapaSalta = false; }
  mapaT = tAhora;
}
void mapaVista(bool) {}   // ya no se usa (la indicación va siempre en pantalla de tablero); se deja porque la app vieja lo manda
bool mapaHay() { return tAhora - mapaT < 8 && callesHay->nv > 0; }
static bool soloMapa() { return esMaps(laReac); }   // la indicación va siempre en pantalla de tablero, sin el muñequito
// Las calles con el rumbo hacia arriba y el carro en (X, Y0), en pixeles de pantalla; s = pixeles por metro. Se pintan
// punto a punto (barato). RR > 0: solo dentro del círculo de centro (X, Yc); si no, toda la pantalla.
// persp: como vista desde atrás del carro (lo de adelante se aleja hacia un horizonte y se adelgaza), con la paleta del tablero.
static void pintarCalles(float X, float Yc, float Y0, float s, float RR, float a, float grosor, bool persp = false) {
  const float k = lim((tAhora - mapaTU) * 2.5f, 0, 1);
  mapaTU = tAhora;
  mpx += (mtx - mpx) * k; mpy += (mty - mpy) * k;                    // entre un aviso y otro se desliza
  mrumbo += (fmodf(mtr - mrumbo + 540, 360) - 180) * k;
  const float h = mrumbo * PI / 180, ch = cosf(h), sh = sinf(h);
  const float x0 = RR > 0 ? X - RR : 0, x1 = RR > 0 ? X + RR : W - 1, y0 = RR > 0 ? Yc - RR : 0, y1 = RR > 0 ? Yc + RR : H - 1;
  const Calles &c = *callesHay;
  static const Col COLOR[3] = {{105, 125, 175}, {235, 240, 255}, {70, 165, 255}};   // calle, avenida, la ruta (el azul de Cubi)
  for (int pasada = 0; pasada < 3; pasada++) for (int v = 0; v < c.nv; v++) {      // calles, encima avenidas, encima la ruta
    if (c.clase[v] != pasada) continue;
    static const Col TABLERO[3] = {{46, 60, 100}, {86, 106, 156}, {50, 150, 255}};   // calle, avenida, la ruta
    static const Col TAB_DIA[3] = {{204, 208, 212}, {178, 184, 194}, {52, 140, 250}};   // de día: calles grises sobre el fondo claro
    const Col col = persp ? (mapaDeDia ? TAB_DIA[pasada] : TABLERO[pasada]) : COLOR[pasada];
    const int g = (int)(grosor * (pasada == 2 ? 2.2f : pasada == 1 ? 1.5f : 1) + 0.5f);
    float ax = 0, ay = 0;
    for (int i = c.ini[v]; i < c.ini[v + 1]; i++) {
      const float dx = (c.x[i] - 128) * MAPA_M - mpx, dy = (c.y[i] - 128) * MAPA_M - mpy;
      float bx = X + s * (dx * ch - dy * sh), by = Y0 - s * (dx * sh + dy * ch), lejos = 1;
      if (persp && by < Y0) { const float d = Y0 - by; lejos = 250 / (250 + d); bx = X + (bx - X) * lejos; by = Y0 - d * lejos; }
      if (i > c.ini[v] && !(fminf(ax, bx) > x1 || fmaxf(ax, bx) < x0 || fminf(ay, by) > y1 || fmaxf(ay, by) < y0)) {
        const int n = (int)fmaxf(fabsf(bx - ax), fabsf(by - ay)) + 1;
        const int gs = persp ? (int)fmaxf(1, g * lejos + 0.5f) : g, g0 = -(gs / 2), g1 = g0 + gs - 1, paso = gs > 3 ? gs / 2 : 1;   // gruesa: no hace falta pintar cada punto
        for (int j = 0; j <= n; j += paso) {
          const int qx = (int)(ax + (bx - ax) * j / n), qy = (int)(ay + (by - ay) * j / n);
          if (qx < x0 - 4 || qx > x1 + 4 || qy < y0 - 4 || qy > y1 + 4) continue;
          for (int oy = g0; oy <= g1; oy++) for (int ox = g0; ox <= g1; ox++) {
            const int ex = qx + ox, ey = qy + oy;
            if (ex < 0 || ex >= W || ey < 0 || ey >= H) continue;
            if (RR > 0) { const float ddx = ex - X, ddy = ey - Yc; if (ddx * ddx + ddy * ddy >= RR * RR) continue; }
            dib::mezclar(dib::idx(ex, ey), col, a);
          }
        }
      }
      ax = bx; ay = by;
    }
  }
}
// el carro, como el marcador de navegación de Google: un halo azul clarito que late, un disco blanco y adentro la
// flecha azul con la muesca abajo, mirando hacia arriba
static void carrito(float cx, float cy, float e, float a) {
  const float late = 0.5f + 0.5f * sinf(tAhora * 2.4f);
  figura(cx - 30 * e, cy - 30 * e, cx + 30 * e, cy + 30 * e, solido(Col{70, 150, 255}, (0.16f + 0.1f * late) * a), [=](float px, float py) { return dCirculo(px, py, cx, cy, (21 + 5 * late) * e); });
  figura(cx - 16 * e, cy - 16 * e, cx + 16 * e, cy + 16 * e, solido(BLANCO, a), [=](float px, float py) { return dCirculo(px, py, cx, cy, 13.5f * e); });
  figura(cx - 12 * e, cy - 12 * e, cx + 12 * e, cy + 12 * e, solido(Col{26, 115, 232}, a), [=](float px, float py) {
    const float tx = cx, ty = cy - 8.5f * e, my = cy + 2.5f * e;                       // la punta y la muesca
    return fminf(dTriangulo(px, py, tx, ty, cx - 7 * e, cy + 6.5f * e, cx, my), dTriangulo(px, py, tx, ty, cx, my, cx + 7 * e, cy + 6.5f * e)) - 0.8f * e;
  });
}
// la flecha del giro en un disco azul: lado -1 izquierda, 1 derecha, 0 derecho. La forma (giroForma) la manda el celular:
// 0 giro normal, 1 curva suave, 2 curva cerrada, 3 en U, 4 glorieta, 5 salida o mantenerse a un lado
static void discoGiro(float dx, float ty, float R, float lado, float u, float a) {
  const float e = R / 44, g = 4.6f * e, vai = 4 * sinf(tAhora * 5), L = lado;
  const int f = lado == 0 && giroForma != 4 ? 0 : giroForma;            // sin lado solo hay derecho y glorieta
  const float ox = dx + L * vai, oy = ty - (L == 0 ? vai : 0);          // empuja hacia donde va
  {
    ConColor cc;
    figura(dx - R - 18, ty - R - 18, dx + R + 18, ty + R + 18, degr(AZUL, ty - R, ty + R, 14, a), [=](float px, float py) { return dCirculo(px, py, dx, ty, R); });
  }
  if (f == 5)                                                           // la otra rama de la bifurcación, apagada
    figura(dx - R, ty - R, dx + R, ty + R, solido(BLANCO, 0.4f * a), [=](float px, float py) { return dSegmento(px, py, ox, oy + 6 * e, ox - L * 15 * e, oy - 16 * e, g); });
  figura(dx - R, ty - R, dx + R, ty + R, solido(BLANCO, a), [=](float px, float py) {
    auto S = [=](float x0, float y0, float x1, float y1) { return dSegmento(px, py, ox + x0 * e, oy + y0 * e, ox + x1 * e, oy + y1 * e, g); };
    auto P = [=](float x, float y, float ux, float uy, float k) {       // la punta en (x, y) mirando hacia (ux, uy)
      const float tx = ox + x * e, tyy = oy + y * e; k *= e;
      return fminf(dSegmento(px, py, tx, tyy, tx - k * (ux - uy), tyy - k * (uy + ux), g), dSegmento(px, py, tx, tyy, tx - k * (ux + uy), tyy - k * (uy - ux), g));
    };
    switch (f) {
      case 1: return fminf(fminf(S(-L * 8, 24, -L * 8, 2), S(-L * 8, 2, L * 14, -20)), P(L * 14, -20, L * 0.707f, -0.707f, 13));      // curva suave
      case 2: return fminf(fminf(S(-L * 10, 24, -L * 10, -14), S(-L * 10, -14, L * 14, 10)), P(L * 14, 10, L * 0.707f, 0.707f, 13));  // curva cerrada
      case 3: {                                                                                                                      // en U: sube, da la vuelta y baja
        const float cy = oy - 6 * e;
        float d = py <= cy ? fabsf(hypotf(px - ox, py - cy) - 14 * e) - g : 1e3f;
        d = fminf(d, fminf(S(-L * 14, 24, -L * 14, -6), S(L * 14, -6, L * 14, 18)));
        return fminf(d, P(L * 14, 20, 0, 1, 11));
      }
      case 4: {                                                                                                                      // glorieta: entra por abajo y sale
        const float cx = -L * 7;
        float d = fabsf(hypotf(px - ox - cx * e, py - oy + 2 * e) - 12 * e) - g * 0.85f;
        d = fminf(d, S(cx, 26, cx, 10));
        if (L == 0) return fminf(d, fminf(S(0, -14, 0, -29), P(0, -30, 0, -1, 9)));
        return fminf(d, fminf(S(cx + L * 12, -2, L * 25, -2), P(L * 26, -2, L, 0, 9)));
      }
      case 5: return fminf(fminf(S(0, 24, 0, 6), S(0, 6, L * 15, -16)), P(L * 15, -16, L * 0.707f, -0.707f, 12));                      // salida / mantente a ese lado
    }
    if (L == 0) return fminf(S(0, 24, 0, -22), P(0, -22, 0, -1, 14));                                                                // derecho
    return fminf(fminf(S(-L * 13, 24, -L * 13, -6), S(-L * 13, -6, L * 20, -6)), P(L * 20, -6, L, 0, 13));                            // sube y dobla
  });
}
// La indicación de Maps con la estética del tablero del carro (sin el muñequito): el mapa ampliado y en perspectiva en toda
// la pantalla (claro de día, oscuro de noche), el carro al centro, la velocidad grande a la izquierda y arriba la tarjeta
// oscura con la flecha grande, los metros y la calle. Sin calles del celular se ve una carretera que avanza.
static void pantallaMapa(float lado, float u, float a) {
  dib::SinZoom sz;
  ConColor cc;   // el mapa conserva sus colores en las caras blancas
  const bool dia = mapaDeDia;
  { Pintura f; f.arriba = dia ? Col{240, 242, 240} : Col{12, 22, 52}; f.abajo = dia ? Col{224, 228, 225} : Col{4, 7, 16}; f.y0 = 0; f.y1 = H; f.alfa = a;
    figura(0, 0, W, H, f, [](float, float) { return -1.f; }); }
  const float cy = 304;
  if (mapaHay()) pintarCalles(CX, 0, cy, 1.5f, 0, a, 5, true);
  else {                                                                                                  // sin mapa: una carretera recta con sus rayas corriendo
    for (int s = -1; s <= 1; s += 2) linea(CX + s * 150, H + 10, CX + s * 14, 96, 2.2f, dia ? Col{176, 182, 190} : Col{78, 96, 140}, a);
    for (int i = 0; i < 6; i++) {
      const float p = fmodf(i / 6.f + tAhora * 0.35f, 1.f), q = p * p, y0 = 100 + (H - 90) * q, y1 = y0 + 10 + 34 * q;
      linea(CX, y0, CX, y1, 1 + 3.5f * q, dia ? Col{150, 158, 170} : Col{200, 214, 245}, a * tramo(p, 0, 0.15f));
    }
  }
  carrito(CX, cy, 1.7f, a);
  if (mapaKmh >= 0) {                                                                                     // la velocidad, grande, a la izquierda (como en el tablero)
    char v[8]; snprintf(v, sizeof v, "%d", mapaKmh);
    const Col tinta = dia ? Col{28, 32, 42} : Col{255, 255, 255};
    texto(v, 70, 206, 76, tinta, a); texto("km/h", 70, 250, 19, dia ? Col{96, 104, 118} : Col{170, 184, 214}, a);
  }
  // la tarjeta de arriba (entra bajando): flecha grande, metros y calle
  const bool hayM = tAhora < gradosHasta && gradosM, ya = hayM && gradosN <= 60;
  const float by = 64 - 150 * (1 - tramo(u, 0.05f, 0.5f)), bw = 206, bh = 54;
  figura(CX - bw - 4, by - bh - 4, CX + bw + 4, by + bh + 4, solido(dia ? Col{32, 38, 52} : Col{10, 16, 34}, 0.96f * a), [=](float px, float py) { return dCaja(px, py, CX, by, bw, bh, 26); });
  if (!dia) figura(CX - bw - 4, by - bh - 4, CX + bw + 4, by + bh + 4, solido(Col{60, 100, 190}, 0.9f * a), [=](float px, float py) { return fabsf(dCaja(px, py, CX, by, bw, bh, 26)) - 1.3f; });
  discoGiro(CX - bw + 62, by, 46 + (ya ? 4 * fabsf(sinf(tAhora * 7)) : 0), lado, u, a);
  const float xt = CX + 58;
  if (hayM) {
    char b[16];
    if (gradosN >= 1000) snprintf(b, sizeof b, "%d,%d km", gradosN / 1000, gradosN % 1000 / 100); else snprintf(b, sizeof b, "%d m", gradosN);
    texto(b, xt, calleTxt[0] ? by - 14 : by, 52, BLANCO, a);
  }
  if (calleTxt[0]) { char corta[22]; snprintf(corta, sizeof corta, "%.19s", calleTxt); texto(corta, xt, hayM ? by + 28 : by, 22, Col{176, 196, 236}, a); }
  if (hayM) {                                                                                            // abajo: la barrita que se llena al acercarse al giro
    const float lleno = 1 - lim(gradosN / 300.f, 0, 1), bw2 = 150, yb = H - 12;
    figura(CX - bw2 - 4, yb - 6, CX + bw2 + 4, yb + 6, solido(dia ? Col{196, 202, 210} : Col{40, 52, 84}, a), [=](float px, float py) { return dCaja(px, py, CX, yb, bw2, 3, 3); });
    if (lleno > 0.02f) figura(CX - bw2 - 4, yb - 6, CX + bw2 + 4, yb + 6, solido(Col{52, 140, 250}, a), [=](float px, float py) { return dCaja(px, py, CX - bw2 + bw2 * lleno, yb, bw2 * lleno, 3.5f, 3.5f); });
  }
}

// la luz del borde en cada reacción
static void luzReac(float u) {
  int donde = MARCO; Col c{255, 214, 70}; float f = 0.4f, ancho = 64, radio = 44;
  switch (laReac) {
    case ENOJO: {                                                   // el rojo va subiendo con la rabia, pega un salto con el estallido y baja al calmarse
      const float carga = tramo(u, 0.2f, 2.0f), pum = campana(u, 2.0f, 2.6f), calma = tramo(u, 3.0f, 4.2f);
      donde = ABAJO; c = Col{200, 14, 36}; f = (0.5f + 0.4f * carga) * (1 - calma) / fmaxf(0.01f, tramo(u, 0, 0.4f));
      ancho = (30 + 170 * carga + 70 * pum) * (1 - 0.8f * calma); if (f > 2) f = 2; break;
    }      // rojo que sube
    case CALOR: donde = ARRIBA; c = Col{196, 14, 30}; f = 0.92f + 0.05f * sinf(u * 3); ancho = 20 + 190 * tramo(u, 0.2f, 2.2f) * (1 - tramo(u, 6.3f, 7.5f)); break;           // el calor baja de arriba
    case FRIO: c = Col{64, 196, 244}; f = 1; ancho = 46; radio = 92; break;                                                  // marco de hielo
    case SONROJO: donde = OVALO_LUZ; c = Col{255, 92, 152}; f = 0.6f * tramo(u, 0.6f, 1.4f); ancho = 170; break;                       // se pone rosita
    case AMOR: case CARICIA: c = Col{255, 92, 152}; donde = OVALO_LUZ; f = 0.5f; ancho = 150; break;
    case TRISTE: donde = ARRIBA; c = Col{44, 96, 210}; f = 0.55f; ancho = 230; break;
    case SUENO: donde = ARRIBA; c = Col{110, 80, 220}; f = 0.4f; ancho = 230; break;
    case SORPRESA: c = Col{255, 255, 255}; f = 0.6f * expf(-u * 3); ancho = 80; break;
    case WHATSAPP: case LLAMADA: c = Col{60, 224, 120}; f = 0.35f + 0.25f * fabsf(sinf(u * 4)); break;
    case BATERIA: donde = ABAJO; c = Col{230, 40, 40}; f = 0.3f + 0.3f * fabsf(sinf(u * 3.9f)); ancho = 130; break;
    case CINTURON:                                                    // solo con el clic: un destello verde que se va apagando
      if (u < 2.45f) return;
      c = Col{70, 230, 120}; f = 0.9f * expf(-(u - 2.45f) * 1.4f); ancho = 100; break;
    case FELIZ: case LLEGAMOS: case SALUDO: f = 0.35f + 0.1f * sinf(u * 6.5f); break;
    case CURIOSA: c = Col{120, 200, 255}; f = 0.35f; break;
    case KAME: { if (u > 2.6f) return; const float cg = tramo(u, 0.4f, 2.6f); c = Col{90, 170, 255}; f = 0.2f + 0.75f * cg; ancho = 40 + 60 * cg; break; }   // crece con la carga
    case PUM: { const float k2 = pumK(u); if (k2 < 0 || k2 > 3.2f) return; c = Col{255, 80, 140}; f = 0.3f + 0.55f * (1 - fmodf(k2, 1.f)); ancho = 70; break; }   // late con el 3, 2, 1
    case PPT_PIEDRA: case PPT_PAPEL: case PPT_TIJERA: if (u < 2.3f) return; f = 0.8f * expf(-(u - 2.3f) * 1.6f); ancho = 90; break;   // un destello amarillo cuando saca la suya                                            // amarillo
    default: return;
  }
  luzBorde(donde, c, f * tramo(u, 0, donde == MARCO || donde == OVALO_LUZ ? 1.2f : 0.4f) * (1 - tramo(u, DURA[laReac] - 0.6f, DURA[laReac])), ancho, radio);
}

static void extrasReac(float u, bool delante) {
  if (!delante) luzReac(u);
  switch (laReac) {
    case KAME:
      if (delante) kame(u);
      break;
    case PUM:
      if (delante) pistolaConfeti(u);
      break;
    case SALUDO:
      if (delante) manoJuego(CX + 136, 262 + subeDe(u, 2.6f, 0.15f), 0.95f, 1, 1, 0.32f * sinf(u * 10));   // sube la mano abierta y saluda
      if (delante && u > 0.6f) {
        for (int i = 0; i < 4; i++) chispa(CX + (i < 2 ? -1 : 1) * (170 + 20 * (i & 1)), 60 + 90 * (i & 1) + 10 * sinf(u * 5 + i), 16, campana(u, 0.6f + i * 0.1f, 2.4f));
      }
      break;
    case BACHE:
      if (!delante) {
        for (int i = 0; i < 5; i++) {                                // estelas del vuelo
          float x = CX + (i - 2) * 50;
          if (u > 0.15f && u < 0.6f) { float y = 330 - (u - 0.15f) * 500; linea(x, y, x, y + 90, 3, Col{200, 235, 255}, 0.6f * campana(u, 0.15f, 0.6f)); }
          if (u > 0.82f && u < 1.08f) { float y = (u - 0.82f) * 700 - 140; linea(x, y, x, y + 90, 3, Col{200, 235, 255}, 0.6f * campana(u, 0.82f, 1.08f)); }
        }
      } else if (u >= 1.05f && u < 2.0f) {
        polvo(tramo(u, 1.05f, 2.0f), 1);
        for (int i = 0; i < 4; i++) chispa(CX + (i - 1.5f) * 120, H - 70 - 30 * (i % 2), 15, campana(u, 1.07f, 1.6f));
      }
      break;
    case RISA:
      if (delante) {
        const float a = tramo(u, 0, 0.15f) * (1 - tramo(u, 2.6f, 3.0f));
        for (int s = -1; s <= 1; s += 2) {                           // lagrimitas de risa
          const Ojo &o = s < 0 ? ahora.L : ahora.R;
          const float p = fmodf(u * 1.6f + (s > 0 ? 0.5f : 0), 1.f);
          gota(o.x + s * (o.w / 2 + 22 + p * 20), o.y - 10 + p * 60, 9, a * campana(p, 0, 1));
        }
        for (int i = 0; i < 5; i++) {                                // "ja ja ja" que va saltando a los lados
          const float q = (u - 0.2f - i * 0.5f) / 0.9f;
          if (q <= 0 || q >= 1) continue;
          dib::texto("JA", CX + (i % 2 ? -1 : 1) * (140 + 10 * q), 104 + 30 * (i % 3) - 34 * q, 32 + 12 * q, Col{255, 220, 80}, a * campana(q, 0, 1));
        }
        manoBlanca(CX + 112, 316 + subeDe(u, 3.0f, 0) - 16 * fabsf(sinf(u * 11)), -PI / 2 - 0.3f, 1.05f, false, a);   // palmotea
      }
      break;
    case FRENAZO:
      if (delante) {
        float k = tramo(u, 0.12f, 0.3f), a = 1 - tramo(u, 2.7f, 3.1f);
        grieta(CX, EY + 6, k * 1.7f, a);
        if (u > 0.1f && u < 0.4f) {                                  // destello del golpe
          float R = 30 + 200 * tramo(u, 0.1f, 0.4f);
          figura(CX - R - 4, EY - R - 4, CX + R + 4, EY + R + 4, solido(BLANCO, 0.6f * (1 - tramo(u, 0.1f, 0.4f))), [=](float px, float py) { return fabsf(dCirculo(px, py, CX, EY, R)) - 3; });
        }
        if (u > 3.2f) gota(CX + EX + 76, EY - 30 + tramo(u, 3.2f, 3.9f) * 60, 12, 1 - tramo(u, 3.8f, 4.0f));
      }
      break;
    case ACELERON:
    case ARRANQUE:
      if (!delante) tunel(u, 1 - tramo(u, 1.5f, 1.8f));
      else if (u > 1.6f) for (int i = 0; i < 4; i++) chispa(CX + (i < 2 ? -1 : 1) * (165 + 15 * (i & 1)), 70 + 100 * (i & 1), 15, campana(u, 1.6f + i * 0.08f, 2.8f));
      break;
    case GIRO_IZQ:
    case GIRO_DER: {
      float lado = laReac == GIRO_IZQ ? -1 : 1, borde = lado > 0 ? W - 2 : 2;
      if (!delante && u > 0.1f && u < 0.45f) {                       // estelas del vuelo
        for (int i = 0; i < 5; i++) {
          float y = EY - 70 + i * 35, x = CX + lado * (40 + 150 * tramo(u, 0.12f, 0.3f)) - lado * i * 6;
          linea(x, y, x - lado * 120, y, 3, Col{200, 235, 255}, 0.7f * campana(u, 0.1f, 0.45f));
        }
      }
      if (delante && u > 0.28f) {
        float p = u - 0.28f;
        for (int i = 0; i < 7; i++) {                                // golpe contra el borde
          float ang = (i - 3) * 0.32f, l0 = 20 + p * 160, l1 = l0 + 34;
          float x0 = borde - lado * cosf(ang) * l0, y0 = EY + sinf(ang) * l0 * 1.4f;
          float x1 = borde - lado * cosf(ang) * l1, y1 = EY + sinf(ang) * l1 * 1.4f;
          linea(x0, y0, x1, y1, 3, BLANCO, 1 - tramo(p, 0.05f, 0.35f));
        }
        for (int i = 0; i < 3; i++) {                                // estrellitas de mareo
          float a = u * 4 + i * 2.094f, cx = borde - lado * 80;
          chispa(cx + cosf(a) * 46, EY - 120 + sinf(a) * 14, 13, tramo(u, 0.4f, 0.6f) * (1 - tramo(u, 2.1f, 2.4f)));
        }
      }
      break;
    }
    case CARICIA:
      if (delante) for (int i = 0; i < 4; i++) {
        float p = fmodf(u * 0.7f + i * 0.25f, 1.f);
        corazonito(CX + (i - 1.5f) * 95 + sinf(p * 8 + i) * 10, EY + 60 - p * 170, 14, campana(p, 0, 1) * (1 - tramo(u, 2.2f, 2.6f)));
      }
      break;
    case WHATSAPP:
      if (delante) {
        ConColor cc;
        const Col verde{37, 211, 102};
        float a = tramo(u, 0.05f, 0.25f) * (1 - tramo(u, 5.5f, 6.0f));
        float pop = 1 + 0.35f * expf(-u * 5) * sinf(u * 18);                                // entra con rebote
        float lat = fmodf(u, 1.6f), vibra = lat < 0.4f ? sinf(lat * 80) * 5 * (1 - lat / 0.4f) : 0;   // vibra como el celular
        float x = CX - 30 + vibra, y = 84, R = 44 * pop * (1 + 0.03f * sinf(u * 5));
        for (int i2 = 0; i2 < 2; i2++) {                                                    // ondas verdes
          float p = fmodf(u * 0.7f + i2 * 0.5f, 1.f), Rr = R + 6 + 50 * p;
          figura(x - Rr - 4, y - Rr - 4, x + Rr + 4, y + Rr + 4, solido(verde, a * 0.55f * (1 - p), 6),
                 [=](float px, float py) { return fabsf(dCirculo(px, py, x, y, Rr)) - 2.5f; });
        }
        logoWhatsapp(x, y, R, a);
        float bs = u < 0.5f ? 0 : 1 + 0.4f * expf(-(u - 0.5f) * 6) * sinf((u - 0.5f) * 20);   // globito rojo con el "1"
        if (bs > 0.05f) {
          float bx = x + R * 0.74f, by = y - R * 0.74f;
          circulo(bx, by, 15 * bs, Col{255, 59, 48}, a, 4);
          dib::texto("1", bx, by - 1, 22 * bs, BLANCO, a);
        }
        if (u > 1.0f) {                                                                     // alguien está escribiendo...
          float ap = tramo(u, 1.0f, 1.3f) * a, gx = x + R + 74, gy = y + 6;
          figura(gx - 48, gy - 24, gx + 48, gy + 30, solido(BLANCO, ap, 6), [=](float px, float py) {
            return fminf(dCaja(px, py, gx, gy, 44, 20, 20), dSegmento(px, py, gx - 34, gy + 12, gx - 42, gy + 24, 4));
          });
          for (int k2 = 0; k2 < 3; k2++) circulo(gx - 18 + k2 * 18, gy - 4 * campana(fmodf(u * 1.8f - k2 * 0.18f, 1.f), 0, 0.4f), 5, Col{90, 96, 110}, ap);
        }
      }
      break;
    case LLAMADA:
      if (delante) telefonoIcono(CX, 84, 40, u, tramo(u, 0.1f, 0.35f) * (1 - tramo(u, 5.5f, 6.0f)));
      break;
    case CALOR:
      if (delante) {
        float a = tramo(u, 0.2f, 0.6f) * (1 - tramo(u, 7.0f, 7.5f));
        for (int s = -1; s <= 1; s += 2) {             // ojos cerrados del sofoco: rayitas con la punta de afuera caída
          const Ojo &o = s < 0 ? ahora.L : ahora.R;
          linea(o.x - s * 30, o.y + o.h / 2 - 16, o.x + s * 30, o.y + o.h / 2 - 5, 5.5f, ahora.a, tramo(u, 0.45f, 0.7f) * (1 - tramo(u, 7.0f, 7.4f)));
        }
        for (int i = 0; i < 4; i++) {                                // gotas de sudor que caen
          float p = fmodf(u * 0.5f + i * 0.27f, 1.f), s = (i % 2) ? 1 : -1;
          gota(CX + s * (EX + 70 + 12 * (i / 2)), EY - 70 + p * 150, 10, a * campana(p, 0, 1));
        }
        {                                                            // se echa aire con un abanico
          const float ax = CX + 128, ay = 296 + subeDe(u, 7.5f, 0.3f), mid = -PI / 2 - 0.55f + 0.34f * sinf(u * 11);
          for (int i = 0; i < 5; i++) {
            const float an = mid + (i - 2) * 0.36f, ex = ax + cosf(an) * 76, ey = ay + sinf(an) * 76;
            figura(fminf(ax, ex) - 16, fminf(ay, ey) - 16, fmaxf(ax, ex) + 16, fmaxf(ay, ey) + 16, solido(i % 2 ? Col{150, 200, 245} : Col{196, 228, 252}, a),
                   [=](float qx, float qy) { return dCapsula2(qx, qy, ax, ay, 3, ex, ey, 15); });
          }
          for (int i = 0; i < 5; i++) { const float an = mid + (i - 2) * 0.36f; linea(ax, ay, ax + cosf(an) * 84, ay + sinf(an) * 84, 1.5f, Col{70, 110, 160}, a); }   // las varillas
          manoBlanca(ax + 6, ay + 10, mid, 1.0f, false, a);
          for (int i = 0; i < 3; i++) {                              // el airecito del abanico le llega a la cara
            const float p = fmodf(u * 1.5f + i * 0.33f, 1.f), x = ax - 34 - 70 * p, y = 232 + i * 17 + 4 * sinf(p * 6 + i);
            linea(x, y, x - 20, y - 3, 1.7f, Col{214, 236, 255}, 0.7f * a * campana(p, 0, 1));
          }
          if (u > 3.0f && u < 4.6f) {                                // se seca la frente con la otra mano y salpica
            const float q = suave((u - 3.2f) / 1.0f), hx = ahora.L.x - 30 + (ahora.R.x - ahora.L.x + 60) * q, hy = ahora.L.y - ahora.L.h / 2 - 4;
            manoBlanca(hx, hy, 0.2f, 1.0f, false, a * campana(u, 3.0f, 4.6f) * 1.6f > 1 ? a : a * campana(u, 3.0f, 4.6f) * 1.6f);
            for (int i = 0; i < 3; i++) gota(hx + 30 + 14 * i, hy - 6 - 10 * i + 40 * q * q, 5, a * campana(u, 3.3f, 4.4f));
          }
        }
      } else {
        float a = tramo(u, 0.2f, 0.6f) * (1 - tramo(u, 7.0f, 7.5f));
      }
      break;
    case CINTURON:
      {
        const float a = tramo(u, 0.05f, 0.35f) * (1 - tramo(u, 5.1f, 5.6f)), q = cinturonQ(u), dc = u - 2.45f;
        const Col tela{224, 220, 234}, sombra{120, 114, 140}, hilo{168, 162, 186}, metal{196, 202, 214}, metalO{112, 118, 134};
        const Col caja{44, 46, 58}, roja{228, 56, 68}, rojaL{255, 128, 132}, chis{255, 216, 72};
        const float hx = CX - 112, hy = 304, sx = CX + 178, sy = 140;                 // la hebilla y el anclaje del hombro
        const float ax = CX + 160, ay = 196, bx = hx + 44, by = hy - 4;               // la lengüeta va de (a) a (b)
        const float lx = ax + (bx - ax) * q, ly = ay + (by - ay) * q;
        // la correa: dos tramos con comba mientras la jala; con el clic se tensa y vibra hasta quedarse quieta
        const float comba = 18 * sinf(q * PI) * (1 - tramo(u, 2.4f, 2.5f)) + (dc > 0 ? 7 * expf(-dc * 6) * sinf(dc * 42) : 0);
        const float dx = lx - sx, dy = ly - sy, L = sqrtf(dx * dx + dy * dy) + 0.01f, nx = -dy / L, ny = dx / L;
        const float mx = (sx + lx) / 2 - nx * comba, my = (sy + ly) / 2 - ny * comba;   // la comba cuelga hacia abajo
        auto tramoCorrea = [&](float x0, float y0, float x1, float y1) {
          linea(x0, y0, x1, y1, 12.5f, sombra, a); linea(x0, y0, x1, y1, 10.5f, tela, a);
          const float ex = x1 - x0, ey = y1 - y0, l = sqrtf(ex * ex + ey * ey) + 0.01f, px = -ey / l * 6.5f, py = ex / l * 6.5f;
          for (float d = 6; d < l - 8; d += 13)                                        // las costuras, a cada lado
            for (int s2 = -1; s2 <= 1; s2 += 2)
              linea(x0 + ex * d / l + px * s2, y0 + ey * d / l + py * s2, x0 + ex * (d + 6) / l + px * s2, y0 + ey * (d + 6) / l + py * s2, 0.9f, hilo, a);
        };
        if (!delante) break;                                                          // todo va por delante, sólido: la correa cruza por debajo de los ojos
        linea(sx, sy, mx, my, 15.5f, NEG, a); linea(mx, my, lx, ly, 15.5f, NEG, a);   // el borde negro la separa de lo que toque
        tramoCorrea(sx, sy, mx, my); tramoCorrea(mx, my, lx, ly);
        circulo(sx, sy, 15, metalO, a); circulo(sx, sy, 9, metal, a);                 // la argolla
        // la hebilla: su correíta, la caja oscura y el botón rojo con brillo; da un saltico con el clic
        const float salto = dc > 0 ? 5 * expf(-dc * 9) * sinf(dc * 30) : 0, hyy = hy + salto;
        linea(hx - 20, hyy + 8, hx - 74, hyy + 62, 11, sombra, a); linea(hx - 20, hyy + 8, hx - 74, hyy + 62, 9, tela, a);
        figura(hx - 36, hyy - 26, hx + 36, hyy + 26, solido(caja, a), [=](float qx, float qy) { return dCaja(qx, qy, hx, hyy, 30, 20, 8); });
        figura(hx - 36, hyy - 26, hx + 36, hyy + 26, solido(metalO, a), [=](float qx, float qy) { return fabsf(dCaja(qx, qy, hx, hyy, 30, 20, 8)) - 1.2f; });
        figura(hx - 24, hyy - 14, hx + 12, hyy + 14, solido(roja, a), [=](float qx, float qy) { return dCaja(qx, qy, hx - 6, hyy, 15, 10, 5); });
        figura(hx - 20, hyy - 10, hx + 6, hyy - 2, solido(rojaL, 0.7f * a), [=](float qx, float qy) { return dCaja(qx, qy, hx - 7, hyy - 6, 10, 1.6f, 1.6f); });
        figura(hx + 16, hyy - 10, hx + 32, hyy + 10, solido(NEG, a), [=](float qx, float qy) { return dCaja(qx, qy, hx + 25, hyy, 4, 7, 2); });   // la ranura
        // la lengüeta de metal con su ojal y la cabeza de plástico donde da la vuelta la correa
        const float ux = (bx - ax), uy = (by - ay), ul = sqrtf(ux * ux + uy * uy), cx2 = ux / ul, sy2 = uy / ul;
        if (q < 1) linea(lx, ly, lx + cx2 * 22, ly + sy2 * 22, 6.5f, metal, a), linea(lx + cx2 * 9, ly + sy2 * 9, lx + cx2 * 15, ly + sy2 * 15, 2.2f, metalO, a);
        linea(lx - cx2 * 10, ly - sy2 * 10, lx + cx2 * 2, ly + sy2 * 2, 11, caja, a);
        // la mano: sube a agarrarla, la lleva y después del clic la suelta
        const float llega = suave(lim((u - 0.5f) / 0.45f, 0, 1)), suelta = tramo(u, 3.1f, 3.5f);
        const float mhx = lx + 20 + (1 - llega) * 30 + suelta * 60, mhy = ly - 16 + (1 - llega) * 150 + suelta * 120;
        if (suelta < 1) manoBlanca(mhx, mhy, PI * 0.82f, 1.1f, false, a * (1 - suelta));
        if (dc > 0 && dc < 0.6f) {                                                     // ¡clic!: un aro de luz y chispas
          const float t = dc / 0.6f, R = 14 + 64 * t;
          figura(hx - R - 6, hy - R - 6, hx + R + 6, hy + R + 6, solido(BLANCO, 0.8f * a * (1 - t)), [=](float qx, float qy) { return fabsf(dCirculo(qx, qy, hx + 10, hy, R)) - 2.4f * (1 - t) - 0.6f; });
          for (int i = 0; i < 6; i++) {
            const float an = -PI + i * 0.52f + 0.3f, r0 = 30 + 34 * t, r1 = r0 + 14 * (1 - t);
            linea(hx + 10 + cosf(an) * r0, hy - 4 + sinf(an) * r0, hx + 10 + cosf(an) * r1, hy - 4 + sinf(an) * r1, 2.8f, chis, a * (1 - t));
          }
        }
        if (u > 3.4f) {                                                                // pulgar arriba y una estrellita junto al ojo que guiña
          const float sube = tramo(u, 3.4f, 3.7f), rebote = 1 + 0.25f * campana(u, 3.5f, 3.9f), af = a * sube;
          manoBlanca(CX + 96, 286 + 60 * (1 - sube) - 5 * fabsf(sinf(u * 6)), -PI / 2 + 0.1f, 1.25f * rebote, true, af);
          chispa(ahora.R.x + 58, ahora.R.y - 48, 14 + 5 * sinf(u * 9), af * campana(u, 3.6f, 5.0f));
        }
      }
      break;
    case SONROJO:
      if (delante) {
        const float fin = 1 - tramo(u, 4.1f, 4.6f), a = tramo(u, 0.3f, 0.8f) * fin, pena = tramo(u, 0.7f, 1.0f) * fin, espia = campana(u, 2.5f, 3.3f);
        for (int s2 = -1; s2 <= 1; s2 += 2) {
          const Ojo &o = s2 < 0 ? ahora.L : ahora.R;
          const float bx = o.x + s2 * 8, by = o.y + 50, late = 1 + 0.08f * sinf(u * 6);  // el rubor, debajo de cada ojo: sube y late
          // las manitos suben a taparse la cara; la del ojo que espía se corre un poquito
          const float corre = s2 > 0 ? 16 * espia : 0;
          manoBlanca(o.x + s2 * (56 + corre), o.y + 84 + 130 * (1 - pena) - 5 * sinf(u * 4.2f) + corre * 0.6f, -PI / 2 - s2 * 0.25f, 1.15f, false, a);
        }
        if (u > 0.85f && u < 1.9f) for (int i = 0; i < 2; i++) {                       // "pff": le sale humito de la pena
          const float p = (u - 0.85f - i * 0.2f) / 0.85f;
          if (p > 0 && p < 1) circulo(CX + (i ? 26 : -22) + 8 * sinf(p * 5), 78 - 30 * p, 8 + 9 * p, Col{255, 200, 214}, 0.55f * (1 - p));
        }
        for (int i = 0; i < 3; i++) {                                                  // corazoncitos que suben
          const float p = fmodf(u * 0.45f + i * 0.33f, 1.f);
          corazonito(CX + (i - 1) * 146 + 10 * sinf(p * 6 + i), 150 - p * 90, 9 + 3 * (i % 2), a * campana(p, 0, 1));
        }
      }
      break;
    case ENOJO:
      if (delante) {
        const float a = tramo(u, 0.1f, 0.3f) * (1 - tramo(u, 3.9f, 4.4f)), carga = tramo(u, 0.2f, 2.0f), dp = u - 2.0f, calma = tramo(u, 2.9f, 3.6f);
        const float late = 1 + 0.18f * fabsf(sinf(u * 7)), R = (8 + 5 * carga) * late;
        if (dp < 0 || dp > 0.5f) {                                                    // la boca brava: un arquito hacia abajo
          const float bx = ahora.bx, by = ahora.by + 10;
          figura(bx - 22, by - 22, bx + 22, by, solido(Col{255, 255, 255}, a), [=](float qx, float qy) { return dArco(qx, qy, bx, by, 15, -PI * 0.82f, -PI * 0.18f) - 3.6f; });
        }
        const float mx = ahora.R.x + 58, my = ahora.R.y - 72;                          // la venita de la rabia: crece mientras se carga
        for (int sx = -1; sx <= 1; sx += 2) for (int sy = -1; sy <= 1; sy += 2) {
          const float cx2 = mx + sx * R * 1.35f, cy2 = my + sy * R * 1.35f, mid = atan2f(-sy, -sx);
          figura(cx2 - R - 5, cy2 - R - 5, cx2 + R + 5, cy2 + R + 5, solido(Col{238, 48, 62}, a * (1 - calma), 6), [=](float qx, float qy) { return dArco(qx, qy, cx2, cy2, R, mid - 0.75f, mid + 0.75f) - 3.2f; });
        }
        for (int s2 = -1; s2 <= 1; s2 += 2) {
          // los puños apretados, temblando; con el estallido suben y golpean
          const float golpe = dp > 0 && dp < 0.5f ? sinf(dp / 0.5f * PI) : 0;
          manoBlanca(CX + s2 * 138 + 2.5f * carga * sinf(u * 50 + s2), 300 + subeDe(u, 4.4f, 0.2f) - 46 * golpe + 14 * (dp > 0.25f ? expf(-(dp - 0.25f) * 8) : 0), -PI / 2, 1.1f + 0.1f * carga, false, a);
          if (dp > 0 && dp < 1.5f) for (int i = 0; i < 3; i++) {                       // ¡le sale humo por los lados!
            const float p = dp / 1.5f + i * 0.14f;
            if (p < 1) circulo((s2 < 0 ? ahora.L.x : ahora.R.x) + s2 * (44 + 60 * p), ahora.R.y - 54 - 44 * p, 7 + 13 * p, Col{226, 226, 236}, 0.75f * a * (1 - p));
          }
          const float p = fmodf(u * 1.1f + (s2 > 0 ? 0.5f : 0), 1.f);                  // resopla
          circulo(CX + s2 * (40 + 50 * p), ahora.by + 18 + 8 * p, 6 + 8 * p, Col{226, 226, 236}, 0.8f * a * (1 - p) * calma);
        }
        if (dp > 0 && dp < 0.45f) for (int i = 0; i < 10; i++) {                       // el estallido: rayas rojas alrededor
          const float an = i * 0.628f + 0.2f, t = dp / 0.45f, r0 = 128 + 40 * t, r1 = r0 + 20 * (1 - t), cy0 = 170;
          linea(CX + cosf(an) * r0 * 1.25f, cy0 + sinf(an) * r0 * 0.8f, CX + cosf(an) * r1 * 1.25f, cy0 + sinf(an) * r1 * 0.8f, 3, Col{255, 70, 60}, a * (1 - t));
        }
      }
      break;
    case PAPAS:
      if (delante) {
        const float a = tramo(u, 0.1f, 0.4f) * (1 - tramo(u, 4.5f, 5.0f)), p = fmodf(u, 1.5f) / 1.5f;
        const Col roja{214, 44, 48}, osc{150, 22, 30}, papa{255, 206, 70}, tost{226, 150, 40};
        const float cx = CX + 112, cy = 312 + subeDe(u, 5.0f);                                               // la cajita roja, abajo a la derecha
        for (int i = 0; i < 6; i++) {                                                      // las papas asoman
          const float x0 = cx - 26 + i * 10.5f, alto = 30 + 9 * ((i * 7) % 3), inc = (i - 2.5f) * 2.4f;
          linea(x0, cy - 12, x0 + inc, cy - 12 - alto, 4.6f, i % 2 ? papa : tost, a);
        }
        figura(cx - 40, cy - 24, cx + 40, cy + 34, solido(roja, a), [=](float qx, float qy) { return dCaja(qx, qy, cx, cy + 6, 34 - (qy - cy) * 0.14f, 24, 7); });
        figura(cx - 20, cy - 4, cx + 20, cy + 22, solido(osc, a), [=](float qx, float qy) { return dCaja(qx, qy, cx, cy + 9, 12, 7, 5); });
        manoBlanca(cx + 34, cy + 22, -PI / 2 - 0.5f, 0.95f, false, a);                     // una mano sostiene la caja
        // la otra lleva una papa a la boca: sube en la primera mitad, espera el mordisco y vuelve
        const float sube = p < 0.5f ? suave(p / 0.5f) : 1 - suave((p - 0.62f) / 0.38f), bx = ahora.bx, by = ahora.by;
        const float hx = cx - 70 + (bx - 46 - (cx - 70)) * sube, hy = cy - 6 + (by + 26 - (cy - 6)) * sube;
        const float largo = p > 0.5f && p < 0.62f ? 12 : 24;                                // mordida: queda la mitad
        if (p < 0.62f) linea(hx + 12, hy - 12, hx + 12 + largo * 0.7f, hy - 12 - largo * 0.7f, 4.6f, papa, a);
        manoBlanca(hx, hy, -PI / 4, 1.0f, false, a);
      }
      break;
    case BEBIDA:
      if (delante) {
        const float a = tramo(u, 0.1f, 0.4f) * (1 - tramo(u, 4.5f, 5.0f)), cy = 318 + 3 * sinf(u * 5) + subeDe(u, 5.0f), bx = ahora.bx, by = ahora.by;
        const Col vaso{246, 150, 172}, tapa{255, 206, 216}, osc{214, 104, 134};
        linea(CX + 8, cy - 46, CX + 8, by + 34, 3.4f, BLANCO, a); linea(CX + 8, by + 34, bx + 2, by + 6, 3.4f, BLANCO, a);   // el pitillo, doblado hacia la boca
        figura(CX - 50, cy - 40, CX + 50, cy + 46, solido(vaso, a), [=](float qx, float qy) { return dCaja(qx, qy, CX, cy + 2, 40 - (qy - cy) * 0.16f, 36, 8); });
        figura(CX - 40, cy + 2, CX + 40, cy + 20, solido(osc, 0.8f * a), [=](float qx, float qy) { return dCaja(qx, qy, CX, cy + 10, 34, 5, 4); });   // la franja del vaso
        figura(CX - 56, cy - 50, CX + 56, cy - 30, solido(tapa, a), [=](float qx, float qy) { return dCaja(qx, qy, CX, cy - 40, 48, 7, 5); });       // la tapa
        for (int s2 = -1; s2 <= 1; s2 += 2) manoBlanca(CX + s2 * 50, cy + 8, s2 > 0 ? PI : 0, 0.95f, false, a);   // las dos manos
      }
      break;
    case CONSOLA:
      if (delante) {
        const float a = tramo(u, 0.1f, 0.4f) * (1 - tramo(u, 4.5f, 5.0f)), lado = sinf(u * 2.6f), cx = CX + 6 * lado, cy = 306 + subeDe(u, 5.0f);
        const Col roja{232, 72, 72}, azul{70, 150, 242}, gris{60, 64, 78}, raya{104, 110, 128};
        figura(cx - 112, cy - 40, cx - 52, cy + 40, solido(roja, a), [=](float qx, float qy) { return dCaja(qx, qy, cx - 78, cy, 26, 36, 14); });   // mando rojo
        figura(cx + 52, cy - 40, cx + 112, cy + 40, solido(azul, a), [=](float qx, float qy) { return dCaja(qx, qy, cx + 78, cy, 26, 36, 14); });   // mando azul
        figura(cx - 60, cy - 40, cx + 60, cy + 40, solido(gris, a), [=](float qx, float qy) { return dCaja(qx, qy, cx, cy, 58, 36, 5); });           // la pantalla
        figura(cx - 52, cy - 30, cx + 52, cy + 30, solido(raya, a), [=](float qx, float qy) {                                                       // el juego: rayas que corren
          return fmaxf(dCaja(qx, qy, cx, cy, 50, 28, 3), fabsf(fmodf(qx + qy + tAhora * 60 + 2000, 34.f) - 17) - 6); });
        circulo(cx - 78, cy - 14, 6, Col{120, 24, 30}, a); circulo(cx + 78, cy + 12, 5, Col{24, 70, 140}, a); circulo(cx + 88, cy - 4, 5, Col{24, 70, 140}, a);
        for (int s2 = -1; s2 <= 1; s2 += 2)                                              // las manos, con el pulgar apretando por turnos
          manoBlanca(cx + s2 * 96, cy + 14 + 3 * fabsf(sinf(u * 9 + (s2 > 0 ? 1.6f : 0))), s2 > 0 ? PI : 0, 1.0f, false, a);
      }
      break;
    case LEEMAPA:
      if (delante) {
        const float a = tramo(u, 0.1f, 0.4f) * (1 - tramo(u, 4.0f, 4.5f)), cy = 282 + subeDe(u, 4.5f) + 3 * sinf(u * 2.2f);
        const Col papel{246, 240, 224}, doblez{196, 186, 160}, via{200, 204, 214};
        figura(CX - 132, cy - 66, CX + 132, cy + 66, solido(papel, a), [=](float qx, float qy) {         // el papel, plegado en tres: zigzag arriba y abajo
          const float z = 8 * (fabsf(fmodf((qx - CX + 126) / 84, 2.f) - 1) - 0.5f);
          return dCaja(qx, qy + z, CX, cy, 126, 56, 4); });
        for (int k2 = -1; k2 <= 1; k2 += 2) linea(CX + k2 * 42, cy - 52, CX + k2 * 42, cy + 52, 1.2f, doblez, a);   // los dobleces
        linea(CX - 110, cy + 10, CX - 30, cy - 24, 3, via, a); linea(CX - 30, cy - 24, CX + 104, cy - 6, 3, via, a); linea(CX - 60, cy + 44, CX - 10, cy - 40, 3, via, a);
        linea(CX - 96, cy + 34, CX - 44, cy + 4, 3.4f, Col{66, 150, 246}, a); linea(CX - 44, cy + 4, CX + 30, cy + 18, 3.4f, Col{66, 150, 246}, a);
        linea(CX + 30, cy + 18, CX + 78, cy - 18, 3.4f, Col{66, 150, 246}, a);                              // la ruta
        circulo(CX + 78, cy - 24, 8, Col{232, 60, 66}, a); circulo(CX + 78, cy - 24, 3, papel, a);         // el destino
        for (int s2 = -1; s2 <= 1; s2 += 2) manoBlanca(CX + s2 * 128, cy + 10, s2 > 0 ? PI : 0, 1.0f, false, a);
      }
      break;
    case CONO:
      if (delante) {
        const float a = tramo(u, 0.1f, 0.4f) * (1 - tramo(u, 5.1f, 5.6f)), p = fmodf(u, 1.7f) / 1.7f, cerca = tramo(p, 0.15f, 0.4f) * (1 - tramo(p, 0.62f, 0.8f));
        const int n = (int)(u / 1.7f) + (p > 0.5f ? 1 : 0);                               // lamidas: la bola de arriba se va gastando
        const Col cono{222, 160, 84}, rejilla{166, 104, 46}, vainilla{255, 238, 196}, fresa{255, 146, 180}, brillo{255, 216, 228};
        const float bx = ahora.bx, by = ahora.by, cx = bx + 40 * (1 - cerca), top = by + 18 + 14 * (1 - cerca) + subeDe(u, 5.6f);
        const float r2 = 21 - 2 * fminf(n, 3), r1 = 26, y2 = top + r2, y1 = y2 + r2 * 0.6f + r1 * 0.55f, yc = y1 + r1 * 0.5f;
        figura(cx - 25, yc, cx + 25, yc + 54, solido(cono, a), [=](float qx, float qy) { return dTriangulo(qx, qy, cx - 23, yc, cx + 23, yc, cx, yc + 52); });
        figura(cx - 25, yc, cx + 25, yc + 54, solido(rejilla, a), [=](float qx, float qy) {                                     // la galleta: rejilla en rombos
          return fmaxf(dTriangulo(qx, qy, cx - 23, yc, cx + 23, yc, cx, yc + 52), fminf(fabsf(fmodf(qx + qy + 2000, 11.f) - 5.5f), fabsf(fmodf(qx - qy + 2000, 11.f) - 5.5f)) - 0.8f); });
        circulo(cx, y1, r1, vainilla, a); circulo(cx - 1, y2, r2, fresa, a);
        linea(cx + 7, y2 + r2 * 0.7f, cx + 8, y2 + r2 * 0.7f + 9 + 4 * sinf(u * 3), 3.2f, fresa, a);                             // se chorrea
        figura(cx - r2, y2 - r2, cx, y2, solido(brillo, 0.8f * a), [=](float qx, float qy) { return dElipse(qx, qy, cx - r2 * 0.36f, y2 - r2 * 0.4f, r2 * 0.3f, r2 * 0.18f); });
        figura(cx - 28, yc - 6, cx + 28, yc + 6, solido(cono, a), [=](float qx, float qy) { return dCaja(qx, qy, cx, yc, 26, 4.2f, 3.5f); });   // el borde de la galleta
        if (n == 0) { linea(cx + 3, top - 4, cx + 9, top - 15, 1.3f, Col{120, 70, 40}, a); circulo(cx + 3, top - 2, 6, Col{226, 40, 60}, a); }   // la cereza (se va con el primer lengüetazo)
        manoBlanca(cx + 16, yc + 36, PI, 0.9f, false, a);
        const float lame = campana(p, 0.36f, 0.64f);                                       // la lengüita
        if (lame > 0.05f) figura(bx - 9, by, bx + 9, by + 24, solido(Col{255, 120, 150}, a), [=](float qx, float qy) { return dCaja(qx, qy, bx, by + 3 + 8 * lame, 6.5f, 2 + 8 * lame, 6); });
      }
      break;
    case CRISPETAS:
      if (delante) {
        const float a = tramo(u, 0.1f, 0.4f) * (1 - tramo(u, 5.1f, 5.6f)), p = fmodf(u, 1.4f) / 1.4f, q = lim((p - 0.2f) / 0.35f, 0, 1);
        const bool falla = (int)(u / 1.4f) % 3 == 2;
        const Col roja{222, 48, 52}, blanca{250, 246, 240}, crema{255, 246, 214}, tost{255, 226, 150}, mant{255, 200, 70};
        auto crispeta = [&](float x, float y, float s, float al) {
          circulo(x - s * 0.5f, y + s * 0.2f, s * 0.62f, crema, al); circulo(x + s * 0.5f, y + s * 0.25f, s * 0.6f, tost, al);
          circulo(x, y - s * 0.3f, s * 0.7f, crema, al); circulo(x + s * 0.1f, y + s * 0.15f, s * 0.28f, mant, al);
        };
        const float cx = CX + 100, cy = 308 + subeDe(u, 5.6f);                                               // la caja de rayas, abajo a la derecha
        for (int i = 0; i < 7; i++)                                                        // el montón (de vez en cuando salta una)
          crispeta(cx - 25 + i * 8.4f, cy - 34 - 5 * (i % 2) - 4 * (i % 3) - 16 * campana(fmodf(u * 0.9f + i * 0.37f, 1.f), 0, 0.3f), 8.5f, a);
        auto dCajaP = [=](float qx, float qy) { return dCaja(qx, qy, cx, cy, 30 - (qy - cy) * 0.13f, 30, 4); };
        figura(cx - 36, cy - 32, cx + 36, cy + 32, solido(roja, a), dCajaP);
        figura(cx - 36, cy - 32, cx + 36, cy + 32, solido(blanca, a), [=](float qx, float qy) {
          const float w = 30 - (qy - cy) * 0.13f;
          return fmaxf(dCajaP(qx, qy), (fabsf(fmodf((qx - cx) / w * 2.5f + 10.25f, 1.f) - 0.5f) - 0.25f) * w * 0.4f); });
        figura(cx - 40, cy - 36, cx + 40, cy - 24, solido(blanca, a), [=](float qx, float qy) { return dCaja(qx, qy, cx, cy - 30, 37, 4.5f, 4); });
        manoBlanca(cx + 36, cy + 10, PI, 0.95f, false, a);
        // la otra mano la lanza: la crispeta sube en arco hasta la boca... o le pega en la frente y rebota
        const float mx0 = CX - 66, my0 = 306 + subeDe(u, 5.6f), lanza = campana(p, 0.08f, 0.3f);
        const float tx = falla ? CX + 6 : ahora.bx, ty = falla ? (ahora.L.y + ahora.R.y) / 2 - ahora.L.h / 2 - 6 : ahora.by;
        if (p < 0.2f) crispeta(mx0 + 10, my0 - 26 - 22 * lanza, 9, a);
        else if (p < 0.55f) crispeta(mx0 + 10 + (tx - mx0 - 10) * q, my0 - 48 + (ty - my0 + 48) * q - 70 * sinf(q * PI), 9, a);
        else if (falla && p < 0.95f) {
          const float t = (p - 0.55f) / 0.4f;
          crispeta(tx + 70 * t, ty - 36 * sinf(t * PI * 0.6f) + 150 * t * t, 9, a * (1 - t * t));
          chispa(tx, ty, 13 * (1 - t), a * (1 - t), Col{255, 240, 190});
        }
        manoBlanca(mx0, my0 - 22 * lanza, -PI / 2 + 0.25f, 1.0f, false, a);
      }
      break;
    case FELIZ:
      if (delante) {
        const float a = tramo(u, 0.05f, 0.3f) * (1 - tramo(u, 3.2f, 3.6f)), br = fabsf(sinf(u * 6.5f));
        for (int s2 = -1; s2 <= 1; s2 += 2)                                               // las manos arriba, celebrando
          manoBlanca(CX + s2 * (140 + 6 * sinf(u * 6.5f)), 172 + subeDe(u, 3.6f, 0) - 30 * br, -PI / 2 + s2 * (0.3f + 0.3f * sinf(u * 13)), 1.15f, false, a);
        for (int i = 0; i < 6; i++) {                                                      // destellos que van saltando alrededor
          const float p = fmodf(u * 0.9f + i * 0.31f, 1.f), an = i * 1.05f + 0.4f;
          chispa(CX + cosf(an) * 150, 150 + sinf(an) * 96, 8 + 10 * sinf(p * PI), a * campana(p, 0, 1), i % 2 ? Col{255, 226, 90} : Col{255, 250, 220});
        }
      }
      break;
    case TRISTE:
      if (delante) {
        const float a = tramo(u, 0.4f, 0.9f) * (1 - tramo(u, 4.5f, 5.0f)), seca = campana(u, 3.3f, 4.3f);
        const Col nube{150, 160, 184}, nubeO{112, 122, 150};
        const float nx = CX + 6 * sinf(u * 1.3f), ny = 76;                                // la nubecita gris encima, con su llovizna
        for (int i = 0; i < 4; i++) {
          const float p = fmodf(u * 1.6f + i * 0.27f, 1.f), x = nx - 30 + i * 20;
          linea(x, ny + 14 + 22 * p, x - 2, ny + 22 + 22 * p, 1.6f, Col{120, 190, 255}, a * (1 - p));
        }
        circulo(nx - 24, ny + 3, 13, nubeO, a); circulo(nx + 24, ny + 4, 12, nubeO, a); circulo(nx - 8, ny - 5, 17, nube, a); circulo(nx + 12, ny - 2, 15, nube, a);
        figura(nx - 38, ny + 2, nx + 38, ny + 18, solido(nube, a), [=](float qx, float qy) { return dCaja(qx, qy, nx, ny + 9, 36, 7, 7); });
        for (int s2 = -1; s2 <= 1; s2 += 2) {                                              // los lagrimones
          const Ojo &o = s2 < 0 ? ahora.L : ahora.R;
          const float p = fmodf(u * 0.75f + (s2 > 0 ? 0.5f : 0), 1.f);
          if (s2 < 0 && seca > 0.3f) continue;
          gota(o.x + s2 * 8, o.y + o.h / 2 + 12 + p * 64, 6 + 4 * tramo(p, 0, 0.3f), a * (1 - tramo(p, 0.75f, 1)));
        }
        if (seca > 0.02f) manoBlanca(ahora.L.x - 6 + 12 * sinf(u * 9), ahora.L.y + 14 + 90 * (1 - seca), -PI / 2 + 0.3f, 1.05f, false, a * fminf(1, seca * 3));   // se seca con la mano
      }
      break;
    case SUENO:
      if (delante) {
        const float a = tramo(u, 0.2f, 0.6f) * (1 - tramo(u, 5.0f, 5.5f)), bos = campana(u, 1.0f, 3.0f);
        if (bos > 0.05f) {                                                                 // se tapa el bostezo dándose palmaditas, y le sale una lagrimita
          manoBlanca(ahora.bx + 30 - 8 * fabsf(sinf(u * 9)), ahora.by + 22 + 150 * (1 - fminf(1, bos * 2.5f)), -PI / 2 - 0.45f, 1.15f, false, a);
          gota(ahora.R.x + ahora.R.w / 2 + 8, ahora.R.y + 4 + 10 * bos, 6, a * tramo(bos, 0.6f, 0.9f));
        }
        for (int i = 0; i < 3; i++) {                                                      // z z z
          const float p = fmodf((u - 3.0f) * 0.5f + i / 3.f, 1.f);
          if (u > 3.0f + i * 0.6f) dib::texto("z", CX + 96 + p * 54, 120 - p * 70, 18 + p * 22, Col{190, 170, 255}, a * campana(p, 0, 1) * 0.9f);
        }
      }
      break;
    case PPT_PIEDRA: case PPT_PAPEL: case PPT_TIJERA:
      if (delante) {
        const float a = tramo(u, 0.05f, 0.3f) * (1 - tramo(u, 4.5f, 5.0f)), sale = u >= 2.3f, pop = campana(u, 2.3f, 2.8f);
        const float bate = u > 0.5f && u < 2.3f ? fabsf(sinf((u - 0.5f) / 0.6f * PI)) : 0;
        const float x = CX, y = 286 + subeDe(u, 5.0f, 0) - 26 * bate - 10 * pop;
        manoJuego(x, y, 1.2f * (1 + 0.2f * pop), sale ? laReac - PPT_PIEDRA : 0, a);
        if (!sale && u > 0.5f) {                                                                 // la cuenta: 1, 2, 3 salta al lado del puño
          const int n = (int)((u - 0.5f) / 0.6f); const float q = fmodf(u - 0.5f, 0.6f) / 0.6f;
          char c[2] = {(char)('1' + (n > 2 ? 2 : n)), 0};
          dib::texto(c, CX + 108, 262 - 24 * q, 44 + 14 * campana(q, 0, 1), Col{255, 220, 80}, a * (1 - tramo(q, 0.7f, 1)));
        }
        if (sale && u < 3.2f) for (int i = 0; i < 6; i++) {                                      // ¡ya!: destellos alrededor
          const float an = i * 1.047f + 0.4f, t = (u - 2.3f) / 0.9f, R = 70 + 50 * t;
          chispa(x + cosf(an) * R * 1.3f, y - 20 + sinf(an) * R * 0.7f, 12 * (1 - t), a * (1 - t), Col{255, 226, 90});
        }
      }
      break;
    case SORPRESA:
      break;
    case AMOR:
      if (delante) for (int i = 0; i < 5; i++) {
        float p = fmodf(u * 0.5f + i * 0.2f, 1.f);
        corazonito(40 + i * 92 + sinf(p * 6 + i) * 12, H - 20 - p * 300, 12 + 4 * (i % 2), campana(p, 0, 1) * 0.8f);
      }
      break;
    case MAREO:
      if (delante) {
        float a = tramo(u, 0, 0.2f) * (1 - tramo(u, 2.9f, 3.2f));
        espiral(ahora.L.x, ahora.L.y, u * 9, a); espiral(ahora.R.x, ahora.R.y, -u * 9, a);
        for (int i = 0; i < 3; i++) {                                // estrellitas dando vueltas
          float g = u * 3.5f + i * 2.094f;
          chispa(CX + cosf(g) * 130, 50 + sinf(g) * 18, 14, a);
        }
      }
      break;
    case MAPS_RECTO:
    case MAPS_IZQ:
    case MAPS_DER:
      if (delante) pantallaMapa(laReac == MAPS_IZQ ? -1 : laReac == MAPS_DER ? 1 : 0, u, tramo(u, 0.05f, 0.3f) * (1 - tramo(u, 4.1f, 4.5f)));
      break;
    case LLEGAMOS:
      if (delante) {
        confeti(u, 1 - tramo(u, 3.4f, 4.0f));
        for (int i = 0; i < 4; i++) chispa(CX + (i < 2 ? -1 : 1) * (170 + 15 * (i & 1)), 70 + 110 * (i & 1), 16, campana(fmodf(u + i * 0.3f, 1.2f), 0, 1.2f));
      }
      break;
    case SOL:
      if (delante) solHistoria(u);
      break;
    case LLUVIA:
      if (delante) lluviaVidrio(u);
      break;
    case FRIO:
      if (delante) {                                   // la boquita ondulada, tiritando
        const float af = tramo(u, 0, 0.3f) * (1 - tramo(u, 4.5f, 5.0f)), bx = ahora.bx, by = ahora.by - 4;
        for (int i = 0; i < 6; i++) {
          const float x0 = bx - 21 + i * 7, y0 = by + 3.5f * sinf(i * 1.57f + u * 26), y1 = by + 3.5f * sinf((i + 1) * 1.57f + u * 26);
          linea(x0, y0, x0 + 7, y1, 2.3f, ahora.a, af);
        }
      }
      if (delante) for (int i = 0; i < 9; i++) {
        float p = fmodf(u * (0.25f + 0.1f * hash(i, 12)) + hash(i, 13), 1.f);
        copo(hash(i, 14) * W + sinf(u * 2 + i) * 14, -10 + p * (H + 20), 9 + 5 * hash(i, 15), u + i, campana(p, 0, 1) * (1 - tramo(u, 4.5f, 5.0f)));
      }
      break;
    case CURIOSA:
      if (delante) {                                               // un signo de pregunta que salta junto al ojo grande
        const float a = tramo(u, 0.2f, 0.45f) * (1 - tramo(u, 2.0f, 2.4f));
        dib::texto("?", ahora.R.x + 62, ahora.R.y - 62 - 8 * fabsf(sinf(u * 5)), 46 + 10 * campana(u, 0.2f, 0.6f), Col{120, 200, 255}, a);
      }
      break;
    case BATERIA:
      if (delante) pilita(CX, 84, 0.15f, fmodf(u, 0.8f) < 0.45f, tramo(u, 0, 0.3f) * (1 - tramo(u, 3.6f, 4.0f)));
      break;
    case GUINO:
      if (delante && u > 0.2f) {                                   // la estrella sale del ojo, crece y se va, dejando chispitas
        const float q = lim((u - 0.2f) / 1.3f, 0, 1), al = campana(u, 0.2f, 1.8f), x = ahora.R.x + 36 + 56 * q, y = ahora.R.y - 34 - 50 * q;
        chispa(x, y, 10 + 18 * sinf(q * PI), al, Col{255, 226, 90});
        for (int i = 0; i < 3; i++) chispa(x - 16 - i * 15, y + 13 + i * 12, 6 - i, al * (1 - q), Col{255, 246, 200});
      }
      break;
    default: break;
  }
}

// ---------------------------------------------------------------- control
void iniciar(uint32_t s) { semilla = s ? s : 1; ahora = meta = neutro(); }
void base(Base b) { if (b != laBase) { laBase = b; baseDesde = tAhora; } }
Base baseActual() { return laBase; }
// la indicación de Maps se mantiene: si llega otra igual mientras está en pantalla, no vuelve a empezar, solo dura más
static float reacExtra = 0;
void reaccion(Reac r) {
  if (r == laReac && esMaps(r)) { reacExtra = fmaxf(reacExtra, tAhora - reacDesde - 1.5f); return; }
  laReac = r; reacDesde = tAhora; pumCuenta = -1; reacExtra = 0;
}
Reac reaccionActual() { return laReac; }

void cuadro(float t, float dt, const Entradas &e0) {
  Entradas e = e0;
  if (e.ritmo == R_PIANO || e.ritmo == R_CORAZON) e.ritmo = R_KPOP;   // ya no hay teclado (parecía una dentadura): esas canciones van con el micrófono
  tAhora = t; curvaAhora = e.curva;
  dib::blanco = elPaquete == TRAZO || elPaquete == BOLITA;   // Lino y Mochi: todo en un solo color, el de "Color de los ojos" (blanco de fábrica)
  // las caras nuevas van más contenidas: con el tamaño de fábrica nada se sale de la pantalla
  const bool nueva = elPaquete >= CARICATURA;
  dib::zoom = nueva ? elTamano * 0.75f : elTamano; dib::zx = CX; dib::zy = nueva ? 176 : 250;   // de fábrica quedan un poco más chicas que la pantalla   // crece hacia arriba y a los lados; abajo casi no se corta
  // modo mapa (el celular va mandando la posición): la pantalla de navegación se queda fija; ni la música ni el
  // tiempo la quitan, solo otra reacción o que el celular deje de mandar
  if (esMaps(laReac) && t - mapaT < 8 && t - reacDesde - reacExtra > 2.5f) {
    reacExtra = t - reacDesde - 2.5f;
    if (gradosM && gradosHasta > 0) gradosHasta = fmaxf(gradosHasta, t + 1);   // los metros tampoco se van
  }
  if (laReac != NINGUNA && t - reacDesde - reacExtra >= DURA[laReac]) laReac = NINGUNA;
  if (laReac == PUM && pumCuenta >= 0 && t - reacDesde - pumCuenta > 5.6f) laReac = NINGUNA;   // terminó de reírse
  float vel;
  float u = t - reacDesde;
  if (reacExtra > 0 && u > 1.5f) u = fmaxf(1.5f, u - reacExtra);   // mantenida: se queda quieta en pantalla el tiempo extra
  meta = (laReac != NINGUNA) ? deReac(u, e, vel) : deBase(t, e, vel);
  float k = 1 - expf(-dt * vel);
  float *a = (float *)&ahora, *b = (float *)&meta;
  for (size_t i = 0; i < sizeof(Rostro) / sizeof(float); i++) a[i] += (b[i] - a[i]) * k;
  // lo que no se suaviza: párpados, risa y boca van al instante (si no, el parpadeo se ve lento)
  ahora.L.tapa = meta.L.tapa; ahora.R.tapa = meta.R.tapa;
  if (vel >= 14) { ahora.boca = meta.boca; }
  ahora.corazon = meta.corazon;

  if (laReac != NINGUNA) extrasReac(u, false); else extrasBase(t, e, false);
  if (!soloMapa()) {                                              // con el mapa completo no se dibuja la cara
    ojo(ahora, ahora.L, -1);
    ojo(ahora, ahora.R, 1);
    boca(ahora);
  }
  if (laReac != NINGUNA) extrasReac(u, true); else extrasBase(t, e, true);
  dib::zoom = 1;                                                  // lo de arriba (los grados) va sin zoom, pegado al borde
  if (t < gradosHasta && !(gradosM && esMaps(laReac))) {   // "24°" arriba, entra y sale suave (con la flecha de Maps los metros van en su pantalla)
    float a = fminf(lim((t - gradosDesde) / 0.3f, 0, 1), lim((gradosHasta - t) / 0.4f, 0, 1));
    const Col c = BLANCO;
    char b[16];
    if (gradosM) {                                                 // "2.600 m"
      if (gradosN >= 1000) snprintf(b, sizeof b, "%d.%03d m", gradosN / 1000, gradosN % 1000); else snprintf(b, sizeof b, "%d m", gradosN);
      texto(b, CX, 46, 48, c, a);
    } else {
    snprintf(b, sizeof b, "%d", gradosN);
    const float tam = 52, x = CX - 8, ancho = anchoTexto(b, tam), gx = x + ancho / 2 + 12, gy = 30;
    texto(b, x, 46, tam, c, a);
    figura(gx - 10, gy - 10, gx + 10, gy + 10, solido(BLANCO, a), [=](float px, float py) { return fabsf(dCirculo(px, py, gx, gy, 6.5f)) - 2.6f; });
    }
  }
  dib::blanco = false;
}

void progreso(float t, float p) {
  // dos ojitos cerrados que respiran y un anillo que se va llenando
  dib::blanco = false;
  Rostro r = neutro(AZUL);
  arco(r, CX - EX, EY + 10 + 3 * sinf(t * 2), EW, false, 0.8f);
  arco(r, CX + EX, EY + 10 + 3 * sinf(t * 2), EW, false, 0.8f);
  const float x = CX, y = MY, R = 34, a1 = -PI / 2 + 2 * PI * lim(p, 0, 1);
  figura(x - R - 6, y - R - 6, x + R + 6, y + R + 6, solido(Col{40, 60, 100}), [=](float px, float py) { return fabsf(dCirculo(px, py, x, y, R)) - 4; });
  figura(x - R - 6, y - R - 6, x + R + 6, y + R + 6, tonoP(r, y - R, y + R, 10), [=](float px, float py) {
    float a = atan2f(py - y, px - x); if (a < -PI / 2) a += 2 * PI;
    float d = fabsf(dCirculo(px, py, x, y, R)) - 4.5f;
    return a <= a1 ? d : fmaxf(d, 6.f);
  });
  char pct[8]; snprintf(pct, sizeof(pct), "%d%%", (int)(lim(p, 0, 1) * 100));
  dib::texto(pct, x, y, 22, TONOS[AZUL].a, 1);
  dib::texto("Actualizando…", CX, H - 26, 30, TONOS[AZUL].a, 0.6f + 0.4f * sinf(t * 3));
  dib::blanco = false;
}

}  // namespace caras
