# Offline Android wrapper

`dist/qibla-offline.apk` bundles the repository's single `index.html` file. The
Android wrapper supplies GPS and compass readings to that page because mobile
browsers may refuse sensor access to `content://` files.

The APK requests only precise location permission. It declares no Internet
permission, performs the Qibla calculation locally, and applies Android's
local magnetic-declination model to the compass heading. GPS can take time to
get a fix outdoors. If GPS is unavailable, the app shows a location error.
On launch, the app may use a GPS fix from the last five minutes. After a fix,
the button becomes "Refresh location" and requests a new GPS fix with visible
progress.
The adaptive launcher icon uses the same compass colors as the page; a PNG
preview is available at `android/icon-preview.png`.
The in-app About section explains the calculation, privacy, accuracy, and
upstream MIT license notice.

On this computer the general-purpose JDK and Android SDK are installed in
`~/.local/share/jdk-21` and `~/Android/Sdk`. To rebuild, run from the repository root:

```sh
android/build.sh
```

On another computer, install JDK 17 or newer and Android SDK Platform 35 plus
Build Tools 35.0.0, then set `QIBLA_JDK_PATH` and `QIBLA_SDK_PATH` for the
build command.

The signing key is generated once in `android/.signing/`. Keep that directory
private and retain it for future updates to the installed app.
