# Meat Suit Diagnostics: Android app

A Kotlin and Jetpack Compose app for Android 13+ (built for a Samsung Galaxy A14). See [../docs/DESIGN.md](../docs/DESIGN.md) section 8.

## Build

Open the `android/` folder in Android Studio and let it sync. Or from a terminal, with `JAVA_HOME` set to Android Studio's bundled JDK (`C:\Program Files\Android\Android Studio\jbr`):

```bash
./gradlew testDebugUnitTest      # unit tests (domain logic)
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # needs keystore.properties, see below
```

## Testing against a local server (emulator)

Debug builds may use plain HTTP to `10.0.2.2`, which is the development machine as seen from the emulator. Release builds are HTTPS-only.

1. Start the server with `server/scripts/dev_server.py` and issue a key with `python -m app.cli new-api-key` (`DATABASE_PATH=dev.db`).
2. Install the debug APK on the emulator and open the setup link:
   ```bash
   adb shell "am start -a android.intent.action.VIEW -d 'meatsuit://setup?url=http%3A%2F%2F10.0.2.2%3A8000&key=<key>'"
   ```
3. To test reminders, create a check-in a few minutes ahead in the admin app (http://localhost:8000/admin), then tap **Sync now** in the app's settings. `adb shell dumpsys alarm | grep meatsuit` lists the pending alarms.

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
