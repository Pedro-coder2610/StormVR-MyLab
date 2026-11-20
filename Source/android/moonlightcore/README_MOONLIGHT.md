# moonlightcore — núcleo de streaming do Moonlight/Artemis dentro do StormVR

Só o necessário para **conectar ao Sunshine/Apollo/GeForce e receber o vídeo/áudio**, sem a interface do Artemis:
- 29 arquivos Java do Artemis (pacote `com.limelight`): `NvConnection`, `NvHTTP`, `PairingManager`, `StreamConfiguration`,
  `MediaCodecDecoderRenderer` (vídeo, escreve numa `Surface`), `AndroidAudioRenderer`, `MoonBridge` (JNI) e dependências diretas.
  Lista exata do fechamento de dependências: veja `src/main/java`. + `AndroidCryptoProvider`.
- Código nativo: `src/main/jni` (moonlight-core, moonlight-common-c, libopus, openssl pré-compilado). Sem `evdev_reader` (modo root).
- NÃO incluídos (UI do Artemis): `Game`, `PcView`, `AppView`, configurações, descoberta mDNS, entrada por gamepad/mouse.

## Como ligar
1. `android/gradle.properties`: `stormvr.moonlight=true`
2. Sync do Gradle. Precisa do NDK 27.0.12077973 (o Android Studio oferece instalar).
3. Se o AGP 8.5.2 reclamar do compileSdk 35 / dependências, suba o AGP (ex.: 8.9+) em `build.gradle.kts` e `gradle-wrapper.properties`.
4. Se compilar, o próximo passo (fachada do StormVR) é: parear por PIN (`PairingManager.pair`), listar apps (`NvHTTP.getAppList`),
   `launchApp` e `NvConnection.start(...)` com `MediaCodecDecoderRenderer.setRenderTarget(surface)`:
   - **tela cheia**: `Surface` de um `SurfaceView` comum (sem GL, sem estéreo);
   - **tela flutuante na Sala 3D**: `Surface(SurfaceTexture)` ligado à textura do `ScreenSource` da sala.

## Status
Escrito/copiado, **NUNCA compilado** (sem Gradle/NDK no ambiente de desenvolvimento). Desligado por padrão para não quebrar o build atual.
