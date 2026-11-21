# Mis Finanzas

App Android personal que **registra tus gastos sola leyendo las notificaciones** de Google Wallet y de tus
bancos. Kotlin + Jetpack Compose + Room. No usa servidor: todo se queda en tu teléfono (solo usa
internet para dibujar el mapa y escribir direcciones).
No está en la Play Store; se instala con ADB.

## Qué hace

- Lee las notificaciones de Google Wallet, BBVA, Nu, RappiCard, Banamex y Mercado Pago y registra cada
  compra con su comercio, monto, tarjeta y categoría.
- Evita duplicados: si Wallet y el banco avisan del mismo pago (mismo monto y banco, y casi a la vez o con
  un comercio parecido hasta 48 h después) se guarda uno solo. Lo que marques como "Es duplicado de" no
  se vuelve a registrar.
- Lo que no sabe leer lo guarda en "Recolectadas" para que tú lo etiquetes (gasto, ingreso,
  transferencia o ignorar).
- Pantalla de tarjetas con fecha de corte y fecha límite de pago, y aviso diario cuando se acerca el límite.
- Compras a meses: pasas una compra a N meses y la app suma cada mensualidad en su mes, no la compra entera.
- Huella, rostro o PIN para entrar (se vuelve a pedir tras 60 s fuera de la app).
- "Ocultar cantidades" (el ojo de arriba) para enseñar la app sin mostrar cifras; también aplica al widget.
- Mapa de gastos: cada compra en persona guarda dónde estabas al pagar (una sola lectura de ubicación,
  no te sigue). Las compras en línea y suscripciones no se ubican. También puedes poner el lugar a mano.
- Widget con lo gastado esta semana y hoy, y captura rápida de gastos a mano.
- Respaldo semanal a tu Google Drive: cada sábado, solo en el WiFi de casa, sube una copia de la base a la
  carpeta "Respaldos Mis Finanzas" y guarda las últimas 8 semanas. La app solo ve los archivos que ella crea.
- Las notificaciones que marcas "Ignorar" se borran solas a los 30 días.
- Scripts en Python para cargar los movimientos de tus estados de cuenta en PDF (lo que se pagó sin
  notificación).

## Carpetas

```
app/src/main/java/com/misfinanzas/
  WalletListener.kt   Servicio que recibe cada notificación
  Parsers.kt          Un lector por app + nombres de tarjeta + tipos + "gemelas" (duplicados)
  Categorias.kt       Reglas comercio → categoría (EL ORDEN IMPORTA)
  Registro.kt         Única vía de escritura: llaves anti-duplicado, fusión, importar archivo
  Ciclos.kt           Fechas de corte / límite de pago
  Recordatorios.kt    Aviso diario de fecha límite
  Meses.kt            Compras a meses: reparto de mensualidades
  Ubicacion.kt        Ubicación de cada compra en persona + dirección escrita
  Ajustes.kt          Preferencias (huella, ocultar cantidades)
  Respaldo.kt         Respaldo semanal a Google Drive
  data/Db.kt          Base de datos (Room)
  ui/                 Pantallas
  widget/             Widget + ventana de captura rápida
app/src/test/         Pruebas de los lectores y de las fechas
herramientas/         Scripts Python para estados de cuenta
estados/              Tus PDFs y movimientos.csv   (NO se sube a git)
respaldos/            Copias de tu base de datos   (NO se sube a git)
```

## Compilar e instalar

Necesitas JDK 17 y el Android SDK (o Android Studio, que trae ambos).

```bash
echo "sdk.dir=/ruta/a/android-sdk" > local.properties     # solo si no usas Android Studio
export JAVA_HOME=/ruta/a/jdk17
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Para ADB inalámbrico: en el teléfono, Opciones de desarrollador → Depuración inalámbrica →
"Vincular con código" y luego `adb pair IP:PUERTO` y `adb connect IP:PUERTO`.

**Después de instalar**, abre la app y dale permiso de **acceso a notificaciones** (Ajustes →
Notificaciones → Acceso a notificaciones → Mis Finanzas). Sin eso no registra nada. Para el mapa, dale
ubicación **"Permitir todo el tiempo"**: el pago llega con la app cerrada.

### Mapa (opcional): tu propia llave de Google Maps

Sin llave la app funciona igual, solo no aparecen la pantalla "Mapa" ni "Elegir en el mapa".

1. En [console.cloud.google.com](https://console.cloud.google.com) crea un proyecto y habilita
   **Maps SDK for Android** (el mapa en apps Android no tiene costo).
2. **APIs y servicios → Credenciales → Crear credenciales → Clave de API.**
3. Restríngela: *Apps para Android* con el paquete `com.misfinanzas` y el SHA-1 de tu llave de firma
   (`keytool -list -v -keystore ~/.android/debug.keystore -storepass android`), y *Restringir clave* solo a
   Maps SDK for Android.
4. Ponla en `local.properties` (no se sube a git):
   ```
   MAPS_API_KEY=tu_llave
   ```
5. Vuelve a compilar.

### Respaldo en Drive (opcional)

En el mismo proyecto de Google Cloud:

1. Habilita **Google Drive API**.
2. **Google Auth Platform → Comenzar**: tipo *Externo*, tu correo de contacto. En *Público* agrega tu cuenta
   como usuario de prueba (o publica la app para que el permiso no caduque a los 7 días).
3. **Clientes → Crear cliente → Android** con el paquete `com.misfinanzas` y el SHA-1 de tu llave de firma.
4. En la app: menú → **Respaldo en Drive** → *Usar el WiFi actual* (estando en casa) → *Conectar Drive*.

No hace falta copiar ningún ID a la app: Google la reconoce por el paquete y la huella.

## Adaptarla a ti

- **Tus tarjetas**: dalas de alta en la pantalla Tarjetas con su día de corte y día límite de pago.
- **Apodo de tarjeta en Wallet**: si en Google Wallet le pusiste un nombre a una tarjeta, agrégalo en
  `Tarjetas.canonica` de `Parsers.kt` para que se agrupe con el banco correcto.
- **Categorías**: agrega en `Categorias.kt` los comercios que uses seguido. El orden importa (por ejemplo,
  "OXXO GAS" debe ir a Gasolina antes de que "OXXO" lo mande a Súper).
- **Otro banco**: escribe un `object` nuevo que implemente `FuenteNotificacion` en `Parsers.kt`, agrégalo a
  `Fuentes.todas`, su paquete a `<queries>` en `AndroidManifest.xml`, y una prueba en `ParsersTest.kt`.
  Las notificaciones que juntes en "Recolectadas" sirven de ejemplo para escribirlo.

## Tipos de movimiento

`gasto`, `ingreso`, `transferencia`. Solo `gasto` suma en "Gastado este mes". Los pagos de tarjeta y
movimientos entre tus cuentas son transferencias.

## Estados de cuenta (opcional, mensual)

Sirve para cargar lo que no llegó por notificación. Requiere Python 3 con `pdfplumber`
(`pip install pdfplumber`).

```bash
# 1. Pon cada PDF en estados/<banco>/  (carpetas: rappi, nu, bbva, banamex, mercadopago)
#    Fíate del periodo impreso dentro del PDF, no del nombre del archivo.
# 2. PDF → texto (solo procesa los que no tienen .txt)
python -I herramientas/pdf_a_texto.py estados
# 3. Extraer y cuadrar contra los totales del banco → estados/movimientos.csv
python -I herramientas/extraer_estados.py estados/
# 4. Copiar la base del teléfono (también los archivos -wal y -shm) y simular la carga
adb exec-out run-as com.misfinanzas cat databases/finanzas.db > respaldos/finanzas.db
adb exec-out run-as com.misfinanzas cat databases/finanzas.db-wal > respaldos/finanzas.db-wal
python -I herramientas/importar_estados.py estados/movimientos.csv respaldos/finanzas.db
# 5. Si todo se ve bien: generar el archivo y dejar que la app lo cargue al abrirse
python -I herramientas/importar_estados.py estados/movimientos.csv respaldos/finanzas.db --exportar importar.json
adb exec-in run-as com.misfinanzas sh -c 'cat > files/importar.tmp' < importar.json
adb shell run-as com.misfinanzas mv files/importar.tmp files/importar.json
adb shell am start -n com.misfinanzas/.MainActivity     # logcat: "Registro: Importados N movimientos"
```

Para revisar un estado antes de cargarlo (cuadre, intereses y comisiones, duplicados, cargos que la app no
tenía, mensualidades, comercios nuevos):

```bash
python -I herramientas/revisar_estado.py estados/<banco>/<archivo>.txt respaldos/finanzas.db
```

En el paso 3 cada línea debe decir `[OK]`. Un `[DIF]` solo es aceptable si lo explican mensualidades,
intereses o ajustes; si no, tu banco tiene un formato que el lector no entiende.

**Qué se carga** (con `fuente = 'estado'`, a las 12:00):

| En el estado | En la app |
|---|---|
| compra | gasto, categoría según `Categorias.kt` |
| reembolso, cashback | ingreso |
| interés, IVA, comisión | gasto, categoría "Intereses y comisiones" |
| pago de tarjeta, mensualidad ("02 DE 03"), traspaso a meses, disposición a Cuenta Nu | no se carga |

Se puede repetir sin duplicar: cada movimiento lleva una llave única, y lo que la app ya capturó por
notificación (misma tarjeta y monto, ±2 días) se salta.

Nota: el lector de Mercado Pago asume que el estado es de 2026 (`anio = 2026` en `extraer_estados.py`);
cámbialo si es de otro año.

## Cuidado con tus datos

- **Nunca reemplaces el archivo de la base en el teléfono**: para cargas masivas usa `files/importar.json`.
- Antes de instalar una versión que cambie la base (migración), haz una copia a `respaldos/`.
- `estados/`, `respaldos/`, `*.db`, `*.csv`, `*.json` y `*.pdf` están en `.gitignore`: no los subas.
- Tu llave de Google Maps va en `local.properties`, que también está en `.gitignore`. Nunca la pongas en el código.
