# Kardex Clientes · APK (WebView)

App Android que envuelve `kardex.html` (empaquetado en `app/src/main/assets/`)
y queda conectada a tu Firebase (config ya embebida en el HTML).

## Qué incluye
- WebView con JavaScript, DOM storage e IndexedDB (Firebase Auth + Firestore funcionan).
- Selector de archivos para **importar** Excel/JSON.
- Guardado de **descargas** (Excel/JSON) en la carpeta *Descargas* del teléfono.
- Botón atrás del sistema navega dentro del WebView.

## Cómo obtener el APK (solo GitHub Actions)
1. Crea un repositorio en GitHub y sube **todo** el contenido de esta carpeta.
2. Ve a la pestaña **Actions** → workflow **Build APK** → se ejecuta solo al hacer push
   (o lánzalo a mano con *Run workflow*).
3. Al terminar, descarga los artefactos:
   - **kardex-debug-apk** → `app-debug.apk` (instalable de inmediato; recomendado para probar).
   - **kardex-release-apk** → `app-release-signed.apk` (firmado, también instalable).
4. Pásalo al teléfono e instálalo (habilita "instalar apps desconocidas").

## Datos del proyecto
- Package / applicationId: `com.arrazolapp.kardex`
- minSdk 24 · targetSdk 34 · compileSdk 34
- AGP 8.5.2 · Gradle 8.7 · Kotlin 1.9.24 · JDK 17

## Para actualizar el contenido de la app
Reemplaza `app/src/main/assets/kardex.html` por la nueva versión, sube el cambio
y vuelve a correr Actions. (Sube `versionCode`/`versionName` en `app/build.gradle`
si quieres distinguir versiones.)
