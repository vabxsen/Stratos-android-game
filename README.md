# Stratos (native Android)

Stack slabs and climb from sunrise into the stars. A fully native Kotlin rewrite of the original
Vite + Capacitor web game: no WebView, no Node.js.

## Requirements
- JDK 17 or newer (JDK 21 works)
- Android SDK with platform 36 (Android Studio installs this)
- `local.properties` pointing at your SDK, e.g. `sdk.dir=C\:\\Android\\Sdk` (Android Studio writes it for you)

## Build and run
```
gradlew.bat installDebug
```
Or open the folder in Android Studio and press Run. Debug builds install as `com.vabxsen.stratos.debug`,
so they sit alongside the release app.

Debug builds also accept an autoplay switch that lands every slab perfectly, handy for checking the
whole sky cycle:
```
adb shell am start -n com.vabxsen.stratos.debug/com.vabxsen.stratos.MainActivity --ez autoplay true
```

## Features
- Isometric tower rendered on a Canvas at the display's native refresh rate (Choreographer)
- Sky that moves from dawn through day, golden hour and dusk to a starry night; UI ink flips to stay readable
- Perfect drops keep the slab whole; three in a row grows it back. Ripple effect and combo counter
- Synthesized sound effects (low-latency AudioTrack, pentatonic notes rising with your combo)
- Haptics: light tick per drop, firmer click on perfect drops, error buzz on game over
- Pause button, back button pauses/resumes, auto-pause when the app is backgrounded or the shade is pulled down
- Saves best height, sound and vibration settings (backed up with Android Auto Backup)
- Share your height from the game-over screen
- Immersive full screen, display-cutout aware, portrait, screen kept awake only while playing
- Keyboard and gamepad support (Space/Enter/A to drop, P/Start to pause)
- Accessibility: TalkBack labels, live score announcements, respects "Remove animations"
- Adaptive launcher icon with themed (monochrome) variant and Android 12+ splash screen

## Project layout
- `GameView.kt`: game rules, simulation, and drawing
- `MainActivity.kt`: menus, HUD, lifecycle, settings, sharing, input
- `Sfx.kt`: the sound synthesizer and mixer
- `Haptics.kt`, `Prefs.kt`: vibration and saved settings
- `app/src/main/assets/fonts`: Unbounded and Figtree (SIL Open Font License, see the OFL files)

## Publishing to Google Play
1. Create an upload key once (keep it safe and backed up):
   `keytool -genkeypair -v -keystore stratos-upload.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload`
2. Create `keystore.properties` in the project root (it is git-ignored):
   ```
   storeFile=stratos-upload.jks
   storePassword=...
   keyAlias=upload
   keyPassword=...
   ```
3. Build the bundle: `gradlew.bat bundleRelease` and upload `app/build/outputs/bundle/release/app-release.aab`.
4. Bump `versionCode` in `app/build.gradle.kts` for every upload.

The application ID is `com.vabxsen.stratos`; it can't be changed after the first upload.

## License
Code is MIT licensed (see `LICENSE`). The bundled Unbounded and Figtree fonts are under the SIL Open Font License.
