// ===== Mapi: la pantalla redonda del mapa, debajo del volante =====
// Placa: Waveshare ESP32-S3-Touch-AMOLED-1.75 (redonda 466x466, CO5300 por QSPI). Siempre muestra el mapa, con la
// estética de la pantalla grande del carro: calles azules, ruta violeta, tarjetas de vidrio y la pila de gato.
// Todo lo manda el celular por Bluetooth (app Cubi): las calles, dónde va el carro, el giro, la pila, el clima y la hora.
// Sin celular queda la hora y lo último que supo.
// La letra (fuente.h) es la de Cubi: la copia el taller de GitHub antes de compilar.
// ponytail: sin táctil ni actualización por Bluetooth todavía (se actualiza por el cable).
#include <Arduino_GFX_Library.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <Wire.h>
#include <Update.h>
#include "fuente.h"

#if __has_include("version.h")
#include "version.h"                       // lo escribe el taller de GitHub: el número de la versión publicada
#else
#define VERSION_MAPI "sin número"
#endif
static const int P_CS = 12, P_SCK = 38, P_D0 = 4, P_D1 = 5, P_D2 = 6, P_D3 = 7, P_RST = 39, P_SDA = 15, P_SCL = 14;
#define BLE_SRV "6e400101-b5a3-f393-e0a9-e50e24dcca9e"   // distinto al de Cubi: así la app sabe cuál es cuál
#define BLE_RX  "6e400102-b5a3-f393-e0a9-e50e24dcca9e"
#define BLE_TX  "6e400103-b5a3-f393-e0a9-e50e24dcca9e"
#define BLE_OTA "6e400104-b5a3-f393-e0a9-e50e24dcca9e"

constexpr int D = 466;                     // la pantalla
constexpr float C = D / 2.f;
Arduino_DataBus *bus; Arduino_CO5300 *panel;
uint16_t *fb;                              // la memoria del cuadro (en la PSRAM)
float tAhora = 0;

// ---------------------------------------------------------------- dibujo
struct Col { float r, g, b; };
struct V { float x, y, z; };
static const Col BLANCO{255, 255, 255};
static inline float lim(float v, float a, float b) { return v < a ? a : v > b ? b : v; }
static inline uint16_t empacar(float r, float g, float b) {
  int R = (int)lim(r, 0, 255), G = (int)lim(g, 0, 255), B = (int)lim(b, 0, 255);
  return (uint16_t)(((R & 0xF8) << 8) | ((G & 0xFC) << 3) | (B >> 3));
}
static inline void mezclar(int x, int y, const Col &c, float a) {
  if (a <= 0.004f || x < 0 || y < 0 || x >= D || y >= D) return;
  uint16_t &p = fb[y * D + x];
  if (a >= 0.996f) { p = empacar(c.r, c.g, c.b); return; }
  float r = ((p >> 11) & 31) * (255.f / 31), g = ((p >> 5) & 63) * (255.f / 63), b = (p & 31) * (255.f / 31);
  p = empacar(r + (c.r - r) * a, g + (c.g - g) * a, b + (c.b - b) * a);
}
// distancias a figuras (negativo = adentro)
static inline float dCaja(float px, float py, float cx, float cy, float hw, float hh, float r) {
  float qx = fabsf(px - cx) - hw + r, qy = fabsf(py - cy) - hh + r, ax = fmaxf(qx, 0), ay = fmaxf(qy, 0);
  return sqrtf(ax * ax + ay * ay) + fminf(fmaxf(qx, qy), 0) - r;
}
static inline float dCirculo(float px, float py, float cx, float cy, float r) { return sqrtf((px - cx) * (px - cx) + (py - cy) * (py - cy)) - r; }
static inline float dSegmento(float px, float py, float ax, float ay, float bx, float by, float r) {
  float pax = px - ax, pay = py - ay, bax = bx - ax, bay = by - ay, h = lim((pax * bax + pay * bay) / (bax * bax + bay * bay + 1e-6f), 0, 1);
  float dx = pax - bax * h, dy = pay - bay * h; return sqrtf(dx * dx + dy * dy) - r;
}
static float dTriangulo(float px, float py, float ax, float ay, float bx, float by, float cx, float cy) {   // con signo (negativo adentro)
  auto lado = [&](float x0, float y0, float x1, float y1) { float ex = x1 - x0, ey = y1 - y0, wx = px - x0, wy = py - y0, h = lim((wx * ex + wy * ey) / (ex * ex + ey * ey), 0, 1);
    float dx = wx - ex * h, dy = wy - ey * h; return dx * dx + dy * dy; };
  float d = fminf(lado(ax, ay, bx, by), fminf(lado(bx, by, cx, cy), lado(cx, cy, ax, ay)));
  float s1 = (bx - ax) * (py - ay) - (by - ay) * (px - ax), s2 = (cx - bx) * (py - by) - (cy - by) * (px - bx), s3 = (ax - cx) * (py - cy) - (ay - cy) * (px - cx);
  bool dentro = (s1 >= 0 && s2 >= 0 && s3 >= 0) || (s1 <= 0 && s2 <= 0 && s3 <= 0);
  return dentro ? -sqrtf(d) : sqrtf(d);
}
// pinta una figura descrita por su distancia, con borde suave; color de arriba a abajo (degradado) y transparencia
template <typename F> void figura(float bx0, float by0, float bx1, float by1, const Col &c0, const Col &c1, float a, F d) {
  int x0 = (int)fmaxf(0, bx0 - 2), y0 = (int)fmaxf(0, by0 - 2), x1 = (int)fminf(D, bx1 + 2), y1 = (int)fminf(D, by1 + 2);
  float inv = 1.f / fmaxf(1, by1 - by0);
  for (int y = y0; y < y1; y++) {
    float k = lim((y - by0) * inv, 0, 1); Col c{c0.r + (c1.r - c0.r) * k, c0.g + (c1.g - c0.g) * k, c0.b + (c1.b - c0.b) * k};
    for (int x = x0; x < x1; x++) { float dd = d(x + 0.5f, y + 0.5f); if (dd < 0.5f) mezclar(x, y, c, lim(0.5f - dd, 0, 1) * a); }
  }
}
template <typename F> void figura(float bx0, float by0, float bx1, float by1, const Col &c, float a, F d) { figura(bx0, by0, bx1, by1, c, c, a, d); }
// una fila de pixeles de xa a xb, con los extremos suaves
static void fila(int y, float xa, float xb, const Col &c, float a) {
  if (y < 0 || y >= D || xb <= 0 || xa >= D || xb <= xa) return;
  if (xa < 0) xa = 0;
  if (xb > D) xb = D;
  const int i0 = (int)xa, i1 = (int)xb;
  if (i0 == i1) { mezclar(i0, y, c, (xb - xa) * a); return; }
  mezclar(i0, y, c, (1 - (xa - i0)) * a);
  uint16_t v = empacar(c.r, c.g, c.b);
  for (int x = i0 + 1; x < i1; x++) { if (a >= 0.996f) fb[y * D + x] = v; else mezclar(x, y, c, a); }
  if (i1 < D) mezclar(i1, y, c, (xb - i1) * a);
}
static void rellenar(const float *px, const float *py, int n, const Col &c, float a) {   // polígono, fila por fila
  float y0 = 1e9f, y1 = -1e9f;
  for (int i = 0; i < n; i++) { y0 = fminf(y0, py[i]); y1 = fmaxf(y1, py[i]); }
  const int iy0 = (int)fmaxf(0, ceilf(y0 - 0.5f)), iy1 = (int)fminf(D - 1, floorf(y1 - 0.5f));
  for (int iy = iy0; iy <= iy1; iy++) {
    const float yc = iy + 0.5f; float xs[16]; int m = 0;
    for (int i = 0; i < n && m < 16; i++) { const int j = i + 1 == n ? 0 : i + 1; if ((py[i] <= yc) != (py[j] <= yc)) xs[m++] = px[i] + (yc - py[i]) * (px[j] - px[i]) / (py[j] - py[i]); }
    for (int i = 1; i < m; i++) { const float v = xs[i]; int j = i; while (j > 0 && xs[j - 1] > v) { xs[j] = xs[j - 1]; j--; } xs[j] = v; }
    for (int k = 0; k + 1 < m; k += 2) fila(iy, xs[k], xs[k + 1], c, a);
  }
}
static void disco(float cx, float cy, float r, const Col &c, float a) {
  for (int iy = (int)fmaxf(0, cy - r); iy <= (int)fminf(D - 1, cy + r); iy++) {
    const float dy = (iy + 0.5f - cy) / r;
    if (dy > -1 && dy < 1) { const float hw = r * sqrtf(1 - dy * dy); fila(iy, cx - hw, cx + hw, c, a); }
  }
}
// una línea gruesa: un cuadrilátero con las puntas redondas (rápido: fila por fila, sin calcular distancias)
static void grueso(float ax, float ay, float bx, float by, float w, const Col &c, float a) {
  float dx = bx - ax, dy = by - ay, l = sqrtf(dx * dx + dy * dy);
  if (l < 0.01f) { disco(ax, ay, w / 2, c, a); return; }
  float nx = -dy / l * w / 2, ny = dx / l * w / 2, px[4] = {ax + nx, bx + nx, bx - nx, ax - nx}, py[4] = {ay + ny, by + ny, by - ny, ay - ny};
  rellenar(px, py, 4, c, a);
}
// texto (Baloo 2 de Cubi), centrado en (cx, cy) o alineado a la izquierda si izq
static const Letra *letra(uint32_t cod) { for (int i = 0; i < FUENTE_N; i++) if (FUENTE[i].cod == cod) return &FUENTE[i]; return nullptr; }
static uint32_t sigUtf8(const char *&s) {
  uint8_t c = (uint8_t)*s++;
  if (c < 0x80) return c;
  int n = c >= 0xF0 ? 3 : c >= 0xE0 ? 2 : 1; uint32_t cod = c & (0x3F >> n);
  while (n-- && *s) cod = (cod << 6) | ((uint8_t)*s++ & 0x3F);
  return cod;
}
static inline float alfaLetra(const Letra *l, int gx, int gy) {
  if (gx < 0 || gy < 0 || gx >= l->w || gy >= l->h) return 0;
  uint32_t k = (uint32_t)gy * l->w + gx; uint8_t b = FUENTE_DATOS[l->ini + (k >> 1)];
  return ((k & 1) ? (b >> 4) : (b & 15)) * (1.f / 15);
}
static float anchoTexto(const char *s, float tam) { float esc = tam / FUENTE_ALTO, w = 0; while (*s) { const Letra *l = letra(sigUtf8(s)); if (l) w += l->avance * esc; } return w; }
static void texto(const char *s, float x, float cy, float tam, const Col &c, float alfa = 1, bool izq = false, float cabe = 1e9f) {
  float esc = tam / FUENTE_ALTO, w = anchoTexto(s, tam);
  if (w > cabe) { tam *= cabe / w; esc = tam / FUENTE_ALTO; w = cabe; }   // no cabe: más pequeña
  if (!izq) x -= w / 2;
  const float base = cy + 20.f * esc;
  while (*s) {
    const Letra *l = letra(sigUtf8(s));
    if (!l) continue;
    if (l->w) {
      float gx0 = x + l->x0 * esc, gy0 = base + l->y0 * esc;
      int ax = (int)fmaxf(0, floorf(gx0)), ay = (int)fmaxf(0, floorf(gy0)), bx = (int)fminf(D, ceilf(gx0 + l->w * esc)), by = (int)fminf(D, ceilf(gy0 + l->h * esc));
      for (int py = ay; py < by; py++) {
        float v = (py + 0.5f - gy0) / esc - 0.5f; int v0 = (int)floorf(v); float fv = v - v0;
        for (int px = ax; px < bx; px++) {
          float u = (px + 0.5f - gx0) / esc - 0.5f; int u0 = (int)floorf(u); float fu = u - u0;
          float a = (alfaLetra(l, u0, v0) * (1 - fu) + alfaLetra(l, u0 + 1, v0) * fu) * (1 - fv) + (alfaLetra(l, u0, v0 + 1) * (1 - fu) + alfaLetra(l, u0 + 1, v0 + 1) * fu) * fv;
          if (a > 0.01f) mezclar(px, py, c, a * alfa);
        }
      }
    }
    x += l->avance * esc;
  }
}
// texto con un filo oscuro alrededor, para leerse encima del mapa
static void textoFilo(const char *s, float x, float cy, float tam, const Col &c, bool izq = false, float cabe = 1e9f) {
  const Col o{8, 16, 52}; const float k = fmaxf(1.2f, tam * 0.045f);
  for (int i = 0; i < 8; i++) texto(s, x + k * cosf(i * PI / 4), cy + k * sinf(i * PI / 4), tam, o, 0.55f, izq, cabe);
  texto(s, x, cy, tam, c, 1, izq, cabe);
}
// un texto largo en dos renglones (cortado en el espacio más cercano a la mitad); si aún no cabe, se achica
static void dosRenglones(const char *s, float x, float cy, float tam, const Col &c, float cabe) {
  if (anchoTexto(s, tam) <= cabe) { texto(s, x, cy, tam, c, 1, true, cabe); return; }
  const int n = strlen(s); int corte = -1;
  for (int i = 0; i < n; i++) if (s[i] == ' ' && (corte < 0 || abs(i - n / 2) < abs(corte - n / 2))) corte = i;
  if (corte < 0) { texto(s, x, cy, tam, c, 1, true, cabe); return; }
  char a[48]; strlcpy(a, s, min(corte + 1, (int)sizeof a));
  texto(a, x, cy - tam * 0.6f, tam, c, 1, true, cabe); texto(s + corte + 1, x, cy + tam * 0.6f, tam, c, 1, true, cabe);
}
// tarjeta de vidrio, como las de la pantalla grande: translúcida, más clara arriba, borde de luz y su sombra
static void vidrio(float x0, float y0, float x1, float y1, float r, bool oscuro = false) {
  const float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, hw = (x1 - x0) / 2, hh = (y1 - y0) / 2;
  figura(x0, y0 + 3, x1, y1 + 5, Col{4, 6, 20}, 0.35f, [=](float px, float py) { return dCaja(px, py, cx, cy + 4, hw, hh, r); });
  if (oscuro) figura(x0, y0, x1, y1, Col{10, 18, 52}, Col{6, 10, 32}, 0.82f, [=](float px, float py) { return dCaja(px, py, cx, cy, hw, hh, r); });
  else figura(x0, y0, x1, y1, Col{150, 172, 255}, Col{110, 134, 232}, 0.42f, [=](float px, float py) { return dCaja(px, py, cx, cy, hw, hh, r); });
  figura(x0, y0, x1, y1, BLANCO, 0.75f, [=](float px, float py) { return fabsf(dCaja(px, py, cx, cy, hw, hh, r)) - 1.1f; });
}

// ---------------------------------------------------------------- lo que manda el celular
// Las calles: el mismo formato que el mini mapa de Cubi (de a 3 m, un byte por coordenada; 128 = el origen).
// Clases: 0 calle, 1 avenida, 2 la ruta, 3 agua, 4 parque, 5 río, 6 la ruta con tráfico lento, 7 la ruta trancada. Hay dos juegos: uno se dibuja mientras el otro se carga.
constexpr int MAX_VIAS = 260, MAX_PUNTOS = 2000; constexpr float MAPA_M = 3;
struct Calles { uint8_t x[MAX_PUNTOS], y[MAX_PUNTOS]; uint16_t ini[MAX_VIAS + 1]; uint8_t clase[MAX_VIAS]; int nv = 0, np = 0; };
Calles *callesHay, *callesCarga;
static int hexv(char c) { return c <= '9' ? c - '0' : (c | 32) - 'a' + 10; }
static void mapaVias(const char *w, bool primero, bool fin) {
  Calles &c = *callesCarga;
  if (primero) { c.nv = 0; c.np = 0; }
  while (*w && c.nv < MAX_VIAS) {
    c.clase[c.nv] = (uint8_t)lim(*w++ - '0', 0, 7); c.ini[c.nv] = c.np;
    while (*w && *w != '.') {
      if (!w[1] || !w[2] || !w[3]) { w += strlen(w); break; }
      if (c.np < MAX_PUNTOS) { c.x[c.np] = hexv(w[0]) * 16 + hexv(w[1]); c.y[c.np] = hexv(w[2]) * 16 + hexv(w[3]); c.np++; }
      w += 4;
    }
    if (*w == '.') w++;
    if (c.np - c.ini[c.nv] >= 2) c.nv++; else c.np = c.ini[c.nv];
  }
  c.ini[c.nv] = c.np;
  if (fin) { Calles *t = callesHay; callesHay = callesCarga; callesCarga = t; }
}
// ---------------------------------------------------------------- su giroscopio (QMI8658, el mismo de Cubi)
// Para que el mapa gire suave y al instante en las curvas: se mide el giro alrededor de la vertical. La vertical sale de
// la gravedad (el acelerómetro), así que sirve como sea que quede montada la pantalla. Un giro a la derecha visto desde
// arriba es horario: con los dos vectores en el mismo marco, el rumbo sube con -(giro · arriba).
// ponytail: si el sentido saliera al revés en esta placa, cambiar GIRO_SIGNO (se ve: el mapa gira al lado contrario).
#define GIRO_SIGNO 1
uint8_t imuAddr = 0; V gravedad{0, 0, 1}; float sesgo = 0, rumboVel = 0, dtCuadro = 0;
static void i2cW(uint8_t a, uint8_t r, uint8_t v) { Wire.beginTransmission(a); Wire.write(r); Wire.write(v); Wire.endTransmission(); }
static bool i2cR(uint8_t a, uint8_t r, uint8_t *b, uint8_t n) {
  Wire.beginTransmission(a); Wire.write(r);
  if (Wire.endTransmission(false) != 0 || Wire.requestFrom(a, n) != n) return false;
  for (int i = 0; i < n; i++) b[i] = Wire.read();
  return true;
}
static bool imuLeer(V &acc, V &gir) {
  uint8_t b[12]; if (!imuAddr || !i2cR(imuAddr, 0x35, b, 12)) return false;
  int16_t v[6]; for (int i = 0; i < 6; i++) v[i] = int16_t(b[2 * i] | (b[2 * i + 1] << 8));
  acc = {v[0] / 8192.f, v[1] / 8192.f, v[2] / 8192.f}; gir = {v[3] / 64.f, v[4] / 64.f, v[5] / 64.f};   // g y grados por segundo
  return true;
}
static void imuInit() {
  for (uint8_t a : {0x6B, 0x6A}) { uint8_t id; if (i2cR(a, 0x00, &id, 1) && id == 0x05) { imuAddr = a; break; } }
  if (!imuAddr) { Serial.println("Mapi: sin giroscopio"); return; }
  i2cW(imuAddr, 0x02, 0x40); i2cW(imuAddr, 0x03, 0x15); i2cW(imuAddr, 0x04, 0x55); i2cW(imuAddr, 0x08, 0x03);   // como en Cubi
  delay(50);
}
static void imuCuadro(float dt) {
  V a, g; if (!imuLeer(a, g)) { rumboVel = 0; return; }
  gravedad.x += (a.x - gravedad.x) * 0.03f; gravedad.y += (a.y - gravedad.y) * 0.03f; gravedad.z += (a.z - gravedad.z) * 0.03f;
  const float n = sqrtf(gravedad.x * gravedad.x + gravedad.y * gravedad.y + gravedad.z * gravedad.z) + 1e-6f;
  const float w = (g.x * gravedad.x + g.y * gravedad.y + g.z * gravedad.z) / n;   // giro alrededor de la vertical
  if (fabsf(w - sesgo) < 1.5f) sesgo += (w - sesgo) * 0.01f;                        // quieto: aprende el corrimiento del sensor
  const float r = -(w - sesgo) * GIRO_SIGNO;
  rumboVel = fabsf(r) < 0.6f ? 0 : r;                                               // el temblor de la calle no gira el mapa
}

// dónde va el carro (metros al este y al norte del origen, rumbo en grados). Entre aviso y aviso sigue andando solo.
float mpx = 0, mpy = 0, mrumbo = 0, fijoX = 0, fijoY = 0, fijoR = 0, fijoT = -100, velX = 0, velY = 0, velR = 0, mapaT = -100;
static void mapaPos(float x, float y, float r) {
  const float dt = tAhora - fijoT;
  if (dt > 8) { mpx = x; mpy = y; mrumbo = r; velX = velY = velR = 0; }
  else if (dt > 0.2f) { velX = lim((x - fijoX) / dt, -60, 60); velY = lim((y - fijoY) / dt, -60, 60); velR = lim((fmodf(r - fijoR + 540, 360) - 180) / dt, -70, 70); }
  fijoX = x; fijoY = y; fijoR = r; fijoT = tAhora; mapaT = tAhora;
}
char giroDir[8] = "", giroCalle[40] = "", llegaTxt[8] = ""; int giroM = -1, faltaM = -1; float giroT = -100;
int pilaB = -1, pilaReal = -1, pilaWltp = -1; bool pilaCarga = false; float pilaKw = 0, pilaHoras = -1, pilaProm = 0;
int climaG = -1000; char climaE[12] = "", climaLugar[28] = ""; bool climaNoche = false;
int horaMin = -1; float horaT = 0;
char calleAhora[40] = ""; int limV = -1; bool limX = false;   // la calle por donde va y el límite de la vía (de Google)

static String decodificar(const String &s) {   // %XX y + -> espacio
  String o; o.reserve(s.length());
  for (unsigned i = 0; i < s.length(); i++) {
    char c = s[i];
    if (c == '+') o += ' ';
    else if (c == '%' && i + 2 < s.length()) { o += (char)(hexv(s[i + 1]) * 16 + hexv(s[i + 2])); i += 2; }
    else o += c;
  }
  return o;
}
static String valor(const String &q, const char *k) {
  String busca = String(k) + "=";
  int i = q.startsWith(busca) ? 0 : q.indexOf("&" + busca);
  if (i < 0) return "";
  if (i > 0 || !q.startsWith(busca)) i += 1;
  i += busca.length(); int j = q.indexOf('&', i);
  return decodificar(q.substring(i, j < 0 ? q.length() : j));
}
static bool tiene(const String &q, const char *k) { String b = String(k) + "="; return q.startsWith(b) || q.indexOf("&" + b) >= 0; }
float ultimoMsg = -1000;                   // cuándo habló el celular por última vez
static void orden(const String &linea) {
  ultimoMsg = tAhora;
  int s = linea.indexOf('?'); String ruta = s < 0 ? linea : linea.substring(0, s), q = s < 0 ? "" : linea.substring(s + 1);
  if (ruta == "/mapa") {
    if (tiene(q, "w")) mapaVias(valor(q, "w").c_str(), valor(q, "n") == "1", valor(q, "f") == "1");
    if (tiene(q, "g")) mapaVias(valor(q, "g").c_str(), false, false);
    if (tiene(q, "x")) mapaPos(valor(q, "x").toFloat(), valor(q, "y").toFloat(), valor(q, "r").toFloat());
  } else if (ruta == "/giro") {   // d: izq, der, recto, fin o vacío (sin ruta); m: metros; c: la calle
    strlcpy(giroDir, valor(q, "d").c_str(), sizeof giroDir); giroM = tiene(q, "m") ? valor(q, "m").toInt() : -1;
    strlcpy(giroCalle, valor(q, "c").c_str(), sizeof giroCalle); giroT = tAhora;
  } else if (ruta == "/llega") { strlcpy(llegaTxt, valor(q, "h").c_str(), sizeof llegaTxt); faltaM = tiene(q, "d") ? valor(q, "d").toInt() : -1; }
  else if (ruta == "/pila") {
    pilaB = valor(q, "b").toInt(); pilaReal = tiene(q, "r") ? valor(q, "r").toInt() : -1; pilaWltp = tiene(q, "w") ? valor(q, "w").toInt() : -1;
    pilaCarga = valor(q, "c") == "1"; pilaKw = valor(q, "k").toFloat(); pilaHoras = tiene(q, "h") ? valor(q, "h").toFloat() : -1; pilaProm = valor(q, "p").toFloat();
  } else if (ruta == "/clima") { climaG = valor(q, "t").toInt(); strlcpy(climaE, valor(q, "e").c_str(), sizeof climaE); strlcpy(climaLugar, valor(q, "l").c_str(), sizeof climaLugar); climaNoche = valor(q, "n") == "1"; }
  else if (ruta == "/hora") { horaMin = valor(q, "m").toInt(); horaT = tAhora; }
  else if (ruta == "/calle") strlcpy(calleAhora, valor(q, "c").c_str(), sizeof calleAhora);
  else if (ruta == "/limite") { limV = valor(q, "v").toInt(); limX = valor(q, "x") == "1"; }
}

// ---------------------------------------------------------------- Bluetooth
QueueHandle_t colaBle; bool conectado = false; BLECharacteristic *tx;
struct Orden { char t[244]; };
// Actualización por Bluetooth (como la de Cubi): "/ota/inicio?tam=&md5=", el archivo en pedazos por la característica
// OTA (cada uno con confirmación, en orden) y "/ota/fin" (verifica y reinicia). Responde "ota|ok" o "ota|!por qué".
volatile uint32_t otaTotal = 0, otaHecho = 0; volatile bool otaError = false; uint32_t reinicioEn = 0;
static void avisar(const String &s) { if (conectado) { tx->setValue(s.c_str()); tx->notify(); } }
static void ota(const String &linea) {
  if (linea.startsWith("/ota/inicio")) {
    String q = linea.substring(linea.indexOf('?') + 1); uint32_t tam = valor(q, "tam").toInt();
    if (tam < 100000 || !Update.begin(tam)) { avisar(String("ota|!no se puede actualizar: ") + Update.errorString()); return; }
    if (tiene(q, "md5")) Update.setMD5(valor(q, "md5").c_str());
    otaHecho = 0; otaError = false; otaTotal = tam; avisar("ota|ok");
  } else if (linea.startsWith("/ota/fin")) {
    uint32_t tam = otaTotal, hecho = otaHecho; otaTotal = 0;
    if (otaError || hecho != tam) { Update.abort(); avisar(String("ota|!llegaron ") + hecho + " de " + tam + " bytes"); return; }
    if (!Update.end()) { avisar(String("ota|!el archivo no pasó la verificación: ") + Update.errorString()); return; }
    avisar("ota|ok"); reinicioEn = millis() + 1500;
  } else if (linea.startsWith("/ota/cancelar")) { if (otaTotal) { otaTotal = 0; Update.abort(); } }
}
class Ota : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {             // escribe directo en la memoria
    if (!otaTotal || otaError) return;
    size_t n = c->getLength();
    if (Update.write(c->getData(), n) != n) otaError = true;
    otaHecho += n;
  }
};
class Rx : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String v = c->getValue();
    if (v.startsWith("/ota/")) { ota(v); return; }           // la actualización se atiende de una (no espera al dibujo)
    if (v == "/hola") { avisar("ver|" VERSION_MAPI); return; }   // la app pregunta qué versión tiene
    Orden o; strlcpy(o.t, v.c_str(), sizeof o.t);
    if (xQueueSend(colaBle, &o, 0) != pdTRUE) { Orden basura; xQueueReceive(colaBle, &basura, 0); xQueueSend(colaBle, &o, 0); }
  }
};
class Srv : public BLEServerCallbacks {
  void onConnect(BLEServer *) override { conectado = true; }
  void onDisconnect(BLEServer *s) override { conectado = false; if (otaTotal) { otaTotal = 0; Update.abort(); } s->startAdvertising(); }   // cortada a mitad: sigue la versión que tenía
};
static void bleInit() {
  colaBle = xQueueCreate(24, sizeof(Orden));
  BLEDevice::init("Mapi"); BLEDevice::setMTU(247);
  BLEServer *s = BLEDevice::createServer(); s->setCallbacks(new Srv());
  BLEService *sv = s->createService(BLE_SRV);
  BLECharacteristic *rx = sv->createCharacteristic(BLE_RX, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR); rx->setCallbacks(new Rx());
  tx = sv->createCharacteristic(BLE_TX, BLECharacteristic::PROPERTY_NOTIFY); tx->addDescriptor(new BLE2902());
  sv->createCharacteristic(BLE_OTA, BLECharacteristic::PROPERTY_WRITE)->setCallbacks(new Ota());
  sv->start();
  BLEAdvertising *a = BLEDevice::getAdvertising(); a->addServiceUUID(BLE_SRV); a->setScanResponse(true); BLEDevice::startAdvertising();
}

// ---------------------------------------------------------------- las piezas de la pantalla
uint16_t *fondoFijo;
static void fondo() {   // el azul de la pantalla grande, más oscuro abajo: se pinta una vez y luego solo se copia
  if (!fondoFijo) {
    fondoFijo = (uint16_t *)ps_malloc(D * D * 2);
    // con tramado: la pantalla tiene pocos tonos por color y sin esto el degradado se ve en rayas
    static const float T[16] = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
    for (int y = 0; y < D; y++) for (int x = 0; x < D; x++) {
      const float t = (T[(x & 3) | ((y & 3) << 2)] + 0.5f) / 16.f;
      // el fondo del mapa de Google de la pantalla grande (#101b38), un poco más claro arriba
      const float r = (20 - 8.f * y / D) / 8.f, g = (33 - 12.f * y / D) / 4.f, b = (66 - 20.f * y / D) / 8.f;
      fondoFijo[y * D + x] = (uint16_t)(((int)(r + t) << 11) | ((int)(g + t) << 5) | (int)(b + t));
    }
  }
  memcpy(fb, fondoFijo, D * D * 2);
}
static void mapa(float cyCarro) {
  const float dtp = lim(tAhora - fijoT, 0, 1.6f), k = 0.25f;   // sigue andando con la velocidad que traía; se corrige suave
  mpx += (fijoX + velX * dtp - mpx) * k; mpy += (fijoY + velY * dtp - mpy) * k;
  if (imuAddr) {   // el giro lo pone su giroscopio (al instante y suave); el rumbo del GPS solo corrige de a poco
    mrumbo += rumboVel * dtCuadro;
    mrumbo += (fmodf(fijoR - mrumbo + 540, 360) - 180) * (tAhora - fijoT < 3 ? 0.03f : 0);
  } else mrumbo += (fmodf(fijoR + velR * dtp - mrumbo + 540, 360) - 180) * k;
  const Calles &c = *callesHay;
  if (!c.nv) return;
  // Acostado, como los navegadores: una cámara detrás y arriba del carro mirando hacia adelante. Lo de lejos se achica
  // y sube hacia el horizonte. Medidas en metros; (x a la derecha, y hacia adelante del carro).
  const float F = 470, ALTO = 150, ATRAS = 150, IN = 0.82f, cI = cosf(IN), sI = sinf(IN), CERCA = -110, LEJOS = 560;
  const float cy0 = cyCarro + F * (ATRAS * sI - ALTO * cI) / (ATRAS * cI + ALTO * sI);
  auto proy = [&](float x, float y, float &sx, float &sy, float &kk) {
    const float prof = (y + ATRAS) * cI + ALTO * sI; kk = F / prof; sx = C + x * kk; sy = cy0 - ((y + ATRAS) * sI - ALTO * cI) * kk;
  };
  const float h = mrumbo * PI / 180, ch = cosf(h), sh = sinf(h);
  auto aCarro = [&](int i, float &x, float &y) {   // del mapa (este, norte) al carro (derecha, adelante)
    const float dx = (c.x[i] - 128) * MAPA_M - mpx, dy = (c.y[i] - 128) * MAPA_M - mpy;
    x = dx * ch - dy * sh; y = dx * sh + dy * ch;
  };
  // los colores del mapa de Google de la pantalla grande (CubiGoogle.ESTILO), con la ruta azul de Google
  static const Col AGUA{12, 42, 85}, PARQUE{18, 48, 63}, CALLE{42, 58, 102}, AVENIDA{58, 77, 128}, RIO{16, 52, 104}, RUTA_B{26, 86, 196}, RUTA{76, 139, 245};
  for (int v = 0; v < c.nv; v++) {                 // agua y parques: lo que queda detrás de la cámara se recorta
    if (c.clase[v] != 3 && c.clase[v] != 4) continue;
    float qx[72], qy[72], kk, ax, ay; int n = 0;
    aCarro(c.ini[v + 1] - 1, ax, ay);
    for (int i = c.ini[v]; i < c.ini[v + 1]; i++) {
      float bx, by; aCarro(i, bx, by);
      const float da = ay - CERCA, db = by - CERCA;
      if ((da >= 0) != (db >= 0) && n < 71) { proy(ax + (bx - ax) * da / (da - db), CERCA, qx[n], qy[n], kk); n++; }
      if (db >= 0 && n < 71) { proy(bx, by, qx[n], qy[n], kk); n++; }
      ax = bx; ay = by;
    }
    if (n >= 3) rellenar(qx, qy, n, c.clase[v] == 3 ? AGUA : PARQUE, 0.9f);
  }
  struct Pasada { uint8_t clase; Col col; float ancho; };   // ancho en metros
  // el tráfico va encima de la ruta, como en Google Maps: naranja lo lento, rojo lo trancado
  const Pasada PAS[] = {{5, RIO, 6}, {0, CALLE, 8}, {1, AVENIDA, 13}, {2, RUTA_B, 19}, {2, RUTA, 12}, {6, Col{242, 153, 0}, 12}, {7, Col{232, 69, 60}, 12}};
  for (const auto &ps : PAS) for (int v = 0; v < c.nv; v++) {
    if (c.clase[v] != ps.clase) continue;
    float ax, ay; aCarro(c.ini[v], ax, ay);
    for (int i = c.ini[v] + 1; i < c.ini[v + 1]; i++) {
      float bx, by; aCarro(i, bx, by);
      float px = ax, py = ay, qx = bx, qy = by;
      ax = bx; ay = by;
      if ((py < CERCA && qy < CERCA) || (py > LEJOS && qy > LEJOS) || (fminf(fabsf(px), fabsf(qx)) > LEJOS && px * qx > 0)) continue;
      if (py < CERCA) { const float t = (CERCA - py) / (qy - py); px += (qx - px) * t; py = CERCA; }   // se recorta en el borde de la cámara
      if (qy < CERCA) { const float t = (CERCA - qy) / (py - qy); qx += (px - qx) * t; qy = CERCA; }
      float sx0, sy0, k0, sx1, sy1, k1; proy(px, py, sx0, sy0, k0); proy(qx, qy, sx1, sy1, k1);
      if (fmaxf(sx0, sx1) < -20 || fminf(sx0, sx1) > D + 20 || fmaxf(sy0, sy1) < -20 || fminf(sy0, sy1) > D + 20) continue;
      // un cuadrilátero que se angosta con la distancia, y la unión redonda
      const float w0 = ps.ancho * k0 / 2, w1 = ps.ancho * k1 / 2, ddx = sx1 - sx0, ddy = sy1 - sy0, l = sqrtf(ddx * ddx + ddy * ddy) + 1e-4f, nx = -ddy / l, ny = ddx / l;
      float X[4] = {sx0 + nx * w0, sx1 + nx * w1, sx1 - nx * w1, sx0 - nx * w0}, Y[4] = {sy0 + ny * w0, sy1 + ny * w1, sy1 - ny * w1, sy0 - ny * w0};
      rellenar(X, Y, 4, ps.col, 1);
      disco(sx1, sy1, w1, ps.col, 1);
    }
  }
}
static void carrito(float cx, float cy) {   // la flecha azul de Google, con su borde blanco
  disco(cx, cy, 30, Col{66, 133, 244}, 0.18f);
  float px[4] = {cx, cx + 13, cx, cx - 13}, py[4] = {cy - 17, cy + 14, cy + 7, cy + 14};
  float ox[4], oy[4]; for (int i = 0; i < 4; i++) { ox[i] = cx + (px[i] - cx) * 1.3f; oy[i] = cy + (py[i] - cy) * 1.3f + 1; }
  rellenar(ox, oy, 4, BLANCO, 1); rellenar(px, py, 4, Col{66, 133, 244}, 1);
}
// arriba y abajo se oscurece un poco, para que se lean las tarjetas
static void velos() {   // con cuentas enteras sobre el 5-6-5: con decimales tardaban medio cuadro
  // en dieciseisavos y con tramado al redondear, para que no salgan rayas
  static const int T[16] = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
  const int cr = (6 >> 3) << 4, cg = (12 >> 2) << 4, cb = (40 >> 3) << 4;
  for (int y = 0; y < 150; y++) {
    const int a = (int)(64 * 0.62f * powf(1 - y / 150.f, 1.4f));
    if (a <= 0) continue;
    for (int k = 0; k < 2; k++) {
      const int yy = k ? D - 1 - y : y; uint16_t *f = fb + yy * D;
      for (int x = 0; x < D; x++) {
        uint16_t q = f[x]; int r = ((q >> 11) & 31) << 4, g = ((q >> 5) & 63) << 4, b = (q & 31) << 4, t = T[(x & 3) | ((yy & 3) << 2)];
        r = (r + (((cr - r) * a) >> 6) + t) >> 4; g = (g + (((cg - g) * a) >> 6) + t) >> 4; b = (b + (((cb - b) * a) >> 6) + t) >> 4;
        f[x] = (uint16_t)((r << 11) | (g << 5) | b);
      }
    }
  }
}
// la flecha del giro
static void flecha(float cx, float cy, float k, const char *dir) {
  if (!strcmp(dir, "fin")) {
    figura(cx - k, cy - k, cx + k, cy + k, Col{255, 107, 94}, 1, [=](float x, float y) { return fabsf(dCirculo(x, y, cx, cy, k * 0.36f)) - k * 0.09f; });
    disco(cx, cy, k * 0.13f, Col{255, 107, 94}, 1); return;
  }
  const float w = k * 0.2f;
  if (!strcmp(dir, "recto")) {
    figura(cx - k, cy - k, cx + k, cy + k, BLANCO, 1, [=](float x, float y) { return dSegmento(x, y, cx, cy + k * 0.55f, cx, cy - k * 0.15f, w / 2); });
    float px[3] = {cx, cx - k * 0.36f, cx + k * 0.36f}, py[3] = {cy - k * 0.62f, cy - k * 0.12f, cy - k * 0.12f}; rellenar(px, py, 3, BLANCO, 1); return;
  }
  const float s = !strcmp(dir, "izq") ? -1 : 1;
  figura(cx - k, cy - k, cx + k, cy + k, BLANCO, 1, [=](float x, float y) {
    return fminf(dSegmento(x, y, cx - s * k * 0.3f, cy + k * 0.55f, cx - s * k * 0.3f, cy - k * 0.1f, w / 2), dSegmento(x, y, cx - s * k * 0.3f, cy - k * 0.1f, cx + s * k * 0.2f, cy - k * 0.1f, w / 2)); });
  float px[3] = {cx + s * k * 0.62f, cx + s * k * 0.14f, cx + s * k * 0.14f}, py[3] = {cy - k * 0.1f, cy - k * 0.48f, cy + k * 0.28f}; rellenar(px, py, 3, BLANCO, 1);
}
// la pila: la cabeza de gato de vidrio, llena hasta el porcentaje (con ola), y el número adentro
static float dGato(float x, float y, float cx, float cy, float h) {
  const float hr = h * 0.44f, hy = cy + h * 0.08f;
  float d = (sqrtf((x - cx) * (x - cx) / (hr * hr) + (y - hy) * (y - hy) / (hr * hr * 0.74f)) - 1) * hr * 0.86f;
  for (int s = -1; s <= 1; s += 2) d = fminf(d, dTriangulo(x, y, cx + s * hr * 0.08f, hy - hr * 0.78f, cx + s * hr * 0.76f, hy - hr * 1.3f, cx + s * hr * 0.98f, hy - hr * 0.2f));
  return d;
}
static void pila(float cx, float cy, float h) {
  if (pilaB < 0) return;
  const int pc = (int)lim(pilaB, 0, 100);
  const Col liq0 = pc < 15 ? Col{255, 154, 138} : pc < 35 ? Col{255, 224, 138} : Col{168, 255, 203}, liq1 = pc < 15 ? Col{240, 68, 58} : pc < 35 ? Col{245, 165, 36} : Col{47, 196, 122};
  const float x0 = cx - h * 0.5f, y0 = cy - h * 0.5f, x1 = cx + h * 0.5f, y1 = cy + h * 0.55f;
  if (pilaCarga) { const float late = 0.5f + 0.5f * sinf(tAhora * 6); figura(x0 - 8, y0 - 8, x1 + 8, y1 + 8, liq1, 0.25f + 0.25f * late, [=](float x, float y) { return dGato(x, y, cx, cy, h) - 5; }); }
  figura(x0, y0, x1, y1, BLANCO, 0.95f, [=](float x, float y) { return dGato(x, y, cx, cy, h); });
  figura(x0, y0, x1, y1, Col{40, 62, 160}, Col{24, 40, 120}, 1, [=](float x, float y) { return dGato(x, y, cx, cy, h) + h * 0.05f; });
  const float nivel = cy + h * 0.46f - h * 0.86f * pc / 100, amp = h * (pilaCarga ? 0.03f : 0.015f), fase = tAhora * (pilaCarga ? 6.f : 2.5f);
  figura(x0, nivel - amp * 2, x1, y1, liq0, liq1, 1, [=](float x, float y) { return fmaxf(dGato(x, y, cx, cy, h) + h * 0.05f, (nivel + amp * sinf(x * 0.25f + fase)) - y); });
  if (pilaCarga) for (int i = 0; i < 4; i++) {           // burbujas que suben
    float sube = fmodf(tAhora * 22 + i * 11, fmaxf(4, cy + h * 0.4f - nivel)), bx = cx - h * 0.22f + i * h * 0.15f;
    disco(bx, cy + h * 0.4f - sube, 1.6f + (i % 2), BLANCO, 0.7f);
  }
  char t[6]; snprintf(t, sizeof t, "%d", pc);
  textoFilo(t, cx - (pilaCarga ? h * 0.08f : 0), cy + h * 0.15f, h * (pc == 100 ? 0.3f : 0.36f), BLANCO);
  if (pilaCarga) { const float rx = cx + anchoTexto(t, h * 0.36f) / 2 + h * 0.02f, ry = cy + h * 0.12f, k = h * 0.1f;
    float px[6] = {rx + k * 0.25f, rx - k * 0.6f, rx - k * 0.05f, rx - k * 0.3f, rx + k * 0.6f, rx + k * 0.05f}, py[6] = {ry - k, ry + k * 0.15f, ry + k * 0.15f, ry + k, ry - k * 0.2f, ry - k * 0.2f};
    rellenar(px, py, 6, Col{255, 247, 194}, 1); }
}
static void nube(float cx, float cy, float r) {
  const bool sol = !strcmp(climaE, "despejado") && !climaNoche, luna = !strcmp(climaE, "despejado") && climaNoche;
  if (sol) { disco(cx, cy, r * 0.62f, Col{255, 208, 64}, 1); return; }
  if (luna) { figura(cx - r, cy - r, cx + r, cy + r, Col{236, 236, 214}, 1, [=](float x, float y) { return fmaxf(dCirculo(x, y, cx, cy, r * 0.7f), -dCirculo(x, y, cx + r * 0.38f, cy - r * 0.28f, r * 0.58f)); }); return; }
  const Col nb{238, 243, 252};
  disco(cx - r * 0.38f, cy + r * 0.12f, r * 0.42f, nb, 1); disco(cx + r * 0.08f, cy - r * 0.16f, r * 0.55f, nb, 1); disco(cx + r * 0.5f, cy + r * 0.14f, r * 0.38f, nb, 1);
  figura(cx - r, cy, cx + r, cy + r * 0.6f, nb, 1, [=](float x, float y) { return dCaja(x, y, cx + r * 0.06f, cy + r * 0.32f, r * 0.62f, r * 0.2f, r * 0.18f); });
  if (!strcmp(climaE, "lluvia") || !strcmp(climaE, "tormenta")) for (int i = 0; i < 3; i++) {
    float p = fmodf(tAhora * 1.6f + i * 0.37f, 1), gx = cx + (i - 1) * r * 0.42f, gy = cy + r * (0.62f + 0.5f * p);
    grueso(gx, gy, gx - r * 0.08f, gy + r * 0.2f, r * 0.1f, Col{120, 190, 255}, 1 - p * 0.8f);
  }
}
static void horaTexto(char *b, size_t n) {
  if (horaMin < 0) { b[0] = 0; return; }
  int m = (horaMin + (int)((tAhora - horaT) / 60)) % 1440, h = m / 60 % 12; snprintf(b, n, "%d:%02d", h == 0 ? 12 : h, m % 60);
}
static void metros(int m, char *b, size_t n) {
  if (m < 15) snprintf(b, n, "ya"); else if (m < 1000) snprintf(b, n, "%d m", (m + 5) / 10 * 10); else snprintf(b, n, "%d,%d km", m / 1000, m % 1000 / 100);
}

static void pintar() {
  fondo();
  // el giro se queda hasta que el celular diga que ya no hay ruta (parado, Maps no manda avisos nuevos); sin celular, 3 min
  const bool nav = giroDir[0] && tAhora - ultimoMsg < 180;
  if (otaTotal) {                                            // actualizándose por Bluetooth
    char p[24]; snprintf(p, sizeof p, "%d %%", (int)(100.f * otaHecho / otaTotal));
    textoFilo("Actualizando", C, 200, 40, BLANCO); textoFilo(p, C, 260, 56, BLANCO);
    return;
  }
  const float cyCarro = nav ? 322 : 312;
  mapa(cyCarro);
  velos();
  carrito(C, cyCarro);
  char b[40];
  if (nav) {                                                 // arriba: la tarjeta del giro
    vidrio(78, 46, 388, 156, 32, true);
    flecha(134, 101, 74, giroDir);
    if (!strcmp(giroDir, "fin")) snprintf(b, sizeof b, giroM >= 0 && giroM > 15 ? "Llegando" : "Llegaste"); else if (giroM >= 0) metros(giroM, b, sizeof b); else b[0] = 0;
    if (b[0]) {
      texto(b, 186, giroCalle[0] ? 84 : 101, 44, BLANCO, 1, true, 190);
      if (giroCalle[0]) texto(giroCalle, 188, 128, 21, Col{205, 218, 255}, 1, true, 186);
    } else if (giroCalle[0]) dosRenglones(giroCalle, 186, 101, 25, BLANCO, 190);   // sin metros ("Dirígete al noroeste…"): la indicación en grande, en dos renglones
    if (pilaB >= 0 || llegaTxt[0] || faltaM >= 0) {           // sin pila ni hora de llegada, la tarjeta de abajo sobra
    vidrio(124, 336, 342, 412, 34);                          // abajo: la pila, a qué hora se llega y lo que falta
    pila(165, 373, 56);
    if (llegaTxt[0]) { snprintf(b, sizeof b, "llega %s", llegaTxt); texto(b, 202, 358, 23, BLANCO, 1, true, 132); }
    char f[16] = "", r[12] = ""; if (faltaM >= 0) metros(faltaM, f, sizeof f); if (pilaReal >= 0) snprintf(r, sizeof r, "%d km", pilaReal);
    snprintf(b, sizeof b, "%s%s%s", f, f[0] && r[0] ? "  ·  " : "", r); texto(b, 202, 389, 16, Col{225, 232, 255}, 1, true, 132);
    }
  } else {                                                   // sin ruta: la hora grande, el clima y la pila con la autonomía
    horaTexto(b, sizeof b);
    if (b[0]) textoFilo(b, C, 96, 84, BLANCO);
    if (climaG > -1000) {
      char g[40]; snprintf(g, sizeof g, "%d°  %s", climaG, climaLugar);
      const float w = fminf(170, anchoTexto(g, 24)), x0 = C - (w + 54) / 2;
      vidrio(x0, 160, x0 + w + 54, 206, 23); nube(x0 + 26, 182, 16); texto(g, x0 + 46, 182, 24, BLANCO, 1, true, 170);
    }
    if (calleAhora[0]) textoFilo(calleAhora, C, climaG > -1000 ? 228 : 182, 19, Col{220, 228, 255}, false, 300);   // la calle por donde va
    if (pilaB >= 0) {
      vidrio(96, 330, 370, 418, 40);
      pila(146, 373, 66);
      if (pilaCarga) {
        snprintf(b, sizeof b, pilaKw > 0.3f ? "Cargando %.1f kW" : "Cargando", pilaKw); for (char *p = b; *p; p++) if (*p == '.') *p = ',';
        texto(b, 190, 356, 24, BLANCO, 1, true, 168);
        if (pilaHoras >= 0) { snprintf(b, sizeof b, "lleno en %d h %02d", (int)pilaHoras, (int)(pilaHoras * 60) % 60); texto(b, 190, 390, 18, Col{225, 232, 255}, 1, true, 168); }
      } else {
        if (pilaReal >= 0) { snprintf(b, sizeof b, "Real %d km", pilaReal); texto(b, 190, 356, 26, BLANCO, 1, true, 168); }
        char w[16] = "", p[16] = ""; if (pilaWltp >= 0) snprintf(w, sizeof w, "WLTP %d km", pilaWltp); if (pilaProm > 0) snprintf(p, sizeof p, "%.1f", pilaProm);
        for (char *q = p; *q; q++) if (*q == '.') *q = ',';
        snprintf(b, sizeof b, "%s%s%s", w, w[0] && p[0] ? " · " : "", p); texto(b, 190, 390, 18, Col{225, 232, 255}, 1, true, 168);
      }
    }
  }
  // el límite de velocidad de la vía, como la señal de tránsito; si va pasado, el aro rojo late
  if (limV > 0) {
    const float sx = 404, sy = 236, r = 27, late = limX ? 0.5f + 0.5f * sinf(tAhora * 9) : 0;
    if (limX) disco(sx, sy, r + 8, Col{232, 69, 60}, 0.25f + 0.35f * late);
    disco(sx, sy, r, Col{232, 40, 40}, 1); disco(sx, sy, r - 5.5f, BLANCO, 1);
    char v[6]; snprintf(v, sizeof v, "%d", limV); texto(v, sx, sy, limV >= 100 ? 20 : 24, Col{20, 20, 24});
  }
  if (!conectado) texto("buscando el celular…", C, 440, 15, Col{170, 184, 214}, 0.9f);
}

// ---------------------------------------------------------------- arranque y vuelta
void setup() {
  Serial.begin(115200);
  Wire.begin(P_SDA, P_SCL, 400000);
  fb = (uint16_t *)ps_malloc(D * D * 2);
  callesHay = (Calles *)ps_calloc(1, sizeof(Calles)); callesCarga = (Calles *)ps_calloc(1, sizeof(Calles));
  bus = new Arduino_ESP32QSPI(P_CS, P_SCK, P_D0, P_D1, P_D2, P_D3);
  panel = new Arduino_CO5300(bus, P_RST, 0, D, D, 6, 0, 0, 0);   // el 6: así lo trae el ejemplo de Waveshare para esta placa
  if (!panel->begin()) Serial.println("Mapi: la pantalla no arrancó");
  panel->fillScreen(0); panel->setBrightness(200);
  imuInit();
  bleInit();
  Serial.println("Mapi v" VERSION_MAPI " lista");
}

void loop() {
  static uint32_t antes = millis();
  const uint32_t ahora = millis(); dtCuadro = (ahora - antes) / 1000.f; tAhora += dtCuadro; antes = ahora;
  imuCuadro(dtCuadro);
  if (reinicioEn && (int32_t)(ahora - reinicioEn) > 0) ESP.restart();   // actualizado: arranca con la versión nueva
  Orden o; int n = 0;
  while (n++ < 12 && xQueueReceive(colaBle, &o, 0) == pdTRUE) orden(String(o.t));
  pintar();
  const uint32_t pintado = millis();
  panel->draw16bitRGBBitmap(0, 0, fb, D, D);
  if (Serial.available() && Serial.read() == 't')
    Serial.printf("cuadro %lu ms (dibujo %lu, pantalla %lu)\n", (unsigned long)(millis() - ahora), (unsigned long)(pintado - ahora), (unsigned long)(millis() - pintado));
}
