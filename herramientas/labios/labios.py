# Entrenar el lip sync de Cubi con el micrófono del computador.
#   1. Grabar: pones una canción en el parlante y el micrófono la graba (Enter para terminar).
#   2. Entrenar: un separador de voz (Demucs) saca de cada grabación cuándo hay alguien cantando; con eso se
#      entrena un detector chiquito que usa las mismas cuentas que hace el robot (FFT de 512 a 16 kHz, cada 32 ms)
#      y se compara con el detector que tiene hoy. Sale voz_modelo.h para el firmware.
# Las grabaciones quedan en Documentos\Cubi labios.
import json, os, sys, threading, time
import numpy as np
import sounddevice as sd
from scipy.io import wavfile
from scipy.signal import resample_poly, medfilt

CARPETA = os.environ.get('CUBI_LABIOS') or os.path.join(os.path.expanduser('~'), 'Documents', 'Cubi labios')
GRAB = os.path.join(CARPETA, 'grabaciones')
CONF = os.path.join(CARPETA, 'ajustes.json')
SR_GRAB, SR, NF = 48000, 16000, 512           # como el robot: 16 kHz, bloques de 512 (32 ms, 31,25 Hz por casilla)
NB = 24                                       # bandas del detector nuevo
BORDES = np.unique(np.round(np.geomspace(2, 256, NB + 1)).astype(int))   # casillas 2..255 (62 Hz a 8 kHz)
NB = len(BORDES) - 1


def leer_conf():
    try: return json.load(open(CONF, encoding='utf-8'))
    except Exception: return {}


def elegir_mic(conf):
    mics = [(i, d['name']) for i, d in enumerate(sd.query_devices())
            if d['max_input_channels'] > 0 and sd.query_hostapis(d['hostapi'])['name'] == 'MME']
    print('\nMicrófonos:')
    for n, (i, nom) in enumerate(mics): print(f'  {n + 1}. {nom}')
    r = input(f'¿Cuál uso? (Enter = {conf.get("mic_nombre", "el de siempre")}): ').strip()
    if r.isdigit() and 1 <= int(r) <= len(mics):
        conf['mic'], conf['mic_nombre'] = mics[int(r) - 1]
        json.dump(conf, open(CONF, 'w', encoding='utf-8'), ensure_ascii=False)
    return conf.get('mic')


def grabar():
    conf = leer_conf()
    mic = elegir_mic(conf)
    nombre = input('\nNombre de la canción (ej. Shakira - Hips Dont Lie): ').strip() or time.strftime('cancion_%H%M%S')
    nombre = ''.join(c for c in nombre if c not in '\\/:*?"<>|')
    trozos, fin = [], threading.Event()
    def cb(datos, *_): trozos.append(datos[:, 0].copy())
    print('\nPon la canción desde el principio. Grabando... (Enter para terminar)')
    with sd.InputStream(device=mic, channels=1, samplerate=SR_GRAB, dtype='float32', callback=cb):
        threading.Thread(target=lambda: (input(), fin.set()), daemon=True).start()
        while not fin.is_set():
            time.sleep(0.2)
            if trozos:
                nv = float(np.sqrt(np.mean(trozos[-1] ** 2)) + 1e-9)
                barra = '#' * int(max(0, min(40, (20 * np.log10(nv) + 60) * 0.8)))
                print(f'\r  {len(trozos) * len(trozos[0]) / SR_GRAB:6.0f} s  {barra:<40}', end='', flush=True)
    x = np.concatenate(trozos)
    if len(x) < SR_GRAB * 20: print('\nMuy corta (menos de 20 s): no la guardo.'); return
    pico = np.max(np.abs(x))
    if pico < 0.01: print('\nNo se oyó casi nada: revisa el micrófono o sube el volumen.'); return
    ruta = os.path.join(GRAB, nombre + '.wav')
    wavfile.write(ruta, SR_GRAB, (x / max(pico, 1e-3) * 0.9 * 32767).astype(np.int16))
    print(f'\nGuardada: {ruta} ({len(x) / SR_GRAB:.0f} s)' + ('  ojo: se saturó, baja el volumen' if pico > 0.98 else ''))


# ---------------------------------------------------------------- lo que oye el robot
def bloques(x16):
    n = len(x16) // NF
    b = x16[:n * NF].reshape(n, NF) * 32767 / 4   # escala parecida a la del micrófono del robot
    b = b - b.mean(axis=1, keepdims=True)
    rms = np.sqrt(np.mean(b ** 2, axis=1))
    return np.abs(np.fft.rfft(b * np.hamming(NF), axis=1))[:, :NF // 2], rms


def rasgos(m):
    """Lo que calcularía el robot para el detector nuevo: energía por banda en log, menos su promedio lento."""
    e = np.stack([np.log((m[:, a:b] ** 2).sum(1) + 1) for a, b in zip(BORDES[:-1], BORDES[1:])], 1)
    media, n = np.zeros_like(e), e[0].copy()
    for t in range(len(e)):
        n += (e[t] - n) * (0.05 if t < 60 else 0.003)   # ~10 s, como la base del robot (arranca rápido)
        media[t] = n
    z = e - media
    d = np.vstack([np.zeros((1, NB)), np.diff(e, axis=0)])
    dm = np.zeros_like(d)                                # qué tanto sube y baja cada banda (sílabas)
    for t in range(1, len(d)): dm[t] = dm[t - 1] + (np.abs(d[t]) - dm[t - 1]) * 0.15
    return np.hstack([z, dm, np.ones((len(e), 1))]).astype(np.float32)


def detector_hoy(m, rms):
    """El detector que tiene hoy el robot (analizarVoz), con el celular diciendo que suena música."""
    out, vMod, vPrev, vozS, rs, base, piso = [], 0, 0, 0, 0, 0.3, 30
    canta, sube, cantas = False, 0, []
    for v, r in zip(m, rms):
        piso = piso * 0.9 + r * 0.1 if r < piso else piso * 1.0004
        hay = r > piso * 3 and r > 40
        eb, ev, eh = (v[2:7] ** 2).sum(), (v[9:97] ** 2).sum(), (v[112:] ** 2).sum()
        lg, ar = np.log(v[9:97] + 1).sum(), v[9:97].sum()
        plano = np.exp(lg / 88) / (ar / 88 + 1)
        frac = ev / (eb + ev + eh + 1)
        db = 10 * np.log10(ev + 1)
        vMod += (abs(db - vPrev) - vMod) * 0.15; vPrev = db
        fracN, tonN = np.clip((frac - 0.30) / 0.40, 0, 1), np.clip((0.62 - plano) / 0.32, 0, 1)
        modN = np.clip((vMod - 0.6) / 2.4, 0, 1)
        rs += (frac - rs) * 0.3
        base += (rs - base) * (0.05 if rs < base else 0.003)
        vozMus = np.clip((rs - base) / max(0.06, base * 0.5), 0, 1) * (0.5 + 0.5 * modN) if hay else 0
        vozS += ((max((0.5 * fracN + 0.5 * tonN) * modN, vozMus) if hay else 0) - vozS) * 0.25
        if not canta and vozS > 0.35:
            sube += 1
            canta = sube >= 2
        else: sube = 0
        if canta and vozS < 0.35 * 0.7: canta = False
        cantas.append(canta)
    return np.array(cantas)


# ---------------------------------------------------------------- la verdad: cuándo canta (Demucs)
def etiquetas(ruta, n):
    cache = ruta[:-4] + '.voz.npy'
    if os.path.exists(cache):
        y = np.load(cache)
        if len(y) >= n: return y[:n]
    import torch
    from demucs.pretrained import get_model
    from demucs.apply import apply_model
    global _modelo
    if '_modelo' not in globals():
        print('  cargando el separador de voz (la primera vez lo descarga, ~80 MB)...')
        _modelo = get_model('htdemucs'); _modelo.eval()
    sr, x = wavfile.read(ruta)
    x = x.astype(np.float32) / 32767
    x44 = resample_poly(x, 147, 160)                                    # 48 kHz -> 44,1 kHz
    w = torch.tensor(np.stack([x44, x44]))[None]
    with torch.no_grad():
        fuentes = apply_model(_modelo, w, device='cpu', progress=True)[0]
    voz = fuentes[_modelo.sources.index('vocals')].mean(0).numpy()
    resto = x44[:len(voz)] - voz
    paso = 44100 * NF / SR
    def db(s):
        k = int(len(s) // paso)
        return np.array([10 * np.log10(np.mean(s[int(i * paso):int((i + 1) * paso)] ** 2) + 1e-10) for i in range(k)])
    dv, dr = db(voz), db(resto)
    k = min(len(dv), len(dr))
    dv, dr = dv[:k], dr[:k]
    y = (dv > dr - 10) & (dv > np.percentile(dv, 95) - 30)              # se oye voz por encima del acompañamiento
    y = medfilt(y.astype(float), 7) > 0.5                               # sin parpadeos de menos de ~100 ms
    np.save(cache, y)
    return y[:n]


def entrenar():
    wavs = sorted(f for f in os.listdir(GRAB) if f.endswith('.wav'))
    if len(wavs) < 3: print(f'\nHay {len(wavs)} canciones; graba por lo menos 3 (mejor 20).'); return
    datos = []
    for i, f in enumerate(wavs):
        print(f'\n[{i + 1}/{len(wavs)}] {f[:-4]}')
        ruta = os.path.join(GRAB, f)
        sr, x = wavfile.read(ruta)
        m, rms = bloques(resample_poly(x.astype(np.float32) / 32767, 1, 3))
        y = etiquetas(ruta, len(m))
        n = min(len(y), len(m))
        datos.append((f[:-4], rasgos(m)[:n], y[:n], detector_hoy(m[:n], rms[:n])))
    # prueba honesta: se entrena sin una de cada 4 canciones y se mide en esas
    prueba = [d for i, d in enumerate(datos) if i % 4 == 3] or datos[-1:]
    entreno = [d for d in datos if not any(d is p for p in prueba)]
    w = ajustar(np.vstack([d[1] for d in entreno]), np.concatenate([d[2] for d in entreno]))
    print('\n%-40s %8s %8s %8s' % ('canción (prueba)', 'canta %', 'hoy', 'nuevo'))
    tot = [0, 0, 0]
    for nom, X, y, hoy in prueba:
        nuevo = decidir(X @ w)
        a, b = (hoy == y).mean(), (nuevo == y).mean()
        print('%-40s %7.0f%% %7.0f%% %7.0f%%' % (nom[:40], 100 * y.mean(), 100 * a, 100 * b))
        tot[0] += len(y); tot[1] += a * len(y); tot[2] += b * len(y)
    print('%-40s %8s %7.0f%% %7.0f%%' % ('TOTAL (acierta)', '', 100 * tot[1] / tot[0], 100 * tot[2] / tot[0]))
    w = ajustar(np.vstack([d[1] for d in datos]), np.concatenate([d[2] for d in datos]))   # el final, con todo
    exportar(w)


def ajustar(X, y, pasos=400):
    """Regresión logística (cabe en el robot: una suma de 49 productos cada 32 ms)."""
    mu, sd_ = X[:, :-1].mean(0), X[:, :-1].std(0) + 1e-6
    Xn = np.hstack([(X[:, :-1] - mu) / sd_, X[:, -1:]])
    w = np.zeros(X.shape[1], np.float32)
    peso = np.where(y, 0.5 / max(y.mean(), 1e-3), 0.5 / max(1 - y.mean(), 1e-3))
    for _ in range(pasos):
        p = 1 / (1 + np.exp(-np.clip(Xn @ w, -30, 30)))
        g = Xn.T @ ((p - y) * peso) / len(y) + 1e-3 * np.r_[w[:-1], 0]
        w -= 0.5 * g
    # se deshace la normalización: el robot usa los rasgos tal cual
    wr = w[:-1] / sd_
    return np.r_[wr, w[-1] - (wr * mu).sum()].astype(np.float32)


def decidir(z):
    """Como el robot: se suaviza (~120 ms) y entra en 0,5 / sale en 0,35."""
    p, s, canta, out = 1 / (1 + np.exp(-np.clip(z, -30, 30))), 0, False, []
    for v in p:
        s += (v - s) * 0.25
        canta = s > 0.5 if not canta else s > 0.35
        out.append(canta)
    return np.array(out)


def exportar(w):
    ruta = os.path.join(CARPETA, 'voz_modelo.h')
    num = lambda a: ', '.join(f'{v:.5g}f' for v in a)
    with open(ruta, 'w', encoding='utf-8') as f:
        f.write('// Detector de canto entrenado con herramientas/labios/labios.py (' + time.strftime('%Y-%m-%d') + ')\n')
        f.write(f'#pragma once\nconstexpr int VOZ_NB = {NB};\n')
        f.write('constexpr int VOZ_BORDES[VOZ_NB + 1] = {' + ', '.join(map(str, BORDES)) + '};\n')
        f.write('constexpr float VOZ_W_NIVEL[VOZ_NB] = {' + num(w[:NB]) + '};\n')
        f.write('constexpr float VOZ_W_MOV[VOZ_NB] = {' + num(w[NB:2 * NB]) + '};\n')
        f.write(f'constexpr float VOZ_B = {w[-1]:.5g}f;\n')
    print(f'\nListo: {ruta}\nDile a Claude que ya entrenaste y él lo pone en el robot.')


if __name__ == '__main__':
    os.makedirs(GRAB, exist_ok=True)
    while True:
        n = len([f for f in os.listdir(GRAB) if f.endswith('.wav')])
        r = input(f'\n=== Cubi: entrenar el lip sync ({n} canciones grabadas) ===\n'
                  '  1. Grabar una canción\n  2. Entrenar con lo grabado\n  3. Salir\n> ').strip()
        if r == '1': grabar()
        elif r == '2': entrenar()
        elif r == '3' or r == '': break
