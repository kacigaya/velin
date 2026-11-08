# Velin

Velin is an open-source, native Android mail client for [Tuta](https://tuta.com). It uses Kotlin, Jetpack Compose, and Material 3 Expressive.

Velin is not affiliated with Tuta GmbH.

## Status

Early development. The screens exist and use sample data. Velin cannot sign in to Tuta yet. See [docs/ROADMAP.md](docs/ROADMAP.md).

## Goals

- Feature parity with the official Tuta mail app.
- Tuta's end-to-end encryption model, unchanged.
- A Pixel-native interface: dynamic color, adaptive layouts, light and dark themes.
- No analytics, no tracking.

## Build

Requirements: JDK 21 and Android SDK platform 37.

1. Create `local.properties` with `sdk.dir=/path/to/android-sdk`.
2. Run `./gradlew :app:assembleDebug`.

Run the tests:

```sh
./gradlew testDebugUnitTest
```

Record screenshots into `*/build/outputs/roborazzi`:

```sh
./gradlew testDebugUnitTest -Proborazzi.record=true
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Roadmap and parity checklist](docs/ROADMAP.md)
- [Backend feasibility research](docs/research/backend-feasibility.md)

## License

GPL-3.0. See [LICENSE](LICENSE).

Icons are [Material Symbols](https://github.com/google/material-design-icons) by Google, licensed under Apache 2.0.
