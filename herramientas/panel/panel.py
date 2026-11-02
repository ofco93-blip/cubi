# Panel en vivo: Cubi en la pantalla del PC escuchando el micrófono, con las mismas cuentas del robot
# (analizarVoz de cubi.ino) y la misma cara (caras.cpp compilado como cubi_cara.dll). Se mueven las perillas
# hasta que se vea bien y "Guardar" deja ajustes_cubi.json para pasárselo a Claude.
import ctypes, json, os, threading, time, tkinter as tk
from tkinter import ttk
import numpy as np
import sounddevice as sd
from PIL import Image, ImageTk

AQUI = os.path.dirname(os.path.abspath(__file__))
CARPETA = os.environ.get('CUBI_LABIOS') or os.path.join(os.path.expanduser('~'), 'Documents', 'Cubi labios')
SALIDA = os.path.join(CARPETA, 'ajustes_cubi.json')
SR, NF = 16000, 512
PAQUETES = ['Lumi azul', 'Minimal', 'Anime', 'Peluche', 'Colores']

dll = ctypes.CDLL(os.path.join(AQUI, 'cubi_cara.dll'))
dll.nombreRitmo.restype = ctypes.c_char_p
dll.cuadroPanel.restype = ctypes.POINTER(ctypes.c_ubyte)
dll.cuadroPanel.argtypes = [ctypes.c_float, ctypes.c_float, ctypes.c_int, ctypes.c_int] + [ctypes.c_float] * 5 + [ctypes.c_int, ctypes.c_float, ctypes.c_float]
dll.arrancar()
W, H = dll.ancho(), dll.alto()
RITMOS = [dll.nombreRitmo(i).decode() for i in range(dll.nRitmos())]


class Oido:
    """analizarVoz + el pedazo de tareaAudio que decide si "hay" sonido, igual que en el robot."""
    def __init__(s, aj):
        s.aj = aj
        s.piso, s.pico = 30.0, 300.0
        s.vMod = s.vPrev = s.vozS = 0.0; s.vMax = s.vMin = 60.0; s.boca = 0.0; s.ancho = 0.5
        s.canta = False; s.sube = 0; s.rs = 0.0; s.base = 0.3; s.abre = 0.8
        s.nivel = s.rms = 0.0; s.hay = False
        s.ham = np.hamming(NF)

    def bloque(s, x):
        aj = s.aj
        v = x * 32767 / 4 * aj['mic_pc']           # escala parecida a la del micrófono del robot
        v = v - v.mean()
        rms = float(np.sqrt(np.mean(v ** 2)))
        m = np.abs(np.fft.rfft(v * s.ham))[:NF // 2]
        s.piso = s.piso * 0.9 + rms * 0.1 if rms < s.piso else s.piso * 1.0004
        hay = rms > s.piso * aj['sk'] and rms > 40
        suena = rms > s.piso * 1.8 and rms > 30
        s.pico = max(rms, s.pico * 0.998)
        s.nivel = min(1, rms / s.pico) if suena else 0
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

        if hay and s.canta:                         # vocal por formantes
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


class Panel:
    def __init__(s):
        s.aj = {'sk': 3.0, 'uv': 0.35, 'gb': 1.3, 'mic_pc': 1.0}
        try: s.aj.update(json.load(open(SALIDA, encoding='utf-8')))
        except Exception: pass
        s.oido = Oido(s.aj)
        s.r = tk.Tk(); s.r.title('Cubi en vivo'); s.r.configure(bg='#111')
        s.img = tk.Label(s.r, bg='black'); s.img.grid(row=0, column=0, rowspan=20, padx=10, pady=10)
        col = tk.Frame(s.r, bg='#111'); col.grid(row=0, column=1, sticky='n', padx=10, pady=10)
        est = {'bg': '#111', 'fg': '#ddd'}

        tk.Label(col, text='Micrófono', **est).pack(anchor='w')
        mics = [(i, d['name']) for i, d in enumerate(sd.query_devices())
                if d['max_input_channels'] > 0 and sd.query_hostapis(d['hostapi'])['name'] == 'MME']
        s.mics = mics
        s.mic = ttk.Combobox(col, values=[n for _, n in mics], state='readonly', width=34)
        ug = [k for k, (_, n) in enumerate(mics) if 'UGREEN' in n]
        s.mic.current(ug[0] if ug else 0); s.mic.pack(anchor='w'); s.mic.bind('<<ComboboxSelected>>', lambda e: s.abrir())

        fila = tk.Frame(col, bg='#111'); fila.pack(anchor='w', pady=6)
        s.paq = ttk.Combobox(fila, values=PAQUETES, state='readonly', width=10); s.paq.current(0); s.paq.pack(side='left')
        s.paq.bind('<<ComboboxSelected>>', lambda e: dll.ponerPaquete(s.paq.current()))
        s.rit = ttk.Combobox(fila, values=RITMOS, state='readonly', width=10); s.rit.current(0); s.rit.pack(side='left', padx=6)
        s.bailando = tk.IntVar(value=1)
        tk.Checkbutton(fila, text='Bailando', variable=s.bailando, selectcolor='#333', **est).pack(side='left')

        def perilla(clave, texto, a, b, ayuda):
            tk.Label(col, text=texto, **est).pack(anchor='w', pady=(8, 0))
            v = tk.DoubleVar(value=s.aj[clave])
            tk.Scale(col, from_=a, to=b, resolution=0.01, orient='horizontal', length=300, variable=v,
                     command=lambda x: s.aj.__setitem__(clave, float(x)), bg='#111', fg='#ddd', highlightthickness=0).pack(anchor='w')
            tk.Label(col, text=ayuda, fg='#888', bg='#111', font=('Segoe UI', 8)).pack(anchor='w')
        perilla('mic_pc', 'Volumen del micrófono del PC', 0.1, 8, 'Solo para el PC: que la barra de Nivel se mueva como en el carro')
        perilla('sk', 'Sensibilidad (sk)', 1.3, 8, 'Más alto = necesita sonar más fuerte sobre el ruido para escuchar')
        perilla('uv', 'Umbral de voz (uv)', 0.05, 0.95, 'Más bajo = canta más fácil (y también con instrumentos)')
        perilla('gb', 'Qué tanto abre la boca (gb)', 0.3, 3, 'Más alto = boca más abierta')

        s.cv = tk.Canvas(col, width=300, height=120, bg='#111', highlightthickness=0); s.cv.pack(anchor='w', pady=10)
        tk.Button(col, text='Guardar ajustes', command=s.guardar, width=20).pack(anchor='w')
        s.msg = tk.Label(col, text='', **est); s.msg.pack(anchor='w', pady=4)

        s.t0 = time.time(); s.antes = s.t0; s.bocaV = 0.0; s.cantaHasta = 0.0; s.fuerte = 0.0
        s.flujo = None; s.abrir()
        s.r.after(40, s.cuadro); s.r.protocol('WM_DELETE_WINDOW', s.cerrar); s.r.mainloop()

    def abrir(s):
        if s.flujo: s.flujo.close()
        dev = s.mics[s.mic.current()][0]
        def cb(datos, *_): s.oido.bloque(datos[:, 0].astype(np.float64))
        try:
            s.flujo = sd.InputStream(device=dev, channels=1, samplerate=SR, blocksize=NF, dtype='float32', callback=cb); s.flujo.start()
            s.msg.config(text='')
        except Exception as e:
            s.flujo = None; s.msg.config(text='No abre ese micrófono: ' + str(e)[:60])

    def cuadro(s):
        ahora = time.time(); t = ahora - s.t0; dt = min(0.1, ahora - s.antes); s.antes = ahora
        o = s.oido
        if o.canta: s.cantaHasta = ahora + 1.5               # como el robot: la cara de cantar aguanta entre frases
        canta = o.canta or ahora < s.cantaHasta
        s.bocaV += ((o.boca if o.canta else 0) - s.bocaV) * 0.6
        s.fuerte = s.fuerte + dt if o.canta and o.vozS > 0.6 else 0   # nota sostenida fuerte: emoción
        bpm = 110; fase = t * bpm / 60
        musica = bool(s.bailando.get()) or canta
        p = dll.cuadroPanel(t, dt, int(musica), int(canta), s.bocaV, o.ancho, o.vozS, o.nivel,
                            1.0 if s.fuerte > 0.7 else 0.0, s.rit.current(), bpm, fase - int(fase))
        im = Image.frombuffer('RGB', (W, H), ctypes.string_at(p, W * H * 3), 'raw', 'RGB', 0, 1)
        s.foto = ImageTk.PhotoImage(im); s.img.config(image=s.foto)
        s.barras(o)
        s.r.after(max(1, int(40 - (time.time() - ahora) * 1000)), s.cuadro)

    def barras(s, o):
        c = s.cv; c.delete('all')
        for i, (nom, v, col) in enumerate([('Nivel', o.nivel, '#4a9'), ('Voz', o.vozS, '#59f'), ('Boca', s.bocaV, '#f95')]):
            y = 10 + i * 32
            c.create_text(0, y + 8, text=nom, anchor='w', fill='#ddd')
            c.create_rectangle(50, y, 290, y + 16, outline='#444')
            c.create_rectangle(50, y, 50 + 240 * min(1, v), y + 16, fill=col, width=0)
            if nom == 'Voz': c.create_line(50 + 240 * s.aj['uv'], y - 3, 50 + 240 * s.aj['uv'], y + 19, fill='white', width=2)
        c.create_text(50, 110, anchor='w', fill='#ff6' if o.canta else '#666', text='CANTANDO' if o.canta else ('oye sonido' if o.hay else 'silencio'))

    def guardar(s):
        os.makedirs(CARPETA, exist_ok=True)
        json.dump({k: round(v, 2) for k, v in s.aj.items()}, open(SALIDA, 'w', encoding='utf-8'), indent=1)
        s.msg.config(text='Guardado en ' + SALIDA)

    def cerrar(s):
        if s.flujo: s.flujo.close()
        s.r.destroy()


if __name__ == '__main__':
    Panel()
