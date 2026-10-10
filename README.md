# dB Meter

An Android sound level meter. It shows the current level in dBA, dBC or dBZ, a 20-second
graph, and the maximum, minimum and equivalent level (Leq) since the last reset.

**[Download the latest APK](https://github.com/Unpiloted0852/dBMeter/releases/latest)**

## What it does

- Records raw 16-bit audio at 48 kHz from Android's unprocessed microphone source (or the
  voice-recognition source on phones without one), so no automatic gain or noise suppression
  changes the level.
- A, C and Z frequency weighting (IEC 61672 curves) and Fast (125 ms) or Slow (1 s) time
  weighting of the squared signal, as a hardware meter does.
- **Leq** is an energy average, not an average of decibel values.
- Warns at 85 dB and above, and when the microphone overloads.
- **Calibrate** adds a fixed trim to every reading, saved between launches.
- The screen stays on while the app is open. No audio is stored or sent anywhere.
- Checks this repository's releases at launch and offers to install a newer version.

## Things worth knowing

- Out of the box the reading relies on the microphone sensitivity that Android's
  compatibility rules prescribe. Real phones are typically within a few dB of it. For
  readings you can rely on, calibrate once against a real sound level meter.
- A phone microphone has a noise floor of roughly 30 dB and overloads somewhere between
  about 100 and 130 dB depending on the phone. Outside that range no app can measure.
- It is not a certified instrument and is no substitute for one where the law asks for one.

## Building

Open the folder in Android Studio, or run:

```bash
./gradlew assembleDebug
```

`./gradlew testDebugUnitTest` checks the weighting curves and the level maths.

Release signing is read from an untracked `keystore.properties` in the project root
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); without it the release build is
left unsigned.
