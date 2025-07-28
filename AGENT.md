# Moonlight Android (Artemis) Agent Guide

## Build & Test Commands
- **Build APK**: `gradlew.bat assembleDebug` / `gradlew.bat assembleRelease`
- **Run tests**: `gradlew.bat test` (runs all unit tests)
- **Single test class**: `gradlew.bat test --tests "com.limelight.StartupTest"`
- **Lint check**: `gradlew.bat lint`
- **Clean build**: `gradlew.bat clean`

## Architecture & Structure
- **Main module**: `app/` (Android application)
- **Flavors**: `root` (rooted devices), `nonRoot_game` (standard)
- **Core packages**: `binding/` (hardware abstraction), `nvstream/` (network protocol), `computers/` (PC discovery), `preferences/` (settings)
- **Native code**: `app/src/main/jni/` (NDK builds with Android.mk)
- **Tests**: Robolectric-based unit tests in `app/src/test/`

## Code Style & Conventions
- **Language**: Java (min SDK 21, target SDK 34, compile SDK 36)
- **Naming**: PascalCase classes, camelCase methods/variables, UPPER_SNAKE_CASE constants
- **Package structure**: `com.limelight.*` with clear separation (input/, audio/, video/, etc.)
- **Indentation**: 4 spaces, opening braces on same line
- **Patterns**: Observer (listeners), Service-oriented, Context pattern, Handler threading
- **Error handling**: Custom exceptions, graceful null handling, proper resource cleanup
