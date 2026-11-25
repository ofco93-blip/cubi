# mapa_pais.py <pais.osm.pbf> <salida.cubimapa>: las vías de un país en un solo archivo, para el mapa del carro sin internet.
# Formato (enteros big-endian), el mismo que lee CubiService.callesGuardadas:
#   "CUBIMAP1", n, y n veces (clave, desde, largo) ordenado por clave; luego los cuadros, cada uno comprimido con zlib.
#   Un cuadro mide 0.02° (unos 2,2 km). clave = (fila << 16) | columna, con fila = (lat + 90) / 0.02 y columna = (lon + 180) / 0.02.
#   Dentro del cuadro, tramo tras tramo: clase (0 calle, 1 avenida; +128 si trae nombre, y entonces sigue el largo
#   (varint) y el nombre en UTF-8), cuántos puntos (varint) y los puntos como saltos
#   (varint zigzag, en millonésimas de grado) desde el punto anterior; el primero, desde la esquina del cuadro.
# Los sitios (ciudades, barrios, centros comerciales, hospitales…) van igual, como un "tramo" de UN solo punto con nombre y
# clase 6 (ciudad o pueblo), 7 (barrio, vereda), 8 (sitio de interés), 9 (peaje) o 10 (cámara de velocidad). Una app vieja los salta sola: solo toma tramos de 2 o más.
# Una vía que cruza cuadros se parte: cada pedazo termina en el primer punto del cuadro siguiente, y el que sigue arranca ahí.
import re, struct, sys, zlib
T = 20000
VIA = re.compile(r"^(motorway|trunk|primary|secondary|tertiary|residential|unclassified|living_street)(_link)?$")
GRANDE = ("motorway", "trunk", "primary", "secondary")

def varint(o, v):
    while v > 127: o.append(v & 127 | 128); v >>= 7
    o.append(v)
def zz(v): return v << 1 if v >= 0 else (-v << 1) - 1
def cuadro(la, lo): return ((la + 90_000_000) // T) << 16 | ((lo + 180_000_000) // T)

def meter(cuadros, clase, pts, nombre=""):
    """pts: [(lat, lon)] en millonésimas. Lo parte por cuadros y lo agrega."""
    i = 0
    while i < len(pts) - 1:
        k = cuadro(*pts[i]); j = i + 1
        while j < len(pts) - 1 and cuadro(*pts[j]) == k: j += 1
        o = cuadros.setdefault(k, bytearray()); nb = nombre.encode("utf-8")[:60].decode("utf-8", "ignore").encode("utf-8")
        o.append(clase | (128 if nb else 0))
        if nb: varint(o, len(nb)); o.extend(nb)
        varint(o, j - i + 1)
        pa, po = (k >> 16) * T - 90_000_000, (k & 0xffff) * T - 180_000_000
        for la, lo in pts[i:j + 1]: varint(o, zz(la - pa)); varint(o, zz(lo - po)); pa, po = la, lo
        i = j

def punto(cuadros, clase, la, lo, nombre):
    nb = nombre.encode("utf-8")[:60].decode("utf-8", "ignore").encode("utf-8")
    if not nb: return
    k = cuadro(la, lo); o = cuadros.setdefault(k, bytearray())
    o.append(clase | 128); varint(o, len(nb)); o.extend(nb); varint(o, 1)
    varint(o, zz(la - ((k >> 16) * T - 90_000_000))); varint(o, zz(lo - ((k & 0xffff) * T - 180_000_000)))

LUGAR = {"city": 6, "town": 6, "village": 7, "suburb": 7, "neighbourhood": 7, "hamlet": 7}
INTERES = {("shop", "mall"), ("amenity", "hospital"), ("amenity", "fuel"), ("amenity", "university"), ("amenity", "bus_station"),
           ("aeroway", "aerodrome"), ("leisure", "stadium"), ("tourism", "attraction"), ("amenity", "police"), ("amenity", "charging_station")}
def clase_sitio(tags):
    """6, 7, 8, 9 o 10 si es un sitio que vale la pena rotular; 0 si no"""
    if tags.get("barrier", "") == "toll_booth": return 9
    if tags.get("highway", "") == "speed_camera": return 10
    if "name" not in tags: return 0
    if tags.get("place", "") in LUGAR: return LUGAR[tags.get("place")]
    return 8 if any(tags.get(k, "") == v for k, v in INTERES) else 0

def escribir(cuadros, salida):
    claves = sorted(cuadros); partes = [zlib.compress(bytes(cuadros[k]), 9) for k in claves]
    desde = 12 + 12 * len(claves)
    with open(salida, "wb") as f:
        f.write(b"CUBIMAP1" + struct.pack(">i", len(claves)))
        for k, p in zip(claves, partes): f.write(struct.pack(">iii", k, desde, len(p))); desde += len(p)
        for p in partes: f.write(p)
    return desde

def leer(salida, k):
    """lo mismo que hace la app: los tramos de un cuadro, [(clase, [(lat, lon)], nombre)]"""
    d = open(salida, "rb").read(); n = struct.unpack(">i", d[8:12])[0]
    for i in range(n):
        c, de, la = struct.unpack(">iii", d[12 + 12 * i:24 + 12 * i])
        if c == k: b = zlib.decompress(d[de:de + la]); break
    else: return []
    def vi():
        nonlocal p
        v = s = 0
        while True:
            x = b[p]; p += 1; v |= (x & 127) << s; s += 7
            if x < 128: return v
    uz = lambda v: v >> 1 if v % 2 == 0 else -(v + 1 >> 1)
    p = 0; r = []
    while p < len(b):
        cl = b[p]; p += 1; nom = ""
        if cl & 128: ln = vi(); nom = b[p:p + ln].decode("utf-8"); p += ln; cl &= 127
        pa, po = (k >> 16) * T - 90_000_000, (k & 0xffff) * T - 180_000_000; pts = []
        for _ in range(vi()): pa += uz(vi()); po += uz(vi()); pts.append((pa, po))
        r.append((cl, pts, nom))
    return r

def demo():
    import os, tempfile
    via = [(4_650_000, -74_060_000), (4_655_000, -74_058_000), (4_661_000, -74_057_000), (4_662_000, -74_020_500), (4_662_100, -74_020_400)]
    c = {}; meter(c, 1, via, "Avenida Boyacá"); meter(c, 0, via[:2]); punto(c, 8, 4_651_000, -74_059_000, "Centro Comercial")
    assert clase_sitio({"barrier": "toll_booth"}) == 9 and clase_sitio({"highway": "speed_camera"}) == 10 and clase_sitio({"name": "X", "place": "town"}) == 6 and clase_sitio({"name": "X", "shop": "mall"}) == 8 and clase_sitio({"shop": "mall"}) == 0
    f = os.path.join(tempfile.mkdtemp(), "p"); escribir(c, f)
    vuelta = [p for k in sorted(c) for cl, pts, nom in leer(f, k) for p in pts if cl < 6]
    assert set(vuelta) == set(via), vuelta
    uno = leer(f, cuadro(*via[0]))
    assert len(c) == 3 and uno[0] == (1, via[:3], "Avenida Boyacá") and uno[1] == (0, via[:2], "") and uno[2] == (8, [(4_651_000, -74_059_000)], "Centro Comercial"), uno   # cada pedazo acaba en el primer punto del cuadro siguiente
    print("ok")

if __name__ == "__main__":
    if len(sys.argv) < 3: demo(); sys.exit()
    import osmium
    cuadros = {}; cuenta = [0, 0, 0]
    class H(osmium.SimpleHandler):
        def node(self, n):
            cs = clase_sitio(n.tags)
            if cs and n.location.valid(): punto(cuadros, cs, round(n.location.lat * 1e6), round(n.location.lon * 1e6), n.tags.get("name") or ("Peaje" if cs == 9 else "Cámara")); cuenta[2] += 1
        def way(self, w):
            t = w.tags.get("highway", "")
            if not VIA.match(t):
                cs = clase_sitio(w.tags)                              # un centro comercial o un hospital dibujado como figura: va su centro
                ps = [(n.location.lat, n.location.lon) for n in w.nodes if n.location.valid()] if cs else []
                if ps: punto(cuadros, cs, round(sum(p[0] for p in ps) / len(ps) * 1e6), round(sum(p[1] for p in ps) / len(ps) * 1e6), w.tags.get("name")); cuenta[2] += 1
                return
            pts = [(round(n.location.lat * 1e6), round(n.location.lon * 1e6)) for n in w.nodes if n.location.valid()]
            if len(pts) < 2: return
            meter(cuadros, 1 if t.startswith(GRANDE) else 0, pts, w.tags.get("name") or w.tags.get("ref") or ""); cuenta[0] += 1; cuenta[1] += len(pts)
    H().apply_file(sys.argv[1], locations=True)
    peso = escribir(cuadros, sys.argv[2])
    print(f"{cuenta[0]} vías, {cuenta[1]} puntos, {cuenta[2]} sitios, {len(cuadros)} cuadros, {peso / 1e6:.1f} MB")
