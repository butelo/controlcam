# ControlCam

Turn spare Android phones into WiFi-triggered remote cameras.

ControlCam is a pair of small Android apps that work together over your local
network:

- **ControlCam** (the *camera* app) runs on any phone you want to use as a
  camera. It keeps the camera open, announces itself on the LAN, and takes a
  full-resolution photo every time it's told to.
- **ControlButton** (the *controller* app) runs on the phone in your hand. It
  discovers ControlCam phones automatically and has one big shutter button
  that fires **every camera at once**.

No accounts, no cloud, no internet — just two phones on the same WiFi.

## Why

Useful whenever you want one or more cameras firing from a distance, without
touching them:

- **DIY document/book scanners**: mount the camera phone on a stand over
  your scan surface and page through books or stacks of documents triggering
  from your other phone — the camera never moves, so framing is identical in
  every shot and there's no tap-shake blurring your scans
- Multi-angle shots of the same moment (skate tricks, experiments, birds…)
- Photo-booth / tripod setups with the photographer in frame
- Stop-motion or repeatable setups where the phone must not move between shots
- Giving an old phone a second life as a static camera

## How it works

Discovery and triggering use a tiny text protocol (`common/Protocol.kt`):

1. Each camera broadcasts a UDP beacon every couple of seconds on port
   `47821`: `CCAM1|CAM|<tcpPort>|<name>`
2. The controller listens for those beacons and keeps a live list of cameras
   (manual `ip:port` entries are also supported).
3. Pressing the shutter opens a short TCP connection to every camera
   (port `47822`) and sends `CCAM1|SNAP|<id>`.
4. Each camera captures a full-res JPEG through [CameraX], plays a shutter
   click, and saves it to `DCIM/ControlCam` in the phone's gallery, then
   answers with an ack that the controller surfaces as a beep + vibration.

## Features

**Camera app**

- Foreground service with camera preview and a 5×10 framing grid overlay
- Front/back lens switch, flash auto/on/off toggle
- Shutter sound, on-screen status HUD (IP, lens, flash, last saved file)
- Holds Wi-Fi multicast + wake locks so it never dozes off mid-session
- Photos go straight to MediaStore (`DCIM/ControlCam`) — no storage
  permission needed on modern Android

**Controller app**

- Giant full-screen shutter button, colour-coded (idle / sending)
- Volume up/down keys work as a remote shutter
- Fires all discovered cameras simultaneously, tracks per-shot acks
- Audible ack/nack tones and haptic feedback
- Add cameras manually by `ip:port` if UDP broadcast is blocked on your router
- Peers that stop beaconing are pruned automatically

## Project layout

```
common/     shared protocol, LAN/broadcast helpers, wake-lock holder
camera/     ControlCam — the camera app (CameraX + MediaStore)
controller/ ControlButton — the shutter remote
```

Both apps target Android 8.0+ (minSdk 26) and are written in Kotlin.

## Building

Standard Android/Gradle project:

```bash
./gradlew :camera:assembleDebug      # ControlCam
./gradlew :controller:assembleDebug  # ControlButton
```

Then install the APKs on the phones:

```bash
adb install -r camera/build/outputs/apk/debug/camera-debug.apk
adb install -r controller/build/outputs/apk/debug/controller-debug.apk
```

Release builds are unsigned by default; if you want them signed, drop your
keystore at `keystore/controlcam.jks` and set `CONTROLCAM_STORE_PASSWORD`,
`CONTROLCAM_KEY_ALIAS` and `CONTROLCAM_KEY_PASSWORD` in your environment.

## Using it

1. Put both phones on the same WiFi (a hotspot from one of them works too).
2. Open ControlCam on every camera phone, grant the camera permission, and
   frame your shot. Note the phone won't sleep while it's running.
3. Open ControlButton on your phone — cameras appear in the list within a
   few seconds.
4. Tap the big button (or press volume). Every camera fires, beeps confirm
   each shot, and photos land in each camera phone's gallery.

## Tips

- Some routers block UDP broadcast between clients ("AP isolation"). If no
   cameras show up, use the `+` button on the controller and add the camera's
   IP manually (it's shown on the camera's HUD).
- For long sessions, exclude both apps from battery optimization — they'll
   ask on first run.

[CameraX]: https://developer.android.com/jetpack/androidx/releases/camera
