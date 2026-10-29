#pragma once
// Las caras de Isa (traídas a Cubi): ojos cápsula estilo Eilik con degradado, boquita vertical y reacciones de caricatura.
#include "dibujo.h"

namespace caras {

enum Base : uint8_t { REPOSO, ESCUCHA, HABLA, CANTA, PIENSA, DUERME, TELEFONO, N_BASE };   // TELEFONO = en llamada
enum Reac : uint8_t {
  NINGUNA, SALUDO, BACHE, FRENAZO, ACELERON, ARRANQUE, GIRO_IZQ, GIRO_DER, CARICIA, WHATSAPP, LLAMADA,
  CALOR, RISA, SORPRESA, AMOR, MAREO, MAPS_IZQ, MAPS_DER, LLEGAMOS, SOL, LLUVIA, FRIO, BATERIA, GUINO, CURIOSA, N_REAC
};
extern const char *NOMBRE_BASE[N_BASE];
extern const char *NOMBRE_REAC[N_REAC];

// ritmos de la música (cada uno con su baile y su instrumento)
enum Ritmo : uint8_t { R_POP, R_URBANO, R_ELECTRO, R_TROPICAL, R_KPOP, R_CORAZON, R_GROOVE, R_GUITARRA, R_DISFRUTA, R_ROCK,
                       R_JAZZ, R_DJ, R_PIANO, R_BARRAS, N_RITMO };   // los 4 últimos son de Cubi
extern const char *NOMBRE_RITMO[N_RITMO];

// lo que llega de los sensores en cada cuadro
struct Entradas {
  float boca = 0;      // 0..1: qué tan abierta (voz o música)
  float pulso = 0;     // 0..1: fase del golpe de la música (0 = golpe)
  float bpm = 0;
  uint8_t ritmo = R_POP;
  bool canta = true;    // el artista está cantando (si no, la boca se cierra y solo baila)
  float nivel = 0;      // 0..1: qué tan fuerte suena (las barritas)
  float ancho = 0.5f;   // 0 = boca redonda (o, u) .. 1 = estirada (i, e)
};

void iniciar(uint32_t semilla);
void base(Base b);
Base baseActual();
void reaccion(Reac r);
Reac reaccionActual();
float duracion(Reac r);
// dibuja un cuadro; t en segundos, dt desde el anterior
void cuadro(float t, float dt, const Entradas &e);
// pantalla de "actualizando" (p de 0 a 1)
void progreso(float t, float p);

}  // namespace caras
