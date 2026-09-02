# RodoCam — Auditoría técnica exhaustiva y hoja de ruta hacia paridad con Pixel Camera

> **Rol:** Principal Android Software Architect / especialista CameraX–Camera2
> **Base auditada:** `main @ 68b0c8c` (fork de Google *JetpackCamera* — JCA)
> **Dispositivo objetivo primario:** Samsung Galaxy S21 FE (Exynos 2100 / SD 888, Camera2 `FULL`, 3 lentes traseras 12 MP W / 12 MP UW / 8 MP Tele 3x + frontal 32 MP)
> **Meta:** app equivalente a la cámara del **Google Pixel 10 Pro XL**, publicable en Play Store, compatible con cualquier dispositivo y con acceso a todas las lentes/capacidades.
> **Stack:** CameraX 1.6.2 · Compose BOM 2026.08.00 · Material3 1.4.0 (Expressive) · Kotlin 2.2.0 · AGP 9.3.1 · Gradle 9.6.1 · Hilt 2.59.2 (kapt) · compileSdk 37.1 · minSdk 24 · targetSdk 35

---

## Nota previa: sobre descompilar/“reverse-engineering” de Pixel Camera, GCam ports y Samsung Camera

**No puedo descargar, descompilar ni analizar el bytecode de esas apps**, y recomiendo no hacerlo en este proyecto:

1. **Legal / licencia.** Google Camera (Pixel Camera), Samsung Camera y los puertos GCam (que son binarios modificados de Google Camera) son software propietario con cláusulas explícitas anti-ingeniería-inversa (Google Play ToS §, Samsung EULA). Aunque la intención sea “solo estudiar”, reproducir su estructura a partir del desensamblado contamina legalmente el código que después subirás a Play Store (riesgo de retirada por DMCA/“copyright infringement” y de suspensión de la cuenta de desarrollador).
2. **Técnico.** Este sandbox no tiene acceso a APKs de Play ni herramientas (`apktool`, `jadx`); además, el 90 % del valor de Pixel Camera está en bibliotecas nativas HDR+/Night Sight (`libgcam`, `libgcastartup`) y modelos TFLite ofuscados, no en el código Java/Kotlin observable. No obtendríamos nada replicable de forma legítima.
3. **Alternativa mejor y 100 % legal** (es lo que aplico en la Fase 2–4 de este informe):
   - **Documentación pública de features**: blog de Google (Pixel 10: Camera Coach, Pro Res Zoom 100x, Auto Best Take, Video Boost, Night Sight, Pro Controls: ISO/shutter/WB/focus, Dual exposure, Top Shot, Long Exposure, Action Pan, Macro Focus, Astro, 8K30/4K120 video, Audio eraser, 10-bit HDR HLG). Podemos replicar la **UX y los controles** legítimamente; la UX no es patentable/protegida por copyright en su idea, solo en sus assets concretos.
   - **Proyectos open-source con licencia permisiva** cuyo código sí podemos leer y adaptar:
     - **Open Camera** (GPL-3 — solo como referencia, NO copiar código): manual ISO/shutter/focus, RAW, HDR bracketing, focus peaking, histograma, zebras.
     - **FreeDcam** (GPL-3 — referencia): acceso a *vendor tags* Samsung/Qualcomm y multi-lente física.
     - **GrapheneOS Camera** (MIT — copiable): arquitectura CameraX limpia, QR, multi-cámara.
     - **Fossify Camera** (GPL-3 — referencia).
     - **AOSP `Camera2Basic`/`Camera2Raw`/`Camera2Video`** (Apache-2.0 — copiable): RAW DNG con `DngCreator`, sesiones de alta velocidad 120/240 fps, `CameraConstrainedHighSpeedCaptureSession`.
     - **CameraX propio** (Apache-2.0): `camera-extensions` (Night/HDR/Bokeh del OEM — en el S21 FE Samsung expone Night + Bokeh + HDR vía Extensions), `Camera2Interop`, `ImageCapture.OutputFormat.RAW`/`RAW_JPEG` (disponible desde 1.5), `CAPTURE_MODE_ZERO_SHUTTER_LAG`, `LowLightBoost`.
   - **Camera2 metadata pública del S21 FE** para saber qué expone el HAL: `REQUEST_AVAILABLE_CAPABILITIES` = BACKWARD_COMPATIBLE, MANUAL_SENSOR, MANUAL_POST_PROCESSING, RAW, READ_SENSOR_SETTINGS, BURST_CAPTURE, PRIVATE_REPROCESSING, LOGICAL_MULTI_CAMERA (ID 0 lógico → físicos 2/3/…), CONSTRAINED_HIGH_SPEED_VIDEO (1080p120/240). Los IDs 20/21/22/23 (UW/Tele independientes) solo se exponen a apps del sistema en One UI; la vía correcta multi-lente es **cámara lógica + `setPhysicalCameraId` / zoom ratio < 1.0 y > 3.0** (CameraX lo abstrae con `ZoomState`).

Por lo tanto: **la paridad con Pixel Camera se construye desde las APIs públicas y la UX documentada, no desde su APK**. El resto del documento lo asume.

---

# 1. INFORME DE AUDITORÍA Y BÚSQUEDA DE ERRORES (LINE-BY-LINE)

Leyenda de severidad: 🔴 **Crítico** (crash/leak/pérdida de datos) · 🟠 **Alto** (mal funcionamiento visible) · 🟡 **Medio** (deuda técnica / calidad) · 🔵 **Bajo** (estilo / limpieza).

## 1.1 Errores críticos y de runtime

### A. Capa `core/camera`

| # | Sev | Archivo:línea | Hallazgo | Impacto | Corrección |
|---|-----|---------------|----------|---------|------------|
| C-01 | 🔴 | `CameraXCameraSystem.kt:116-120` | `lateinit var cameraProvider` y `systemConstraints` se inicializan en `initialize()` (suspend). Cualquier método público (`getCurrentSettings`, `takePicture`, `setZoomRatio`, …) llamado antes/después de un fallo de `initialize()` lanza `UninitializedPropertyAccessException`. | Crash al abrir la app si `ProcessCameraProvider.getInstance` falla (p.ej. HAL bloqueado por otra app en S21 FE, `CameraAccessException MAX_CAMERAS_IN_USE`). | Convertir a `StateFlow<InitState>` (`Uninitialized/Initializing/Ready/Failed(cause)`), o `CompletableDeferred`, y que todos los métodos `await`en el estado `Ready` o devuelvan `Result.failure`. Propagar `Failed` a la UI con snackbar + botón “Reintentar”. |
| C-02 | 🔴 | `CameraXCameraSystem.kt:498, 507` | `currentSettings.value!!` dentro de `runCameraSession`. | NPE si el flujo de settings se reinicia (rotación durante `initialize`) → crash. | Capturar `val settings = currentSettings.value ?: return` o modelar `currentSettings` como `StateFlow<CameraAppSettings>` no nulo con valor por defecto. |
| C-03 | 🔴 | `CameraXCameraSystem.kt:553-565` | `takePicture` lanza `RuntimeException("Attempted to capture image while ImageCapture use case is null")`. | Crash real cuando el usuario pulsa disparo en modo VIDEO_ONLY o mientras se re-bindea tras cambiar lente/resolución (ventana de ~200–600 ms en la que `imageCaptureUseCase == null`). | Devolver `ImageCaptureEvent.ImageCaptureError(IllegalStateException)`; en UI deshabilitar el botón durante `CameraState.Type.OPENING`/rebinding (usar `cameraInfo.cameraState`). |
| C-04 | 🔴 | `CameraXCameraSystem.kt:510-515` | `finally { cameraProvider.unbindAll() }` — TODO(tm) reconocido. `unbindAll()` desde un `finally` que se ejecuta al cancelar la coroutine puede correr en un hilo que no es Main → `IllegalStateException: Not in application's main thread` en `LifecycleCamera.unbind`. | Crash intermitente al salir de la pantalla de preview / al ir a Settings. | `withContext(Dispatchers.Main.immediate) { cameraProvider.unbindAll() }` dentro de `NonCancellable`. Mejor aún: un único `LifecycleOwner` propio (`CameraLifecycleOwner`) por sesión y llamar `lifecycle.currentState = DESTROYED` en vez de `unbindAll()` global. |
| C-05 | 🔴 | `CameraSession.kt:179` | `cameraProvider.unbindAll()` + rebind en **cada** emisión de `transientSettings` que altere `useCases`. El `collectLatest` cancela el bloque anterior pero no espera a que el `SurfaceRequest` viejo sea liberado → el `CameraXViewfinder` puede quedar con un `Surface` huérfano (leak de `SurfaceTexture`, pantalla negra 1–2 s). | Pantalla negra / flicker al cambiar flash→HDR→aspect ratio rápidamente; en S21 FE se reproducen `CameraDevice.StateCallback.onError(ERROR_CAMERA_DEVICE)` con bindings rápidos. | Diferenciar *transient* real (flash, zoom, torch, AF) de *perpetual* (lente, resolución, HDR, estabilización, aspect). Solo los segundos deben rebindear. Los primeros van por `CameraControl`/`Camera2CameraControl` sin tocar el binding. Debounce de 150 ms en cambios perpetuos. |
| C-06 | 🔴 | `CameraSession.kt:762` | `throw IllegalStateException` cuando `StabilizationMode.AUTO` llega a `getVideoCapture`/`setVideoStabilization`. El comentario dice que AUTO debe resolverse antes, pero `CameraAppSettings` por defecto es `AUTO` y en dispositivos sin `PREVIEW_STABILIZATION` (S21 FE con API 34 la soporta, pero API 33 no) el resolvedor devuelve AUTO. | Crash en arranque en dispositivos con Android ≤ 13 o sin stabilization modes. | Resolver AUTO → `ON`/`OFF` en función de `CameraConstraints.supportedStabilizationModes` en `PerpetualSessionSettings` **antes** de construir use cases; nunca lanzar, degradar a OFF con log. |
| C-07 | 🟠 | `CameraSession.kt:652-664` | `getWidthFromCropRect` calcula `bottom - top` y `getHeightFromCropRect` calcula `right - left` (semántica invertida). Funciona hoy por coincidencia porque ambos se usan de forma simétrica en `:274-277`. | Aspect ratio incorrecto del `CaptureResult`/thumbnail cuando el sensor no es cuadrado y se rota el crop. | Renombrar/corregir (`width = right-left`, `height = bottom-top`) y añadir test unitario. |
| C-08 | 🟠 | `CameraSession.kt:1366` | `catch (_: Exception) {}` alrededor del cierre del `Recording`/efecto. | Se ocultan `IOException` de MediaStore (disco lleno, URI revocado) → el usuario cree que el vídeo se guardó. | Capturar tipos concretos, loggear y emitir `VideoRecordingEvent.Error(cause)`. |
| C-09 | 🟠 | `CameraSession.kt:413` | `// todo(): handle torch on Auto FlashMode` — en `FlashMode.AUTO` durante vídeo no se activa torch; la rama cae a OFF. | Vídeo oscuro en interior con flash AUTO (Pixel Camera enciende torch si `lux < umbral`). | Suscribirse a `CameraInfo.exposureState`/luminancia media del `ImageAnalysis` (Fase 3 histograma) y `enableTorch(true)` cuando `FlashMode.AUTO && isLowLight`. |
| C-10 | 🟠 | `CameraSession.kt` (`getImageCapture`) | ImageCapture se construye sin `setCaptureMode(CAPTURE_MODE_ZERO_SHUTTER_LAG)`, sin `setOutputFormat(RAW/RAW_JPEG)`, sin `setJpegQuality`, sin `setResolutionSelector` por lente. | Latencia de disparo 300–500 ms en S21 FE (Pixel ≈ 0 con ZSL); imposibilidad de RAW. | Ver Fase 2. |
| C-11 | 🟠 | `CameraSession.kt:1024` | Permiso `RECORD_AUDIO` se lee una sola vez al preparar `PendingRecording`. Si el usuario revoca el permiso en segundo plano (Android 13 “auto-revoke”) y vuelve, `withAudioEnabled()` lanza `SecurityException`. | Crash al iniciar grabación. | Comprobar `checkSelfPermission` inmediatamente antes de `withAudioEnabled()` y envolver en `try/catch SecurityException` → grabar sin audio + snackbar. |
| C-12 | 🟠 | `CameraXCameraSystem.kt:578` | `MIME_TYPE = "image/jpeg"` hardcodeado aunque `ImageOutputFormat` pueda ser `JPEG_ULTRA_HDR` (sigue siendo JPEG, OK) — pero bloquea HEIC/DNG futuros y el nombre de archivo `JCA-*.jpg` está en `contentValues` fijo. | Ficheros con extensión incorrecta al añadir HEIC/RAW. | Derivar MIME + extensión del `ImageOutputFormat` (`image/jpeg`, `image/heic`, `image/x-adobe-dng`). |
| C-13 | 🟠 | `CameraXCameraSystem.kt:636-639` | Post-procesadores (`ImagePostProcessor`) se ejecutan **síncronamente** dentro del callback `onCaptureSuccess`/`onImageSaved` (hilo del executor de CameraX). | Bloquea el pipeline de captura siguiente (ráfagas), jank si el procesador es pesado (Ultra HDR gainmap). | Ejecutar en `Dispatchers.Default` con `withContext`, emitir progreso. |
| C-14 | 🟠 | `CameraXCameraSystem.kt:172-178` + `CameraExt.kt` (`LensFacing.toCameraSelector`, `appLensFacing`) | Solo `DEFAULT_BACK_CAMERA`/`DEFAULT_FRONT_CAMERA`; `appLensFacing` lanza `IllegalArgumentException` para `LENS_FACING_EXTERNAL`. `FIXED_FRAME_RATES = {15,30,60}`. | (a) Dispositivos con cámara USB/externa (tablets, Android TV boxes) → crash al enumerar. (b) No se descubren UW/Tele físicas ni 120 fps. Bloqueante para “acceso a todas las lentes”. | Enumerar `cameraProvider.availableCameraInfos`, mapear a un modelo `CameraLens(id, facing, focalLengthMm, isLogical, physicalIds, zoomRange, capabilities)`; tratar EXTERNAL como `LensFacing.EXTERNAL`; derivar frame rates de `CameraInfo.supportedFrameRateRanges` + `StreamConfigurationMap.highSpeedVideoFpsRanges`. |
| C-15 | ✅ (Fase 2) | `FocusMetering.kt` | `FocusMeteringAction` con solo `FLAG_AF`, auto-cancel 2500 ms, sin `AE`/`AWB`, sin lock. | Tap-to-focus no ajusta exposición (Pixel hace AF+AE+AWB y muestra slider de EV; long-press = AE/AF lock). | `FLAG_AF or FLAG_AE or FLAG_AWB`, `disableAutoCancel()` en modo lock, y `Camera2CameraControl` para `CONTROL_AE_LOCK`. |
| C-16 | 🟡 | `CameraSession.kt:698-705` | Comentario obsoleto sobre crash (“this will crash if…”) que ya no aplica al código actual. | Confunde a mantenedores. | Eliminar/actualizar. |
| C-17 | 🟡 | `ConcurrentCameraSession.kt` | Sesión concurrente hace su propio `unbindAll()` y no comparte el `CameraLifecycleOwner` → al alternar single↔concurrent hay doble unbind. | Pantalla negra al desactivar dual-cam. | Unificar ciclo de vida (ver C-04). |

### B. Capa `ui/controller`, `feature/preview`

| # | Sev | Archivo:línea | Hallazgo | Impacto | Corrección |
|---|-----|---------------|----------|---------|------------|
| U-01 | 🔴 | `CameraControllerImpl.kt:58, 79, 84` | `startCamera()` llama a `stopCamera()` (que hace `job?.cancel()` **sin `join`**) y lanza un nuevo `runCamera`. La sesión previa sigue ejecutando su `finally { unbindAll() }` mientras la nueva ya hizo `bindToLifecycle` → la nueva sesión queda desbindeada (pantalla negra). Además `// TODO(yasith): Handle Exceptions from binding use cases` — ninguna excepción de `bindToLifecycle` (`IllegalArgumentException: No supported surface combination`) se captura. | Pantalla negra tras rotación / volver de Settings; crash en dispositivos `LEGACY`/`LIMITED` con combinaciones Preview+ImageCapture+VideoCapture+ImageAnalysis (4 streams). | `stopCamera()` → `job?.cancelAndJoin()` en `suspend`; en `startCamera` usar `runCatching { cameraSystem.runCamera() }.onFailure { snackbar(R.string.camera_bind_error) }`. Degradar use cases si `LIMITED` (quitar `ImageAnalysis`). |
| U-02 | 🔴 | `CaptureControllerImpl.kt` (`stopVideoRecording`) | Cancela `recordingJob` (propietario del callback `VideoRecordEvent`) **antes** de enviar `stopVideoRecording()` → el `Finalize` event nunca llega, el `ImageWell` no se actualiza y `MediaStore` puede quedar con `IS_PENDING=1` (vídeo invisible en galería). | Vídeos “perdidos” (pendientes) en el S21 FE al detener grabación. | Invertir orden: `cameraSystem.stopVideoRecording()` → esperar `Finalize` (con timeout 5 s) → cancelar job. |
| U-03 | 🟠 | `CaptureControllerImpl.kt` (`startVideoRecording`) | Solo captura `IllegalStateException`; `SecurityException` (audio) e `IOException`/`RuntimeException` de `Recorder` no. | Crash. | Capturar `Exception` genérica con mapeo a mensajes de usuario. |
| U-04 | 🟠 | `PreviewScreen.kt:399-400, 417-430` | `val onTapToFocusLambda = cameraController?.let { it::tapToFocus }` crea una **nueva referencia de función en cada recomposición**; se usa como clave de `remember(...)` para el lambda del viewfinder → el `remember` se invalida en cada frame, se recrea el `Modifier.pointerInput` y se pierden gestos en curso (pinch a mitad, doble tap). Además la clave incluye `surfaceRequest`. | Zoom por pinch “errático”/saltos; tap-to-focus que a veces no dispara. | `val onTapToFocus = remember(cameraController) { { x: Float, y: Float -> cameraController?.tapToFocus(x, y) } }` y usar `rememberUpdatedState` dentro de `pointerInput(Unit)`. |
| U-05 | 🟠 | `PreviewScreen.kt:227-237` | Se consulta `READ_EXTERNAL_STORAGE` para el ImageWell en todas las APIs. En API ≥ 33 ese permiso siempre es *denied* → el ImageWell se muestra vacío/deshabilitado en Android 13+ (S21 FE). Manifest (`:35-41`) declara `READ_EXTERNAL_STORAGE maxSdk=28` y `READ_MEDIA_* minSdk=33`; **API 29–32 sin permiso alguno**. | Miniatura de última foto no aparece en Android 13/14/15; en Android 10–12 crash `SecurityException` al abrir la galería. | Usar `READ_MEDIA_VISUAL_USER_SELECTED`/`READ_MEDIA_IMAGES` (≥33), `READ_EXTERNAL_STORAGE` (≤32), o mejor: no pedir permiso y abrir la última captura por su propio `Uri` (la app es *owner* del `MediaStore` item). |
| U-06 | 🟠 | `PreviewViewModel.kt:123-156` | `cameraPropertiesJSON` es un `String` privado actualizado por callback (`:138`) pero `debugUiState(...)` (`:156`) lo lee **una sola vez** en la construcción del flujo → siempre `""`. Estado que no recompone. | Panel de debug muestra JSON vacío. | `MutableStateFlow<String>` y combinarlo en el flujo de `uiState`. |
| U-07 | 🟠 | `PreviewViewModel.kt` | `lateinit var externalUriProgress` sin comprobación `isInitialized` en la ruta no-externa. | Crash al recibir intent `ACTION_VIDEO_CAPTURE` sin `EXTRA_OUTPUT`. | Nullable + `?.`. |
| U-08 | 🟡 | `PreviewScreen.kt` (`ZoomStateManager`) | `remember { ZoomStateManager(initialRange) }` con el rango de la lente inicial; al flip a frontal (rango 1–8x) mantiene 0.5–10x. | El slider permite pedir 0.5x en frontal → CameraX clampa pero la UI muestra valor falso. | `remember(lensFacing, zoomRange) { … }` o `LaunchedEffect(zoomRange) { manager.updateRange() }`. |
| U-09 | 🟡 | `PreviewViewModel.kt` | Controladores (`Capture/Camera/Zoom/QuickSettings/ImageWell`) construidos inline en el ViewModel en vez de inyectados por Hilt → no testeables, duplicación de scopes. | Deuda. | Proveer vía `@ViewModelScoped` en un módulo `ControllerModule`. |

### C. Capa `ui/components`, `feature/settings`

| # | Sev | Archivo:línea | Hallazgo | Impacto | Corrección |
|---|-----|---------------|----------|---------|------------|
| K-01 | 🔴 | `ZoomBarComponents.kt:200` | `TODO("Zoom button with value $buttonValue needs a test tag")` — `TODO()` de Kotlin **lanza `NotImplementedError` en runtime**. Se ejecuta para cualquier nivel de zoom que no sea `<1, 1, 2, 5`. | En cuanto se añada 10x (S21 FE tiene 3x óptico → botón 3x) la app crashea. Hoy mismo: `ZoomControlUiStateAdapter` solo emite 0.5/1/2/5, así que el crash está latente. | Test tag genérico `"ZoomButton_${buttonValue}"`. |
| K-02 | 🔴 | `CaptureScreenComponents.kt:757, 773` (`StabilizationIcon`) | Dos `TODO(...)` en ramas `else` de `when(stabilizationMode)` → crash al mostrar icono para `AUTO`/`OPTICAL`+`HQ` combinados. | Crash en preview con estabilización óptica (el S21 FE tiene OIS en W y Tele). | Iconos por modo + rama `else -> Icons.Outlined.VideoStable`. |
| K-03 | 🔴 | `CaptureButtonComponents.kt:124` | `else -> TODO("Keycode not assigned to CaptureSource")` en el `onKeyEvent` del botón de disparo. | Cualquier tecla hardware no mapeada (volumen en algunos OEM, botón Bixby remapeado, teclado BT) con el foco en el botón → crash. | `else -> return false`. |
| K-04 | 🔴 | `CaptureModeUiStateAdapter.kt:325` | `CaptureMode.STANDARD -> TODO()` | `STANDARD` es el **valor por defecto** de `CameraAppSettings`; solo no crashea porque la rama superior lo transforma antes. Latente. | Implementar rama explícita. |
| K-05 | 🔴 | `SettingsComponents.kt:324, 436, 536, 556` | `TODO(...)` en `description` cuando un `UiState` es `Disabled` (aspect ratio, LLB priority) y en mapeo de FPS (test tag y `else -> TODO("Unhandled Target FPS")`). | Al añadir 120 fps o cuando el adaptador marque una opción como *Disabled* → crash en Settings. | Sustituir por `stringResource(R.string.disabled_generic)` / tags dinámicos. |
| K-06 | 🟠 | `SettingsScreen.kt:107` | `viewModel.setGrantedPermissions(permissionStates)` **en el cuerpo de la composición** (efecto secundario sin `LaunchedEffect`) → se ejecuta en cada recomposición y actualiza un `StateFlow` → recomposición → bucle potencial. | Consumo de CPU/jank en Settings; en modo debug, `Recomposition loop` warnings. | `LaunchedEffect(permissionStates) { viewModel.setGrantedPermissions(permissionStates) }`. |
| K-07 | 🟠 | `CaptureScreenComponents.kt:531-652` (`PreviewDisplay`) | `rememberTransformableState` (deprecado); `Log.d("TAG", …)` literal; `implementationMode = if (SDK_INT > 24) EXTERNAL else EMBEDDED` (debería ser `>= 24`/o preferir `EMBEDDED` cuando hay efectos de zoom animado, porque `SurfaceView` no soporta `graphicsLayer` transforms). El pinch usa `zoom` incremental sin `coerceIn` del rango real (lo hace ZoomStateManager, OK) pero no consume el gesto → el `verticalDrag` de los QuickSettings compite. | Gestos erráticos (pinch abre la bottom-sheet). | `Modifier.pointerInput { detectTransformGestures(panZoomLock = true) }` + `awaitEachGesture` con `consume()`; `EMBEDDED` cuando `isConcurrent || hasEffect`. |
| K-08 | 🟡 | `ZoomBarComponents.kt:164` | Strings `"selected"/"not selected"` hardcodeadas en `contentDescription`. | A11y/i18n. | `stringResource`. |
| K-09 | 🟡 | `ZoomControlUiStateAdapter.kt` | Niveles fijos 0.5/1/2/5. | En S21 FE la UX correcta es 0.6 / 1 / 3 (óptico) / 10 (digital max); en Pixel 10 Pro XL 0.5 / 1 / 2 / 5 / 10 / 20 / 30 / 100. | Derivar botones de `cameraInfo` (focal lengths de las físicas + `maxZoomRatio`). |
| K-10 | 🟡 | `QuickSettingsScreen.kt` | Filas de HDR/aspect/capture mode ignoran `Disabled` reasons de forma silenciosa (se muestran habilitados y al tocar no ocurre nada). | Botones “dummy”. | Mostrar `alpha 0.38` + toast con la razón (`DisabledReason`). |

### D. `app/`, manifest, DI

| # | Sev | Archivo:línea | Hallazgo | Impacto | Corrección |
|---|-----|---------------|----------|---------|------------|
| A-01 | 🟠 | `AndroidManifest.xml:68` | Solo `IMAGE_CAPTURE` en `<intent-filter>`, pero `PreviewViewModel`/`JcaApp` manejan `VIDEO_CAPTURE` y `STILL_IMAGE_CAMERA`. | La app no aparece como selector para grabar vídeo desde otras apps (WhatsApp, etc.). Play Store espera consistencia. | Añadir `android.media.action.VIDEO_CAPTURE`, `STILL_IMAGE_CAMERA`, `VIDEO_CAMERA` + `<queries>`. |
| A-02 | 🟠 | `AndroidManifest.xml:64` | `screenOrientation="nosensor"` → bloquea rotación de UI; aceptable para cámara, pero en tablets/foldables (Play Store “large screen” policy) **Google penaliza** orientación bloqueada. | Rechazo/baja calidad en Play Console “Large screen quality”. | `fullUser` + manejo de `DisplayRotation` ya existente, o `nosensor` solo en `smallestScreenWidthDp < 600` vía `<activity-alias>`. |
| A-03 | 🟡 | `MainActivity.kt:306` | `Intent(Camera.ACTION_NEW_PICTURE)` broadcast **deprecado desde API 24** y sin efecto en ≥ 24 (`MediaStore` ya notifica). | Warning de lint; ruido. | Eliminar. |
| A-04 | 🟡 | `MainActivity.kt` | `@RequiresApi(M)` sobre `onCreate` con `minSdk 24` — redundante. | Limpieza. | Eliminar. |
| A-05 | 🔴 (Play) | `app/build.gradle.kts` (`buildTypes.release`) | `isMinifyEnabled=true` con solo `proguard-android-optimize.txt`; **sin `proguard-rules.pro` específico para Hilt/kapt + Camera2Interop reflection (`Camera2CameraInfo`) + `atomicfu`**; **sin `signingConfig`**; `versionCode 1 / 0.1.0`. | El APK release firmado con debug key no se puede subir; R8 puede eliminar clases de Camera2Interop. | Ver Fase 1: reglas keep + `signingConfigs.release` leído de env/`keystore.properties` (coherente con el YAML propuesto). |
| A-06 | 🟡 | `di/CameraModule.kt` | `CameraXCameraSystem` `@ActivityRetainedScoped` — correcto; pero `ProcessCameraProvider` se obtiene dentro y no se inyecta → no mockeable. | Testabilidad. | Proveer `ProcessCameraProvider` con `@Provides suspend`/`ListenableFuture` wrapper. |

### E. Build system

| # | Sev | Archivo:línea | Hallazgo | Impacto | Corrección |
|---|-----|---------------|----------|---------|------------|
| B-01 | 🟠 | `build.gradle.kts:27-41` | `installGitHooks` (Copy a `.git/hooks`) se convierte en dependencia de **todas** las tareas vía `taskGraph.whenReady`. Incompatible con *configuration cache* (accede a `rootDir/.git` en ejecución) y falla/ensucia en CI (checkout shallow sin `.git/hooks` escribible) y en Windows. | Warnings de config-cache, tiempo extra en cada build, posibles fallos en CI. | `onlyIf { System.getenv("CI") == null && rootDir.resolve(".git").isDirectory }` y quitar el `whenReady`; ejecutar el hook solo en `prepareKotlinBuildScriptModel`/manual. |
| B-02 | 🟠 | `gradle.properties:13` | `-Xmx8192m` > RAM disponible en `ubuntu-latest` (7 GB) → JVM no arranca o OOM-kill. | Fallo de CI aleatorio. | `-Xmx4g` (+ override en CI vía `GRADLE_OPTS`, ya incluido en el YAML). |
| B-03 | 🟡 | `libs.versions.toml` | Hilt vía **kapt** con Kotlin 2.2 (kapt está en modo mantenimiento; K2 + kapt es lento). | Tiempo de build +40 %. | Migrar a KSP (`com.google.devtools.ksp` 2.2.0-x, Hilt ≥ 2.51 lo soporta). |
| B-04 | 🟡 | `libs.versions.toml` | `compileSdk 37.1` con AGP 9.3.1: correcto, pero `targetSdk 35` → Play exigirá **targetSdk 36** a partir del 31-08-2026 para apps nuevas (política vigente). | Rechazo en Play Console. | `targetSdk = 36` y revisar edge-to-edge forzado (API 35+) — la app ya usa `enableEdgeToEdge`, OK. |
| B-05 | 🔵 | `gradle/init.gradle.kts` | `spotless ratchetFrom origin/main` falla en checkouts sin remote. | CI lint rojo. | `fetch-depth: 0` (incluido en YAML). |

## 1.2 Inventario de deuda declarada (TODO/FIXME)

40 ocurrencias en código no-test. Los 9 `TODO(...)`/`TODO()` **ejecutables** (K-01…K-05) son crashes reales; los otros 31 son comentarios. Se listan en `git grep -n "TODO\|FIXME" -- '*.kt' ':!*test*'`.

## 1.3 Controles desconectados / lógica muerta

- `CameraSystem` no expone ISO/shutter/WB/focus-distance/AE-lock → cualquier UI futura estaría “dummy”. Debe añadirse `setManualExposure`, `setFocusDistance`, `setWhiteBalance`, `lockAeAf`.
- `DebugUiState.cameraPropertiesJSON` (U-06) — estado que no recompone.
- `StabilizationMode.AUTO` — declarado en settings, no resoluble en sesión (C-06).
- `LensFacing.EXTERNAL` — declarado en `CameraExt` solo para lanzar excepción (C-14).
- `ImageOutputFormat` — enum con `JPEG`/`JPEG_ULTRA_HDR` pero MIME/extension fijos (C-12).
- `QuickSettings` `Disabled` reasons no llegan al usuario (K-10).
- `FIXED_FRAME_RATES` 15/30/60 — 15 no existe como opción en UI; 120 (que el S21 FE soporta a 1080p) no existe.

## 1.4 Manejo de permisos y excepciones

- Permisos: `CAMERA` y `RECORD_AUDIO` correctos vía `PermissionsScreen`; **almacenamiento** mal segmentado (U-05); no hay manejo de `shouldShowRequestPermissionRationale=false` → “Abrir ajustes”.
- Excepciones: sin captura en `bindToLifecycle` (U-01), `takePicture` (C-03), `Recorder.start` (U-03), `ProcessCameraProvider.getInstance` (C-01). No existe un `CameraErrorHandler` central ni `CrashReporting` (recomendado: Firebase Crashlytics o ACRA para el track de Play interno).

---

# 2. AUDITORÍA DE WORKFLOWS DE CI/CD (GITHUB ACTIONS)

## 2.1 Estado actual — `.github/workflows/build-apk.yml`

| Problema | Detalle |
|----------|---------|
| Solo `workflow_dispatch` | Ningún push/PR se valida automáticamente; los errores llegan tarde. |
| `gradle/actions/setup-gradle@v3` | Deprecado; v4 es la línea mantenida (caché de configuración, cleanup). |
| Sin Android SDK/CMake explícitos | `ubuntu-latest` trae un SDK preinstalado, pero **no** CMake 3.22.1 que exige `core/camera` (`externalNativeBuild`). Puede fallar con “CMake 3.22.1 not found” si la imagen cambia. |
| `-Xmx8192m` heredado | Runner con 7 GB → riesgo de OOM (B-02). |
| Sin `concurrency` | Ejecuciones duplicadas gastan minutos. |
| Sin `timeout-minutes` | Un daemon colgado consume las 6 h máximas. |
| `assembleDebug` genérico | Compila también flavors futuros; mejor `:app:assembleStableDebug`. |
| Sin tests ni lint | No hay puerta de calidad. |
| Sin firma release / mapping | No produce artefacto publicable ni `mapping.txt` para desobfuscar crashes. |
| Sin retención de artefactos | Default 90 días; sin nombre único por SHA. |
| Hook de git en CI | `installGitHooks` intenta escribir en `.git/hooks` (B-01). |

## 2.2 Workflow optimizado — COPIAR ÍNTEGRO en `.github/workflows/build-apk.yml`

> Copia también disponible en el repo: [`docs/ci/build-apk.proposed.yml`](ci/build-apk.proposed.yml)

```yaml
# =============================================================================
#  RodoCam — CI/CD de compilación Android
#  Ruta destino EXACTA (copiar/pegar):  .github/workflows/build-apk.yml
# =============================================================================

name: Build Android APK

on:
  push:
    branches: [ main, genspark_ai_developer ]
    tags: [ 'v*' ]
    paths-ignore:
      - '**.md'
      - 'docs/**'
  pull_request:
    branches: [ main ]
    paths-ignore:
      - '**.md'
      - 'docs/**'
  workflow_dispatch:
    inputs:
      build_release:
        description: 'Compilar también la variante release (requiere secretos de firma)'
        type: boolean
        default: false

permissions:
  contents: read
  actions: read
  checks: write

concurrency:
  group: build-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

env:
  # Sobrescribe el -Xmx8192m de gradle.properties: los runners tienen ~7 GB.
  GRADLE_OPTS: >-
    -Dorg.gradle.jvmargs=-Xmx4g\ -XX:MaxMetaspaceSize=1g\ -XX:+HeapDumpOnOutOfMemoryError\ -Dfile.encoding=UTF-8
    -Dorg.gradle.daemon=false
    -Dorg.gradle.workers.max=2
  KOTLIN_DAEMON_JVM_OPTIONS: -Xmx2g
  # Evita que el task installGitHooks escriba en .git/hooks en CI.
  CI: 'true'

jobs:
  static-analysis:
    name: Lint (spotless / ktlint)
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - name: Checkout
        uses: actions/checkout@v4
        with:
          fetch-depth: 0   # spotless ratchetFrom origin/main necesita historial

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          cache-read-only: ${{ github.ref != 'refs/heads/main' }}
          gradle-home-cache-cleanup: true

      - name: Spotless check
        run: |
          chmod +x gradlew
          ./gradlew --init-script gradle/init.gradle.kts spotlessCheck \
            --no-daemon --stacktrace --console=plain
        continue-on-error: true   # Cambiar a false cuando el código esté formateado

  unit-tests:
    name: Unit tests
    runs-on: ubuntu-latest
    timeout-minutes: 40
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3
        with:
          packages: 'platform-tools cmake;3.22.1'

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          cache-read-only: ${{ github.ref != 'refs/heads/main' }}
          gradle-home-cache-cleanup: true

      - name: Run unit tests (flavor stable, debug)
        run: |
          chmod +x gradlew
          ./gradlew testStableDebugUnitTest \
            --no-daemon --stacktrace --console=plain --continue

      - name: Upload test reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: unit-test-reports
          path: |
            **/build/reports/tests/**
            **/build/test-results/**
          retention-days: 14
          if-no-files-found: ignore

  build:
    name: Build APK
    runs-on: ubuntu-latest
    timeout-minutes: 60
    needs: [ static-analysis ]
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3
        with:
          packages: 'platform-tools cmake;3.22.1'

      - name: Accept SDK licenses
        run: yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true

      - name: Setup Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          cache-read-only: ${{ github.ref != 'refs/heads/main' }}
          gradle-home-cache-cleanup: true
          build-scan-publish: false

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Print environment (diagnóstico)
        run: |
          ./gradlew --version
          echo "ANDROID_HOME=$ANDROID_HOME"
          free -h

      - name: Build Debug APK
        run: ./gradlew :app:assembleStableDebug --no-daemon --stacktrace --console=plain

      - name: Decode keystore
        id: keystore
        if: >-
          (startsWith(github.ref, 'refs/tags/v') || inputs.build_release == true)
          && secrets.ANDROID_KEYSTORE_BASE64 != ''
        run: |
          echo "${{ secrets.ANDROID_KEYSTORE_BASE64 }}" | base64 -d > "$RUNNER_TEMP/release.jks"
          echo "path=$RUNNER_TEMP/release.jks" >> "$GITHUB_OUTPUT"

      - name: Build Release APK (firmado)
        if: steps.keystore.outcome == 'success'
        env:
          RODOCAM_KEYSTORE_PATH: ${{ steps.keystore.outputs.path }}
          RODOCAM_KEYSTORE_PASSWORD: ${{ secrets.ANDROID_KEYSTORE_PASSWORD }}
          RODOCAM_KEY_ALIAS: ${{ secrets.ANDROID_KEY_ALIAS }}
          RODOCAM_KEY_PASSWORD: ${{ secrets.ANDROID_KEY_PASSWORD }}
        run: ./gradlew :app:assembleStableRelease --no-daemon --stacktrace --console=plain

      - name: Verify generated APKs
        run: |
          find app/build/outputs -type f \( -name '*.apk' -o -name 'mapping.txt' \) -print
          test -n "$(find app/build/outputs/apk -name '*.apk' -print -quit)"

      - name: Upload Debug APK
        uses: actions/upload-artifact@v4
        with:
          name: rodocam-debug-apk-${{ github.sha }}
          path: app/build/outputs/apk/stable/debug/*.apk
          if-no-files-found: error
          retention-days: 30

      - name: Upload Release APK + mapping
        if: steps.keystore.outcome == 'success'
        uses: actions/upload-artifact@v4
        with:
          name: rodocam-release-${{ github.sha }}
          path: |
            app/build/outputs/apk/stable/release/*.apk
            app/build/outputs/mapping/stableRelease/mapping.txt
          if-no-files-found: error
          retention-days: 90

      - name: Upload Lint reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: lint-reports
          path: '**/build/reports/lint-results*.html'
          if-no-files-found: ignore
          retention-days: 14

  ci-status:
    name: CI status
    runs-on: ubuntu-latest
    needs: [ static-analysis, unit-tests, build ]
    if: always()
    steps:
      - name: Evaluate
        run: |
          echo "static-analysis: ${{ needs.static-analysis.result }}"
          echo "unit-tests:      ${{ needs.unit-tests.result }}"
          echo "build:           ${{ needs.build.result }}"
          if [ "${{ needs.build.result }}" != "success" ] || [ "${{ needs.unit-tests.result }}" != "success" ]; then
            echo "::error::Un job crítico falló"; exit 1
          fi
```

### 2.3 Cambios en el repo que acompañan al workflow (los haré en Fase 1)

1. **`app/build.gradle.kts`** — `signingConfigs.create("release")` leyendo `RODOCAM_KEYSTORE_PATH/PASSWORD/KEY_ALIAS/KEY_PASSWORD` de `System.getenv()` con fallback a `keystore.properties` (gitignored); `buildTypes.release.signingConfig = signingConfigs.getByName("release")` solo si el keystore existe.
2. **`build.gradle.kts`** — `installGitHooks` con `onlyIf { System.getenv("CI") == null }` y sin `taskGraph.whenReady`.
3. **`gradle.properties`** — `-Xmx4g`.
4. **Secretos de GitHub** que debes crear (Settings → Secrets → Actions): `ANDROID_KEYSTORE_BASE64` (`base64 -w0 release.jks`), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
5. **Branch protection** en `main`: check requerido `CI status`.

---

# 3. PLAN DE ACCIÓN FASEADO

Cada fase termina con: build verde en CI, APK instalable en el S21 FE, checklist de QA manual (abajo) y tag `vX.Y.Z`.

## Fase 1 — Estabilización y conexión del 100 % de los controles (1–2 semanas)

**Objetivo:** cero crashes conocidos, cero `TODO()` ejecutables, todos los botones visibles funcionan o muestran por qué no.

1. **Eliminar los 9 `TODO()` de runtime** (K-01…K-05) → tags/iconos/`return false`.
2. **Ciclo de vida de sesión** (C-01, C-02, C-04, C-05, U-01, C-17): `InitState` flow; `cancelAndJoin`; `NonCancellable + Main.immediate` en unbind; `CameraLifecycleOwner` por sesión; separar transient vs perpetual; debounce 150 ms.
3. **Errores de captura** (C-03, U-02, U-03, C-08, C-11): `Result`/eventos en lugar de throws; orden stop→finalize→cancel; `SecurityException` de audio.
4. **Gestos** (U-04, U-08, K-07): `remember` estable, `detectTransformGestures` con consumo, `ZoomStateManager` reactivo al rango, doble-tap = 1x↔2x (como Pixel).
5. **Permisos de galería** (U-05, manifest): abrir última captura por `Uri` propia; `READ_MEDIA_VISUAL_USER_SELECTED` en 34+.
6. **Manifest/Play** (A-01, A-02, A-05, B-04): intent-filters VIDEO_CAPTURE/STILL_IMAGE_CAMERA; `targetSdk 36`; `signingConfig` release; `proguard-rules.pro` con keeps para `androidx.camera.camera2.interop.**`, `dagger.hilt.**`, `kotlinx.atomicfu.**`; `versionCode` derivado de `GITHUB_RUN_NUMBER`.
7. **Build** (B-01, B-02, B-03): hooks `onlyIf`, `-Xmx4g`, migración kapt→KSP.
8. **Limpieza** (A-03, A-04, C-16, K-08, C-07 + test): eliminar código muerto y comentarios obsoletos; `DebugUiState` reactivo (U-06).
9. **Observabilidad**: `CameraErrorHandler` central + `Timber`; Crashlytics opcional en flavor `stable`.
10. **QA manual S21 FE**: rotación x20, flip x20, cambiar aspect/HDR/estabilización en bucle, grabar 10 s y comprobar que aparece en galería, revocar audio en background y grabar, tecla volumen para disparar.

### Estado de la Fase 1 (actualizado 2026-09-02, rama `genspark_ai_developer`)

| # | Ítem | Estado | Commits / notas |
|---|------|--------|-----------------|
| 1 | 9 `TODO()` de runtime (K-01…K-05) | ✅ Hecho | `ZoomBarComponents`, `TestTags`, `CaptureScreenComponents`, `CaptureButtonComponents`, `CaptureModeUiStateAdapter`, `SettingsComponents` + strings nuevos. |
| 2 | Ciclo de vida de sesión (C-01, C-02, C-04, C-05, C-06, C-17, U-01) | ✅ Hecho | `CameraControllerImpl` con `Mutex`+`cancelAndJoin` y callback `onCameraError` → snackbar; `CameraXCameraSystem` sin `lateinit`/`!!`, `unbindAll` en `NonCancellable + Main.immediate`; `CoroutineLifecycleOwner` siempre en main thread; estabilización `AUTO` degrada a `OFF`. `InitState` flow y debounce quedan para Fase 2 (no bloqueantes). |
| 3 | Errores de captura (C-03, C-08, C-11, U-02, U-03) | ✅ Hecho | `ImageCaptureUnavailableException`/`VideoCaptureUnavailableException`; `StartRecording` sin `VideoCapture` emite `OnVideoRecordError`; `SecurityException` de audio → graba sin audio; `stopVideoRecording` espera al start (`join`) y no lo cancela; `VideoCaptureError` ante cualquier fallo al iniciar. |
| 4 | Gestos (U-04, U-08, K-07) | ✅ Hecho | Lambda `tapToFocus` memorizada; `ZoomStateManager.onZoomRangeChanged`; `PreviewDisplay` con `rememberUpdatedState`, `pointerInput(Unit)`, deltas de pinch no-op filtrados. Doble-tap 1x↔2x → Fase 3 (UX Pixel). |
| 5 | Permisos de galería (U-05) | ✅ Hecho | `getLastCapturedMedia` en IO y tolerante a `SecurityException` (API ≤ 28 sin permiso). |
| 6 | Manifest/Play (A-01, A-03, A-04, A-05, B-04) | ✅ Hecho | Intent-filters `IMAGE_CAPTURE`/`VIDEO_CAPTURE`/`STILL_IMAGE_CAMERA(_SECURE)`/`VIDEO_CAMERA`; `targetSdk 36`; `signingConfigs.release` desde `RODOCAM_*` o `keystore.properties`; `proguard-rules.pro`; `versionCode` desde `GITHUB_RUN_NUMBER`. A-02 (orientación en tablets) → Fase 3. |
| 7 | Build (B-01, B-02) | ✅ Hecho | `installGitHooks` `onlyIf !CI` sin `taskGraph.whenReady`; `-Xmx4g`. B-03 (kapt→KSP) → Fase 2, requiere validar con CI verde primero. |
| 8 | Limpieza (A-03, A-04, C-07, C-12, C-16, U-06) | ✅ Hecho | `Camera.ACTION_NEW_PICTURE` eliminado; helpers cropRect corregidos + `CropRectDimensionsTest`; `ImageOutputFormat.mimeType/fileExtension`; `DebugUiState` reactivo. |
| 9 | Observabilidad (`CameraErrorHandler`, Timber) | ⏳ Pendiente | Se hará junto con Fase 2 cuando existan más fuentes de error. |
| 10 | QA manual S21 FE | ⏳ Pendiente | Requiere el APK de CI (`.github/workflows/build-apk.yml` debe sustituirse por `docs/ci/build-apk.proposed.yml`). |

> Verificación: en el sandbox no hay Android SDK, por lo que la validación ha sido estática (imports, tipos, llamadas cruzadas). El primer build real lo hará GitHub Actions al abrir/actualizar el PR.

## Fase 2 — Control profesional y multi-cámara real (2–4 semanas)

**Objetivo:** modo **Pro** de Pixel 10 (ISO, shutter, WB, focus, EV, dual exposure) + selección de lente física + RAW/HEIC + ZSL.

1. **Modelo de lentes** (C-14): `CameraLensRepository` que enumera `availableCameraInfos`, lee `Camera2CameraInfo.getCameraCharacteristic(LENS_INFO_AVAILABLE_FOCAL_LENGTHS, SENSOR_INFO_PHYSICAL_SIZE, REQUEST_AVAILABLE_CAPABILITIES)` y, si `LOGICAL_MULTI_CAMERA`, `getPhysicalCameraIds()`. UI: chips 0.6x / 1x / 3x (S21 FE) ó 0.5x/1x/2x/5x/10x (Pixel) generados dinámicamente; la conmutación se hace con `setZoomRatio` sobre la lógica (transición suave, como Pixel) y, en dispositivos que exponen físicas independientes, con `CameraSelector` filtrado por ID. Fallback de nombre por focal (< 20 mm eq = UW, > 50 mm eq = Tele).
2. **Controles manuales vía `Camera2CameraControl`** (nuevo `ManualControlsController` + `ManualUiState`):
   - `CONTROL_AE_MODE=OFF`, `SENSOR_SENSITIVITY` (ISO; rango `SENSOR_INFO_SENSITIVITY_RANGE`), `SENSOR_EXPOSURE_TIME` (rango `SENSOR_INFO_EXPOSURE_TIME_RANGE`, S21 FE ≈ 1/24000 s–30 s), `CONTROL_AWB_MODE`/`COLOR_CORRECTION_GAINS` + `COLOR_CORRECTION_MODE=TRANSFORM_MATRIX` para WB en kelvin, `CONTROL_AF_MODE=OFF` + `LENS_FOCUS_DISTANCE` (dioptrías; 0 = ∞).
   - **Dual Exposure** (Pixel): dos sliders → `CONTROL_AE_EXPOSURE_COMPENSATION` (brillo) + `TONEMAP_CURVE`/`CONTROL_POST_RAW_SENSITIVITY_BOOST` (sombras) donde esté disponible; fallback a EV único.
   - AE/AF lock por long-press (C-15). Guardar `CaptureResult` (`SENSOR_SENSITIVITY`, `SENSOR_EXPOSURE_TIME`) para la UI en tiempo real vía `Camera2Interop.Extender.setSessionCaptureCallback`.
3. **RAW + JPEG simultáneo**: `ImageCapture.Builder().setOutputFormat(OUTPUT_FORMAT_RAW_JPEG)` (CameraX ≥ 1.5, requiere `REQUEST_AVAILABLE_CAPABILITIES_RAW`; S21 FE lo tiene). Guardar DNG con `MediaStore` (`image/x-adobe-dng`) + JPEG. Opción “solo RAW”. Ajustar C-12.
4. **HEIC**: `ImageCapture.OUTPUT_FORMAT_JPEG` → post-conversión con `HeifWriter` (androidx.heifwriter) en `Dispatchers.Default` (C-13) cuando `Build.VERSION ≥ 28` y `MediaCodecList` tenga encoder HEVC image; si no, fallback JPEG. Toggle en Settings “Formato: JPEG / HEIC / Ultra HDR”.
5. **ZSL**: `setCaptureMode(CAPTURE_MODE_ZERO_SHUTTER_LAG)` cuando `cameraInfo.isZslSupported` y no hay HDR/extensions; fallback `MINIMIZE_LATENCY`. Métrica de latencia en debug overlay.
6. **CameraX Extensions**: `ExtensionsManager` → Night / HDR / Bokeh / Face Retouch del OEM (Samsung expone Night y Bokeh en S21 FE). Toggle en QuickSettings; deshabilita controles manuales cuando activo.
7. **Frame rates reales**: `supportedFrameRateRanges` + `CONSTRAINED_HIGH_SPEED_VIDEO` → 24/30/60 y, cuando exista, 120 (1080p) vía `Recorder` + `setTargetFrameRate(Range(120,120))` (CameraX ≥ 1.4 soporta high-speed en `VideoCapture` con `HIGH_SPEED_VIDEO` surface combination).
8. **Settings**: nueva sección “Pro”, persistida en DataStore (`CameraAppSettings` + proto).

### Estado de la Fase 2 (actualizado 2026-09-02, rama `genspark_ai_developer`)

| # | Ítem | Estado | Implementación / notas |
|---|------|--------|------------------------|
| 1 | Modelo de lentes físicas (C-14) | ✅ Hecho | `LensInfo`/`LensKind`/`buildLensInfos` en `core/model`; `CameraInfo.physicalLenses(context)` lee `physicalCameraIds` (API 28+) + `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`/`SENSOR_INFO_PHYSICAL_SIZE` y deriva el ratio de zoom equivalente. `CameraConstraints.physicalLenses`. Chips de zoom generados por `buildZoomLevels` (S21 FE → 0.6x / 1x / 2x / 3x; sin físicas → 0.5/1/2/5 filtrados por rango). La conmutación usa `setZoomRatio` sobre la cámara lógica (transición suave). `CameraSelector` por ID físico → Fase 4 si algún OEM no expone lógica. Tests: `LensInfoTest`, `ZoomLevelsTest`. |
| 2 | Controles manuales (`Camera2CameraControl`) | ✅ Hecho | `ManualControls`/`ManualCapabilities`/`ExposureInfo` (`core/model`); `CameraInfo.manualCapabilities` lee rangos ISO/exposición/EV, AF/AWB modes, locks, RAW, ZSL. `CameraSession.applyManualControls` aplica `CONTROL_AE_MODE_OFF` + `SENSOR_SENSITIVITY` + `SENSOR_EXPOSURE_TIME`, `CONTROL_AE_EXPOSURE_COMPENSATION`, `CONTROL_AE_LOCK`, `CONTROL_AWB_MODE`/`CONTROL_AWB_LOCK`, `CONTROL_AF_MODE_OFF` + `LENS_FOCUS_DISTANCE`. Lectura en tiempo real (`publishExposureInfo`, throttle ~8 Hz) → `CameraState.exposureInfo`. Sanitizado por lente activa. **WB en kelvin**: `ManualControls.whiteBalanceKelvin` (2000–10000 K, paso 100) → `CONTROL_AWB_MODE_OFF` + `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX` + `COLOR_CORRECTION_GAINS` (ganancias RGGB calculadas con el locus planckiano CIE 1931 → sRGB lineal, `ColorScience.kt`, monótonas y acotadas 0.25–4×) + transformación identidad; requiere `MANUAL_POST_PROCESSING` y `AWB_MODE_OFF` (`ManualCapabilities.supportsWhiteBalanceKelvin`). **Dual Exposure (sombras)**: `ManualControls.shadowsBoost` (−1..+1) → `TONEMAP_MODE_CONTRAST_CURVE` + `TONEMAP_CURVE` con curva potencia (gamma 1/2.2 neutra, hasta 1/4.4 al levantar, lineal al hundir; extremos anclados 0/1) muestreada a `TONEMAP_MAX_CURVE_POINTS` (máx. 64); el brillo es el EV existente. Ambos se descartan por `sanitize` si la lente no los soporta. Tests: `ColorScienceTest` (7), `ManualControlsTest` (+4) — JVM ✅. |
| 2b | UI Pro | ✅ Hecho | `ManualControlsUiState` + adapter, `ManualControlsController` (+impl), `ManualControlsPanel`/`ProModeToggle` en `PreviewScreen` (chips ISO/Obturador/EV/**Sombras**/WB/Enfoque/AE-lock con valores en vivo, sliders logarítmicos con debounce 40 ms, Reset). Panel WB: chips de preset + chip **K** que abre el slider kelvin (se preposiciona con la temperatura aproximada del preset activo, `approximateKelvin`); slider de sombras con snap a 0 y lectura en %. `ManualControlsController.setWhiteBalanceKelvin/setShadowsBoost`; tests `ManualControlsControllerImplTest` (9). Se oculta en modo externo, cámara dual y dispositivos sin `MANUAL_SENSOR`. |
| 2c | Gestos del visor (C-15) | ✅ Hecho | **Pulsación larga = bloqueo AE/AF** al estilo Pixel: `CameraSystem.lockFocusAndExposure(x, y)` envía un `FocusMeteringEvent(lock = true)` → `FocusMeteringAction` (AF+AE+AWB) con `disableAutoCancel()` y activa `aeLock`/`awbLock` transitorios en `ManualControls` (no requiere modo Pro; se omite con extensión activa). El toque simple siguiente inicia un enfoque normal (auto-cancel 2,5 s) y libera los locks (`tapToFocus` → `setViewfinderLocks(false)`). `FocusState.Specified.isLocked` → `FocusMeteringUiState.Specified.isLocked`: el indicador permanece visible con borde primario y badge de candado (`FOCUS_LOCK_BADGE_TAG`, `stateDescription` accesible) hasta el siguiente toque; haptic `LongPress` al bloquear. **Doble toque = zoom 1x ↔ 2x** (`doubleTapZoomTarget`, animación 300 ms vía `ZoomStateManager.animatedZoom`; tolerancia ±5 % alrededor de 1x; deshabilitado si la lente no alcanza 2x). El doble toque ya **no** invierte la cámara (sólo el botón). `CameraController.lockFocusAndExposure` (+impl, +fake); `FakeCameraSystem` registra `focusMeteringRequests`. Tests: `FocusMeteringUiStateAdapterTest` (7, JVM ✅), `DoubleTapZoomTest` (8, JVM ✅), instrumentados `ViewfinderGesturesTest` (doble toque → 2x/1x, long-press → badge → tap libera) y `SwitchCameraTest.doubleTap_doesNotFlipCamera`. |
| 3 | RAW + JPEG | ✅ Hecho | `ImageOutputFormat.RAW_JPEG` (+ `HEIC`); `CameraConstraints.supportedImageFormatsMap` incluye RAW cuando el HAL lo anuncia (`ManualCapabilities.isRawSupported`). `ImageCapture.OUTPUT_FORMAT_RAW_JPEG` + `takePicture(raw, jpeg, ...)` (`RawJpegCapture.kt`) guarda el `.dng` en `MediaStore` junto al JPEG con el mismo nombre base. Ajuste "Formato de foto" en Settings (JPEG / HEIC / RAW+JPEG) persistido (`KEY_IMAGE_FORMAT`). ZSL se desactiva automáticamente en RAW. |
| 4 | HEIC | ✅ Hecho | Módulo `:core:camera:postprocess:heic` (`androidx.heifwriter` 1.1.0): la captura sale como JPEG del HAL y `HeicImagePostProcessor` (registrado por Hilt `@IntoSet` en el `PostProcessModule` existente) transcodifica a HEIC con `HeifWriter` (grid, calidad 90, EXIF copiado con orientación normalizada) sobrescribiendo la fila de `MediaStore` (`IS_PENDING`). Si el dispositivo no tiene encoder HEVC (`MediaCodecList`) o la codificación falla, la fila se revierte a `image/jpeg` + `.jpg` sin perder la foto. Tests: `JpegExifTest` (7, JVM ✅), `HeicImagePostProcessorTest` (6, Robolectric con `ContentProvider` falso). |
| 5 | ZSL | ✅ Hecho | `createImageUseCase(preferZeroShutterLag)` → `CAPTURE_MODE_ZERO_SHUTTER_LAG` si `isZslSupported` y modo IMAGE_ONLY sin efecto/vídeo/UltraHDR; si no, `MAXIMIZE_QUALITY`. Métrica de latencia en debug → Fase 3. |
| 6 | CameraX Extensions | ✅ Hecho | `CameraExtensionMode` (NONE/NIGHT/BOKEH/HDR/FACE_RETOUCH) persistido (`KEY_EXTENSION_MODE`). `CameraXCameraSystem.initialize` obtiene `ExtensionsManager` (tolerante a fallo → sin extensiones) y publica `CameraConstraints.supportedExtensionModes` por lente. `runSingleCameraSession` enlaza con `getExtensionEnabledCameraSelector` y, con extensión activa, omite `VideoCapture`/efectos, fuerza JPEG y desactiva ZSL (las extensiones sólo soportan Preview+ImageCapture). `tryApplyExtensionModeConstraints` degrada a NONE en vídeo-solo, cámara dual, LLB o lente sin soporte y resetea controles manuales/UltraHDR/RAW; elegir HDR/RAW/Ultra HDR después apaga la extensión (`dropExtensionIfConflicting`). UI: fila "Scene mode" en ajustes rápidos (`ExtensionModeRow`, chips Off/Night/Portrait/HDR+/Retouch sólo con los modos que anuncia el dispositivo; iconos vectoriales nuevos), `ExtensionModeUiState` + adapter, `QuickSettingsController.setExtensionMode` (persistencia vía `SettingsRepository.updateExtensionMode`); el panel Pro se oculta con extensión activa. Tests: `ExtensionModeUiStateAdapterTest` (7, JVM ✅), `QuickSettingsControllerImplTest` (+2), `QuickSettingsUiStateAdapterTest` actualizado. Samsung S21 FE expone normalmente NIGHT/BOKEH/HDR/FACE_RETOUCH vía su vendor library; sin ella la fila no aparece. |
| 7 | Frame rates reales | ✅ Parcial | `TARGET_FPS_24`/`TARGET_FPS_120` añadidos a `FIXED_FRAME_RATES`, filtrados por `supportedFrameRateRanges`; diálogo de FPS en Settings ahora dinámico (15/24/30/60/120) con rationale por opción. `CONSTRAINED_HIGH_SPEED_VIDEO` (120 fps slow-motion) → Fase 4. |
| 8 | Settings / persistencia | ✅ Hecho | `CameraAppSettings.manualControls` + `isProModeEnabled`; `isProModeEnabled` persistido en DataStore (`KEY_PRO_MODE_ENABLED`) a través de `SettingsDataSource` → `SettingsRepository`; el toggle Pro del visor lo escribe vía `ManualControlsControllerImpl.onProModeEnabledPersist`. Los valores manuales son transitorios (se resetean a AUTO al desactivar Pro), como en Pixel. |

> **Fase 2 completa (100 %).** Verificación: sin Android SDK en el sandbox, validación estática (paridad interfaz/impl/fakes de `CameraSystem`, `CameraController`, `ManualControlsController`, `QuickSettingsController`; call sites de constructores con argumentos nombrados; ≤100 columnas en código nuevo; imports; recursos `R.string`/`R.drawable` referenciados existen; XML bien formado; firmas CameraX 1.6.2 comprobadas contra los AAR reales). Tests JVM ejecutados en el sandbox con kotlinc/JUnit: `ManualControlsTest`, `ColorScienceTest`, `LensInfoTest`, `ManualControlsFormatTest`, `DoubleTapZoomTest`, `FocusMeteringUiStateAdapterTest` → **46/46 ✅**. Requieren Gradle (coroutines-test/Robolectric): `ManualControlsControllerImplTest`, `QuickSettingsControllerImplTest`, `ExtensionModeUiStateAdapterTest`, `HeicImagePostProcessorTest`, `JpegExifTest`. El build real y `testDebugUnitTest` los ejecuta GitHub Actions al actualizar el PR. Dispositivo objetivo de QA: Galaxy S21 FE (lentes 0.6x/1x/3x, `MANUAL_SENSOR`, RAW, ZSL, Extensions vendor).

> **Optimización de UI (pase Fase 2):** lambdas estables en `PreviewScreen` (`remember` con claves de controlador; sin `it::method` ni lambdas inline en filas recompuestas cada frame), sliders Pro con `SliderSync` (el pulgar no salta al recibir el eco del controlador, debounce 40 ms), `AnimatedVisibility` respetando `LocalDisableAnimations`, semántica TalkBack (`contentDescription` en sliders/toggle Pro, `stateDescription` Manual/Auto en chips y "bloqueado" en el indicador AE/AF).

## Fase 3 — Asistencia visual y UX de nivel Pixel (2–3 semanas)

1. **Pipeline `ImageAnalysis`** ligero (640×480, `STRATEGY_KEEP_ONLY_LATEST`, `RGBA_8888` o YUV) compartido por: **histograma RGB/luma en tiempo real** (Compose `Canvas`, 64 bins, 15 fps), **zebras** (shader AGSL en API ≥ 33 / RenderEffect; fallback overlay bitmap) con umbral 95–100 %, **focus peaking** (Sobel/Laplaciano sobre luma en el `SingleSurfaceForcingEffect` OpenGL ya existente — añadir uniform `peakingEnabled`, color configurable), **detección de escena oscura** para C-09/Night auto.
2. **Grids de composición**: 3×3, cuadrícula dorada, diagonales, 4×4, centro; **nivel horizontal** (sensor `ROTATION_VECTOR`, como Pixel — línea que se pone amarilla al nivelar).
3. **Haptics**: `HapticFeedbackConstants` en disparo, cambio de lente, snap a 1x del zoom, AE/AF lock (`CONFIRM`, `GESTURE_END`, `CLOCK_TICK` en slider manual). Ajuste on/off.
4. **UX Pixel**: carrusel de modos inferior (Foto/Vídeo/Retrato/Noche/Pro/Cámara lenta/Time-lapse/Panorámica) con `HorizontalPager` + snapping; barra de zoom “pill” 0.5–1–2–5; indicador de ISO/shutter/EV bajo el visor; “Top Shot”-like: buffer circular de 1,5 s en `ImageAnalysis` para elegir mejor frame (versión ligera); temporizador 3/10 s; “Camera Coach” simplificado: sugerencias basadas en histograma (sub/sobre-exposición, horizonte inclinado).
5. **Accesibilidad e i18n**: todos los strings en `strings.xml` (K-08), `contentDescription` semánticas, TalkBack sobre sliders manuales.
6. **Tests**: unit tests de adaptadores (ZoomControl dinámico, ManualUiState), screenshot tests Compose de la barra Pro, instrumentation smoke en emulador (`gradle-managed-devices`) en un job nightly.

## Fase 4 — Pipeline de imagen y vídeo avanzado (4–8 semanas, incremental)

1. **Pipeline de imagen propio** (sin depender del OEM): captura de **ráfaga RAW** (`ImageCapture` RAW ×3–8 con `CONTROL_AE_EXPOSURE_COMPENSATION` bracketing) → **alineación + fusión HDR+ simplificada** (merge por mediana ponderada en luma, Wiener-like) → demosaico/tono en **Vulkan/OpenCL vía RenderScript-replacement (Vulkan compute) o C++ NEON** en el módulo nativo existente (`core/camera/src/main/cpp`) → **reducción de ruido** espacial (NLM/bilateral en C++) y temporal (fusión de la ráfaga) → salida JPEG/HEIC/DNG. Modo **Noche** propio con exposiciones largas apiladas cuando no haya Extensions.
2. **Estabilización**: exponer **OIS** (`LENS_OPTICAL_STABILIZATION_MODE`) y **EIS** (`CONTROL_VIDEO_STABILIZATION_MODE` / `PREVIEW_STABILIZATION`) por separado con detección de capacidades; en dispositivos sin EIS, EIS por software opcional (crop + transform desde giroscopio) en el efecto OpenGL.
3. **Vídeo**: 4K60 (S21 FE) / 4K120 y 8K30 (Pixel 10 Pro XL) según `Recorder.getVideoCapabilities`; **HLG10 / HDR10+** (`DynamicRange.HLG_10_BIT`, ya soportado en settings); **preview 60/120 fps** cuando `supportedFrameRateRanges` lo permita (`Preview.Builder().setTargetFrameRate`), con degradación automática por térmica (`PowerManager.getThermalHeadroom`); bitrate y códec (HEVC/AV1 en API ≥ 34) configurables; audio 48 kHz/192 kbps, reducción de viento (`NoiseSuppressor`).
4. **Zoom híbrido tipo “Pro Res Zoom”**: super-resolución por multi-frame (ráfaga + registro subpíxel) en C++ para 3–10x; upscaler ML opcional (TFLite ESRGAN-lite) en gama alta.
5. **Rendimiento**: `Baseline Profiles` + `Macrobenchmark` (startup < 600 ms en S21 FE), `StrictMode` en debug, `LeakCanary`.
6. **Play Store**: `bundleStableRelease` (AAB), Play App Signing, `Data safety`, política de permisos de cámara/micrófono, ficha con capturas por factor de forma, pruebas internas → cerradas → producción por etapas 5 % → 100 %.

### Checklist QA por dispositivo (mínimo antes de cada release)
- [ ] Arranque en frío < 1 s, sin pantalla negra tras 20 rotaciones/flips.
- [ ] Foto en cada lente/zoom, con y sin flash, HDR, Night (extensions), RAW+JPEG.
- [ ] Vídeo 1080p30/60, 4K30/60 (y 120 fps si soportado) — archivo visible en galería < 2 s tras stop.
- [ ] Revocar permisos en background → sin crash, mensajes claros.
- [ ] Intents `IMAGE_CAPTURE`/`VIDEO_CAPTURE` desde otra app.
- [ ] Dispositivo `LIMITED` (emulador API 24) → todas las opciones no soportadas aparecen deshabilitadas con motivo.

---

*Documento generado a partir de lectura estática completa del código en `main @ 68b0c8c`. Las líneas citadas corresponden a ese commit.*
