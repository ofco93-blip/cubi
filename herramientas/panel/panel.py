# Panel en vivo: se conecta a Cubi por Bluetooth desde el PC (sin el celular). Cubi oye con su propio micrófono;
# aquí se ve lo que oye (nivel, voz, vocal, boca), una copia de su cara (caras.cpp como cubi_cara.dll) y las
# perillas, que se guardan en el mismo Cubi al moverlas. Sin Cubi se puede oír con el micrófono del PC: las mismas
# cuentas del robot (analizarVoz de cubi.ino), con las perillas aplicadas aquí.
import asyncio, ctypes, math, json, os, threading, time, tkinter as tk
from tkinter import ttk
import numpy as np
import sounddevice as sd
from bleak import BleakClient, BleakScanner
from PIL import Image, ImageTk

AQUI = os.path.dirname(os.path.abspath(__file__))
SRV, RX, TX = ('6e40000%d-b5a3-f393-e0a9-e50e24dcca9e' % i for i in (1, 2, 3))
PAQUETES = ['Lumi azul', 'Minimal', 'Nova sólida', 'Kira sólida', 'Colores', 'Nova', 'Kira', 'Pixel']

dll = ctypes.CDLL(os.path.join(AQUI, 'cubi_cara.dll'))
dll.nombreRitmo.restype = ctypes.c_char_p
dll.cuadroPanel.restype = ctypes.POINTER(ctypes.c_ubyte)
dll.cuadroPanel.argtypes = [ctypes.c_float, ctypes.c_float, ctypes.c_int, ctypes.c_int] + [ctypes.c_float] * 5 + [ctypes.c_int, ctypes.c_float, ctypes.c_float]
dll.arrancar()
W, H = dll.ancho(), dll.alto()
RITMOS = [dll.nombreRitmo(i).decode() for i in range(dll.nRitmos())]
for f in (dll.nombreBase, dll.nombreReac): f.restype = ctypes.c_char_p
BASES = [dll.nombreBase(i).decode() for i in range(dll.nBases())]
REACS = [dll.nombreReac(i).decode() for i in range(1, dll.nReac())]


# qué instrumento oye (el baile que se pone con cada uno)
INSTRUMENTO = {'kpop': 'voz (micrófono)', 'dj': 'bajo electrónico', 'pop': 'batería', 'tropical': 'maracas',
               'rock': 'guitarra eléctrica', 'guitarra': 'guitarra', 'jazz': 'saxo', 'piano': 'piano'}


def vocal(abre, ancho):
    """La vocal que está haciendo la boca, con las mismas medidas que usa el robot (F1 = abre, F2 = ancho)."""
    if ancho > 0.65: return 'i' if abre < 0.6 else 'e'
    if ancho < 0.35: return 'u' if abre < 0.6 else 'o'
    return 'a' if abre > 0.7 else 'e'


class Cubi:
    """Conexión Bluetooth igual a la de la app: se escribe "id|/ruta?..." y Cubi responde "id|respuesta\\n"."""
    def __init__(s):
        s.loop = asyncio.new_event_loop(); s.cli = None; s.buf = ''; s.pend = {}; s.id = 0
        s.estado = 'Buscando a Cubi...'
        threading.Thread(target=s.loop.run_forever, daemon=True).start()
        asyncio.run_coroutine_threadsafe(s.conectar(), s.loop)

    async def conectar(s):
        while True:
            try:
                s.estado = 'Buscando a Cubi...'
                dev = await BleakScanner.find_device_by_filter(lambda d, ad: SRV in [u.lower() for u in ad.service_uuids], timeout=10)
                if not dev: s.estado = 'No encuentro a Cubi: que esté prendido y cerca'; continue
                s.estado = 'Conectando...'
                s.cli = BleakClient(dev, disconnected_callback=lambda c: setattr(s, 'cli', None))
                await s.cli.connect()
                await s.cli.start_notify(TX, s.llega)
                s.estado = 'Conectado'
                while s.cli and s.cli.is_connected: await asyncio.sleep(0.5)
                s.estado = 'Se desconectó, reconectando...'
            except Exception as e:
                s.cli = None; s.estado = 'Error: ' + str(e)[:60]; await asyncio.sleep(2)

    def llega(s, _, data):
        s.buf += data.decode('utf-8', 'replace')
        while '\n' in s.buf:
            linea, s.buf = s.buf.split('\n', 1)
            i, _, r = linea.partition('|')
            f = s.pend.pop(i, None)
            if f and not f.done(): f.set_result(r)

    async def _pedir(s, ruta):
        if not s.cli: return None
        s.id += 1; i = 'pc%d' % s.id
        f = s.loop.create_future(); s.pend[i] = f
        await s.cli.write_gatt_char(RX, (i + '|' + ruta).encode(), response=False)
        try: return await asyncio.wait_for(f, 1.5)
        except asyncio.TimeoutError: s.pend.pop(i, None); return None

    def pedir(s, ruta):
        """Desde la ventana: devuelve la respuesta o None (espera como mucho 1,5 s)."""
        try: return asyncio.run_coroutine_threadsafe(s._pedir(ruta), s.loop).result(2)
        except Exception: return None


class Oido:
    """analizarVoz + el pedazo de tareaAudio que decide si "hay" sonido, igual que en el robot, con el micrófono del PC."""
    SR, NF = 16000, 512

    def __init__(s, aj):
        s.aj = aj                                   # función que devuelve las perillas actuales
        s.piso, s.pico = 30.0, 300.0
        s.vMod = s.vPrev = s.vozS = 0.0; s.vMax = s.vMin = 60.0; s.boca = 0.0; s.ancho = 0.5
        s.canta = False; s.sube = 0; s.rs = 0.0; s.base = 0.3; s.abre = 0.8
        s.nivel = s.rms = 0.0; s.hay = s.suena = False
        s.ham = np.hamming(s.NF)
        # detector de instrumentos (el de tareaAudio): rasgos promediados cada 2 s
        s.bAvg = s.bPrev = s.fAvg = s.fPrev = 0.0; s.lPrev = np.zeros(128); s.golpes = 0; s.ultGolpe = 0.0
        s.sum = np.zeros(6); s.sN = 0; s.golpesIni = 0; s.tEval = time.time()
        s.insCand = None; s.insN = 0; s.ins = None; s.insDesde = 0.0; s.insAhora = None; s.rasgos = {}

    def bloque(s, x):
        aj = s.aj()
        v = x * 32767 / 4 * aj['mic_pc']            # escala parecida a la del micrófono del robot
        v = v - v.mean()
        rms = float(np.sqrt(np.mean(v ** 2)))
        m = np.abs(np.fft.rfft(v * s.ham))[:s.NF // 2]
        s.piso = s.piso * 0.9 + rms * 0.1 if rms < s.piso else s.piso * 1.0004
        hay = rms > s.piso * aj['sk'] and rms > 40
        s.suena = rms > s.piso * 1.8 and rms > 30
        s.pico = max(rms, s.pico * 0.998)
        s.nivel = min(1, rms / s.pico) if s.suena else 0
        s.rms, s.hay = rms, hay
        eb, ev, eh = (m[2:7] ** 2).sum(), (m[9:97] ** 2).sum(), (m[112:] ** 2).sum()
        plano = np.exp(np.log(m[9:97] + 1).sum() / 88) / (m[9:97].sum() / 88 + 1)
        frac = ev / (eb + ev + eh + 1)
        db = 10 * np.log10(ev + 1)
        s.vMod += (abs(db - s.vPrev) - s.vMod) * 0.15; s.vPrev = db
        fracN, tonN = np.clip((frac - 0.30) / 0.40, 0, 1), np.clip((0.62 - plano) / 0.32, 0, 1)
        modN = np.clip((s.vMod - 0.6) / 2.4, 0, 1)
        s.rs += (frac - s.rs) * 0.3
        s.base += (s.rs - s.base) * (0.05 if s.rs < s.base else 0.003)
        vozMus = np.clip((s.rs - s.base) / max(0.06, s.base * 0.5), 0, 1) * (0.5 + 0.5 * modN) if hay else 0
        s.vozS += ((max((0.5 * fracN + 0.5 * tonN) * modN, vozMus) if hay else 0) - s.vozS) * 0.25
        uv = aj['uv']
        if not s.canta and s.vozS > uv:
            s.sube += 1; s.canta = s.sube >= 2
        else: s.sube = 0
        if s.canta and s.vozS < uv * 0.7: s.canta = False
        if hay and s.canta:                         # la vocal por formantes (F1 = qué tan abierta, F2 = qué tan estirada)
            sv = np.convolve(m, np.ones(5), 'same')
            k1 = 9 + int(np.argmax(sv[9:31])); k2a = max(k1 + 8, 28)
            k2 = k2a + int(np.argmax(sv[k2a:81]))
            s.abre += (np.clip((k1 * 31.25 - 280) / 470, 0, 1) * 0.55 + 0.45 - s.abre) * 0.5
            s.ancho += (np.clip((k2 * 31.25 - 900) / 1300, 0, 1) - s.ancho) * 0.5
        s.vMax = max(db, s.vMax - 0.02); s.vMin = min(db, s.vMin + 0.03)
        o = np.clip((db - s.vMin - 3) / max(6, s.vMax - s.vMin - 3), 0, 1)
        o = min(1, o ** 1.4 * aj['gb']) * s.abre
        t = o if s.canta else 0
        s.boca += (t - s.boca) * (0.85 if t > s.boca else 0.45)
        s.instrumento(m, hay)

    def instrumento(s, m, hay):
        ahora = time.time()
        b, md, hi = m[2:6].sum(), m[17:65].sum(), m[65:256].sum()
        lo = m[6:17].sum(); tot = b + lo + md + hi + 1
        l = np.log1p(m[:128]); flujo = float(np.maximum(0, l[2:] - s.lPrev[2:]).sum()); s.lPrev = l
        s.bAvg += (b - s.bAvg) * 0.04; s.fAvg += (flujo - s.fAvg) * 0.05
        golpe = (b > s.bAvg * 1.45 and b > s.bPrev * 1.1) or (flujo > s.fAvg * 1.7 and flujo > s.fPrev)
        if s.suena and golpe and ahora - s.ultGolpe > 0.25: s.ultGolpe = ahora; s.golpes += 1
        s.fPrev, s.bPrev = flujo, b
        if s.suena:
            plano = np.exp(np.log(m[6:192] + 1).sum() / 186) / (m[6:192].sum() / 186 + 1)
            s.sum += [b / tot, lo / tot, md / tot, hi / tot, plano, s.vozS]; s.sN += 1
        if ahora - s.tEval < 2: return
        s.tEval = ahora
        if s.sN > 20:
            B, L, M, A, F, V = s.sum / s.sN; O = (s.golpes - s.golpesIni) / 2
            c = None
            if V > 0.55: c = 'kpop'
            elif B > 0.42 and O >= 1.5: c = 'dj'
            elif A > 0.22 and F > 0.45 and B > 0.18 and O >= 1.5: c = 'pop'
            elif A > 0.25 and F > 0.5 and B < 0.18 and O > 3: c = 'tropical'
            elif F > 0.4 and M > 0.3 and B < 0.3 and O < 2.5: c = 'rock'
            elif L + M > 0.45 and B < 0.3 and O >= 1: c = 'guitarra' if F >= 0.3 else 'piano'
            elif F < 0.28 and O < 1.2: c = 'jazz' if M > 0.35 else 'piano'
            elif F < 0.3 and O >= 1.2: c = 'piano' if A < 0.08 else 'guitarra'
            s.insAhora = c
            s.rasgos = dict(bajo=B, medios_bajos=L, medios=M, agudos=A, ruido=F, voz=V, golpes_s=O)
            if c == s.insCand: s.insN += 1
            else: s.insCand, s.insN = c, 1
            # como el robot: lo mismo tres veces seguidas (6 s) y no más de un cambio cada 30 s
            if c and s.insN >= 3 and c != s.ins and (s.ins is None or ahora - s.insDesde > 30): s.ins, s.insDesde = c, ahora
        elif not s.suena: s.ins = s.insAhora = None
        s.sum[:] = 0; s.sN = 0; s.golpesIni = s.golpes


class Panel:
    def __init__(s):
        s.cubi = Cubi()
        s.aud = {}; s.aj = None
        s.r = tk.Tk(); s.r.title('Cubi en vivo'); s.r.configure(bg='#111')
        est = {'bg': '#111', 'fg': '#ddd'}
        izq = tk.Frame(s.r, bg='#111'); izq.grid(row=0, column=0, sticky='n', padx=10, pady=10)
        s.img = tk.Label(izq, bg='black'); s.img.pack()
        # ver las caras sin Cubi: base, baile y reacciones (con Cubi conectado, la base la decide lo que oye)
        ver = tk.Frame(izq, bg='#111'); ver.pack(anchor='w', pady=8)
        tk.Label(ver, text='Ver caras:', **est).pack(side='left')
        s.base = ttk.Combobox(ver, values=['lo que oye Cubi'] + BASES, state='readonly', width=14); s.base.current(0); s.base.pack(side='left', padx=4)
        s.base.bind('<<ComboboxSelected>>', lambda e: dll.ponerBase(s.base.current() - 1))
        s.rit = ttk.Combobox(ver, values=RITMOS, state='readonly', width=10); s.rit.current(0); s.rit.pack(side='left', padx=4)
        botones = tk.Frame(izq, bg='#111'); botones.pack(anchor='w')
        for k, n in enumerate(REACS):
            tk.Button(botones, text=n, width=9, command=lambda k=k: dll.ponerReaccion(k + 1), bg='#222', fg='#ddd', relief='flat'
                      ).grid(row=k // 6, column=k % 6, padx=2, pady=2)
        col = tk.Frame(s.r, bg='#111'); col.grid(row=0, column=1, sticky='n', padx=10, pady=10)
        s.con = tk.Label(col, text='', **est, font=('Segoe UI', 10, 'bold')); s.con.pack(anchor='w')
        oye = tk.Frame(col, bg='#111'); oye.pack(anchor='w', pady=(6, 0))
        tk.Label(oye, text='Oír con:', **est).pack(side='left')
        s.mics = [(i, d['name']) for i, d in enumerate(sd.query_devices())
                  if d['max_input_channels'] > 0 and sd.query_hostapis(d['hostapi'])['name'] == 'MME']
        s.fuente = ttk.Combobox(oye, values=['Cubi (su micrófono)'] + ['PC: ' + n for _, n in s.mics], state='readonly', width=34)
        s.fuente.current(0); s.fuente.pack(side='left', padx=4); s.fuente.bind('<<ComboboxSelected>>', lambda e: s.abrirMic())
        s.oido = Oido(lambda: {k: v.get() for k, v in s.vars.items()}); s.flujo = None; s.bocaV = 0.0; s.cantaHasta = 0.0
        s.sostDesde = 0.0; s.vocalS = (0.5, 0.8)

        fila = tk.Frame(col, bg='#111'); fila.pack(anchor='w', pady=6)
        s.paq = ttk.Combobox(fila, values=PAQUETES, state='readonly', width=10); s.paq.current(0); s.paq.pack(side='left')
        s.paq.bind('<<ComboboxSelected>>', lambda e: (dll.ponerPaquete(s.paq.current()), s.mandar('paq', s.paq.current())))
        s.bm = tk.IntVar(value=1)
        tk.Checkbutton(fila, text='Boca con su micrófono', variable=s.bm, selectcolor='#333', command=lambda: s.mandar('bm', s.bm.get()), **est).pack(side='left', padx=8)

        s.vars = {}
        def perilla(clave, texto, a, b, paso, ayuda):
            tk.Label(col, text=texto, **est).pack(anchor='w', pady=(8, 0))
            v = tk.DoubleVar(value=a); s.vars[clave] = v
            sc = tk.Scale(col, from_=a, to=b, resolution=paso, orient='horizontal', length=320, variable=v, bg='#111', fg='#ddd', highlightthickness=0)
            sc.bind('<ButtonRelease-1>', lambda e: s.mandar(clave, v.get())); sc.pack(anchor='w')
            tk.Label(col, text=ayuda, fg='#888', bg='#111', font=('Segoe UI', 8)).pack(anchor='w')
        perilla('mg', 'Ganancia del micrófono de Cubi (mg)', 0, 7, 1, 'Más alto = oye más fuerte (si satura, bájalo)')
        perilla('mic_pc', 'Volumen del micrófono del PC', 0.1, 8, 0.1, 'Solo con el micrófono del PC: que la barra de Nivel se mueva bien')
        s.vars['mic_pc'].set(1)
        perilla('sk', 'Sensibilidad (sk)', 1.3, 8, 0.1, 'Más alto = necesita sonar más fuerte sobre el ruido del carro')
        perilla('uv', 'Umbral de voz (uv)', 0.05, 0.95, 0.01, 'Si abre la boca en los solos, súbelo; si no canta con voz, bájalo')
        perilla('gb', 'Qué tanto abre la boca (gb)', 0.3, 3, 0.05, 'Más alto = boca más abierta')

        s.cv = tk.Canvas(col, width=330, height=230, bg='#111', highlightthickness=0); s.cv.pack(anchor='w', pady=10)
        tk.Label(col, text='Los ajustes se guardan en Cubi al soltar cada perilla.', fg='#888', bg='#111', font=('Segoe UI', 8)).pack(anchor='w')

        s.t0 = s.antes = time.time(); s.fase = 0.0
        threading.Thread(target=s.oir, daemon=True).start()
        s.r.after(40, s.cuadro); s.r.mainloop()

    def mandar(s, clave, v):
        if clave == 'mic_pc': return
        v = int(v) if clave in ('mg', 'paq', 'bm') else round(float(v), 2)
        threading.Thread(target=s.cubi.pedir, args=('/ajuste?%s=%s' % (clave, v),), daemon=True).start()

    def abrirMic(s):
        if s.flujo: s.flujo.close(); s.flujo = None
        k = s.fuente.current()
        if k == 0: return
        s.oido = Oido(s.oido.aj)
        try:
            s.flujo = sd.InputStream(device=s.mics[k - 1][0], channels=1, samplerate=Oido.SR, blocksize=Oido.NF, dtype='float32',
                                     callback=lambda d, *_: s.oido.bloque(d[:, 0].astype(np.float64)))
            s.flujo.start()
        except Exception as e:
            s.flujo = None; s.con.config(text='No abre ese micrófono: ' + str(e)[:50])

    def oir(s):
        """Pide lo que oye Cubi unas 6 veces por segundo (y los ajustes que tiene al conectarse)."""
        while True:
            if not s.cubi.cli: s.aj = None; time.sleep(0.3); continue
            if s.aj is None:
                r = s.cubi.pedir('/estado')
                if r:
                    try:
                        s.aj = json.loads(r)
                        for k, v in s.vars.items(): v.set(s.aj.get(k, v.get()))
                        s.bm.set(int(s.aj.get('bm', 1))); s.paq.current(int(s.aj.get('paq', 0))); dll.ponerPaquete(int(s.aj.get('paq', 0)))
                    except Exception: pass
            r = s.cubi.pedir('/audio')
            if r:
                try: s.aud = json.loads(r)
                except Exception: pass
            time.sleep(0.15)

    def cuadro(s):
        ahora = time.time(); dt = min(0.1, ahora - s.antes); s.antes = ahora
        a = s.aud if s.cubi.cli else {}
        t = ahora - s.t0
        if s.flujo:   # el micrófono del PC, con las cuentas del robot
            o = s.oido
            if o.canta: s.cantaHasta = ahora + 1.5    # como el robot: la cara de cantar aguanta entre frases
            s.bocaV += ((o.boca if o.canta else 0) - s.bocaV) * 0.6
            # nota sostenida, como el robot: la misma vocal con voz fuerte; la emoción sube de 0,6 s a 2,4 s
            misma = abs(o.ancho - s.vocalS[0]) < 0.18 and abs(o.abre - s.vocalS[1]) < 0.18
            if o.canta and o.vozS > 0.5 and misma:
                s.sostDesde = s.sostDesde or ahora
                s.vocalS = (s.vocalS[0] + (o.ancho - s.vocalS[0]) * 0.05, s.vocalS[1] + (o.abre - s.vocalS[1]) * 0.05)
                emo = min(1, max(0, (ahora - s.sostDesde - 0.6) / 1.8))
            else: s.sostDesde = 0.0; s.vocalS = (o.ancho, o.abre); emo = 0
            a = {'estado': (3 if o.canta or ahora < s.cantaHasta else 2) if o.suena else 0, 'canta': int(o.canta or ahora < s.cantaHasta), 'emo': emo,
                 'boca': s.bocaV, 'ancho': o.ancho, 'abre': o.abre, 'voz': o.vozS, 'nivel': o.nivel, 'bpm': 110,
                 'rms': round(o.rms), 'piso': round(o.piso), 'ins': o.ins or '', 'insAhora': o.insAhora, 'rasgos': o.rasgos}
        elif not s.cubi.cli and s.base.get() in ('canta', 'habla'):   # sin Cubi: canta de mentiras para ver la boca y el baile
            u = t % 10
            c = u < 2.6 or 3.5 < u < 7.5      # sílabas y luego un "aaah" sostenido de 4 s
            largo = 3.5 < u < 7.5
            a = {'estado': 3, 'canta': int(c), 'boca': (0.8 if largo else 0.35 + 0.45 * abs(math.sin(t * 5.3))) if c else 0,
                 'ancho': 0.6 if largo else 0.5 + 0.5 * math.sin(t * 2.3), 'voz': 0.7 if c else 0, 'nivel': 0.5 + 0.4 * abs(math.sin(t * 1.7)),
                 'bpm': 110, 'abre': 1.0 if largo else 0.8, 'emo': min(1, max(0, (u - 3.5 - 0.6) / 1.8)) if largo else 0}
        bpm = a.get('bpm', 0) or 110
        s.fase = (s.fase + dt * bpm / 60) % 1000
        musica = a.get('estado', 0) >= 2; canta = bool(a.get('canta', 0))
        p = dll.cuadroPanel(ahora - s.t0, dt, int(musica or canta), int(canta), a.get('boca', 0), a.get('ancho', 0.5), a.get('voz', 0),
                            a.get('nivel', 0), a.get('emo', 0), RITMOS.index(a['ins']) if a.get('ins') in RITMOS else s.rit.current(), bpm, s.fase % 1)
        s.foto = ImageTk.PhotoImage(Image.frombuffer('RGB', (W, H), ctypes.string_at(p, W * H * 3), 'raw', 'RGB', 0, 1))
        s.img.config(image=s.foto)
        if s.flujo: s.con.config(text='Oyendo con el micrófono del PC', fg='#6af')
        else: s.con.config(text=s.cubi.estado + ('' if not a else '   ·   ' + a.get('estilo', '')), fg='#6d6' if s.cubi.cli else '#fa6')
        c = s.cv; c.delete('all')
        uv = s.vars['uv'].get()
        for i, (nom, v, col) in enumerate([('Nivel', a.get('nivel', 0), '#4a9'), ('Voz', a.get('voz', 0), '#59f'), ('Boca', a.get('boca', 0), '#f95')]):
            y = 8 + i * 30
            c.create_text(0, y + 8, text=nom, anchor='w', fill='#ddd')
            c.create_rectangle(50, y, 290, y + 16, outline='#444')
            c.create_rectangle(50, y, 50 + 240 * min(1, v), y + 16, fill=col, width=0)
            if nom == 'Voz': c.create_line(50 + 240 * uv, y - 3, 50 + 240 * uv, y + 19, fill='white', width=2)
        c.create_text(0, 112, anchor='w', fill='#ff6' if canta else '#666', font=('Segoe UI', 12, 'bold'),
                      text='CANTANDO' if canta else ('con música, sin voz' if musica else 'sin música'))
        if canta: c.create_text(200, 125, text=vocal(a.get('abre', 0.8), a.get('ancho', 0.5)).upper(), fill='#ff6', font=('Segoe UI', 36, 'bold'))
        if a.get('emo', 0) > 0:   # la nota sostenida: barrita de drama
            c.create_text(240, 112, anchor='w', fill='#f6a', text='drama'); c.create_rectangle(240, 122, 240 + 80 * a['emo'], 130, fill='#f6a', width=0)
        c.create_text(0, 150, anchor='w', fill='#888', text='rms %s  piso %s  bpm %s' % (a.get('rms', '-'), a.get('piso', '-'), a.get('bpm', '-')))
        ins = INSTRUMENTO.get(a.get('ins'), '')
        c.create_text(0, 176, anchor='w', fill='#6df' if ins else '#666', font=('Segoe UI', 12, 'bold'),
                      text='Instrumento: ' + (ins or ('escuchando...' if musica else '-')))
        if 'insAhora' in a:   # con el micrófono del PC se ve también lo que oye en estos 2 s y por qué
            r = a.get('rasgos') or {}
            c.create_text(0, 198, anchor='w', fill='#aaa', text='ahora: ' + INSTRUMENTO.get(a['insAhora'], 'no sé'))
            c.create_text(0, 218, anchor='w', fill='#777', font=('Segoe UI', 8),
                          text='  '.join('%s %.2f' % (k, v) for k, v in r.items()))
        s.r.after(max(1, int(40 - (time.time() - ahora) * 1000)), s.cuadro)


if __name__ == '__main__':
    Panel()
