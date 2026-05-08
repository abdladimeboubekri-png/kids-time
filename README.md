# KidTime — Android TV Parental Time Manager

A native Android TV app that locks streaming apps (YouTube, Netflix, Disney+, etc.) behind a per-kid PIN with daily time limits and shared time tracking.

## What this app does

- 3 kids, each with a unique 4-digit PIN.
- Each kid gets a daily time budget (default: 3 hours).
- When a kid tries to open a locked app (YouTube, Netflix, etc.), the app overlay appears, kid picks their name + enters PIN.
- Time is consumed only while a locked app is in foreground, by the kid currently logged in.
- If kid A stops watching after 30 min and kid B logs in with their PIN, kid A can come back later and use their remaining 2h 30m.
- When a kid hits 0, the lock screen pops up and won't let them back in until midnight.
- Parents can: set names, change PINs, change daily limit, change which apps are locked, reset time manually.
- Time auto-resets at midnight every day.

## Build instructions

### Prerequisites
- **Android Studio Hedgehog (2023.1.1) or newer** — Download from https://developer.android.com/studio
- **JDK 17** (bundled with Android Studio)
- **A computer with USB cable + Android TV box/stick** OR an Android TV emulator

### Steps to build the APK

1. Open Android Studio
2. **File → Open** → select this `KidTimeTV` folder
3. Wait for Gradle sync to finish (first time will download ~500 MB of dependencies)
4. **Build → Build Bundle(s) / APK(s) → Build APK(s)**
5. The APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

## How to install on Android TV

### Option A: ADB sideload (recommended)
1. On your Android TV: **Settings → Device → About → click "Build" 7 times** to enable Developer Mode
2. **Settings → Developer Options → enable USB Debugging + ADB Debugging**
3. Find your TV's IP: **Settings → Network → Status**
4. On your computer, in a terminal:
   ```
   adb connect <TV_IP>:5555
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

### Option B: USB stick
1. Copy the APK to a USB stick
2. Plug into TV
3. Use a file explorer app (e.g. "X-plore" from Play Store) to browse + install

## First-time setup on the TV

After installing, open KidTime from the TV launcher (it appears in the apps row):

1. **Grant Usage Access**: Tap "Grant Usage Access" → find KidTime in the list → enable
2. **Grant Overlay Permission**: Tap "Grant Overlay" → enable "Display over other apps"
3. **Choose apps to lock**: Tap "Choose apps to lock" → check YouTube, Netflix, etc.
4. **Edit kids & settings**: Set names, PINs, daily limit, admin PIN
5. **Test**: Tap "Test Lock Screen" to see how the PIN screen looks

That's it! Now whenever a kid opens a locked app, the PIN screen will appear.

## Default credentials (CHANGE THESE!)

- Kid 1: Alex — PIN `1111`
- Kid 2: Sam — PIN `2222`
- Kid 3: Mia — PIN `3333`
- Admin PIN: `1234`
- Daily limit: 3 hours each

## Important notes & limitations

- **Doesn't fully prevent uninstall**: Tech-savvy kids could uninstall the app from TV settings. To prevent that, you'd need to enable Device Admin (advanced setup not included here).
- **Background detection latency**: There's a ~1-2 second delay between opening a blocked app and the lock screen appearing. This is normal — the OS only reports foreground app changes that often.
- **Power off**: If the TV is fully powered off, the timer stops. It's not a wall clock — only counts time the kid actually spent watching.
- **Some Android TVs (especially Sony) restrict overlay**: If overlay permission is missing, the lock screen will still launch but as a regular activity.
- **Pre-installed apps**: System apps may not show up in the "choose apps" dialog unless you adjust the filter in `MainActivity.kt`.

## File structure
```
KidTimeTV/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # Permissions + activities
│   │   ├── java/com/kidtime/tv/
│   │   │   ├── MainActivity.kt          # Parent control panel
│   │   │   ├── LockActivity.kt          # PIN entry screen
│   │   │   ├── MonitorService.kt        # Background app watcher
│   │   │   ├── BootReceiver.kt          # Restart after reboot
│   │   │   └── Storage.kt               # SharedPreferences wrapper
│   │   └── res/
│   │       ├── layout/                  # All XML UIs
│   │       ├── drawable/                # Buttons & banner
│   │       └── values/                  # Strings + themes
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── README.md
```

## Troubleshooting

- **Lock screen doesn't appear**: Make sure both Usage Access and Overlay permissions are granted.
- **App keeps closing**: Ensure the foreground service is running (you should see a notification "KidTime is watching").
- **Time isn't tracking**: Check that you actually picked a kid + entered PIN when prompted. Time only consumes for the active kid.
- **Kids find a way around it**: Add the TV's settings app to the blocked list and set a parent PIN there too.

## Want to enhance it further?
- Add Device Admin to prevent uninstall
- Add weekly stats / charts
- Per-app time limits (e.g. only 1h on YouTube)
- Schedule windows (no TV during dinner / school hours)
- Bonus time rewards for chores
- Multi-language (Arabic / French / etc.)

Let me know in the chat and I can extend the app for you.
