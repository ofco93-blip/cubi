// Dibuja las caras de Cubi en el PC con el mismo código del firmware (viene de Isa): escribe cuadros RGB crudos.
// Uso: vista <cara|reaccion|canta_ritmo> <segundos> <salida.raw>
#include "../../firmware/cubi/dibujo.h"
#include "../../firmware/cubi/caras.h"
#include <chrono>
#include <cstdlib>
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
  bool esBase = false; int ritmo = 0;
  if (que.rfind("mini_", 0) == 0) { paquete(MINIMALISTA); que = que.substr(5); }   // mini_x = la misma cara en el paquete minimalista
  bool voz = que.rfind("baila_", 0) != 0;   // baila_x = el mismo baile en una parte sin voz
  if (!voz) que = "canta_" + que.substr(6);
  if (que.rfind("canta_", 0) == 0) { for (int k = 0; k < N_RITMO; k++) if (que.substr(6) == NOMBRE_RITMO[k]) ritmo = k; que = "canta"; }
  for (int b = 0; b < N_BASE; b++) if (que == NOMBRE_BASE[b]) { base((Base)b); esBase = true; }
  const float FPS = 25, dt = 1 / FPS;
  if (!esBase) { base(REPOSO); for (int r = 1; r < N_REAC; r++) if (que == NOMBRE_REAC[r]) { cuadro(0, dt, Entradas()); reaccion((Reac)r); } }
  if (que == "clima") { cuadro(0, dt, Entradas()); reaccion(SOL); mostrarGrados(28, duracion(SOL)); }   // "Cubi, ¿cómo está el clima?"
  static uint8_t rgb[dib::W * dib::H * 3];
  for (int i = 0; i < (int)(seg * FPS); i++) {
    float t = i * dt;
    Entradas e;
    if (que == "habla" || que == "telefono") { float s = fabsf(sinf(t * 9.5f)) * (0.5f + 0.5f * sinf(t * 2.3f)); e.boca = (fmodf(t, 2.2f) < 1.7f) ? s : 0; }
    if (que == "canta") {
      e.ritmo = ritmo; e.canta = voz;
      float bpm = ritmo == R_GUITARRA || ritmo == R_DISFRUTA || ritmo == R_PIANO || ritmo == R_JAZZ ? 84 : ritmo == R_URBANO ? 94 : ritmo == R_ELECTRO || ritmo == R_DJ ? 126 : ritmo == R_ROCK ? 140 : 104;
      e.bpm = bpm; e.pulso = fmodf(t * bpm / 60, 1);
      e.canta = voz && fmodf(t, 4) < 2.5f; e.boca = e.canta ? 0.35f + 0.45f * fabsf(sinf(t * 5.3f)) : 0;
      e.nivel = 0.5f + 0.4f * fabsf(sinf(t * 1.7f));
    }
    dib::empezar(); if (que == "actualizando") progreso(t, t / seg); else cuadro(t, dt, e); dib::terminar();
    for (int y = 0; y < dib::H; y++) for (int x = 0; x < dib::W; x++) {
      uint16_t p = mem[dib::idx(x, y)]; uint8_t *q = rgb + (y * dib::W + x) * 3;
      q[0] = ((p >> 11) & 31) * 255 / 31; q[1] = ((p >> 5) & 63) * 255 / 63; q[2] = (p & 31) * 255 / 31;
    }
    fwrite(rgb, 1, sizeof rgb, o);
  }
  fclose(o);
}
