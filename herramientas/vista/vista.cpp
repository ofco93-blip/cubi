// Dibuja las caras de Cubi en el PC con el mismo código del firmware (viene de Isa): escribe cuadros RGB crudos.
// Uso: vista <cara|reaccion|canta_ritmo> <segundos> <salida.raw>
#include "../../firmware/cubi/dibujo.h"
#include "../../firmware/cubi/caras.h"
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <utility>
#include <string>
using namespace caras;
// "vista medir": cuánto tarda cada baile por cuadro (con voz y sin voz). En el ESP32 es ~60 veces más lento.
static int medir() {
  static uint16_t mem[dib::RW * dib::RH];
  dib::fb = mem; dib::giro = 0;
  iniciar(7);
  for (int b : {REPOSO, HABLA}) {
    base((Base)b); auto t0 = std::chrono::steady_clock::now();
    for (int i = 0; i < 100; i++) { Entradas e; e.boca = 0.5f; dib::empezar(); cuadro(i * 0.04f, 0.04f, e); dib::terminar(); }
    printf("%s - %.0f us\n", NOMBRE_BASE[b], std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - t0).count() / 100);
  }
  base(CANTA);
  for (int pq = 0; pq < N_PAQ; pq++)
  for (int r = 0; r < N_RITMO; r++) for (int voz = 1; voz >= 0; voz--) {
    paquete(pq);
    auto t0 = std::chrono::steady_clock::now();
    const int N = 100;
    for (int i = 0; i < N; i++) {
      float t = i * 0.04f; Entradas e; e.ritmo = r; e.bpm = 110; e.pulso = fmodf(t * 110 / 60, 1);
      e.canta = voz; e.boca = 0.35f + 0.45f * fabsf(sinf(t * 5.3f)); e.nivel = 0.6f;
      dib::empezar(); cuadro(t, 0.04f, e); dib::terminar();
    }
    double us = std::chrono::duration<double, std::micro>(std::chrono::steady_clock::now() - t0).count() / N;
    printf("%s%s %s %.0f us\n", pq ? "mini_" : "", NOMBRE_RITMO[r], voz ? "voz" : "sin", us);
  }
  return 0;
}
int main(int argc, char **argv) {
  if (argc > 1 && std::string(argv[1]) == "medir") return medir();
  if (argc < 4) return 1;
  std::string que = argv[1]; float seg = atof(argv[2]);
  static uint16_t mem[dib::RW * dib::RH];
  dib::fb = mem; dib::giro = 0;
  FILE *o = fopen(argv[3], "wb");
  iniciar(7);
  if (const char *c = getenv("TINTE")) { long v = strtol(c, nullptr, 16); dib::tinte = {float(v >> 16 & 255), float(v >> 8 & 255), float(v & 255)}; }   // TINTE=ff7ab8: el color escogido en la app
  bool esBase = false; int ritmo = 0;
  if (que.rfind("mini_", 0) == 0) { paquete(MINIMALISTA); que = que.substr(5); }
  for (auto pq : {std::make_pair("novas_", NOVA_S), std::make_pair("kiras_", KIRA_S), std::make_pair("colores_", ATARDECER), std::make_pair("ovalo_", OVALO), std::make_pair("neon_", NEON), std::make_pair("simple_", SIMPLE), std::make_pair("pancho_", CARICATURA), std::make_pair("lino_", TRAZO), std::make_pair("mochi_", BOLITA)})   // las caras nuevas, igual que mini_
    if (que.rfind(pq.first, 0) == 0) { paquete(pq.second); que = que.substr(strlen(pq.first)); }   // mini_x = la misma cara en el paquete minimalista
  if (que.rfind("mapa_", 0) == 0) { mapaVista(true); que = "maps_" + que.substr(5); }   // mapa_x = la misma indicación con el mapa completo
  auto fin = [&](const char *f) { const size_t n = strlen(f); if (que.size() <= n || que.compare(que.size() - n, n, f)) return false; que.resize(que.size() - n); return true; };
  for (int i = 0; i < 3; i++) if (fin(("_m" + std::to_string(i)).c_str())) estiloManos(i);   // x_m1 = la misma escena con otro estilo de manos
  if (fin("_dia")) mapaDia(true);   // maps_x_dia = el tablero en versión día
  if (que.rfind("maps_", 0) == 0) mapaVel(45);
  const bool sinMapa = fin("_solo");   // maps_x_solo = sin el mini mapa
  { const char *F[] = {"_suave", "_cerrada", "_u", "_glorieta", "_salida"}; for (int i = 0; i < 5; i++) if (fin(F[i])) mostrarGiro(i + 1); }   // maps_izq_u, maps_der_salida...
  bool guion = que.rfind("cancion_", 0) == 0;   // cancion_x = una cancion con partes: verso suave, silencio, coro duro, nota larga, solo, final
  if (guion) que = "canta_" + que.substr(8);
  bool voz = que.rfind("baila_", 0) != 0;   // baila_x = el mismo baile en una parte sin voz
  if (!voz) que = "canta_" + que.substr(6);
  if (que.rfind("canta_", 0) == 0) { for (int k = 0; k < N_RITMO; k++) if (que.substr(6) == NOMBRE_RITMO[k]) ritmo = k; que = "canta"; }
  for (int b = 0; b < N_BASE; b++) if (que == NOMBRE_BASE[b]) { base((Base)b); esBase = true; }
  const float FPS = 25, dt = 1 / FPS;
  if (!esBase) { base(REPOSO); for (int r = 1; r < N_REAC; r++) if (que == NOMBRE_REAC[r]) { cuadro(0, dt, Entradas()); reaccion((Reac)r); } }
  if (que == "maps_izq" || que == "maps_der") { mostrarGrados(que == "maps_izq" ? 120 : 40, 4.5f, true); mostrarCalle(que == "maps_izq" ? "Calle 85" : "Carrera 15 con 100"); }   // la flecha con los metros que faltan
  bool mapaDemo = que.rfind("maps_", 0) == 0 && !sinMapa;   // calles de prueba: una cuadrícula con dos avenidas
  if (mapaDemo) {
    std::string w; bool pri = true;
    auto h2 = [](int v) { char b[3]; snprintf(b, 3, "%02x", v); return std::string(b); };
    auto via = [&](int cl, int x0, int y0, int x1, int y1) {
      if (!w.empty()) w += ".";
      w += cl ? "1" : "0";
      for (int k = 0; k <= 4; k++) w += h2(128 + (x0 + (x1 - x0) * k / 4) / 3) + h2(128 + (y0 + (y1 - y0) * k / 4) / 3);
      if (w.size() > 180) { mapaVias(w.c_str(), pri, false); pri = false; w.clear(); }
    };
    for (int g = -320; g <= 320; g += 80) { via(g == 0, g - 40, -330, g + 40, 330); via(g == 80, -330, g + 12, 330, g - 12); }
    via(0, -200, -250, 120, 250);
    { const int gx = que == "maps_der" ? 250 : -250; w += std::string(w.empty() ? "" : ".") + "2" + h2(128 - 60 / 3) + h2(128 - 330 / 3) + h2(128 + 12 / 3) + h2(128 + 80 / 3) + (que == "maps_recto" ? h2(128 + 40 / 3) + h2(128 + 330 / 3) : h2(128 + gx / 3) + h2(128 + (gx > 0 ? 60 : 100) / 3)); }   // la ruta
    mapaVias(w.c_str(), pri, true);
  }
  if (que == "maps_recto") { mostrarGrados(1200, 4.5f, true); mostrarCalle("Autopista Norte"); }
  if (que == "altura") { cuadro(0, dt, Entradas()); reaccion(SORPRESA); mostrarGrados(2600, 4, true); }   // subimos a 2.600 m
  if (que == "clima") { cuadro(0, dt, Entradas()); reaccion(SOL); mostrarGrados(28, duracion(SOL)); }   // "Cubi, ¿cómo está el clima?"
  static uint8_t rgb[dib::W * dib::H * 3];
  for (int i = 0; i < (int)(seg * FPS); i++) {
    float t = i * dt;
    Entradas e;
    if (que == "habla" || que == "telefono") { float s = fabsf(sinf(t * 9.5f)) * (0.5f + 0.5f * sinf(t * 2.3f)); e.boca = (fmodf(t, 2.2f) < 1.7f) ? s : 0; }
    if (que == "canta") {
      e.ritmo = ritmo; e.canta = voz;
      float bpm = ritmo == R_GUITARRA || ritmo == R_DISFRUTA || ritmo == R_PIANO || ritmo == R_JAZZ || ritmo == R_VELA ? 84 : ritmo == R_URBANO ? 94 : ritmo == R_ELECTRO || ritmo == R_DJ ? 126 : ritmo == R_ROCK ? 140 : 104;
      e.bpm = bpm; e.pulso = fmodf(t * bpm / 60, 1);
      e.canta = voz && fmodf(t, 4) < 2.5f; e.boca = e.canta ? 0.35f + 0.45f * fabsf(sinf(t * 5.3f)) : 0;
      e.ancho = 0.5f + 0.5f * sinf(t * 2.3f);   // las vocales cambian: a, e, i, o, u
      if (e.canta && fmodf(t, 4) > 1.2f && fmodf(t, 4) < 2.2f) { e.boca = 0.45f + 0.55f * fabsf(sinf(t * 6)); e.ancho = 0.45f; e.voz = 1; e.emocion = 1; }   // un "¡aaah!" sostenido (las dos manos en el micrófono)
      e.nivel = 0.5f + 0.4f * fabsf(sinf(t * 1.7f));
      if (guion) {
        const float lat = 0.04f * fabsf(sinf(t * 11));   // el nivel real tiembla un poco
        e.voz = 0.5f; e.emocion = 0; e.canta = false; e.boca = 0; e.nivel = 0;
        auto canta = [&](float cada, float calla, float b0, float b1) { e.canta = fmodf(t, cada) < cada - calla; e.boca = e.canta ? b0 + (b1 - b0) * fabsf(sinf(t * 5.3f)) : 0; };
        if (t < 1) {}                                                              // todavia no arranca
        else if (t < 9) { e.nivel = 0.33f + lat; canta(2.7f, 0.5f, 0.2f, 0.5f); }  // verso suave
        else if (t < 13) { e.nivel = 0.64f + lat; canta(2.7f, 0.5f, 0.3f, 0.6f); } // sube
        else if (t < 14.2f) { e.nivel = 0.03f; }                                   // silencio antes del coro
        else if (t < 24) { e.nivel = 0.88f + lat; e.voz = 1; canta(3.2f, 0.4f, 0.55f, 1); }   // coro duro
        else if (t < 27) { e.nivel = 0.7f + lat; e.canta = true; e.boca = 0.6f + 0.3f * fabsf(sinf(t * 6)); e.voz = 1; e.emocion = 1; }   // nota larga
        else if (t < 32) { e.nivel = 0.62f + lat; }                                // solo, sin voz
        else if (t < 37) { e.nivel = 0.33f + lat; canta(2.7f, 0.5f, 0.2f, 0.5f); } // verso suave otra vez
      }
    }
    if (que == "confeti" && i == (int)(1.5f * FPS)) cuentaPum();
    if (mapaDemo) mapaPos(4 * sinf(t), -150 + t * 14, que == "maps_der" ? 25 * t / 4.5f : que == "maps_izq" ? -25 * t / 4.5f : 0);   // va andando
    dib::empezar(); if (que == "actualizando") progreso(t, t / seg); else cuadro(t, dt, e); dib::terminar();
    for (int y = 0; y < dib::H; y++) for (int x = 0; x < dib::W; x++) {
      uint16_t p = mem[dib::idx(x, y)]; uint8_t *q = rgb + (y * dib::W + x) * 3;
      q[0] = ((p >> 11) & 31) * 255 / 31; q[1] = ((p >> 5) & 63) * 255 / 63; q[2] = (p & 31) * 255 / 31;
    }
    fwrite(rgb, 1, sizeof rgb, o);
  }
  fclose(o);
}
