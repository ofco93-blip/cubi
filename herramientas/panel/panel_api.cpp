// La cara de Cubi como DLL para el panel en vivo (panel.py): el mismo código del firmware, cuadro a cuadro.
#include "../../firmware/cubi/dibujo.h"
#include "../../firmware/cubi/caras.h"
using namespace caras;
#define API extern "C" __declspec(dllexport)

static uint16_t mem[dib::RW * dib::RH];
static uint8_t rgb[dib::W * dib::H * 3];

API int ancho() { return dib::W; }
API int alto() { return dib::H; }
API int nRitmos() { return N_RITMO; }
API const char *nombreRitmo(int i) { return NOMBRE_RITMO[i]; }
API void arrancar() { dib::fb = mem; dib::giro = 0; iniciar(7); }
API void ponerPaquete(int p) { paquete(p); }

// musica = 0: cara de reposo; 1: bailando (y cantando si canta = 1)
API const uint8_t *cuadroPanel(float t, float dt, int musica, int canta, float boca, float an, float voz, float nivel,
                               float emocion, int ritmo, float bpm, float pulso) {
  base(musica ? CANTA : REPOSO);
  Entradas e;
  e.boca = boca; e.ancho = an; e.canta = canta; e.voz = voz; e.nivel = nivel; e.emocion = emocion;
  if (musica) { e.ritmo = ritmo; e.bpm = bpm; e.pulso = pulso; }
  dib::empezar(); cuadro(t, dt, e); dib::terminar();
  for (int y = 0; y < dib::H; y++) for (int x = 0; x < dib::W; x++) {
    uint16_t p = mem[dib::idx(x, y)]; uint8_t *q = rgb + (y * dib::W + x) * 3;
    q[0] = ((p >> 11) & 31) * 255 / 31; q[1] = ((p >> 5) & 63) * 255 / 63; q[2] = (p & 31) * 255 / 31;
  }
  return rgb;
}
