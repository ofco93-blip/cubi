#pragma once
// Dibujo con bordes suaves sobre la memoria de la pantalla. Todo se dibuja "acostado" (448 x 368) y se
// guarda girado en el panel vertical (368 x 448). Las figuras se describen con su distancia al borde.
#include <Arduino.h>
#include <math.h>
#include "fuente.h"

namespace dib {

constexpr int W = 448, H = 368;          // lo que se ve (Isa acostada)
constexpr int RW = 368, RH = 448;        // el panel
extern uint16_t *fb;
extern uint8_t giro;                     // 0 o 1: hacia qué lado está acostada

struct Col { float r, g, b; };
inline Col mezcla(const Col &a, const Col &b, float t) { return {a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t}; }
constexpr Col BLANCO{255, 255, 255};

inline float lim(float v, float a, float b) { return v < a ? a : (v > b ? b : v); }

// rectángulo tocado en este cuadro (para mandar a la pantalla solo lo que cambió)
extern int cx0, cy0, cx1, cy1;
inline void marcar(int x0, int y0, int x1, int y1) {
  if (x0 < cx0) cx0 = x0;
  if (y0 < cy0) cy0 = y0;
  if (x1 > cx1) cx1 = x1;
  if (y1 > cy1) cy1 = y1;
}

inline uint32_t idx(int x, int y) {
  return giro ? (uint32_t)(RH - 1 - x) * RW + y : (uint32_t)x * RW + (RW - 1 - y);
}

inline uint16_t empacar(float r, float g, float b) {
  int R = (int)lim(r, 0, 255), G = (int)lim(g, 0, 255), B = (int)lim(b, 0, 255);
  return (uint16_t)(((R & 0xF8) << 8) | ((G & 0xFC) << 3) | (B >> 3));
}

inline void mezclar(uint32_t i, const Col &c, float a) {
  if (a <= 0.004f) return;
  if (a >= 0.996f) { fb[i] = empacar(c.r, c.g, c.b); return; }
  uint16_t p = fb[i];
  float r = ((p >> 11) & 31) * (255.f / 31), g = ((p >> 5) & 63) * (255.f / 63), b = (p & 31) * (255.f / 31);
  fb[i] = empacar(r + (c.r - r) * a, g + (c.g - g) * a, b + (c.b - b) * a);
}

// ---------------------------------------------------------------- distancias a figuras (negativo = adentro)
inline float dCaja(float px, float py, float cx, float cy, float hw, float hh, float r) {
  float qx = fabsf(px - cx) - hw + r, qy = fabsf(py - cy) - hh + r;
  float ax = fmaxf(qx, 0), ay = fmaxf(qy, 0);
  return sqrtf(ax * ax + ay * ay) + fminf(fmaxf(qx, qy), 0) - r;
}
inline float dCirculo(float px, float py, float cx, float cy, float r) {
  float dx = px - cx, dy = py - cy;
  return sqrtf(dx * dx + dy * dy) - r;
}
inline float dElipse(float px, float py, float cx, float cy, float rx, float ry) {
  float x = (px - cx) / rx, y = (py - cy) / ry;
  float k0 = sqrtf(x * x + y * y);
  float x2 = x / rx, y2 = y / ry;
  float k1 = sqrtf(x2 * x2 + y2 * y2);
  if (k1 < 1e-6f) return -fminf(rx, ry);
  return k0 * (k0 - 1.f) / k1;
}
inline float dSegmento(float px, float py, float ax, float ay, float bx, float by, float r) {
  float pax = px - ax, pay = py - ay, bax = bx - ax, bay = by - ay;
  float h = lim((pax * bax + pay * bay) / (bax * bax + bay * bay + 1e-6f), 0, 1);
  float dx = pax - bax * h, dy = pay - bay * h;
  return sqrtf(dx * dx + dy * dy) - r;
}
// corazón (centro cx,cy; s = medio ancho aprox.)
inline float dCorazon(float px, float py, float cx, float cy, float s) {
  float x = fabsf(px - cx) / (s * 1.25f), y = (cy - py) / (s * 1.25f) + 0.55f;
  float d;
  if (y + x > 1.f) {
    float dx = x - 0.25f, dy = y - 0.75f;
    d = sqrtf(dx * dx + dy * dy) - 0.35355f;
  } else {
    float dx1 = x, dy1 = y - 1.f;
    float m = 0.5f * fmaxf(x + y, 0.f);
    float dx2 = x - m, dy2 = y - m;
    d = sqrtf(fminf(dx1 * dx1 + dy1 * dy1, dx2 * dx2 + dy2 * dy2)) * (x - y > 0 ? 1.f : -1.f);
  }
  return d * s * 1.25f;
}
// gota: círculo abajo y punta arriba
inline float dGota(float px, float py, float cx, float cy, float s) {
  float x = fabsf(px - cx), y = py - cy;            // y hacia abajo
  float r1 = s, r2 = s * 0.12f, h = s * 1.6f;       // abajo grande, arriba punta
  float yy = -y;                                     // hacia arriba positivo
  float b = (r1 - r2) / h, a = sqrtf(1.f - b * b);
  float k = -x * b + yy * a;                         // (x,yy)·(-b,a)
  if (k < 0) return sqrtf(x * x + yy * yy) - r1;
  if (k > a * h) { float dy = yy - h; return sqrtf(x * x + dy * dy) - r2; }
  return x * a + yy * b - r1;
}

// ---------------------------------------------------------------- pintar una figura
struct Pintura {
  Col arriba{255, 255, 255}, abajo{255, 255, 255};
  float y0 = 0, y1 = 1;      // dónde va el degradado
  float halo = 0;            // ancho del brillo alrededor (px)
  float haloA = 0.35f;       // fuerza del brillo
  Col haloCol{255, 255, 255};
  float alfa = 1;
  bool exacta = true;        // false: la distancia de la figura no es de fiar (elipses muy aplastadas): pixel por pixel
};
// paquete "Robot minimalista": todo en blanco (se quita el color y se deja qué tan claro es)
extern bool blanco;
extern Col tinte;   // el color de los ojos (blanco = como siempre): lo toma todo lo que se dibuja en el minimalista
inline Col sinColor(const Col &c) { float v = fmaxf(c.r, fmaxf(c.g, c.b)) * (1.f / 255); return {v * tinte.r, v * tinte.g, v * tinte.b}; }
// fondo de color (degradado de arriba a abajo) en vez de negro: se pinta al borrar, así no cuesta más que el negro.
// Después de cambiarlo hay que llamar todo() para que se pinte la pantalla entera una vez.
void ponerFondo(const Col &arriba, const Col &abajo);
void sinFondo();
// zoom de la cara (para el acrílico que la agranda): lo que se dibuja se escala alrededor de (zx, zy).
// Las figuras se siguen describiendo en las medidas de siempre; figura() y texto() hacen la cuenta.
extern float zoom, zx, zy;
extern float sube;   // corre hacia abajo lo que se dibuje (para que algo entre subiendo desde fuera de la pantalla)

inline Pintura solido(const Col &c, float alfa = 1, float halo = 0) {
  Pintura p; p.arriba = p.abajo = p.haloCol = c; p.alfa = alfa; p.halo = halo; return p;
}

// se recorre en cuadritos de 8x8: los que quedan del todo adentro se pintan sin calcular nada y los de afuera
// se saltan; solo los del borde (y el brillo) se calculan pixel por pixel
template <typename F>
void figura1(float bx0, float by0, float bx1, float by1, const Pintura &p0, F d) {
  if (p0.alfa <= 0.01f) return;
  Pintura p = p0;
  if (blanco) { p.arriba = sinColor(p.arriba); p.abajo = sinColor(p.abajo); p.haloCol = sinColor(p.haloCol); }
  float m = p.halo + 1.5f;
  int x0 = (int)fmaxf(0, floorf(bx0 - m)), y0 = (int)fmaxf(0, floorf(by0 - m));
  int x1 = (int)fminf(W, ceilf(bx1 + m)), y1 = (int)fminf(H, ceilf(by1 + m));
  if (x0 >= x1 || y0 >= y1) return;
  marcar(x0, y0, x1, y1);
  const float inv = 1.f / fmaxf(1.f, p.y1 - p.y0);
  const bool opaco = p.alfa >= 0.996f;
  constexpr int T = 8;
  for (int ty = y0; ty < y1; ty += T) {
    int ty1 = ty + T < y1 ? ty + T : y1;
    for (int tx = x0; tx < x1; tx += T) {
      int tx1 = tx + T < x1 ? tx + T : x1;
      float rt = 0.5f * sqrtf((float)((tx1 - tx) * (tx1 - tx) + (ty1 - ty) * (ty1 - ty)));
      float dc = d((tx + tx1) * 0.5f, (ty + ty1) * 0.5f);
      if (dc > p.halo + rt + 2) continue;                 // todo afuera
      bool lleno = dc < -rt - 2;                           // todo adentro
      // en la memoria del panel las y seguidas quedan juntas: se recorre x por fuera e y por dentro
      Col cs[T]; uint16_t vs[T];
      for (int y = ty; y < ty1; y++) {
        cs[y - ty] = mezcla(p.arriba, p.abajo, lim((y + 0.5f - p.y0) * inv, 0, 1));
        vs[y - ty] = empacar(cs[y - ty].r, cs[y - ty].g, cs[y - ty].b);
      }
      const float nada = fmaxf(0.5f, p.halo), salto = p.exacta ? 0.7f : 0;   // nada: de aquí para afuera no se pinta
      for (int x = tx; x < tx1; x++) {
        float fx = x + 0.5f;
        if (lleno) {
          for (int y = ty; y < ty1; y++) { uint32_t i = idx(x, y); if (opaco) fb[i] = vs[y - ty]; else mezclar(i, cs[y - ty], p.alfa); }
          continue;
        }
        // la distancia dice cuántos pixeles seguidos faltan para llegar al borde: esos se saltan (afuera) o se rellenan
        // (adentro) sin volver a calcularla. El 0,7 es el margen por las figuras cuya distancia no es exacta.
        for (int y = ty; y < ty1;) {
          float dd = d(fx, y + 0.5f);
          if (dd >= nada) { y += 1 + (int)((dd - nada) * salto); continue; }
          if (dd <= -0.5f) {
            int y1 = y + 1 + (int)((-dd - 0.5f) * salto);
            if (y1 > ty1) y1 = ty1;
            // la distancia de algunas figuras (elipses aplastadas) exagera hacia adentro: si el último pixel del
            // tramo no está adentro del todo, se va de a uno
            if (y1 > y + 1 && d(fx, y1 - 0.5f) > -0.5f) y1 = y + 1;
            for (; y < y1; y++) { uint32_t i = idx(x, y); if (opaco) fb[i] = vs[y - ty]; else mezclar(i, cs[y - ty], p.alfa); }
            continue;
          }
          uint32_t i = idx(x, y);
          if (dd < 0.5f) {
            if (p.halo > 0) mezclar(i, p.haloCol, p.haloA * p.alfa);
            mezclar(i, cs[y - ty], lim(0.5f - dd, 0, 1) * p.alfa);
          } else {
            float k = 1.f - dd / p.halo;
            mezclar(i, p.haloCol, k * k * p.haloA * p.alfa);
          }
          y++;
        }
      }
    }
  }
}

template <typename F>
void figura(float bx0, float by0, float bx1, float by1, const Pintura &p0, F d) {
  if (zoom == 1 && sube == 0) { figura1(bx0, by0, bx1, by1, p0, d); return; }
  // el brillo no crece con el zoom (sale más barato y casi no se nota); la cuenta va con el inverso, sin dividir
  const float z = zoom, iz = 1.f / z, ox = zx - zx * iz, oy = zy - (zy + sube) * iz;
  Pintura p = p0;
  p.y0 = zy + (p0.y0 - zy) * z + sube; p.y1 = zy + (p0.y1 - zy) * z + sube;
  figura1(zx + (bx0 - zx) * z, zy + (by0 - zy) * z + sube, zx + (bx1 - zx) * z, zy + (by1 - zy) * z + sube, p,
          [&](float x, float y) { return d(ox + x * iz, oy + y * iz) * z; });
}
// resplandor: solo la luz difuminada alrededor de una figura, sin pintar la figura. Va barato porque es una mancha suave:
// la distancia se calcula cada 4 pixeles y adentro se interpola. halo en pixeles de pantalla (no crece con el zoom).
template <typename F>
void resplandor(float bx0, float by0, float bx1, float by1, const Col &c0, float alfa, float halo, F d) {
  if (alfa <= 0.01f || halo < 1) return;
  const Col c = blanco ? sinColor(c0) : c0;
  const float z = zoom, iz = 1.f / z, ox = zx - zx * iz, oy = zy - zy * iz, ih = 1.f / halo;
  constexpr int P = 4;
  int x0 = (int)fmaxf(0, floorf(zx + (bx0 - zx) * z - halo)) & ~(P - 1), y0 = (int)fmaxf(0, floorf(zy + (by0 - zy) * z - halo)) & ~(P - 1);
  const int x1 = (int)fminf(W, ceilf(zx + (bx1 - zx) * z + halo)), y1 = (int)fminf(H, ceilf(zy + (by1 - zy) * z + halo));
  if (x0 >= x1 || y0 >= y1) return;
  marcar(x0, y0, x1, y1);
  const int ny = (y1 - y0 + P - 1) / P + 1;
  const int cr = (int)lim(c.r, 0, 255) >> 3, cg = (int)lim(c.g, 0, 255) >> 2, cz = (int)lim(c.b, 0, 255) >> 3;   // el color, ya en 5-6-5
  const float a64 = alfa * 64;
  float ca[H / P + 2], cb[H / P + 2], *izq = ca, *der = cb;                       // las distancias de dos columnas vecinas
  auto columna = [&](float *v, int x) { for (int j = 0; j < ny; j++) v[j] = d(ox + x * iz, oy + (y0 + j * P) * iz) * z; };
  columna(izq, x0);
  for (int tx = x0; tx < x1; tx += P) {
    columna(der, tx + P);
    for (int j = 0; j + 1 < ny; j++) {
      const float d00 = izq[j], d01 = izq[j + 1], d10 = der[j], d11 = der[j + 1];
      if (fminf(fminf(d00, d01), fminf(d10, d11)) >= halo) continue;               // este cuadrito queda fuera de la luz
      for (int px = 0; px < P && tx + px < x1; px++) {
        const float fx = px * (1.f / P), da = d00 + (d10 - d00) * fx, db = d01 + (d11 - d01) * fx;
        for (int py = 0; py < P; py++) {
          const int y = y0 + j * P + py;
          if (y >= y1) break;
          const float k = 1 - fmaxf(0, da + (db - da) * (py * (1.f / P))) * ih;
          if (k <= 0.04f) continue;
          // tramado fino (4x4): la pantalla tiene pocos tonos por color y sin esto el degradado se ve en anillos.
          // La mezcla va en enteros sobre el 5-6-5 (64 niveles): en el ESP32 pesa mucho menos que con decimales.
          static const float TRAMA[16] = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
          int a = (int)(k * k * a64 + (TRAMA[(px & 3) | ((y & 3) << 2)] - 7.5f) * 0.21f);
          if (a <= 0) continue;
          if (a > 64) a = 64;
          const uint32_t i = idx(tx + px, y);
          const uint16_t q = fb[i];
          int r = (q >> 11) & 31, g = (q >> 5) & 63, b = q & 31;
          r += ((cr - r) * a) >> 6; g += ((cg - g) * a) >> 6; b += ((cz - b) * a) >> 6;
          fb[i] = (uint16_t)((r << 11) | (g << 5) | b);
        }
      }
    }
    float *t = izq; izq = der; der = t;
  }
}
// para lo que va pegado al borde de la pantalla (números, grados, el sol de la esquina): sin zoom mientras exista
struct SinZoom { float z; SinZoom() : z(zoom) { zoom = 1; } ~SinZoom() { zoom = z; } };

// ---------------------------------------------------------------- texto (Baloo 2, centrado)
inline const Letra *letra(uint32_t cod) {
  for (int i = 0; i < FUENTE_N; i++) if (FUENTE[i].cod == cod) return &FUENTE[i];
  return nullptr;
}
inline uint32_t sigUtf8(const char *&s) {
  uint8_t c = (uint8_t)*s++;
  if (c < 0x80) return c;
  int n = (c >= 0xF0) ? 3 : (c >= 0xE0) ? 2 : 1;
  uint32_t cod = c & (0x3F >> n);
  while (n-- && *s) cod = (cod << 6) | ((uint8_t)*s++ & 0x3F);
  return cod;
}
inline float alfaLetra(const Letra *l, int gx, int gy) {
  if (gx < 0 || gy < 0 || gx >= l->w || gy >= l->h) return 0;
  uint32_t k = (uint32_t)gy * l->w + gx;
  uint8_t b = FUENTE_DATOS[l->ini + (k >> 1)];
  return ((k & 1) ? (b >> 4) : (b & 15)) * (1.f / 15);
}
float anchoTexto(const char *s, float tam);
void texto(const char *s, float cx, float cy, float tam, const Col &c, float alfa = 1);

// ---------------------------------------------------------------- cuadro
// Hay dos memorias: mientras una se manda al panel, la otra se dibuja. Cada una recuerda lo que pintó la última
// vez (para borrarlo) y al panel se manda lo de este cuadro junto con lo del anterior.
struct Rect { int x0, y0, x1, y1; };            // en el panel (vertical)
extern uint8_t cual;                            // qué memoria se está dibujando
void empezar();                                 // borra lo que se pintó la última vez en esta memoria
Rect terminar();                                // lo que hay que mandar al panel (ancho 0 = nada)
void todo();                                    // la próxima vez se manda la pantalla entera

}  // namespace dib
