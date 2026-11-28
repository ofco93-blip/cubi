// el dibujo es casi todo cuentas con decimales: más rápido que el -Os de Arduino
#pragma GCC optimize("O3", "fast-math")
#include "dibujo.h"

namespace dib {

uint16_t *fb = nullptr;
uint8_t giro = 0;
Col tinte{255, 255, 255};
int X0 = 0, Y0 = 0, X1 = W, Y1 = H, RW = 368, RH = 448;
int cx0 = W, cy0 = H, cx1 = 0, cy1 = 0;
uint8_t cual = 0;
struct RL { int x0, y0, x1, y1; };                  // rectángulo acostado
static RL previo[2] = {{0, 0, W, H}, {0, 0, W, H}}, ultimo = {0, 0, W, H};
void redonda() {
  RW = RH = MAXD; X0 = (W - MAXD) / 2; Y0 = (H - MAXD) / 2; X1 = X0 + MAXD; Y1 = Y0 + MAXD;   // -9..457 x -49..417
  todo();
}

// el fondo ya ordenado como queda en memoria (una fila del panel), para cada giro
static uint16_t fondoMem[2][MAXD];
static bool hayFondo = false;
void ponerFondo(const Col &a, const Col &b) {
  const int n = Y1 - Y0;
  for (int y = 0; y < n; y++) {
    Col c = mezcla(a, b, y / (n - 1.f));
    uint16_t v = empacar(c.r, c.g, c.b);
    fondoMem[1][y] = v; fondoMem[0][RW - 1 - y] = v;
  }
  hayFondo = true;
}
void sinFondo() { hayFondo = false; }

static void borrar(const RL &r) {
  if (r.x0 >= r.x1 || r.y0 >= r.y1) return;
  int ya = max(Y0, r.y0), yb = min(Y1, r.y1);
  if (ya >= yb) return;
  for (int x = max(X0, r.x0); x < min(X1, r.x1); x++) {
    // una fila del panel por cada x: las y seguidas quedan juntas en memoria
    uint32_t a = idx(x, giro ? ya : yb - 1);
    if (hayFondo) memcpy(fb + a, &fondoMem[giro ? 1 : 0][giro ? ya - Y0 : RW - (yb - Y0)], (yb - ya) * 2);
    else memset(fb + a, 0, (yb - ya) * 2);
  }
}

void empezar() {
  borrar(previo[cual]);
  cx0 = X1; cy0 = Y1; cx1 = X0; cy1 = Y0;
}

Rect terminar() {
  RL cur = {cx0, cy0, cx1, cy1};
  RL u = cur;
  if (ultimo.x0 < ultimo.x1) {
    if (u.x0 >= u.x1) u = ultimo;
    else { u.x0 = min(u.x0, ultimo.x0); u.y0 = min(u.y0, ultimo.y0); u.x1 = max(u.x1, ultimo.x1); u.y1 = max(u.y1, ultimo.y1); }
  }
  previo[cual] = cur; ultimo = cur;
  if (u.x0 >= u.x1 || u.y0 >= u.y1) return {0, 0, 0, 0};
  u.x0 = max(X0, u.x0) - X0; u.y0 = max(Y0, u.y0) - Y0; u.x1 = min(X1, u.x1) - X0; u.y1 = min(Y1, u.y1) - Y0;   // ya en medidas del panel
  Rect r;
  if (giro) { r.x0 = u.y0; r.x1 = u.y1; r.y0 = RH - u.x1; r.y1 = RH - u.x0; }
  else { r.x0 = RW - u.y1; r.x1 = RW - u.y0; r.y0 = u.x0; r.y1 = u.x1; }
  r.x0 &= ~1; if (r.x1 & 1) r.x1++;                // el panel pide coordenadas pares
  r.y0 &= ~1; if (r.y1 & 1) r.y1++;
  if (r.x1 > RW) r.x1 = RW;
  if (r.y1 > RH) r.y1 = RH;
  return r;
}

void todo() { previo[0] = previo[1] = ultimo = {X0, Y0, X1, Y1}; }

float anchoTexto(const char *s, float tam) {
  float esc = tam / FUENTE_ALTO, w = 0;
  while (*s) {
    const Letra *l = letra(sigUtf8(s));
    if (l) w += l->avance * esc;
  }
  return w;
}

bool blanco = false;
float zoom = 1, zx = W / 2.f, zy = H / 2.f;

void texto(const char *s, float cx, float cy, float tam, const Col &c0, float alfa) {
  if (alfa <= 0.01f) return;
  const Col c = blanco ? sinColor(c0) : c0;
  if (zoom != 1) { cx = zx + (cx - zx) * zoom; cy = zy + (cy - zy) * zoom; tam *= zoom; }
  float esc = tam / FUENTE_ALTO;
  float x = cx - anchoTexto(s, tam) / 2;
  float base = cy + 20.f * esc;                   // las mayúsculas quedan centradas en cy
  while (*s) {
    const Letra *l = letra(sigUtf8(s));
    if (!l) continue;
    if (l->w) {
      float gx0 = x + l->x0 * esc, gy0 = base + l->y0 * esc;
      int ax = (int)fmaxf(X0, floorf(gx0)), ay = (int)fmaxf(Y0, floorf(gy0));
      int bx = (int)fminf(X1, ceilf(gx0 + l->w * esc)), by = (int)fminf(Y1, ceilf(gy0 + l->h * esc));
      if (ax < bx && ay < by) {
        marcar(ax, ay, bx, by);
        for (int py = ay; py < by; py++) {
          float v = (py + 0.5f - gy0) / esc - 0.5f;
          int v0 = (int)floorf(v); float fv = v - v0;
          for (int px = ax; px < bx; px++) {
            float u = (px + 0.5f - gx0) / esc - 0.5f;
            int u0 = (int)floorf(u); float fu = u - u0;
            float a = (alfaLetra(l, u0, v0) * (1 - fu) + alfaLetra(l, u0 + 1, v0) * fu) * (1 - fv) +
                      (alfaLetra(l, u0, v0 + 1) * (1 - fu) + alfaLetra(l, u0 + 1, v0 + 1) * fu) * fv;
            if (a > 0.01f) mezclar(idx(px, py), c, a * alfa);
          }
        }
      }
    }
    x += l->avance * esc;
  }
}

}  // namespace dib

namespace dib { float sube = 0; }
