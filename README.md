# Nonograms (Android)

Kotlin, single-activity, no third-party dependencies beyond AndroidX / Material.
Min SDK 26 (Android 8.0), target/compile SDK 34.

## Build the APK
1. Android Studio -> File > Open -> select this folder, let Gradle sync finish.
2. Build > Build Bundle(s) / APK(s) > Build APK(s)
3. Click "locate" in the popup: app/build/outputs/apk/debug/app-debug.apk
   (or just press Run with a phone / emulator selected)

## Files
- Puzzle.kt        puzzle model, line-logic solver, generator (pure Kotlin)
- NonogramView.kt  the board: drawing, clues, tap / drag / hold gestures, pinch-zoom
- MainActivity.kt  buttons, size dialog, new game / clear / solved dialog
