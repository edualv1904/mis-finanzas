"""
Revisa UN estado de cuenta contra la base de la app y dice qué hay que mirar.

Uso: python -I herramientas/revisar_estado.py estados/<banco>/<archivo>.txt copia/finanzas.db

No modifica nada. Secciones del reporte:
  1. Cuadre contra los totales que imprime el banco.
  2. Intereses, IVA y comisiones (lo que cuesta no pagar completo o cargos raros).
  3. Posibles cargos duplicados dentro del estado.
  4. Cargos del estado que la app no tenía (¿los reconoces?).
  5. Lo que la app registró en ese periodo y el banco no cobró (¿otro periodo? ¿captura errónea?).
  6. Mensualidades a meses que aparecen en el estado.
  7. Comercios que nunca habías usado con esa tarjeta.
"""
import importlib.util
import re
import sqlite3
import sys
from collections import defaultdict
from datetime import date, datetime, timedelta
from pathlib import Path

AQUI = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("extraer_estados", AQUI / "extraer_estados.py")
ex = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ex)

# Día en que empezaste a usar la app: antes de eso, que un cargo "no esté en la app" es normal. Ajústalo.
INICIO_APP = date(2026, 11, 1)
DIA_MS = 86_400_000
COSTOS = ("interes", "iva", "comision")


def ms(d: date) -> int:
    return int(datetime(d.year, d.month, d.day, 12).timestamp() * 1000)


def dia(ms_: int) -> date:
    return datetime.fromtimestamp(ms_ / 1000).date()


# Prefijos de procesadores de pago: no dicen nada del comercio.
GENERICAS = {"paypal", "rest", "clip", "wuzipay", "merpago", "mercadopago", "compra", "pago", "payu", "dlo", "bpk", "conekta", "stripe"}


def palabras(s: str) -> set[str]:
    return {w for w in re.findall(r"[a-z]{3,}", s.lower()) if w not in GENERICAS}


def juntas(s: str) -> str:
    return re.sub(r"[^a-z]", "", s.lower())


def pesos(x: float) -> str:
    return f"${x:,.2f}"


def main(txt: Path, db_path: Path):
    carpeta = txt.parent.name
    if carpeta not in ex.LECTORES:
        sys.exit(f"No sé leer la carpeta '{carpeta}'. Opciones: {', '.join(ex.LECTORES)}")
    tarjeta, lector = ex.LECTORES[carpeta]
    texto = txt.read_text(encoding="utf-8")
    movs = lector(tarjeta, f"{carpeta}/{txt.name}", texto)
    for m in movs:
        m.tipo = ex.clasificar(m)
    if not movs:
        sys.exit("El lector no encontró movimientos: probablemente es un formato nuevo.")

    periodo = ex.periodo_de(texto)
    # Sin periodo impreso, se deduce de las fechas (sin mensualidades: traen la fecha de la compra original).
    propias = [m.fecha for m in movs if m.tipo != "mensualidad"] or [m.fecha for m in movs]
    desde, hasta = periodo if periodo else (min(propias), max(propias))
    print(f"# {tarjeta} · {txt.name} · periodo {desde} a {hasta}{'' if periodo else ' (deducido de las fechas)'}")
    print(f"{len(movs)} movimientos leídos\n")

    # 1. Cuadre
    cargos = round(sum(m.monto for m in movs if m.monto > 0), 2)
    abonos = round(-sum(m.monto for m in movs if m.monto < 0), 2)
    esp_c, esp_a = ex.totales_banco(carpeta, texto)
    print("## 1. Cuadre con el banco")
    for nombre, leido, esperado in (("Cargos", cargos, esp_c), ("Abonos", abonos, esp_a)):
        if esperado is None:
            print(f"- {nombre}: {pesos(leido)} [el estado no trae total para comparar]")
        elif abs(esperado - leido) < 0.02:
            print(f"- {nombre}: {pesos(leido)} [OK]")
        else:
            print(f"- {nombre}: leí {pesos(leido)}, el banco dice {pesos(esperado)} [DIF {leido - esperado:+,.2f}]")
    print()

    # 2. Costos
    costos = [m for m in movs if m.tipo in COSTOS]
    print(f"## 2. Intereses, IVA y comisiones: {pesos(sum(m.monto for m in costos))}")
    for m in costos:
        print(f"- {m.fecha} {m.descripcion} {pesos(m.monto)} [{m.tipo}]")
    if not costos:
        print("- Ninguno.")
    print()

    compras = [m for m in movs if m.tipo == "compra"]

    # 3. Duplicados dentro del estado
    print("## 3. Posibles duplicados (mismo monto, comercio parecido, ≤ 3 días)")
    # Se agrupan: varios cargos iguales seguidos salen en una sola línea.
    grupos, asignado = [], set()
    for i, a in enumerate(compras):
        if i in asignado:
            continue
        g = [a]
        for j in range(i + 1, len(compras)):
            b = compras[j]
            if j not in asignado and abs(a.monto - b.monto) < 0.005 and abs((b.fecha - g[-1].fecha).days) <= 3 and (
                palabras(a.descripcion) & palabras(b.descripcion) or juntas(a.descripcion)[:8] == juntas(b.descripcion)[:8]
            ):
                g.append(b)
                asignado.add(j)
        if len(g) > 1:
            grupos.append(g)
    for g in grupos:
        fechas = sorted({m.fecha for m in g})
        cuando = f"el {fechas[0]}" if len(fechas) == 1 else f"del {fechas[0]} al {fechas[-1]}"
        print(f"- {len(g)} cargos de {pesos(g[0].monto)} en {g[0].descripcion} {cuando}")
    if not grupos:
        print("- Ninguno.")
    print()

    db = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    app = list(db.execute(
        "SELECT id, monto, comercio, fecha, fuente, tipo FROM transacciones "
        "WHERE tarjeta = ? AND fuente NOT IN ('estado', 'msi') AND fecha BETWEEN ? AND ?",
        (tarjeta, ms(desde - timedelta(days=3)), ms(hasta + timedelta(days=3))),
    ))

    # 4. Cargos del estado que la app no capturó
    usados = set()
    faltan = []
    for m in compras:
        par = next((r for r in app if r[0] not in usados and abs(r[1] - m.monto) < 0.005 and abs(r[3] - ms(m.fecha)) <= 2 * DIA_MS), None)
        # Mismo comercio con otro monto (tipo de cambio, propina): también cuenta como capturado.
        par = par or next((r for r in app if r[0] not in usados and palabras(r[2]) & palabras(m.descripcion)
                           and abs(r[1] - m.monto) <= 0.05 * m.monto and abs(r[3] - ms(m.fecha)) <= 3 * DIA_MS), None)
        if par:
            usados.add(par[0])
        elif m.fecha >= INICIO_APP:
            faltan.append(m)
    print(f"## 4. Cargos que la app no tenía (desde que lee notificaciones, {INICIO_APP})")
    for m in faltan:
        print(f"- {m.fecha} {m.descripcion} {pesos(m.monto)}")
    if not faltan:
        print("- Ninguno.")
    antes = sum(1 for m in compras if m.fecha < INICIO_APP)
    if antes:
        print(f"  ({antes} compras son de antes del 1 nov: normal que no estén por notificación)")
    print()

    # 5. Lo que la app tiene y el banco no
    sobran = [r for r in app if r[0] not in usados and r[5] == "gasto" and desde <= dia(r[3]) <= hasta]
    print("## 5. En la app pero no en el estado")
    sin_par = [m for m in compras if not any(
        abs(r[1] - m.monto) < 0.005 and abs(r[3] - ms(m.fecha)) <= 2 * DIA_MS for r in app)]
    for r in sobran:
        pista = ""
        parecido = next((m for m in sin_par if palabras(m.descripcion) & palabras(r[2])
                         and abs(m.monto - r[1]) <= 0.05 * r[1] and abs(ms(m.fecha) - r[3]) <= 3 * DIA_MS), None)
        if parecido:
            pista = f" → en el estado: {parecido.descripcion} {pesos(parecido.monto)} (otro monto: tipo de cambio o propina)"
        elif (hasta - dia(r[3])).days <= 2:
            pista = " → cerca del corte: puede venir en el siguiente estado"
        print(f"- {dia(r[3])} {r[2]} {pesos(r[1])} [vino de: {r[4]}, id {r[0]}]{pista}")
    if not sobran:
        print("- Nada.")
    print()

    # 6. Mensualidades
    mens = [m for m in movs if m.tipo == "mensualidad"]
    print(f"## 6. Mensualidades a meses: {pesos(sum(m.monto for m in mens))}")
    for m in mens:
        n = re.search(r"(\d{1,2}) DE (\d{1,2})", m.descripcion.upper())
        nota = ""
        if n and int(n[1]) == int(n[2]):
            nota = " ← ÚLTIMA"
        print(f"- {m.descripcion} {pesos(m.monto)}{nota}")
    if not mens:
        print("- Ninguna.")
    print()

    # 7. Comercios nuevos
    conocidas = defaultdict(set)
    for (com,) in db.execute("SELECT comercio FROM transacciones WHERE tarjeta = ? AND fecha < ?", (tarjeta, ms(desde))):
        conocidas[tarjeta] |= palabras(com)
    nuevos = [m for m in compras if not (palabras(m.descripcion) & conocidas[tarjeta])]
    print("## 7. Comercios que nunca habías usado con esta tarjeta")
    for m in nuevos:
        print(f"- {m.fecha} {m.descripcion} {pesos(m.monto)}")
    if not nuevos:
        print("- Ninguno.")
    db.close()


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(Path(sys.argv[1]), Path(sys.argv[2]))
