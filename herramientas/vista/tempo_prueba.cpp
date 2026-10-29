// Prueba de tempo.h con ritmos inventados: g++ -std=c++17 tempo_prueba.cpp && ./a.out (falla con código 1)
#include "../../firmware/cubi/tempo.h"
#include <cstdio>
#include <cstdlib>
#include <initializer_list>
#include <utility>

static float azar() { return rand() / (float)RAND_MAX; }

// ataques de un ritmo: golpes = {posición dentro del tiempo, fuerza}, más ruido de fondo
static Tempo ritmo(float bpm, std::initializer_list<std::pair<float, float>> golpes) {
  Tempo t; float h[Tempo::N] = {};
  for (float &v : h) v = azar() * 0.6f;
  float beat = 60 / bpm, t0 = azar();
  for (int b = 0; b < Tempo::N / Tempo::FPS / beat + 2; b++)
    for (auto g : golpes) { int k = int((t0 + (b + g.first) * beat) * Tempo::FPS); if (k >= 0 && k < Tempo::N) h[k] += g.second; }
  for (float v : h) t.poner(v);
  return t;
}

int main() {
  int mal = 0;
  struct { const char *nom; float bpm; Tempo t; } musica[] = {
    { "electro", 126, ritmo(126, { { 0, 3 }, { 0.5f, 1 } }) },
    { "dembow", 95, ritmo(95, { { 0, 3 }, { 0.75f, 2 }, { 1, 3 }, { 1.5f, 2 } }) },
    { "cumbia", 100, ritmo(100, { { 0, 2.5f }, { 0.5f, 1.5f }, { 0.75f, 0.8f } }) },
    { "balada", 72, ritmo(72, { { 0, 2 }, { 0.5f, 0.6f } }) },
  };
  for (auto &m : musica) {
    float bpm, reg; m.t.medir(bpm, reg);
    bool ok = fabsf(bpm - m.bpm) < m.bpm * 0.04f && reg > 0.35f;
    printf("%-8s %5.1f bpm  reg %.2f  %s\n", m.nom, bpm, reg, ok ? "ok" : "MAL");
    mal += !ok;
  }
  for (int i = 0; i < 5; i++) {  // motor y ruido: sin golpe parejo
    Tempo t; for (int k = 0; k < Tempo::N; k++) t.poner(azar() * 0.3f + (azar() < 0.05f ? 2 : 0));
    float bpm, reg; t.medir(bpm, reg);
    printf("ruido    %5.1f bpm  reg %.2f  %s\n", bpm, reg, reg < 0.35f ? "ok" : "MAL");
    mal += reg >= 0.35f;
  }
  return mal ? 1 : 0;
}
