# Wario Land: Shake it! — Android

Port Android del runtime (WarioRecomp) con:

- **Ultrawide sin estirar:** el juego se dibuja a la proporción de la pantalla (aspecto "fill").
- **Controles táctiles editables:** joystick, 1, 2, A, +, − y Shake. Cada control se puede mover,
  cambiar de tamaño (50–200 %) y de opacidad (20–100 %). Los cambios se guardan en el teléfono.
  Para editar, toca el botón ⚙ de arriba a la izquierda; "Restaurar" vuelve a la disposición inicial.
- **Build solo con GitHub Actions:** `.github/workflows/android.yml` (ejecutar a mano con *Run workflow*).

## Requisitos

1. Crea el secreto `WARIO_DATA_URL` con un enlace directo a un `.zip` de la carpeta DATA de tu disco
   (`sys/main.dol`, `sys/boot.bin` y `files/`). El juego no va en el repositorio ni en el APK.
2. Ejecuta el workflow "Android APK". Al terminar, descarga el artefacto `wario-land-shake-it-apk`.

## Estructura

| Ruta | Contenido |
|---|---|
| `app/src/main/java/com/wario/shakeit/GameActivity.java` | Activity (extiende SDLActivity), variables del runtime y overlay |
| `app/src/main/java/com/wario/shakeit/TouchOverlay.java` | Controles táctiles, edición y envío al runtime |
| `app/src/main/assets` ← `runtime/assets` | DSP, caché de pipeline, NAND de arranque y CA (se desempaquetan al primer inicio) |
| `runtime/src/android/android_bridge.cpp` | JNI: `nativeSetTouchState`, `nativeApplyDisplaySettings` |

## Pendiente de verificar en el teléfono

- Cómo llega la carpeta del juego (`sys/` y `files/`) al runtime en Android (`dvd_root` en la configuración).
  Hoy el runtime la busca con `RuntimeConfigFile`; la app todavía no la copia ni la configura.
- Que `libSDL3.so` se empaquete junto a `libmain.so` con el build de Aurora para Android.
- Rendimiento y backend gráfico (Dawn/Vulkan) en GPUs Mali.
