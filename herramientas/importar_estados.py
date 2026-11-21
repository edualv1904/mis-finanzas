"""
Carga los movimientos de estados/movimientos.csv a una copia de la base de datos de la app.

Uso: python importar_estados.py estados/movimientos.csv ruta/finanzas.db [--aplicar]
Sin --aplicar solo muestra lo que haría. Con --exportar archivo.json escribe los movimientos
para que la propia app los cargue (ver Registro.importarArchivo), que es lo seguro en el teléfono. Es seguro repetirlo: cada movimiento tiene una
llave única y no se carga dos veces.
"""
import csv
import json
import re
import sqlite3
import sys
from collections import Counter
from datetime import date, datetime, time, timedelta
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
CATEGORIAS_KT = RAIZ / "app/src/main/java/com/misfinanzas/Categorias.kt"
INTERESES = "Intereses y comisiones"

# Qué se carga y cómo; lo demás (pagos de tarjeta, mensualidades, ajustes, disposiciones) no.
TIPOS = {
    "compra": "gasto",
    "reembolso": "ingreso",
    "interes": "gasto",
    "iva": "gasto",
    "comision": "gasto",
}


def reglas_de_la_app():
    """Las mismas reglas de Categorias.kt, en el mismo orden."""
    texto = CATEGORIAS_KT.read_text(encoding="utf-8")
    return [(c, re.compile(r)) for c, r in re.findall(r'"([^"]+)" to Regex\("""(.+?)"""\)', texto)]


REGLAS = reglas_de_la_app()


def adivinar(comercio: str) -> str:
    c = comercio.upper()
    return next((cat for cat, rx in REGLAS if rx.search(c)), "Otros")


def limpiar(desc: str, tarjeta: str) -> str:
    d = re.sub(r"\s*;\s*Tarjeta Digital \*+\d+", "", desc)
    d = re.sub(r"\s+[A-Z&Ñ]{3,4}\d{6}[A-Z0-9]{3}(MX)?$", "", d)  # RFC pegado al final (Banamex)
    if tarjeta == "Mercado Pago":
        d = re.sub(r"^Compra en\s+", "", d)
        if re.fullmatch(r"0+", d):
            d = "Mercado Pago (comercio no especificado)"
    return re.sub(r"\s+", " ", d).strip()


def mediodia(dia: date) -> int:
    """Sin hora en el estado de cuenta: se guarda a mediodía para que no cambie de día por zona horaria."""
    return int(datetime.combine(dia, time(12, 0)).timestamp() * 1000)


def main(csv_path: Path, db_path: Path, aplicar: bool, exportar: str | None = None):
    db = sqlite3.connect(db_path)
    elegidas = dict(db.execute("SELECT comercio, categoria FROM categoria_comercio"))
    # Las mensualidades de compras a meses (fuente 'msi') no son capturas: no deben tapar una compra real.
    existentes = list(db.execute("SELECT tarjeta, monto, fecha, fuente FROM transacciones WHERE fuente != 'msi'"))
    claves = {r[0] for r in db.execute("SELECT claveUnica FROM transacciones WHERE claveUnica IS NOT NULL")}
    # Lo que marcaste como duplicado en la app no se vuelve a cargar.
    if db.execute("SELECT 1 FROM sqlite_master WHERE name = 'claves_descartadas'").fetchone():
        claves |= {r[0] for r in db.execute("SELECT clave FROM claves_descartadas")}

    nuevos, repetidos, ya_en_app, ocurrencias = [], 0, [], Counter()
    for r in csv.DictReader(open(csv_path, encoding="utf-8")):
        tipo = TIPOS.get(r["tipo"])
        if not tipo:
            continue
        dia = date.fromisoformat(r["fecha"])
        monto = abs(float(r["monto"]))
        comercio = limpiar(r["descripcion"], r["tarjeta"])
        base = f"estado|{r['tarjeta']}|{r['fecha']}|{monto:.2f}|{comercio}"
        ocurrencias[base] += 1
        clave = f"{base}|{ocurrencias[base]}"  # dos compras idénticas el mismo día son dos movimientos
        if clave in claves:
            repetidos += 1
            continue
        # Lo que la app ya capturó por notificación (misma tarjeta y monto, ±2 días) no se duplica.
        fecha = mediodia(dia)
        if any(t == r["tarjeta"] and abs(m - monto) < 0.005 and abs(f - fecha) <= 2 * 86_400_000 and fu != "estado"
               for t, m, f, fu in existentes):
            ya_en_app.append((r["fecha"], comercio, monto))
            continue
        if r["tipo"] in ("interes", "iva", "comision"):
            categoria = INTERESES
        else:
            categoria = elegidas.get(comercio) or adivinar(comercio)
        nuevos.append((monto, "MXN", comercio, r["tarjeta"], fecha, categoria, "estado", clave, "", None, tipo))

    print(f"Nuevos: {len(nuevos)} · ya estaban en la app: {len(ya_en_app)} · cargados antes: {repetidos}")
    for x in ya_en_app:
        print("  ya en la app:", *x)
    por_cat = Counter()
    for n in nuevos:
        por_cat[(n[10], n[5])] += n[0]
    for (tipo, cat), total in sorted(por_cat.items(), key=lambda x: -x[1]):
        print(f"  {tipo:8} {cat:24} {total:12,.2f}")

    # Lo que ya estaba en la app como "Otros" (y no elegiste tú) se reclasifica con las reglas actuales.
    recat = [
        (adivinar(c), i)
        for i, c in db.execute("SELECT id, comercio FROM transacciones WHERE categoria = 'Otros' AND tipo = 'gasto'")
        if c not in elegidas and adivinar(c) != "Otros"
    ]
    print(f"Reclasificados de Otros: {len(recat)}")

    if exportar:
        campos = ["monto", "moneda", "comercio", "tarjeta", "fecha", "categoria", "fuente", "clave", "nota", "_", "tipo"]
        datos = {
            "nuevos": [{k: v for k, v in zip(campos, n) if k != "_"} for n in nuevos],
            "recategorizar": [{"id": i, "categoria": c} for c, i in recat],
        }
        Path(exportar).write_text(json.dumps(datos, ensure_ascii=False), encoding="utf-8")
        print(f"Exportado a {exportar}")

    if aplicar:
        db.executemany("UPDATE transacciones SET categoria = ? WHERE id = ?", recat)
        db.executemany(
            "INSERT INTO transacciones (monto, moneda, comercio, tarjeta, fecha, categoria, fuente, claveUnica, nota, claveVinculada, tipo) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            nuevos,
        )
        db.commit()
        print("Aplicado.")
    db.close()


if __name__ == "__main__":
    salida = sys.argv[sys.argv.index("--exportar") + 1] if "--exportar" in sys.argv else None
    main(Path(sys.argv[1]), Path(sys.argv[2]), "--aplicar" in sys.argv, salida)
