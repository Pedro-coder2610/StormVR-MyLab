# StormVR GitHub build notes

- The Android Gradle project is in `Source/android`.
- The included GitHub Actions workflow builds the debug APK on GitHub-hosted Linux runners.
- It uses Java 17, Gradle 8.7, Android SDK platforms 34 and 35, Build Tools 34.0.0, and NDK 27.0.12077973, matching the project build files inspected in this source archive.
- The APK is uploaded as the workflow artifact `StormVR-debug-APK`.
- Debug builds are for testing, not Play Store release.
- `Source/android/local.properties`, Gradle caches, IDE settings, build outputs, and prebuilt APKs are intentionally omitted. They are machine-specific or generated and must not be committed.
