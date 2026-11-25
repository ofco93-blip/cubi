# Revisa que el código de la página de la app no tenga errores de sintaxis: uno solo deja la app sin botones.
# Uso: python herramientas/revisar_pagina.py   (necesita node)
import io, re, subprocess, sys, tempfile, os
s = io.open("android/app/src/main/assets/index.html", encoding="utf-8").read()
for m in re.finditer(r"<script(?![^>]*src)[^>]*>(.*?)</script>", s, re.S):
    f = os.path.join(tempfile.mkdtemp(), "pagina.js")
    io.open(f, "w", encoding="utf-8").write("\n" * s[:m.start(1)].count("\n") + m.group(1))   # mismas líneas que el html
    if subprocess.call(["node", "--check", f]): sys.exit(1)
print("pagina ok")
