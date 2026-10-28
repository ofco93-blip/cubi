// Dibuja las caras de Cubi en el PC con el mismo código del firmware (viene de Isa): escribe cuadros RGB crudos.
// Uso: vista <cara|reaccion|canta_ritmo> <segundos> <salida.raw>
#include "../../firmware/cubi/dibujo.h"
#include "../../firmware/cubi/caras.h"
#include <cstdlib>
#include <string>
using namespace caras;
int main(int argc, char **argv) {
  if (argc < 4) return 1;
  std::string que = argv[1]; float seg = atof(argv[2]);
  static uint16_t mem[dib::RW * dib::RH];
  dib::fb = mem; dib::giro = 0;
  FILE *o = fopen(argv[3], "wb");
  iniciar(7);
  bool esBase = false; int ritmo = 0;
  if (que.rfind("canta_", 0) == 0) { for (int k = 0; k < N_RITMO; k++) if (que.substr(6) == NOMBRE_RITMO[k]) ritmo = k; que = "canta"; }
  for (int b = 0; b < N_BASE; b++) if (que == NOMBRE_BASE[b]) { base((Base)b); esBase = true; }
  const float FPS = 25, dt = 1 / FPS;
  if (!esBase) { base(REPOSO); for (int r = 1; r < N_REAC; r++) if (que == NOMBRE_REAC[r]) { cuadro(0, dt, Entradas()); reaccion((Reac)r); } }
  static uint8_t rgb[dib::W * dib::H * 3];
  for (int i = 0; i < (int)(seg * FPS); i++) {
    float t = i * dt;
    Entradas e;
    if (que == "habla" || que == "telefono") { float s = fabsf(sinf(t * 9.5f)) * (0.5f + 0.5f * sinf(t * 2.3f)); e.boca = (fmodf(t, 2.2f) < 1.7f) ? s : 0; }
    if (que == "canta") {
      e.ritmo = ritmo;
      float bpm = ritmo == R_GUITARRA || ritmo == R_DISFRUTA || ritmo == R_PIANO || ritmo == R_JAZZ ? 84 : ritmo == R_URBANO ? 94 : ritmo == R_ELECTRO || ritmo == R_DJ ? 126 : ritmo == R_ROCK ? 140 : 104;
      e.bpm = bpm; e.pulso = fmodf(t * bpm / 60, 1); e.boca = 0.35f + 0.45f * fabsf(sinf(t * 5.3f));
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
