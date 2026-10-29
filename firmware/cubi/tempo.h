#pragma once
// Tempo por autocorrelación de los ataques (lo que hacen librosa y aubio). El patrón del golpe se repite aunque
// esté sincopado (dembow, cumbia); el intervalo entre bombos no, y por eso antes el estilo saltaba "al azar".
#include <math.h>

struct Tempo {
  static const int N = 192;              // 6 s de ataques, uno cada bloque de 32 ms
  static constexpr float FPS = 31.25f;   // bloques por segundo (16000 / 512)
  static const int L0 = 10, L1 = 32;     // retrasos que se prueban: 187 a 59 BPM
  float h[N] = {};
  int i = 0, n = 0;
  void poner(float ataque) { h[i] = ataque; i = (i + 1) % N; if (n < N) n++; }
  void borrar() { n = 0; }
  // bpm (0 si aún no hay 6 s) y reg: qué tan parejo se repite el golpe (0 = ruido, 1 = metrónomo)
  void medir(float &bpm, float &reg) const {
    bpm = reg = 0;
    if (n < N) return;
    float m = 0, r0 = 0, r[L1 + 2];
    for (float v : h) m += v;
    m /= N;
    for (float v : h) r0 += (v - m) * (v - m);
    if (r0 <= 0) return;
    for (int L = L0 - 1; L <= L1 + 1; L++) {
      float s = 0;
      for (int k = 0; k + L < N; k++) s += (h[(i + k) % N] - m) * (h[(i + k + L) % N] - m);
      r[L] = s / r0 * N / (N - L);
    }
    // el pico más alto, con preferencia suave por ~115 BPM (así no confunde el golpe con la mitad o el doble)
    int mejor = 0; float pMejor = 0;
    for (int L = L0; L <= L1; L++) {
      if (r[L] < r[L - 1] || r[L] < r[L + 1] || r[L] <= 0) continue;
      float o = log2f(FPS * 60 / L / 115) / 0.9f, p = r[L] * expf(-0.5f * o * o);
      if (p > pMejor) { pMejor = p; mejor = L; }
    }
    if (!mejor) return;
    float a = r[mejor - 1], b = r[mejor], c = r[mejor + 1], d = a - 2 * b + c;
    float lf = mejor + (d < 0 ? 0.5f * (a - c) / d : 0);
    bpm = FPS * 60 / lf;
    reg = fminf(b, 1);
  }
};
