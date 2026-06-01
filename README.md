# OnthecrowVPN

Kotlin Multiplatform / Compose Multiplatform VPN proof of concept.

The first real VPN implementation is Android-only. iOS and Desktop share the same UI and persistence layer, validate/apply configs, and keep compile-safe no-op VPN controllers that report `VPN is not implemented on this platform yet`.

## Architecture

- `composeApp`: shared Compose app entrypoint, Koin initialization, Navigation3 host.
- `androidApp`, `iosApp`, `desktopApp`: thin platform launchers.
- `core:*`: coroutines, datastore, navigation, UI theme, Xray bridge, VPN API/impl.
- `feature:connection:*`: config validation/persistence use cases and the connection UI.

Config validity is intentionally delegated to Xray/libXray. The app trims and checks for empty input, then calls `ConvertShareLinksToXrayJson` and `TestXray`; successful raw config is stored in app-private DataStore and revalidated on the next launch.

## libXray for Android

The repository does not commit a built AAR. Build it locally when you want real Android validation/connect behavior:

```bash
scripts/build-libxray-android.sh
```

The script pins `LIBXRAY_TAG` by default and copies the resulting artifact to `local-libs/libxray/LibXray.aar`, which Gradle picks up automatically. Requirements are the Android SDK/NDK, Go, gomobile, Python 3, and the toolchain required by the pinned `XTLS/libXray` build.

Without the AAR, Android still compiles, but validation returns a clear `libXray is not installed` error.

## Useful Commands

```bash
./gradlew :feature:connection:logic-impl:jvmTest :feature:connection:ui-impl:jvmTest
./gradlew :androidApp:compileDebugKotlin :composeApp:compileKotlinIosSimulatorArm64 :desktopApp:compileKotlin
./gradlew :desktopApp:run
```

For iOS, build the `iosApp` scheme from Xcode or run `xcodebuild` against an available iOS simulator.
