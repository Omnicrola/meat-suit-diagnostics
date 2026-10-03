# Meat Suit Diagnostics: Android app

A Kotlin and Jetpack Compose app for Android 13+ (built for a Samsung Galaxy A14). See [../docs/DESIGN.md](../docs/DESIGN.md) section 8.

## Build

Open the `android/` folder in Android Studio (current version, which bundles JDK 21) and let it sync. Or from a terminal:

```bash
./gradlew testDebugUnitTest      # unit tests (domain logic)
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # needs keystore.properties, see below
```

## Release signing

Updates install over the existing app only if they're signed with the **same key**. If you lose the key, you have to uninstall, which deletes any answers not yet uploaded. Back up the keystore file and its passwords.

Create the keystore once. `keytool` comes with Android Studio's JDK:

```bash
keytool -genkeypair -v -keystore meatsuit-release.jks -alias meatsuit -keyalg RSA -keysize 4096 -validity 36500
```

Then create `android/keystore.properties`. It's ignored by git, as are `*.jks` files:

```properties
storeFile=meatsuit-release.jks
storePassword=...
keyAlias=meatsuit
keyPassword=...
```

## Install on the phone

1. On the phone, enable **Developer options** (Settings › About phone › Software information › tap Build number 7 times), then turn on **USB debugging**.
2. Connect it by USB and run `adb install -r app/build/outputs/apk/release/app-release.apk`. Alternatively, copy the APK to the phone and open it, allowing "Install unknown apps" for your file manager when asked.

## After installing

1. In the admin web app, open **API key**, choose **Generate**, and scan the QR code from the app's setup screen. Scanning with the camera app also works; the app asks before connecting.
2. Go through the reminder checklist: notifications, unrestricted battery, and Samsung's **Never sleeping apps** list.
