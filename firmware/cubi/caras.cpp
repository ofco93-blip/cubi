// el dibujo es casi todo cuentas con decimales: más rápido que el -Os de Arduino
#pragma GCC optimize("O3", "fast-math")
#include "caras.h"

namespace caras {
using namespace dib;

const char *NOMBRE_BASE[N_BASE] = {"reposo", "escucha", "habla", "canta", "piensa", "duerme", "telefono"};
const char *NOMBRE_REAC[N_REAC] = {"", "saludo", "bache", "frenazo", "aceleron", "arranque", "izq", "der", "caricia",
                                   "whatsapp", "llamada", "calor", "risa", "sorpresa", "amor", "mareo", "maps_izq", "maps_der",
                                   "llegamos", "sol", "lluvia", "frio", "bateria", "guino", "curiosa"};
static const float DURA[N_REAC] = {0, 2.6f, 2.4f, 4.0f, 2.8f, 2.8f, 2.4f, 2.4f, 2.6f, 6.0f, 6.0f, 7.5f, 2.0f, 2.0f, 4.0f,
                                   3.2f, 3.0f, 3.0f, 4.0f, 7.0f, 7.0f, 5.0f, 4.0f, 1.2f, 2.4f};
float duracion(Reac r) { return DURA[r]; }
const char *NOMBRE_RITMO[N_RITMO] = {"pop", "urbano", "electro", "tropical", "kpop", "corazon", "groove", "guitarra", "disfruta", "rock",
                                     "jazz", "dj", "piano", "barras"};

// ---------------------------------------------------------------- medidas (ojos grandes y redondos, como Copi)
constexpr float CX = W / 2.f, EY = 166, EX = 88, EW = 104, EH = 164, MY = 296, MIRA = 50;

struct Tono { Col a, b; };
enum { AZUL, ROJO, ROSA, ORO, NARANJA, MORADO, HIELO, VERDE };
static const Tono TONOS[] = {
    {{185, 245, 255}, {40, 125, 255}},  {{255, 190, 140}, {235, 35, 45}},   {{255, 205, 230}, {255, 70, 150}},
    {{255, 245, 170}, {245, 150, 15}},  {{255, 230, 130}, {255, 95, 35}},   {{225, 200, 255}, {130, 70, 255}},
    {{235, 250, 255}, {120, 200, 255}}, {{200, 255, 210}, {40, 200, 120}},
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
const char *NOMBRE_PAQ[N_PAQ] = {"Cubi azul", "Robot minimalista"};
static uint8_t elPaquete = CUBI_AZUL;
void paquete(uint8_t p) { elPaquete = p < N_PAQ ? p : CUBI_AZUL; }
static Base laBase = REPOSO;
static Reac laReac = NINGUNA;
static float reacDesde = -1, tAhora = 0, baseDesde = 0, curvaAhora = 0;

// ---------------------------------------------------------------- azar
static uint32_t semilla = 12345;
static float azar() { semilla ^= semilla << 13; semilla ^= semilla >> 17; semilla ^= semilla << 5; return (semilla & 0xFFFFFF) / 16777216.f; }
static float hash(int i, int k) { float s = sinf(i * 127.1f + k * 311.7f) * 43758.5453f; return s - floorf(s); }

static inline float suave(float x) { x = lim(x, 0, 1); return x * x * (3 - 2 * x); }
static inline float tramo(float u, float a, float b) { return suave((u - a) / (b - a)); }
static inline float campana(float u, float a, float b) { return (u <= a || u >= b) ? 0 : sinf((u - a) / (b - a) * PI); }

static Rostro neutro(int tono = AZUL) {
  Rostro r;
  r.L = {CX - EX, EY, EW, EH, 0, 0, 0, 1};
  r.R = {CX + EX, EY, EW, EH, 0, 0, 0, 1};
  r.a = TONOS[tono].a; r.b = TONOS[tono].b;
  r.boca = 0; r.bx = CX; r.by = MY; r.bAncho = 1; r.mejillas = 0; r.corazon = 0;
  if (elPaquete == MINIMALISTA) {  // ojitos de píldora parada, más chicos (como el robot del tablero)
    r.L.x = CX - EX * 0.8f; r.R.x = CX + EX * 0.8f; r.L.w = r.R.w = EW * 0.6f; r.L.h = r.R.h = EH * 0.72f;
    r.by = MY - 34;
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
  const bool mini = elPaquete == MINIMALISTA;
  const float x = o.x, y = o.y, hw = o.w / 2, hh = o.h / 2, rad = fminf(o.w, o.h) * 0.46f;
  const float y0 = y - hh + top * o.h, tn = tanf(o.incl) * lado, co = cosf(o.incl);
  const float fz = o.feliz, cy = y + o.h * (0.95f - 0.55f * fz), crx = o.w * 0.78f, cry = o.h * 0.62f;
  if (r.corazon > 0.5f) {
    float s = fminf(o.w, o.h) * 0.55f * (0.9f + 0.1f * r.corazon);
    auto cor = [=](float px, float py) { return dCorazon(px, py, x, y, s); };
    if (mini) volumen(x - s * 1.3f, y - s * 1.3f, x + s * 1.3f, y + s * 1.1f, o.alfa, cor);
    else figura(x - s * 1.3f, y - s * 1.3f, x + s * 1.3f, y + s * 1.1f, tonoP(r, y - s, y + s, 20, o.alfa), cor);
    return;
  }
  if (mini) {   // al entrecerrar se aplasta (no se corta) y todas las puntas quedan redondas
    const float hh2 = fmaxf(hh * (1 - top), 4.f), y2 = y + hh - hh2, cy2 = y2 + 2 * hh2 * (0.95f - 0.55f * fz), cry2 = 2 * hh2 * 0.62f;
    const float yl = y2 - hh2 + fabsf(tn) * hw;
    if (fz > 0.5f) {   // feliz: un arco de trazo grueso, como el robot del tablero (no una píldora recortada)
      const float R = hw * 1.1f, cyA = y + hh * 0.35f;
      figura(x - R - 8, cyA - R - 8, x + R + 8, cyA + 8, solido(BLANCO, o.alfa, 10),
             [=](float px, float py) { return dArco(px, py, x, cyA, R, -PI * 0.86f, -PI * 0.14f) - 6.5f; });
      return;
    }
    volumen(x - hw, y2 - hh2, x + hw, y2 + hh2, o.alfa, [=](float px, float py) {
      float d = dPildora(px, py, x, y2, hw, hh2);
      if (o.incl != 0) d = smax(d, (yl + tn * (px - x) - py) * co, 10);
      if (fz > 0.01f) d = smax(d, -dElipse(px, py, x, cy2, crx, cry2), 8);
      return d;
    });
    return;
  }
  auto forma = [=](float px, float py) {
    float d = dCaja(px, py, x, y, hw, hh, rad);
    if (top > 0.005f || o.incl != 0) d = fmaxf(d, (y0 + tn * (px - x) - py) * co);
    if (fz > 0.01f) d = fmaxf(d, -dElipse(px, py, x, cy, crx, cry));
    return d;
  };
  figura(x - hw, y - hh, x + hw, y + hh, tonoP(r, y - hh, y + hh, 14, o.alfa), forma);
  // brillito
  if (top < 0.3f && fz < 0.3f) {
    float bx = x - o.w * 0.2f, by = y - o.h * 0.27f;
    figura(bx - o.w * .1f, by - o.h * .07f, bx + o.w * .1f, by + o.h * .07f, solido(BLANCO, 0.35f * o.alfa),
           [=](float px, float py) { return dElipse(px, py, bx, by, o.w * .1f, o.h * .07f); });
  }
}

static void boca(const Rostro &r) {
  float op = r.boca;
  if (op < 0.05f) return;
  float w = (26 + 14 * op) * r.bAncho, h = 16 + 62 * op;
  float x = r.bx, y = r.by;
  // minimalista: boca solo al hablar y al cantar (ni en reacciones ni en reposo)
  if (elPaquete == MINIMALISTA && (laReac != NINGUNA || (laBase != CANTA && laBase != HABLA && laBase != TELEFONO))) return;
  if (elPaquete == MINIMALISTA && laBase == CANTA) {             // canta: una boquita que abre y cierra con la voz
    if (op < 0.12f) return;                                      // entre sílabas se cierra del todo
    float bw = 11 * r.bAncho + 4, bh = 5 + 22 * op;
    figura(x - bw - 2, y - bh - 2, x + bw + 2, y + bh + 2, solido(BLANCO, 1, 10),
           [=](float px, float py) { return dCaja(px, py, x, y + 6, bw, bh, fminf(bw, bh)); });
    return;
  }
  if (elPaquete == MINIMALISTA) {  // habla: ondita de sonido chiquita abajo a la derecha (5 barritas que bailan con la voz)
    x += 96; y += 26;
    for (int i = 0; i < 5; i++) {
      float f = (i == 2 ? 1 : i == 1 || i == 3 ? 0.7f : 0.42f) * (0.7f + 0.3f * sinf(tAhora * 13 + i * 1.9f));
      float hb = 8 + 40 * op * f, xb = x + (i - 2) * 13 * fminf(r.bAncho, 1.4f);
      figura(xb - 5, y - hb / 2, xb + 5, y + hb / 2, tonoP(r, y - hb / 2, y + hb / 2, 8),
             [=](float px, float py) { return dCaja(px, py, xb, y, 4.5f, hb / 2, 4.5f); });
    }
    return;
  }
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
static void nota(float x, float y, float s, float alfa) {
  Col c = TONOS[AZUL].a;
  circulo(x, y, s * 0.42f, c, alfa, 8);
  linea(x + s * 0.36f, y, x + s * 0.36f, y - s * 1.3f, s * 0.09f, c, alfa);
  linea(x + s * 0.36f, y - s * 1.3f, x + s * 0.85f, y - s * 0.95f, s * 0.09f, c, alfa);
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
static Pintura blanca(float y0, float y1) { Pintura p; p.arriba = BL; p.abajo = BL_ABAJO; p.y0 = y0; p.y1 = y1; return p; }
static Pintura plana(const Col &c, float y0, float y1, float osc = 0.75f) {
  Pintura p; p.arriba = c; p.abajo = {c.r * osc, c.g * osc, c.b * osc}; p.y0 = y0; p.y1 = y1; return p;
}

// manito tipo guante (como en Lumi): bolita con pulgar, borde oscuro y tres deditos; ang en radianes (0 = dedos arriba)
static void manito(float x, float y, float r, float ang) {
  float pa = ang - 2.1f, px = x + sinf(pa) * r * 0.9f, py = y - cosf(pa) * r * 0.9f, rp = r * 0.42f, b = 4;   // pulgar al lado
  float x0 = fminf(x - r, px - rp) - b - 1, y0 = fminf(y - r, py - rp) - b - 1, x1 = fmaxf(x + r, px + rp) + b + 1, y1 = fmaxf(y + r, py + rp) + b + 1;
  figura(x0, y0, x1, y1, solido(NEG), [=](float qx, float qy) { return fminf(dCirculo(qx, qy, x, y, r + b), dCirculo(qx, qy, px, py, rp + b)); });
  figura(x0, y0, x1, y1, blanca(y - r, y + r), [=](float qx, float qy) { return fminf(dCirculo(qx, qy, x, y, r), dCirculo(qx, qy, px, py, rp)); });
  for (int k = -1; k <= 1; k++) {
    float a = ang + k * 0.38f, sa = sinf(a), ca = cosf(a);
    linea(x + sa * r * 0.55f, y - ca * r * 0.55f, x + sa * r * 1.0f, y - ca * r * 1.0f, 1.4f, NEG, 1);
  }
}
// manito haciendo la V (dos deditos estirados); ang = hacia dónde apuntan los dedos (0 = arriba)
static void manoV(float x, float y, float k, float ang, float abre) {
  Marco m = marco(x, y, ang);
  float r = 18 * k, L = 40 * k, g = 6.5f * k;
  auto forma = [=](float a, float b, float extra) {
    float d = dCirculo(a, b, 0, 0, r + extra);
    for (int s = -1; s <= 1; s += 2) {
      float ax = s * 6 * k, ay = -r * 0.4f, bx = ax + sinf(s * abre) * L, by = ay - cosf(abre) * L;
      d = fminf(d, dSegmento(a, b, ax, ay, bx, by, g + extra));
    }
    d = fminf(d, dCirculo(a, b, -r * 0.85f, r * 0.15f, r * 0.42f + extra));   // pulgar doblado
    return d;
  };
  pieza(m, -r - 30 * k, -r - L - 12, r + 30 * k, r + 6, solido(NEG), [=](float a, float b) { return forma(a, b, 4); });
  pieza(m, -r - 30 * k, -r - L - 12, r + 30 * k, r + 6, blanca(y - r, y + r), [=](float a, float b) { return forma(a, b, 0); });
}
static void destellito(float x, float y, float s, float alfa) { chispa(x, y, s, alfa, Col{255, 236, 140}); }
static void notaColor(float x, float y, float s, const Col &c, float alfa) {
  figura(x - s * 0.6f, y - s * 0.5f, x + s * 0.6f, y + s * 0.5f, solido(c, alfa, 8), [=](float px, float py) { return dElipse(px, py, x, y, s * 0.55f, s * 0.45f); });
  linea(x + s * 0.5f, y, x + s * 0.5f, y - s * 1.8f, s * 0.11f, c, alfa);
  linea(x + s * 0.5f, y - s * 1.8f, x + s * 1.15f, y - s * 1.35f, s * 0.11f, c, alfa);
}
// micrófono de mano: rejilla plateada, aro rojo y mango negro
static void micro(float x, float y, float ang, float e) {
  Marco m = marco(x, y, ang, e);
  pieza(m, -10, -4, 10, 64, plana(Col{60, 60, 74}, y, y + 64), [](float a, float b) { return dCaja(a, b, 0, 30, 8, 32, 6); });
  pieza(m, -12, -6, 12, 8, plana(Col{220, 70, 100}, y - 6, y + 8), [](float a, float b) { return dCaja(a, b, 0, 1, 11, 5, 2); });
  pieza(m, -22, -44, 22, 2, plana(Col{215, 220, 232}, y - 44, y, 0.8f), [](float a, float b) { return dElipse(a, b, 0, -20, 21, 23); });
  pieza(m, -20, -42, 20, 0, solido(Col{130, 136, 156}, 0.8f), [](float a, float b) {
    float g = fminf(fabsf(fmodf(a + 70, 7.f) - 3.5f), fabsf(b + 20)) - 0.9f;
    return fmaxf(g, dElipse(a, b, 0, -20, 19, 21));
  });
  pieza(m, -14, -38, 0, -24, solido(BLANCO, 0.9f), [](float a, float b) { return dElipse(a, b, -7, -31, 5, 4); });
}
// gorra roja de lado (urbano)
static void gorra(float x, float y, float ang) {
  Marco m = marco(x, y, ang);
  Col roja{230, 60, 70}, osc{160, 30, 40};
  pieza(m, 0, -16, 190, 10, plana(osc, y - 16, y + 10), [](float a, float b) { return dCaja(a, b, 120, -3, 72, 10, 10); });
  pieza(m, -158, -112, 158, 6, plana(roja, y - 112, y + 6, 0.82f), [](float a, float b) { return fmaxf(dElipse(a, b, 0, 0, 156, 108), b - 2); });
  pieza(m, -60, -100, 60, 4, solido(osc, 0.5f), [](float a, float b) { return fmaxf(fabsf(a) - 2.5f, fmaxf(dElipse(a, b, 0, 0, 156, 108), b - 2)); });
  pieza(m, -12, -124, 12, -100, plana(osc, y - 124, y - 100), [](float a, float b) { return dCirculo(a, b, 0, -110, 11); });
}
// sombrero vueltiao: ala ancha y copa crema con franjas negras
static void vueltiao(float x, float y, float ang) {
  Marco m = marco(x, y, ang);
  Col crema{245, 232, 200};
  pieza(m, -176, -30, 176, 34, plana(crema, y - 30, y + 34, 0.85f), [](float a, float b) { return dElipse(a, b, 0, 2, 174, 30); });
  pieza(m, -150, -24, 150, 30, solido(NEG), [](float a, float b) {
    return fminf(fabsf(dElipse(a, b, 0, 4, 140, 24)) - 3, fabsf(dElipse(a, b, 0, 4, 102, 17)) - 3);
  });
  pieza(m, -74, -112, 74, 8, plana(crema, y - 112, y + 8, 0.85f), [](float a, float b) { return dCaja(a, b, 0, -50, 72, 56, 30); });
  pieza(m, -74, -100, 74, 4, solido(NEG), [](float a, float b) {
    float d = fminf(fabsf(b + 82) - 3.5f, fabsf(b + 58) - 3.5f);
    d = fminf(d, fabsf(b + 2) - 5);
    return fmaxf(d, dCaja(a, b, 0, -50, 72, 56, 30));
  });
}
static void maracaLumi(float x, float y, float ang, const Col &c) {
  Marco m = marco(x, y, ang, 1.15f);
  pieza(m, -5, -52, 5, 2, plana(Col{200, 150, 90}, y - 52, y), [](float a, float b) { return dCaja(a, b, 0, -25, 4, 27, 4); });
  pieza(m, -26, -104, 26, -42, plana(c, y - 104, y - 42, 0.7f), [](float a, float b) { return dElipse(a, b, 0, -72, 24, 28); });
  pieza(m, -16, -94, 0, -76, solido(BLANCO, 0.9f), [](float a, float b) { return dElipse(a, b, -9, -84, 6, 5); });
}
// guitarra acústica de madera (cuerpo en 8, boca oscura, mástil café); ang: hacia dónde apunta el mástil (0 = arriba)
static void guitarraAcustica(float x, float y, float ang, float vibra) {
  Marco m = marco(x, y, ang, 1.25f);
  Col madera{226, 170, 95}, oscura{150, 95, 45}, mastil{110, 70, 35};
  pieza(m, -8, -178, 8, -20, plana(mastil, y - 170, y), [](float a, float b) { return dCaja(a, b, 0, -96, 6, 78, 3); });
  pieza(m, -14, -200, 14, -168, plana(oscura, y - 200, y - 168), [](float a, float b) { return dCaja(a, b, 0, -184, 12, 15, 5); });
  pieza(m, -48, -60, 48, 60, plana(madera, y - 60, y + 60, 0.8f), [](float a, float b) { return fminf(dElipse(a, b, 0, -28, 35, 30), dElipse(a, b, 0, 18, 46, 38)); });
  pieza(m, -16, -20, 16, 12, solido(Col{45, 30, 20}), [](float a, float b) { return dCirculo(a, b, 0, -6, 14); });
  pieza(m, -20, 28, 20, 38, solido(oscura), [](float a, float b) { return dCaja(a, b, 0, 33, 18, 4, 2); });
  for (int i = -1; i <= 1; i += 2) {
    float o = i * 2.5f, v = vibra;
    pieza(m, -8, -172, 8, 32, solido(Col{235, 230, 215}, 0.7f), [=](float a, float b) { return dSegmento(a - v * sinf(b * 0.2f), b, o, -170, o, 30, 0.7f); });
  }
}
// haces de luz de discoteca desde abajo
static void luces(float q, float g) {
  const float ox = CX, oy = H + 30, L = 460;
  for (int i = 0; i < 4; i++) {
    float a = -PI / 2 + 0.8f * sinf(q * 2 * PI * 2 + i * 1.6f), ab = 0.09f;
    Col c = COLORES[(i + (int)(q * 8)) % 5];
    float alfa = 0.16f + 0.18f * g;
    float n1x = sinf(a - ab), n1y = -cosf(a - ab), n2x = -sinf(a + ab), n2y = cosf(a + ab);   // normales hacia afuera de los dos bordes
    float ex1 = ox + cosf(a - ab) * L, ey1 = oy + sinf(a - ab) * L, ex2 = ox + cosf(a + ab) * L, ey2 = oy + sinf(a + ab) * L;
    figura(fminf(ox, fminf(ex1, ex2)), fminf(oy, fminf(ey1, ey2)), fmaxf(ox, fmaxf(ex1, ex2)), fmaxf(oy, fmaxf(ey1, ey2)), solido(c, alfa),
           [=](float px, float py) { float dx = px - ox, dy = py - oy; return fmaxf(dx * n1x + dy * n1y, dx * n2x + dy * n2y); });
  }
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
// flecha gruesa de Maps (dir = -1 izquierda, 1 derecha)
static void flecha(float x, float y, float dir, float alfa) {
  ConColor cc;
  Pintura p = degr(ORO, y - 30, y + 30, 16, alfa);
  figura(x - 90, y - 44, x + 90, y + 44, p, [=](float px, float py) {
    float cuerpo = dCaja(px, py, x - dir * 22, y, 52, 13, 13);
    float punta = dTriangulo(px, py, x + dir * 84, y, x + dir * 24, y - 42, x + dir * 24, y + 42) - 4;
    return fminf(cuerpo, punta);
  });
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
// manito haciendo cuernitos (rock): bolita con los dedos índice y meñique parados
static void manoCuernos(float x, float y, float r, float ang) {
  Marco m = marco(x, y, ang);
  auto forma = [=](float a, float b, float extra) {
    float d = dCirculo(a, b, 0, 0, r + extra);
    d = fminf(d, dSegmento(a, b, -r * 0.45f, -r * 0.5f, -r * 0.55f, -r * 1.9f, r * 0.24f + extra));
    d = fminf(d, dSegmento(a, b, r * 0.45f, -r * 0.5f, r * 0.55f, -r * 1.9f, r * 0.24f + extra));
    return d;
  };
  pieza(m, -r - 8, -r * 2.3f, r + 8, r + 8, solido(NEG), [=](float a, float b) { return forma(a, b, 4); });
  pieza(m, -r - 8, -r * 2.3f, r + 8, r + 8, blanca(y - r * 2, y + r), [=](float a, float b) { return forma(a, b, 0); });
}


// ---------------------------------------------------------------- accesorios nuevos de Cubi (mismo estilo: contorno negro y degradado)
static const Col dorado{255, 196, 60};
// saxofón: boquilla en (x, y), cuerpo hacia abajo y la campana abre hacia arriba a la derecha
static float dSaxo(float a, float b, float extra) {
  float d = dSegmento(a, b, 0, 0, 16, 32, 5 + extra);
  d = fminf(d, dSegmento(a, b, 16, 32, 24, 148, 13 + extra));
  d = fminf(d, dSegmento(a, b, 24, 148, 52, 174, 16 + extra));
  d = fminf(d, dSegmento(a, b, 52, 174, 80, 160, 16 + extra));
  return fminf(d, dCapsula2(a, b, 80, 160, 16 + extra, 104, 98, 30 + extra));
}
static void saxofon(float x, float y, float ang, float e) {
  Marco m = marco(x, y, ang, e);
  pieza(m, -24, -14, 142, 200, solido(NEG), [](float a, float b) { return dSaxo(a, b, 4); });
  pieza(m, -24, -14, 142, 200, plana(dorado, y, y + 180 * e, 0.72f), [](float a, float b) { return dSaxo(a, b, 0); });
  pieza(m, 70, 80, 140, 116, solido(Col{110, 62, 10}), [](float a, float b) { return dElipse(a, b, 104, 98, 24, 8); });   // boca de la campana
  for (int k = 0; k < 4; k++) {
    float ka = 19 + 2 * k, kb = 58 + 24 * k;
    pieza(m, ka - 8, kb - 8, ka + 8, kb + 8, solido(Col{255, 238, 170}), [=](float a, float b) { return dCirculo(a, b, ka, kb, 5.5f); });
  }
  pieza(m, -8, -8, 8, 8, solido(NEG), [](float a, float b) { return dCirculo(a, b, 0, 0, 5); });
}
// audífonos de DJ: diadema sobre la cabeza y orejeras con lucecita que late
static void audifonos(float y, float g, const Col &luz) {
  figura(CX - 200, y - 170, CX + 200, y - 10, solido(Col{58, 62, 82}), [=](float px, float py) {
    return fmaxf(fabsf(dElipse(px, py, CX, y, 186, 150)) - 7, py - (y - 30));
  });
  for (int lado = -1; lado <= 1; lado += 2) {
    float x = CX + lado * 186;
    figura(x - 26, y - 50, x + 26, y + 50, solido(NEG), [=](float px, float py) { return dCaja(px, py, x, y, 24, 48, 16); });
    figura(x - 22, y - 46, x + 22, y + 46, plana(Col{70, 76, 100}, y - 46, y + 46), [=](float px, float py) { return dCaja(px, py, x, y, 20, 44, 13); });
    figura(x - 10, y - 34, x + 10, y + 34, solido(luz, 0.45f + 0.55f * g, 10), [=](float px, float py) { return dCaja(px, py, x, y, 6, 30, 6); });
  }
}
// tornamesa: caja oscura, disco que gira y un fader
static void tornamesa(float x, float y, float giro, float fader) {
  figura(x - 160, y - 42, x + 160, y + 42, solido(NEG), [=](float px, float py) { return dCaja(px, py, x, y, 156, 38, 16); });
  figura(x - 156, y - 38, x + 156, y + 38, plana(Col{44, 48, 68}, y - 38, y + 38), [=](float px, float py) { return dCaja(px, py, x, y, 152, 34, 13); });
  float dx = x - 46;
  circulo(dx, y, 31, Col{14, 14, 20}, 1);
  figura(dx - 30, y - 30, dx + 30, y + 30, solido(Col{70, 74, 96}, 0.8f), [=](float px, float py) {
    float d = dCirculo(px, py, dx, y, 0); return fminf(fabsf(d - 24) - 0.8f, fabsf(d - 16) - 0.8f);
  });
  int ci = (int)fabsf(giro / 6.283f) % 5;
  circulo(dx, y, 9, COLORES[ci], 1, 6);
  linea(dx + cosf(giro) * 12, y + sinf(giro) * 12, dx + cosf(giro) * 28, y + sinf(giro) * 28, 1.6f, BLANCO, 0.6f);
  float fx = x + 70;
  linea(fx, y - 22, fx, y + 22, 2, Col{20, 22, 30}, 1);
  figura(fx - 14, y - 30, fx + 14, y + 30, plana(BL, y - 30, y + 30), [=](float px, float py) { return dCaja(px, py, fx, y - 16 + 32 * fader, 12, 6, 3); });
  for (int i = 0; i < 3; i++) circulo(x + 112, y - 18 + 18 * i, 6, COLORES[(i + ci) % 5], 0.9f, 6);
}
// teclado de piano: teclas blancas, negras y las que suenan se iluminan
static int tecla(int n, int cual) { return cual ? (n * 7 + 4) % 12 : (n * 5) % 12; }
static float teclaX(int k) { return CX - 196 + 392.f / 12 * (k + 0.5f); }
static void piano(float y, int n, float g) {
  const float x0 = CX - 196, kw = 392.f / 12;
  figura(x0 - 6, y - 40, CX + 202, y + 40, solido(NEG), [=](float px, float py) { return dCaja(px, py, CX, y, 200, 38, 10); });
  figura(x0, y - 34, CX + 196, y + 34, plana(BL, y - 34, y + 34), [=](float px, float py) { return dCaja(px, py, CX, y, 196, 34, 7); });
  for (int k = 0; k < 12; k++) {
    float kx = teclaX(k);
    if (k == tecla(n, 0) || k == tecla(n, 1))
      figura(kx - kw / 2, y - 34, kx + kw / 2, y + 34, solido(COLORES[k % 5], 0.35f + 0.6f * g, 10),
             [=](float px, float py) { return dCaja(px, py, kx, y + 4, kw / 2 - 3, 28, 5); });
    if (k) linea(x0 + kw * k, y - 32, x0 + kw * k, y + 32, 1.2f, Col{150, 160, 190}, 1);
    int o = k % 7;
    if (k < 11 && o != 2 && o != 6) {
      float bx = x0 + kw * (k + 1);
      figura(bx - 9, y - 36, bx + 9, y + 4, solido(NEG), [=](float px, float py) { return dCaja(px, py, bx, y - 16, 8, 19, 3); });
    }
  }
}

// ---------------------------------------------------------------- caras de fondo
static float pulsosVistos = 0, ultimoPulso = 1;

// ---------------------------------------------------------------- Robot minimalista: los bailes
// Los ojos no se transforman: son los de siempre, cabecean con el golpe, se mecen y miran el instrumento.
// El baile lo hace el instrumento (extrasMini) y la boquita, que solo abre cuando el artista canta.
static void bailaMini(Rostro &r, const Entradas &e, float g, float vaiven, float bl) {
  bool lento = e.ritmo == R_CORAZON || e.ritmo == R_JAZZ || e.ritmo == R_PIANO || e.ritmo == R_DISFRUTA || e.ritmo == R_GUITARRA;
  mover(r, (lento ? 12 : 6) * vaiven, -18 + (lento ? 5 : 10) * g);
  mirar(r, 0, 0.2f); tapas(r, bl);
}

static void anillo(float x, float y, float R, float grosor, float alfa) {
  figura(x - R - grosor, y - R - grosor, x + R + grosor, y + R + grosor, solido(BL, alfa, 6),
         [=](float px, float py) { return fabsf(dCirculo(px, py, x, y, R)) - grosor; });
}

// ---------------------------------------------------------------- instrumentos de línea (Robot minimalista)
// Como en el robot del tablero: el instrumento es un dibujo de línea blanca y lo toca una manito blanca rellena.
static inline float smin(float a, float b, float k) { return -smax(-a, -b, k); }
static void aPantalla(const Marco &m, float a, float b, float &x, float &y) { x = m.x + (a * m.c - b * m.s) * m.e; y = m.y + (a * m.s + b * m.c) * m.e; }
static void arcoTrazo(float x, float y, float R, float a0, float a1, float g, float alfa) {
  figura(x - R - g, y - R - g, x + R + g, y + R + g, solido(BL, alfa, 6), [=](float px, float py) { return dArco(px, py, x, y, R, a0, a1) - g; });
}
// manito: palma con pulgar; "apunta" saca el índice, si no es un puño que agarra. ang = hacia dónde van los dedos
static void manoBlanca(float x, float y, float ang, float e, bool apunta, float alfa = 1) {
  Marco m = marco(x, y, ang, e);
  auto d = [=](float a, float b) {
    float p = dElipse(a, b, 0, 0, 19, 15);
    p = smin(p, apunta ? dSegmento(a, b, 8, -5, 34, -5, 6.5f) : dSegmento(a, b, 6, -3, 16, -3, 11), 5);
    return smin(p, dSegmento(a, b, 2, 9, 14, 13, 5.5f), 4);
  };
  pieza(m, -27, -25, 45, 25, solido(NEG, alfa), [=](float a, float b) { return d(a, b) - 4; });   // borde negro: la separa de lo que toca
  pieza(m, -22, -20, 40, 20, solido(BL, alfa, 6), d);
}
// disco de vinilo de línea: borde, surcos que giran y el centro
static void disco(float x, float y, float R, float giro, float alfa) {
  anillo(x, y, R, 2.6f, alfa);
  for (int k = 0; k < 2; k++) {
    float a0 = giro + k * PI;
    arcoTrazo(x, y, R * 0.7f, a0, a0 + 1.3f, 2.1f, alfa);
    arcoTrazo(x, y, R * 0.47f, a0 + 0.8f, a0 + 1.9f, 2.1f, alfa);
  }
  anillo(x, y, R * 0.17f, 2, alfa);
}
// guitarra de línea, acostada sobre el eje del marco (cuerpo en 0, mástil hacia +a). vibra = cuánto vibran las cuerdas
static void guitarraLinea(float x, float y, float ang, float e, bool electrica, float vibra, float alfa) {
  Marco m = marco(x, y, ang, e);
  const float t = 2.6f, n0 = electrica ? 32 : 60;
  auto cuerpo = [=](float a, float b) {
    if (!electrica) return smin(dCirculo(a, b, 0, 0, 34), dCirculo(a, b, 40, 0, 25), 12);   // acústica: dos curvas y la cintura
    float d = smin(dElipse(a, b, 0, 0, 36, 26), dSegmento(a, b, 8, -12, 34, -30, 9), 8);    // eléctrica: dos cuernos
    return smin(d, dSegmento(a, b, 8, 12, 28, 26, 8), 8);
  };
  pieza(m, -42, -46, 72, 42, solido(BL, alfa, 6), [=](float a, float b) { return fabsf(cuerpo(a, b)) - t; });
  pieza(m, n0 - 4, -9, 134, 9, solido(BL, alfa, 6), [=](float a, float b) { return fabsf(dCaja(a, b, (n0 + 130) / 2, 0, (130 - n0) / 2, 5.5f, 3)) - t * 0.8f; });
  pieza(m, 126, -14, 162, 14, solido(BL, alfa, 6), [=](float a, float b) { return fabsf(dCaja(a, b, 144, electrica ? -3 : 0, 12, 8, 4)) - t * 0.8f; });
  if (electrica) for (int i = 0; i < 2; i++) {                       // pastillas
    float pa = -12 + i * 14;
    pieza(m, pa - 6, -15, pa + 6, 15, solido(BL, alfa), [=](float a, float b) { return fabsf(dCaja(a, b, pa, 0, 3, 11, 2)) - 1.8f; });
  } else pieza(m, 8, -14, 36, 14, solido(BL, alfa), [=](float a, float b) { return fabsf(dCirculo(a, b, 22, 0, 10)) - 2.2f; });   // boca
  pieza(m, -20, -9, -12, 9, solido(BL, alfa), [=](float a, float b) { return dSegmento(a, b, -16, -7, -16, 7, 2.2f); });               // puente
  for (int i = -1; i <= 1; i += 2) {                                  // cuerdas
    float yy = i * 2.4f;
    pieza(m, -18, -12, 134, 12, solido(BL, 0.75f * alfa), [=](float a, float b) {
      float v = vibra * sinf(a * 0.35f + tAhora * 70) * fmaxf(0.f, 1 - fabsf(a - 30) / 90);
      return fmaxf(fabsf(b - yy - v) - 0.9f, fabsf(a - 58) - 74);
    });
  }
}
// saxofón de línea, de pie: boquilla arriba, campana abajo que sube a la derecha
static void saxoLinea(float x, float y, float ang, float e, float alfa) {
  Marco m = marco(x, y, ang, e);
  auto tubo = [=](float a, float b) { return smin(dCapsula2(a, b, -6, -70, 5, 0, 22, 13), dCapsula2(a, b, 6, 36, 13, 34, 4, 19), 10); };
  pieza(m, -24, -80, 58, 54, solido(BL, alfa, 6), [=](float a, float b) { return fabsf(tubo(a, b)) - 2.6f; });
  pieza(m, -24, -92, -2, -64, solido(BL, alfa, 6), [=](float a, float b) { return dSegmento(a, b, -6, -70, -18, -86, 3); });    // boquilla
  for (int i = 0; i < 3; i++) { float kx, ky; aPantalla(m, -3 + i * 1.5f, -40 + i * 18, kx, ky); circulo(kx, ky, 3.5f * e, BL, alfa); }   // llaves
}
// micrófono de línea: cabeza en (x, y) y el mango hacia ang
static void microLinea(float x, float y, float ang, float e, float alfa) {
  Marco m = marco(x, y, ang, e);
  pieza(m, -20, -20, 20, 20, solido(BL, alfa, 6), [=](float a, float b) { return fabsf(dCirculo(a, b, 0, 0, 15)) - 2.6f; });
  pieza(m, -14, -14, 14, 14, solido(BL, 0.7f * alfa), [=](float a, float b) { return fmaxf(fminf(fabsf(a) - 1.2f, fabsf(b) - 1.2f), dCirculo(a, b, 0, 0, 13)); });
  pieza(m, 12, -10, 66, 10, solido(BL, alfa, 6), [=](float a, float b) { return fabsf(dCapsula2(a, b, 17, 0, 7, 60, 0, 4.5f)) - 2.4f; });
}
// batería de línea: redoblante a la izquierda, platillo a la derecha y dos manitos con baquetas que golpean por turnos
static void bateria(int n, float g, float alfa) {
  bool izq = n % 2 == 0;
  const float sx = CX - 112, sy = H - 98;
  figura(sx - 40, sy - 14, sx + 40, sy + 42, solido(BL, alfa, 6), [=](float px, float py) {
    float arriba = fabsf(dElipse(px, py, sx, sy, 34, 9)) - 2.4f;
    float lados = fmaxf(fabsf(fabsf(px - sx) - 34) - 2.4f, fabsf(py - sy - 14) - 14);
    float abajo = fmaxf(fabsf(dElipse(px, py, sx, sy + 28, 34, 9)) - 2.4f, sy + 28 - py);
    return fminf(arriba, fminf(lados, abajo));
  });
  const float cx = CX + 112, cy = H - 104, inc = izq ? 0 : 0.14f * g;          // platillo que se mece al golpearlo
  linea(cx - 42, cy + 5 - 42 * inc, cx + 42, cy - 5 + 42 * inc, 3, BL, alfa, 6);
  linea(cx, cy, cx, H - 14, 2, BL, 0.6f * alfa);
  for (int lado = -1; lado <= 1; lado += 2) {
    float sube = (lado < 0) == izq ? 1 - g : 0.75f;
    float tx = lado < 0 ? sx + 6 : cx - 14, ty = (lado < 0 ? sy - 4 : cy - 4) - 20 * sube;
    float hx = tx + lado * 40, hy = ty - 14;                                    // las manitos por fuera: no tapan los ojos
    linea(tx, ty, hx, hy, 2.6f, BL, alfa);
    manoBlanca(hx, hy, lado < 0 ? PI - 0.6f : 0.6f, 0.75f, false, alfa);
  }
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

static void extrasMini(float u, const Entradas &e) {
  int n = (int)pulsosVistos;
  float g = expf(-e.pulso * 6), f = n + e.pulso, vaiven = sinf(f * PI);
  switch (e.ritmo) {
    case R_ROCK: case R_GROOVE: {                       // guitarra eléctrica: la manito rasguea en cada golpe
      float gx = CX - 62, gy = H - 48, ang = -0.3f - 0.08f * g, hx, hy;   // más acostada: el mástil pasa debajo de la boquita
      guitarraLinea(gx, gy, ang, 1, true, 4 * g, 1);
      aPantalla(marco(gx, gy, ang, 1), 0, -14 + 26 * (1 - g), hx, hy);
      manoBlanca(hx, hy, ang + 1.9f, 0.85f, false);
      break;
    }
    case R_GUITARRA: case R_DISFRUTA: {                 // guitarra acústica: rasguea dos veces por golpe
      float rasga = expf(-fmodf(e.pulso * 2, 1.f) * 6), gx = CX - 70, gy = H - 50, ang = -0.28f, hx, hy;
      guitarraLinea(gx, gy, ang, 1.05f, false, 3 * rasga, 1);
      aPantalla(marco(gx, gy, ang, 1.05f), 22, -16 + 26 * (1 - rasga), hx, hy);
      manoBlanca(hx, hy, ang + 1.9f, 0.85f, false);
      break;
    }
    case R_TROPICAL:                                    // maracas a los lados: se sacuden en el 2 y el 4
      for (int s2 = -1; s2 <= 1; s2 += 2) {
        float sh = n % 2 ? g : 0.25f * g, x = CX + s2 * 172, y = EY + 70;
        float ang = s2 * 0.35f + 0.5f * sh * sinf(u * 40), hx = x + sinf(ang) * 38, hy = y - cosf(ang) * 38;
        linea(x, y, hx, hy, 4, BL, 0.95f);
        volumen(hx - 16, hy - 16, hx + 16, hy + 16, 0.95f, [=](float px, float py) { return dCirculo(px, py, hx, hy, 15); });
      }
      break;
    case R_JAZZ: {                                      // saxofón con la manito: se mece de lado a lado con el golpe
      const float sxx = W - 110 + 26 * vaiven, syy = H - 64 - 6 * fabsf(vaiven), ang = 0.2f * vaiven, se = 0.95f;
      float hx, hy;
      saxoLinea(sxx, syy, ang, se, 1);
      aPantalla(marco(sxx, syy, ang, se), -4, -28, hx, hy); manoBlanca(hx, hy, -0.3f + ang, 0.75f, false);
      break;
    }
    case R_DJ: case R_ELECTRO: case R_BARRAS: {         // disco a la derecha (la boquita queda libre) y la manito que lo raya
      float scratch = (n % 4 >= 2) ? sinf(f * PI * 2) : 0, x = CX + 122, y = H - 66;
      disco(x, y, 48, u * 4 + 0.9f * scratch, 1);
      manoBlanca(x - 44 + 10 * scratch, y - 30 + 6 * scratch, 0.45f, 1.15f, true);
      break;
    }
    case R_PIANO: case R_CORAZON: {                     // teclas que tocan una melodía: la que suena se ilumina
      static const uint8_t MEL[8] = {0, 2, 4, 5, 4, 2, 1, 3};
      int t = MEL[n % 8] + ((n / 8) % 2) * 3;
      for (int i = 0; i < 9; i++) {
        float x = CX + (i - 4) * 34, y = H - 40, a = i == t ? 0.4f + 0.6f * g : 0.3f;
        figura(x - 15, y - 26, x + 15, y + 26, solido(BL, a, i == t ? 8 : 0), [=](float px, float py) { return dCaja(px, py, x, y, 13, 24, 13); });
      }
      break;
    }
    case R_KPOP: {                                      // micrófono de línea frente a la boquita, con la manito
      float mx = ahora.bx + 56, my = ahora.by + 4, ang = 0.95f, hx, hy;
      microLinea(mx, my, ang, 1.3f, 1);
      aPantalla(marco(mx, my, ang, 1.3f), 42, 0, hx, hy);
      manoBlanca(hx, hy, ang - PI / 2, 0.85f, false);
      break;
    }
    default: bateria(n, g, 1); break;                   // pop y urbano: batería
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
      mirada(t, lx, ly); mirar(r, 0.5f * lx, 0.5f * ly); tapas(r, fmaxf(bl, (elPaquete == MINIMALISTA ? 0.4f : 0.58f) + 0.06f * sinf(t * 0.8f)));
      mover(r, 26 * sinf(t * 0.051f), 14 * sinf(t * 0.033f + 1));
      vel = 3;
      break;
    case ESCUCHA:
      escalar(r, 1.08f, 1.08f); r.L.y -= 6; r.R.y += 6; mirar(r, 0.25f, -0.1f); tapas(r, bl * 0.9f);
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
      if (elPaquete == MINIMALISTA) bailaMini(r, e, g, vaiven, bl);
      else switch (e.ritmo) {
        case R_URBANO:                                        // gorra, mirada tranquila, cabecea fuerte y rapea
          escalar(r, 0.92f, 0.86f - 0.06f * g); mover(r, 0, 26 + 12 * g); tapas(r, 0.38f);
          r.bx = CX - 24; r.by = MY + 14 + 6 * g;
          break;
        case R_ELECTRO: {                                     // salta con las manitos arriba entre luces
          float salto = 1 - (2 * e.pulso - 1) * (2 * e.pulso - 1);
          mover(r, 16 * sinf((n + e.pulso) * PI * 0.5f), 12 - 40 * salto);
          if (e.pulso < 0.12f) escalar(r, 1.1f, 0.86f);
          if (salto > 0.45f) feliz(r, 1); else tapas(r, bl);
          break;
        }
        case R_TROPICAL:                                      // sombrero vueltiao y maracas, se mece como cadera
          escalar(r, 0.8f, 0.76f); mover(r, 30 * vaiven, 52 - 4 * g);
          if (fmodf(n + e.pulso, 1.f) < 0.5f) feliz(r, 1); else tapas(r, bl);
          abre = 0.8f;
          break;
        case R_KPOP: {                                        // la V sobre un ojo, guiñando el otro
          float lado = 12 * sinf((n + e.pulso) * PI * 0.5f), v = 0.5f + 0.5f * cosf((n + e.pulso) * PI * 0.5f);   // 1 = V derecha puesta
          mover(r, lado, -8 * g);
          if (v < 0.3f) { r.R.feliz = 1; r.R.tapa = 0; } else if (v > 0.7f) { r.L.feliz = 1; r.L.tapa = 0; }
          abre = 0.7f;
          break;
        }
        case R_CORAZON:                                       // corazoncito coreano con los dedos
          mover(r, 12 * sinf((n + e.pulso) * PI * 0.5f), -18 - 8 * g); feliz(r, 1);
          abre = 0.7f;
          break;
        case R_GROOVE: {                                      // "cool": entrecerrados, mira a un lado por golpe y chasquea
          float lado = (n % 2) ? -1 : 1, mira = suave(fminf(1, e.pulso * 4));
          mirar(r, 0.45f * (lado * mira - lado * (1 - mira)), 0);
          mover(r, 0, -14 + 8 * g); escalar(r, 1, 1 - 0.12f * g); tapas(r, 0.42f);
          r.L.incl = r.R.incl = 0.08f * lado * mira;
          abre = 0.7f;
          break;
        }
        case R_DISFRUTA:                                      // ojitos cerrados disfrutando, se mece despacio
          feliz(r, 1); mirar(r, 0.4f * sinf((n + e.pulso) * PI * 0.25f), 0); mover(r, 0, -4 * k); abre = 0.6f;
          break;
        case R_GUITARRA:                                      // toca la guitarra acústica
          feliz(r, 1); escalar(r, 0.9f, 0.9f); mover(r, 30 + 8 * sinf((n + e.pulso) * PI * 0.25f), -26); abre = 0.7f;
          break;
        case R_ROCK:                                          // cabeceo de rockero con cuernitos
          mover(r, 0, 10 - 28 * k); escalar(r, 1 + 0.06f * k, 1 - 0.16f * k); tapas(r, 0.32f);
          r.L.incl = r.R.incl = 0.1f;
          break;
        case R_JAZZ:                                          // "cool" con el saxo: ojos entrecerrados, se mece lento
          escalar(r, 0.84f, 0.84f); mover(r, -44 + 8 * vaiven, -30 + 6 * g); tapas(r, 0.35f);
          r.L.incl = r.R.incl = -0.06f * vaiven;   // con voz: cantante de jazz (micrófono); sin voz: saxo
          break;
        case R_DJ:                                            // con audífonos: cabecea con el golpe y se alegra
          escalar(r, 0.86f, 0.86f - 0.06f * g); mover(r, 0, -36 + 10 * g);
          if (g > 0.5f) feliz(r, 1); else tapas(r, bl);
          abre = 0.7f;
          break;
        case R_PIANO:                                         // disfruta tocando: ojitos felices y se mece
          feliz(r, 1); escalar(r, 0.88f, 0.88f); mover(r, 14 * sinf((n + e.pulso) * PI * 0.5f), -44);
          abre = 0.7f;
          break;
        case R_BARRAS:                                        // las barritas suben con la música y los ojos saltan
          escalar(r, 1 + 0.05f * g, 0.9f - 0.06f * g); mover(r, 0, -40 - 10 * e.nivel);
          if (n % 4 == 3 && g > 0.4f) feliz(r, 1); else tapas(r, bl);
          abre = 0.75f;
          break;
        default:                                              // pop: canta con micrófono
          mover(r, 0, -14 * k); escalar(r, 1 + 0.05f * k, 1 - 0.08f * k);
          mirar(r, 0.35f * ((n % 4 < 2) ? -1 : 1), 0);
          if (n % 8 >= 6) feliz(r, 0.85f); else tapas(r, bl);
          break;
      }
      // con voz la boca sigue al cantante (entre sílabas se cierra); sin voz no hay boca: solo baila o toca
      r.boca = e.canta && abre > 0 ? fmaxf(e.boca * abre, 0.07f) : 0; r.bAncho = 0.75f + 0.6f * e.ancho;
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

static void extrasBase(float t, const Entradas &e, bool delante) {
  float u = t - baseDesde;
  if (!delante && laBase != CANTA) return;
  if (laBase == CANTA && elPaquete == MINIMALISTA) { if (delante) extrasMini(u, e); return; }
  switch (laBase) {
    case ESCUCHA: {
      for (int i = 0; i < 3; i++) {      // ondas de sonido al lado
        float p = fmodf(u * 0.9f + i / 3.f, 1.f), R = 14 + p * 30, x = CX + EX + 66, y = EY;
        float a = campana(p, 0, 1) * 0.7f;
        Rostro r = neutro(AZUL);
        figura(x, y - R, x + R + 4, y + R, tonoP(r, y - R, y + R, 6, a), [=](float px, float py) {
          float d = fabsf(dCirculo(px, py, x, y, R)) - 3;
          return fmaxf(d, x + R * 0.35f - px);
        });
      }
      break;
    }
    case CANTA: {
      int n = (int)pulsosVistos;
      float g = expf(-e.pulso * 6), vaiven = sinf((n + e.pulso) * PI);
      const Ojo &oL = ahora.L, &oR = ahora.R;
      switch (e.ritmo) {
        case R_URBANO:
          if (!delante) break;
          gorra(CX - 10, oL.y - oL.h / 2 + 8, -0.12f);
          if (!e.canta) { manito(W - 70, H - 60 - 30 * g, 30, 0.3f); break; }   // sin voz: la manito sigue el golpe
          manito(ahora.bx + 64, ahora.by + 2, 32, -1.25f);              // rapea: manito de bocina junto a la boca
          if (g > 0.35f) for (int i = 0; i < 3; i++) {
            float a = -0.5f + i * 0.5f, x = ahora.bx + 104, y = ahora.by;
            linea(x + cosf(a) * 8, y + sinf(a) * 8, x + cosf(a) * (8 + 18 * g), y + sinf(a) * (8 + 18 * g), 2.5f, BL, 1);
          }
          break;
        case R_ELECTRO: {
          float salto = 1 - (2 * e.pulso - 1) * (2 * e.pulso - 1);
          if (!delante) {
            luces(fmodf((n % 8 + e.pulso) / 8.f, 1.f), g);
          } else for (int lado = -1; lado <= 1; lado += 2) {
            float x = (lado < 0 ? oL.x : oR.x) + lado * (oL.w / 2 + 40 + 8 * salto), y = oL.y + 30 - 60 * salto;
            manito(x, y, 32, lado * (0.35f + 0.4f * salto));
          }
          break;
        }
        case R_TROPICAL:
          if (!delante) break;
          vueltiao(CX + 34 * vaiven, oL.y - oL.h / 2 - 2 - 4 * g, -0.16f * vaiven);
          for (int lado = -1; lado <= 1; lado += 2) {
            float s2 = lado * sinf((n + e.pulso) * PI), x = CX + lado * 140 + 10 * vaiven, y = H - 42 - 12 * s2;
            maracaLumi(x, y - 6, lado * 0.15f + 0.3f * s2, lado < 0 ? COLORES[0] : COLORES[3]);
            manito(x, y, 30, lado * 0.35f);
          }
          break;
        case R_KPOP: {
          if (!delante) break;
          {
            float v = 0.5f + 0.5f * cosf((n + e.pulso) * PI * 0.5f);
            for (int sgn = -1; sgn <= 1; sgn += 2) {
              float dentro = suave(sgn > 0 ? v : 1 - v);
              const Ojo &o = sgn > 0 ? oR : oL;
              float x = o.x + sgn * (o.w / 2 + 30 + 20 * (1 - dentro)), y = o.y + 120 * (1 - dentro);
              manoV(x, y, 1.3f + 0.4f * dentro, -sgn * (PI / 2) * dentro + sgn * 0.4f * (1 - dentro), 0.12f + 0.12f * dentro);
            }
          }
          break;
        }
        case R_CORAZON: {
          if (!delante) break;
          {
            float x = CX + 12 * sinf((n + e.pulso) * PI * 0.5f), y = MY + 34 - 8 * g;
            linea(x - 8, y - 26, x + 14, y - 56, 7, BL, 1); linea(x + 16, y - 26, x - 6, y - 58, 7, BL, 1);
            manito(x, y, 34, -0.4f);
            corazonito(x + 4, y - 90, 20 + 10 * g, 1);
          }
          break;
        }
        case R_GROOVE: {
          if (!delante) break;
          float lado = (n % 2) ? -1 : 1;
          float hx = lado > 0 ? fminf(oR.x + oR.w / 2 + 46, W - 40) : fmaxf(oL.x - oL.w / 2 - 46, 40), hy = EY + 50 - 10 * g;
          manito(hx, hy, 32, lado * (0.45f - 0.5f * g));
          if (e.pulso < 0.25f) destellito(hx - lado * 6, hy - 46, 14 * (1 - e.pulso * 4) + 6, 1);
          for (int i = 0; i < 2; i++) {
            float q = fmodf(u * 0.6f + i / 2.f, 1.f);
            notaColor(CX - lado * (150 + 20 * q), H - 50 - 150 * q, 24 * (1 - 0.3f * q), COLORES[(i * 3 + n) % 5], campana(q, 0, 1));
          }
          break;
        }
        case R_DISFRUTA:
          if (!delante) break;
          {
            for (int i = 0; i < 3; i++) {
              float q = fmodf(u * 0.3f + i / 3.f, 1.f), lado = (i % 2) ? -1 : 1;
              notaColor(CX + lado * (175 + 10 * q) + 6 * sinf(q * 6), H - 40 - 200 * q, 26 * (1 - 0.3f * q), COLORES[(i * 2) % 5], campana(q, 0, 1));
            }
          }
          break;
        case R_GUITARRA:
          if (!delante) break;
          {
            float rasga = sinf((n + e.pulso) * PI * 2), gx = 118, gy = 284, ang = 0.95f;
            guitarraAcustica(gx, gy, ang, 1.5f * fabsf(rasga));
            manito(gx + 8, gy - 4 + 14 * rasga, 28, -0.3f + 0.4f * rasga);                         // rasguea
            manito(gx + sinf(ang) * 165, gy - cosf(ang) * 165, 24, ang - 1.2f);                   // en el mástil
            for (int i = 0; i < 2; i++) {
              float q = fmodf(u * 0.5f + i / 2.f, 1.f);
              notaColor(W - 80 + 14 * q, H - 60 - 160 * q, 24 * (1 - 0.3f * q), COLORES[(i * 2 + 1) % 5], campana(q, 0, 1));
            }
          }
          break;
        case R_ROCK:
          if (!delante) break;
          for (int lado = -1; lado <= 1; lado += 2) {
            float bombea = expf(-fmodf(e.pulso + (lado > 0 ? 0.5f : 0), 1.f) * 5);
            manoCuernos(CX + lado * 165, H - 70 - 40 * bombea, 26, lado * 0.25f);
          }
          break;
        case R_JAZZ: {
          if (!delante) break;
          if (e.canta) {                                        // cantante de jazz: micrófono y la otra mano al aire
            micro(ahora.bx + 74, ahora.by + 30, -0.55f, 1.6f);
            manito(ahora.bx + 96, ahora.by + 66, 30, -0.5f);
            manito(fmaxf(ahora.L.x - ahora.L.w / 2 - 40, 40), EY + 40 - 14 * vaiven, 28, -0.3f);
            break;
          }
          float bx = ahora.bx, by = ahora.by - 4, ang = -0.95f + 0.06f * vaiven, e2 = 0.85f, c = cosf(ang), sn = sinf(ang);
          auto en = [&](float a, float b, float &wx, float &wy) { wx = bx + (a * c - b * sn) * e2; wy = by + (a * sn + b * c) * e2; };
          saxofon(bx, by, ang, e2);
          float hx, hy;
          en(22, 92, hx, hy); manito(hx, hy + 4, 20, -0.4f + 0.15f * g);   // las manitos en las llaves del cuerpo
          en(26, 140, hx, hy); manito(hx, hy + 4, 20, -0.3f);
          float sx, sy; en(104, 98, sx, sy);
          for (int i = 0; i < 3; i++) {
            float q = fmodf(u * 0.45f + i / 3.f, 1.f);
            notaColor(sx + 20 + 60 * q + 8 * sinf(q * 7 + i), sy - 20 - 170 * q, 24 * (1 - 0.3f * q), i ? dorado : COLORES[1], campana(q, 0, 1));
          }
          break;
        }
        case R_DJ: {
          if (!delante) {
            for (int i = 0; i < 14; i++) {                    // confeti de luces de fondo
              float q = fmodf(u * (0.2f + 0.1f * hash(i, 3)) + hash(i, 4), 1.f);
              circulo(hash(i, 5) * W, q * H, 2.5f, COLORES[i % 5], 0.6f * campana(q, 0, 1));
            }
            break;
          }
          audifonos(ahora.L.y, g, COLORES[(n / 2) % 5]);
          float scratch = sinf((n + e.pulso) * PI * 2);
          tornamesa(CX, H - 40, u * 6 - 0.6f * scratch, 0.5f + 0.4f * sinf((n + e.pulso) * PI * 0.5f));
          manito(CX - 46 + 16 * scratch, H - 54, 26, -0.5f + 0.2f * scratch);                    // rasca el disco
          manito(fminf(ahora.R.x + ahora.R.w / 2 + 64, W - 36), EY + 10 - 40 * g, 28, 0.25f);   // la otra arriba, al ritmo
          break;
        }
        case R_PIANO: {
          if (!delante) break;
          piano(H - 40, n, g);
          manito(teclaX(tecla(n, 0)), H - 66 + 10 * (1 - g), 26, -0.1f);
          manito(teclaX(tecla(n, 1)), H - 66 + 10 * g, 26, 0.1f);
          for (int i = 0; i < 3; i++) {
            float q = fmodf(u * 0.35f + i / 3.f, 1.f), lado = (i % 2) ? -1 : 1;
            notaColor(CX + lado * (170 + 12 * q), H - 90 - 190 * q, 24 * (1 - 0.3f * q), COLORES[(i * 2 + n) % 5], campana(q, 0, 1));
          }
          break;
        }
        case R_BARRAS: {
          if (!delante) break;
          for (int i = 0; i < 11; i++) {
            float x = CX + (i - 5) * 36, a = 0.45f + 0.55f * fabsf(sinf(u * (2.6f + 0.37f * i) + i * 1.7f));
            float h = 12 + 78 * lim(e.nivel * 1.4f, 0.15f, 1) * a * (0.65f + 0.35f * g);
            Col c = COLORES[(i + n) % 5];
            figura(x - 14, H - 14 - h, x + 14, H - 10, plana(c, H - 14 - h, H - 10, 0.6f),
                   [=](float px, float py) { return dCaja(px, py, x, H - 12 - h / 2, 12, h / 2 + 2, 6); });
            circulo(x, H - 34 - h, 5, c, 0.7f * g, 8);   // puntito que salta encima
          }
          break;
        }
        default:
          if (!delante) break;
          if (e.canta) {                                        // canta con micrófono
            micro(ahora.bx + 74, ahora.by + 30, -0.55f, 1.6f);
            manito(ahora.bx + 96, ahora.by + 66, 30, -0.5f);
          } else for (int lado = -1; lado <= 1; lado += 2)      // parte sin voz: aplaude al golpe
            manito(CX + lado * (60 + 50 * (1 - g)), H - 56, 30, lado * 0.4f);
          for (int i = 0; i < 2; i++) {
            float q = fmodf(u * 0.35f + i / 2.f, 1.f), x = 46 + sinf(q * 6 + i) * 10, y = H - 40 - q * 240;
            notaColor(i ? W - x - 20 : x, y, 26, COLORES[(i * 2 + n / 2) % 5], campana(q, 0, 1) * 0.9f);
          }
          break;
      }
      break;
    }
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
    case RISA:
      feliz(r, 1);
      mover(r, 0, 6 * sinf(u * 44));
      r.boca = 0.7f + 0.25f * fabsf(sinf(u * 14)); r.bAncho = 1.9f;
      
      break;
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
      r.boca = (0.2f + 0.12f * sinf(u * 9)) * cansa;               // jadea, nada de boca brava
      vel = 20;
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
    case MAPS_IZQ:
    case MAPS_DER: {                                                 // mira hacia donde hay que girar
      float lado = laReac == MAPS_IZQ ? -1 : 1, k = tramo(u, 0, 0.25f) * (1 - tramo(u, 2.6f, 3.0f));
      mirar(r, 0.8f * lado * k, 0.15f * k); mover(r, 0, 26 * k);
      vel = 20;
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
      r.boca = 0.12f + 0.06f * fabsf(sinf(u * 40));
      vel = 40;
      break;
    }
    case BATERIA:                                                    // cansada, se le cierran los ojos
      tapas(r, 0.45f + 0.15f * sinf(u * 1.5f)); r.L.incl = r.R.incl = -0.12f;
      escalar(r, 0.9f, 0.86f); mover(r, 0, 54);
      vel = 8;
      break;
    case GUINO:
      if (u > 0.15f && u < 1.0f) { r.R.feliz = 1; r.R.y -= 6; }
      r.L.incl = -0.05f; mover(r, 0, -4 * campana(u, 0.1f, 1.0f));
      vel = 25;
      break;
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

static void extrasReac(float u, bool delante) {
  switch (laReac) {
    case SALUDO:
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
      if (delante) for (int s = -1; s <= 1; s += 2) {                // lagrimitas de risa
        float p = fmodf(u * 1.6f + (s > 0 ? 0.5f : 0), 1.f), a = tramo(u, 0, 0.15f) * (1 - tramo(u, 1.75f, 2.0f));
        gota(CX + s * (EX + 66 + p * 20), EY - 10 + p * 60, 9, a * campana(p, 0, 1));
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
        for (int i = 0; i < 4; i++) {                                // gotas de sudor que caen
          float p = fmodf(u * 0.5f + i * 0.27f, 1.f), s = (i % 2) ? 1 : -1;
          gota(CX + s * (EX + 70 + 12 * (i / 2)), EY - 70 + p * 150, 10, a * campana(p, 0, 1));
        }
      } else {
        float a = tramo(u, 0.2f, 0.6f) * (1 - tramo(u, 7.0f, 7.5f));
        for (int i = 0; i < 3; i++) {                                // aire caliente que sube
          float x0 = CX + (i - 1) * 150, p = fmodf(u * 0.4f + i * 0.3f, 1.f);
          for (int k = 0; k < 5; k++) {
            float ya = 60 + k * 18 - p * 30, yb = ya + 18;
            linea(x0 + 8 * sinf(ya * 0.08f + u * 4), ya, x0 + 8 * sinf(yb * 0.08f + u * 4), yb, 2.5f, TONOS[NARANJA].a, a * 0.6f * campana(p, 0, 1));
          }
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
    case MAPS_IZQ:
    case MAPS_DER:
      if (delante) {
        float lado = laReac == MAPS_IZQ ? -1 : 1, a = tramo(u, 0.1f, 0.35f) * (1 - tramo(u, 2.6f, 3.0f));
        flecha(CX + lado * (10 + 14 * fabsf(sinf(u * 6))), 56, lado, a);
      }
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
      if (delante) for (int i = 0; i < 9; i++) {
        float p = fmodf(u * (0.25f + 0.1f * hash(i, 12)) + hash(i, 13), 1.f);
        copo(hash(i, 14) * W + sinf(u * 2 + i) * 14, -10 + p * (H + 20), 9 + 5 * hash(i, 15), u + i, campana(p, 0, 1) * (1 - tramo(u, 4.5f, 5.0f)));
      }
      break;
    case BATERIA:
      if (delante) pilita(CX, 56, 0.15f, fmodf(u, 0.8f) < 0.45f, tramo(u, 0, 0.3f) * (1 - tramo(u, 3.6f, 4.0f)));
      break;
    case GUINO:
      if (delante && u > 0.15f && u < 1.0f) chispa(ahora.R.x + 64, ahora.R.y - 70, 16, campana(u, 0.15f, 1.0f));
      break;
    default: break;
  }
}

// ---------------------------------------------------------------- control
void iniciar(uint32_t s) { semilla = s ? s : 1; ahora = meta = neutro(); }
void base(Base b) { if (b != laBase) { laBase = b; baseDesde = tAhora; } }
Base baseActual() { return laBase; }
void reaccion(Reac r) { laReac = r; reacDesde = tAhora; }
Reac reaccionActual() { return laReac; }

void cuadro(float t, float dt, const Entradas &e) {
  tAhora = t; curvaAhora = e.curva;
  dib::blanco = elPaquete == MINIMALISTA;
  if (laReac != NINGUNA && t - reacDesde >= DURA[laReac]) laReac = NINGUNA;
  float vel;
  float u = t - reacDesde;
  meta = (laReac != NINGUNA) ? deReac(u, e, vel) : deBase(t, e, vel);
  float k = 1 - expf(-dt * vel);
  float *a = (float *)&ahora, *b = (float *)&meta;
  for (size_t i = 0; i < sizeof(Rostro) / sizeof(float); i++) a[i] += (b[i] - a[i]) * k;
  // lo que no se suaviza: párpados, risa y boca van al instante (si no, el parpadeo se ve lento)
  ahora.L.tapa = meta.L.tapa; ahora.R.tapa = meta.R.tapa;
  if (vel >= 14) { ahora.boca = meta.boca; }
  ahora.corazon = meta.corazon;

  if (laReac != NINGUNA) extrasReac(u, false); else extrasBase(t, e, false);
  ojo(ahora, ahora.L, -1);
  ojo(ahora, ahora.R, 1);
  if (elPaquete == MINIMALISTA) for (int s = -1; s <= 1; s += 2) {   // los puntitos a los lados de los ojos
    const Ojo &o = s < 0 ? ahora.L : ahora.R;
    circulo(o.x + s * (o.w / 2 + 24), o.y + o.h * 0.08f, 6, BL, 0.9f, 6);
  }
  boca(ahora);
  if (laReac != NINGUNA) extrasReac(u, true); else extrasBase(t, e, true);
  dib::blanco = false;
}

void progreso(float t, float p) {
  // dos ojitos cerrados que respiran y un anillo que se va llenando
  dib::blanco = elPaquete == MINIMALISTA;
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
