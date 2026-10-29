# Hace una hoja con cuadros de cada cara (y GIFs de las pedidas) usando el programa "vista".
# Uso: python3 hoja.py <vista> <carpeta_salida> [caras...]
import sys, os, subprocess
from PIL import Image
W, H = 448, 368
vista, sal, pedidas = sys.argv[1], sys.argv[2], sys.argv[3:]
os.makedirs(sal, exist_ok=True)
hoja = []
for nombre in pedidas:
    raw = os.path.join(sal, nombre + ".raw")
    subprocess.run([vista, nombre, "6", raw], check=True)
    d = open(raw, "rb").read(); n = len(d) // (W * H * 3); os.remove(raw)
    cuadros = [Image.frombytes("RGB", (W, H), d[i * W * H * 3:(i + 1) * W * H * 3]) for i in range(n)]
    pal = [c.resize((W // 2, H // 2)).quantize(colors=96) for c in cuadros[::2]]
    pal[0].save(os.path.join(sal, nombre + ".gif"), save_all=True, append_images=pal[1:], duration=80, loop=0, optimize=True)
    hoja.append([cuadros[min(n - 1, int(n * k))] for k in (0.1, 0.35, 0.6, 0.85)])
s = 0.5; w, h = int(W * s), int(H * s)
im = Image.new("RGB", (w * 4, h * len(hoja)), (40, 40, 40))
for f, fila in enumerate(hoja):
    for c, cu in enumerate(fila): im.paste(cu.resize((w, h)), (c * w, f * h))
im.save(os.path.join(sal, "caras.png")); print("ok", len(hoja))
