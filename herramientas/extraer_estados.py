"""
Saca los movimientos de los estados de cuenta (ya convertidos a .txt por pdf_a_texto.py)
y los cuadra contra los totales que reporta cada banco.

Uso: python extraer_estados.py estados/   → escribe estados/movimientos.csv
"""
import csv
import re
import sys
from dataclasses import dataclass
from datetime import date
from pathlib import Path

MESES = {m: i + 1 for i, m in enumerate("ene feb mar abr may jun jul ago sep oct nov dic".split())}
MESES_LARGOS = {
    "enero": 1, "febrero": 2, "marzo": 3, "abril": 4, "mayo": 5, "junio": 6, "julio": 7,
    "agosto": 8, "septiembre": 9, "octubre": 10, "noviembre": 11, "diciembre": 12,
}
MONTO = r"([+-])\s*\$\s*([\d,]+\.\d{2})"


def mes(txt: str) -> int:
    return MESES[txt.lower()[:3]]


def dinero(txt: str) -> float:
    return float(txt.replace(",", ""))


@dataclass
class Mov:
    tarjeta: str
    archivo: str
    fecha: date
    descripcion: str
    monto: float  # + cargo, - abono
    tipo: str = ""


def clasificar(m: Mov) -> str:
    d = m.descripcion.upper()
    # Pasar una compra a meses: el banco la "abona" y la vuelve a "cargar" en la promoción. Se anulan entre sí.
    if "TRASPASO A PROMOCION" in d or "TRASPASO A MESES" in d or "PROMOCION MESES" in d or "DIFERIMIENTO DE SALDO" in d:
        return "ajuste"
    # Mensualidad de algo que ya se contó como compra el día que se hizo.
    if re.match(r"^\d{2} DE \d{2}\b", d) or re.search(r"\b\d{1,2} de \d{1,2}$", m.descripcion):
        return "mensualidad"
    # Nu: sacar dinero de la tarjeta a la Cuenta Nu es un préstamo, no un gasto; sus intereses sí son costo.
    if "DISPOSICIÓN DE SALDO" in d or "DISPOSICION DE SALDO" in d:
        return "interes" if "INTERES" in d else "disposicion"
    if m.monto < 0:
        if re.search(r"PAGO|GRACIAS POR TU PAGO|SPEI|BMOVIL|INTERBANCARIO|DEPOSITO", d):
            return "pago_tarjeta"
        return "reembolso"
    if "IVA" in d and ("INTERES" in d or "COMISION" in d):
        return "iva"
    if re.search(r"INTERES", d):
        return "interes"
    if re.search(r"COMISION|ANUALIDAD|REP\.ROBO|REPOSICION", d):
        return "comision"
    return "compra"


def periodo_de(texto: str) -> tuple[date, date] | None:
    m = re.search(r"Periodo:?\s*(\d{1,2})[- ]([a-zA-Z]{3,4})[- ](\d{4})\s*(?:al|-)\s*(\d{1,2})[- ]([a-zA-Z]{3,4})[- ](\d{4})", texto, re.I)
    if m:
        return (date(int(m[3]), mes(m[2]), int(m[1])), date(int(m[6]), mes(m[5]), int(m[4])))
    return None


# ---------- Un lector por banco ----------

def con_monto_diferido(lineas, i_inicio, patron_inicio):
    """Une una línea de movimiento con las siguientes hasta encontrar el monto (Banamex/Rappi parten algunas)."""
    texto = lineas[i_inicio]
    j = i_inicio
    while not re.search(MONTO + r"\s*$", texto) and j + 1 < len(lineas) and j - i_inicio < 10:
        j += 1
        if re.match(patron_inicio, lineas[j]):
            return None, j - 1
        texto += " " + lineas[j]
    m = re.search(MONTO, texto)
    return (texto, j) if m else (None, j)


def leer_iso(tarjeta, archivo, texto):
    """Rappi: '2026-09-16 2026-09-17 SPOTIFY +$129.00'."""
    patron = r"^(\d{4})-(\d{2})-(\d{2}) \d{4}-\d{2}-\d{2} "
    lineas = texto.splitlines()
    movs, i = [], 0
    while i < len(lineas):
        m = re.match(patron, lineas[i])
        if m:
            junto, i = con_monto_diferido(lineas, i, patron)
            if junto:
                mm = list(re.finditer(MONTO, junto))[-1]
                desc = junto[m.end():mm.start()].split("; RFC")[0].strip()
                monto = dinero(mm[2]) * (1 if mm[1] == "+" else -1)
                movs.append(Mov(tarjeta, archivo, date(int(m[1]), int(m[2]), int(m[3])), desc, monto))
        i += 1
    return movs


def leer_rappi_viejo(tarjeta, archivo, texto):
    """Rappi antes de feb 2026: '2026-01-16 HEB TIENDA $0.00 $400.00' y pagos '2026-01-20 PAGO POR SPEI 10,000.00'."""
    lineas = texto.splitlines()
    movs, seccion = [], None
    for i, l in enumerate(lineas):
        if l.startswith("Compras y cargos"):
            seccion = "cargos"
        elif l.startswith("Pagos, devoluciones"):
            seccion = "pagos"
        elif l.startswith(("Total cargos del periodo", "Total pagos del periodo")):
            seccion = None
        m = re.match(r"^(\d{4})-(\d{2})-(\d{2}) (.*)$", l)
        if not (seccion and m):
            continue
        resto = m[4]
        if seccion == "cargos":
            montos = re.findall(r"\$([\d,]+\.\d{2})", resto)
            if not montos:
                continue
            desc = re.split(r" \$| (?:USD|EUR) ", resto)[0].strip()
            if re.match(r"^(USD|EUR) ", resto):  # compra en el extranjero: el comercio viene en la línea anterior
                desc = lineas[i - 1].strip()
            monto = dinero(montos[-1])
        else:
            mm = re.search(r"([\d,]+\.\d{2})$", resto)
            if not mm:
                continue
            desc, monto = resto[:mm.start()].strip(), -dinero(mm[1])
        movs.append(Mov(tarjeta, archivo, date(int(m[1]), int(m[2]), int(m[3])), desc, monto))
    return movs


def leer_rappi(tarjeta, archivo, texto):
    if "Total cargos del periodo" in texto:
        return leer_rappi_viejo(tarjeta, archivo, texto)
    return leer_iso(tarjeta, archivo, texto)


def leer_guiones(tarjeta, archivo, texto):
    """BBVA y Banamex: '13-sep-2026 14-sep-2026 TAQUERIA CENTRO + $300.00'."""
    patron = r"^(\d{2})-([a-z]{3})-(\d{4}) \d{2}-[a-z]{3}-\d{4} "
    lineas = texto.splitlines()
    movs, i = [], 0
    while i < len(lineas):
        m = re.match(patron, lineas[i])
        if m:
            junto, i = con_monto_diferido(lineas, i, patron)
            if junto:
                mm = list(re.finditer(MONTO, junto))[-1]
                desc = junto[m.end():mm.start()]
                desc = re.sub(r"\s+", " ", desc.split(" PAGO RECIBIDO DE")[0]).strip()
                monto = dinero(mm[2]) * (1 if mm[1] == "+" else -1)
                movs.append(Mov(tarjeta, archivo, date(int(m[3]), mes(m[2]), int(m[1])), desc, monto))
        i += 1
    return movs


def leer_nu(tarjeta, archivo, texto):
    """Nu: '14 AGO 2026 14 AGO 2026 Netflix | RFC: S.I. +$199.00'."""
    patron = r"^(\d{2}) ([A-Z]{3}) (\d{4}) \d{2} [A-Z]{3} \d{4} "
    lineas = texto.splitlines()
    movs, i = [], 0
    while i < len(lineas):
        m = re.match(patron, lineas[i])
        if m:
            junto, i = con_monto_diferido(lineas, i, patron)
            if junto:
                mm = list(re.finditer(MONTO, junto))[-1]
                desc = junto[m.end():mm.start()].split("| RFC")[0].strip()
                monto = dinero(mm[2]) * (1 if mm[1] == "+" else -1)
                movs.append(Mov(tarjeta, archivo, date(int(m[3]), mes(m[2]), int(m[1])), desc, monto))
        i += 1
    return movs


def leer_mercadopago(tarjeta, archivo, texto):
    """Mercado Pago: '21/08 Compra en XXXX $ 300.00' / '22/08 Pago del resumen ... - $ 1,000.00'."""
    p = re.search(r"\n(\d{1,2}) ([a-z]+) \d{1,2} [a-z]+ (\d{1,2}) ([a-z]+) - (\d{1,2}) ([a-z]+)", texto)
    fin_mes = MESES_LARGOS[p[6]]
    anio = 2026  # los estados son de 2026; si el periodo cruzara de año habría que ajustarlo
    movs = []
    for m in re.finditer(r"^(\d{2})/(\d{2}) (.+?) (-\s*)?\$ ([\d,]+\.\d{2})$", texto, re.M):
        desc = m[3].strip()
        if desc.startswith("Saldo al corte"):
            continue
        a = anio if int(m[2]) <= fin_mes else anio - 1
        signo = -1 if m[4] else 1
        movs.append(Mov(tarjeta, archivo, date(a, int(m[2]), int(m[1])), desc, signo * dinero(m[5])))
    return movs


LECTORES = {
    "rappi": ("Rappicard", leer_rappi),
    "bbva": ("BBVA Crédito", leer_guiones),
    "banamex": ("Banamex", leer_guiones),
    "nu": ("Nu", leer_nu),
    "mercadopago": ("Mercado Pago", leer_mercadopago),
}


# ---------- Totales que reporta el banco, para cuadrar ----------

def totales_banco(carpeta, texto):
    """(cargos, abonos) según el resumen del estado; None si no se encontró."""
    def buscar(pat):
        m = re.search(pat, texto)
        return dinero(m[1]) if m else None

    if carpeta == "rappi" and "Total cargos del periodo" in texto:
        return buscar(r"Total cargos del periodo \$([\d,]+\.\d{2})"), buscar(r"Total pagos del periodo \$([\d,]+\.\d{2})")
    if carpeta == "rappi":
        return buscar(r"Total de cargos \+\$([\d,]+\.\d{2})"), buscar(r"Total de abonos -\$([\d,]+\.\d{2})")
    if carpeta == "banamex":
        return buscar(r"Total cargos \+ \$([\d,]+\.\d{2})"), buscar(r"Total abonos - \$([\d,]+\.\d{2})")
    if carpeta in ("nu", "bbva"):
        cargos = sum(filter(None, [
            buscar(r"Cargos regulares \(no a meses\) \+ \$([\d,]+\.\d{2})"),
        ]))
        return cargos, buscar(r"Pagos y abonos - \$([\d,]+\.\d{2})")
    if carpeta == "mercadopago":
        compras = buscar(r"\nCompras \$ ([\d,]+\.\d{2})") or 0
        msi = buscar(r"Compras a meses con o sin intereses \$ ([\d,]+\.\d{2})") or 0
        return compras + msi, None
    return None, None


def main(raiz: Path):
    todos, vistos, reporte = [], set(), []
    for carpeta, (tarjeta, lector) in LECTORES.items():
        for txt in sorted((raiz / carpeta).glob("*.txt")):
            texto = txt.read_text(encoding="utf-8")
            periodo = periodo_de(texto)
            # Copias idénticas del mismo periodo: se leen una sola vez.
            clave = (carpeta, periodo) if periodo else (carpeta, txt.name)
            if clave in vistos:
                reporte.append((f"{carpeta}/{txt.name}", "copia repetida, se ignora", "", ""))
                continue
            vistos.add(clave)
            movs = lector(tarjeta, f"{carpeta}/{txt.name}", texto)
            for mv in movs:
                mv.tipo = clasificar(mv)
            todos += movs
            cargos = round(sum(m.monto for m in movs if m.monto > 0), 2)
            abonos = round(-sum(m.monto for m in movs if m.monto < 0), 2)
            esp_c, esp_a = totales_banco(carpeta, texto)
            ok_c = "?" if esp_c is None else ("OK" if abs(esp_c - cargos) < 0.02 else f"DIF {cargos - esp_c:+.2f}")
            ok_a = "?" if esp_a is None else ("OK" if abs(esp_a - abonos) < 0.02 else f"DIF {abonos - esp_a:+.2f}")
            reporte.append((f"{carpeta}/{txt.name}", f"{len(movs)} movs", f"cargos {cargos:,.2f} [{ok_c}]", f"abonos {abonos:,.2f} [{ok_a}]"))

    with open(raiz / "movimientos.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["tarjeta", "fecha", "descripcion", "monto", "tipo", "archivo"])
        for m in sorted(todos, key=lambda m: (m.tarjeta, m.fecha)):
            w.writerow([m.tarjeta, m.fecha.isoformat(), m.descripcion, f"{m.monto:.2f}", m.tipo, m.archivo])

    for fila in reporte:
        print(f"{fila[0]:<34} {fila[1]:<14} {fila[2]:<34} {fila[3]}")
    print(f"\nTotal: {len(todos)} movimientos → {raiz / 'movimientos.csv'}")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
