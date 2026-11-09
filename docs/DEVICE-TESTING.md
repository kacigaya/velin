# Device testing on an arm64 Linux host

Use this setup to run Velin on real Android when the host has no KVM, so the Android emulator cannot start. It runs Android in a container ([redroid](https://github.com/remote-android/redroid-doc)) on the host kernel. The image is arm64, so the arm64-v8a APK runs natively.

The build tools from [Commit451/android-arm-build-tools](https://github.com/Commit451/android-arm-build-tools) build the APK. They do not include `adb` or an emulator.

## Requirements

- An arm64 Linux host with Docker and `sudo`.
- The `binder_linux` kernel module. Check with `modinfo binder_linux`.
- The SDK `platform-tools`. On an arm64 host, the x86-64 `adb` runs through `qemu-x86_64-static` with an x86-64 sysroot.

## Start Android

1. Load the binder driver:

   ```sh
   sudo modprobe binder_linux devices="binder,hwbinder,vndbinder"
   ```

2. Start Android 15 with a phone screen. Bind `adb` to localhost only:

   ```sh
   sudo docker run -d --privileged --name velin-android \
     -p 127.0.0.1:5555:5555 redroid/redroid:15.0.0_64only-latest \
     androidboot.redroid_width=1080 androidboot.redroid_height=2400 \
     androidboot.redroid_dpi=420 androidboot.redroid_gpu_mode=guest
   ```

3. Wait until `sudo docker exec velin-android getprop sys.boot_completed` prints `1`. This takes about 45 seconds.

## Connect and install

`QEMU_LD_PREFIX` must be set in the environment, because the `adb` server starts as a child process and must find the sysroot too.

```sh
export QEMU_LD_PREFIX=$HOME/android-sdk/x86_64-root
adb connect 127.0.0.1:5555
adb -s 127.0.0.1:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 127.0.0.1:5555 shell am start -n app.velin/.MainActivity
```

## Drive and inspect

- Screenshot: `adb exec-out screencap -p > screen.png`.
- Accessibility tree: `adb shell uiautomator dump /data/local/tmp/ui.xml`. Run it through `adb`. As root through `docker exec`, it exits without output. A node with `NAF="true"` is clickable but has no label.
- Input: `adb shell input tap X Y`, `input text`, `input keyevent`. A long press is `input swipe X Y X Y 900`.
- Dark mode: `adb shell cmd uimode night yes`.
- Tablet layout: `adb shell wm size 2560x1600` and `adb shell wm density 320`. Undo with `wm size reset` and `wm density reset`.

## Stop

```sh
sudo docker rm -f velin-android
```

## Limits

- The container has no Google Play services and no camera or sensors.
- Rendering uses software GPU emulation. Do not use it to judge animation smoothness.
- Never put account credentials in scripts, commit messages or logs. Type them on the device from a temporary file with mode 0600, then delete the file.
