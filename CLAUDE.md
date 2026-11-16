# Mis Finanzas

App Android personal que registra gastos leyendo las notificaciones de Google Wallet y de los bancos.
Kotlin + Compose + Room, sin servidor ni internet. No va a la Play Store: se instala por ADB.
Responder en español. Toda la documentación del proyecto está en README.md.

## Reglas de seguridad de datos

1. **Nunca reemplazar el archivo de la base en el teléfono.** Android reinicia la app al instante tras
   `force-stop` (por el listener). Para carga masiva: dejar `files/importar.json` y abrir la app.
2. Antes de instalar una versión con migración: copiar la base a `respaldos/` y simular la migración en
   Python sobre la copia.
3. No hacer `force-stop` mientras el usuario usa la app.
4. No subir datos a ningún servicio ni cargar movimientos sin que el usuario lo apruebe.
5. Nunca poner datos reales (comercios, montos, dígitos de tarjeta) en el código ni en las pruebas:
   el repositorio es público.

## Decisiones de diseño

- Tarjetas con un nombre canónico sin dígitos (`Tarjetas.canonica`).
- Tipos: `gasto`, `ingreso`, `transferencia`. Solo `gasto` suma en "Gastado este mes".
- Duplicados: Wallet reescribe la misma notificación → llave = solo `sbn.key`. Bancos → fuente + título +
  texto. Wallet + banco por el mismo pago (mismo monto, ±10 min, mismo banco) se fusionan.
- App siempre blanca, sin emojis, iconos Material outlined como vector drawables en `res/drawable`.
  El color solo significa algo: verde = ingreso, rojo = alerta. Logo de cada banco = icono de su app
  instalada.
- `importar_estados.py` lee las reglas de categoría directo de `Categorias.kt`.
