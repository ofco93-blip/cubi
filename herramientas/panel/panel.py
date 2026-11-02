# Panel en vivo: se conecta a Cubi por Bluetooth desde el PC (sin el celular). Cubi oye con su propio micrófono;
# aquí se ve lo que oye (nivel, voz, vocal, boca), una copia de su cara (caras.cpp como cubi_cara.dll) y las
# perillas, que se guardan en el mismo Cubi al moverlas.
import asyncio, ctypes, json, os, threading, time, tkinter as tk
from tkinter import ttk
from bleak import BleakClient, BleakScanner
from PIL import Image, ImageTk

AQUI = os.path.dirname(os.path.abspath(__file__))
SRV, RX, TX = ('6e40000%d-b5a3-f393-e0a9-e50e24dcca9e' % i for i in (1, 2, 3))
PAQUETES = ['Lumi azul', 'Minimal', 'Anime', 'Peluche', 'Colores', 'Nova', 'Kira', 'Pixel']

dll = ctypes.CDLL(os.path.join(AQUI, 'cubi_cara.dll'))
dll.nombreRitmo.restype = ctypes.c_char_p
dll.cuadroPanel.restype = ctypes.POINTER(ctypes.c_ubyte)
dll.cuadroPanel.argtypes = [ctypes.c_float, ctypes.c_float, ctypes.c_int, ctypes.c_int] + [ctypes.c_float] * 5 + [ctypes.c_int, ctypes.c_float, ctypes.c_float]
dll.arrancar()
W, H = dll.ancho(), dll.alto()
RITMOS = [dll.nombreRitmo(i).decode() for i in range(dll.nRitmos())]


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


class Panel:
    def __init__(s):
        s.cubi = Cubi()
        s.aud = {}; s.aj = None
        s.r = tk.Tk(); s.r.title('Cubi en vivo'); s.r.configure(bg='#111')
        est = {'bg': '#111', 'fg': '#ddd'}
        s.img = tk.Label(s.r, bg='black'); s.img.grid(row=0, column=0, padx=10, pady=10)
        col = tk.Frame(s.r, bg='#111'); col.grid(row=0, column=1, sticky='n', padx=10, pady=10)
        s.con = tk.Label(col, text='', **est, font=('Segoe UI', 10, 'bold')); s.con.pack(anchor='w')

        fila = tk.Frame(col, bg='#111'); fila.pack(anchor='w', pady=6)
        s.paq = ttk.Combobox(fila, values=PAQUETES, state='readonly', width=10); s.paq.current(0); s.paq.pack(side='left')
        s.paq.bind('<<ComboboxSelected>>', lambda e: s.mandar('paq', s.paq.current()))
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
        perilla('sk', 'Sensibilidad (sk)', 1.3, 8, 0.1, 'Más alto = necesita sonar más fuerte sobre el ruido del carro')
        perilla('uv', 'Umbral de voz (uv)', 0.05, 0.95, 0.01, 'Si abre la boca en los solos, súbelo; si no canta con voz, bájalo')
        perilla('gb', 'Qué tanto abre la boca (gb)', 0.3, 3, 0.05, 'Más alto = boca más abierta')

        s.cv = tk.Canvas(col, width=330, height=170, bg='#111', highlightthickness=0); s.cv.pack(anchor='w', pady=10)
        tk.Label(col, text='Los ajustes se guardan en Cubi al soltar cada perilla.', fg='#888', bg='#111', font=('Segoe UI', 8)).pack(anchor='w')

        s.t0 = s.antes = time.time(); s.fase = 0.0
        threading.Thread(target=s.oir, daemon=True).start()
        s.r.after(40, s.cuadro); s.r.mainloop()

    def mandar(s, clave, v):
        v = int(v) if clave in ('mg', 'paq', 'bm') else round(float(v), 2)
        threading.Thread(target=s.cubi.pedir, args=('/ajuste?%s=%s' % (clave, v),), daemon=True).start()

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
        a = s.aud; bpm = a.get('bpm', 0) or 110
        s.fase = (s.fase + dt * bpm / 60) % 1000
        musica = a.get('estado', 0) >= 2; canta = bool(a.get('canta', 0))
        dll.ponerPaquete(s.paq.current())
        p = dll.cuadroPanel(ahora - s.t0, dt, int(musica or canta), int(canta), a.get('boca', 0), a.get('ancho', 0.5), a.get('voz', 0),
                            a.get('nivel', 0), 0.0, max(0, RITMOS.index(a['ins'])) if a.get('ins') in RITMOS else 0, bpm, s.fase % 1)
        s.foto = ImageTk.PhotoImage(Image.frombuffer('RGB', (W, H), ctypes.string_at(p, W * H * 3), 'raw', 'RGB', 0, 1))
        s.img.config(image=s.foto)
        s.con.config(text=s.cubi.estado + ('' if not a else '   ·   ' + a.get('estilo', '')), fg='#6d6' if s.cubi.cli else '#fa6')
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
        c.create_text(0, 150, anchor='w', fill='#888', text='rms %s  piso %s  bpm %s' % (a.get('rms', '-'), a.get('piso', '-'), a.get('bpm', '-')))
        s.r.after(max(1, int(40 - (time.time() - ahora) * 1000)), s.cuadro)


if __name__ == '__main__':
    Panel()
