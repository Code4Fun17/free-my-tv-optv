# Help: a faster boot, by measuring before you debloat

Free My TV turns off a short list of promo apps when you switch to it. Every TV also starts other apps at **every boot**
that you may never use: its own weather, a content launcher, a screensaver, smart-home controls, calendar sync. They
start right after the home screen appears and compete for the processor while you pick something to watch, so the
first minute after switching on feels slow.

This guide measures your TV's boot first, so you turn off only what actually starts at boot and that you don't use,
then measures again to see the difference. It works on any Google TV or Android TV that Free My TV runs on.

**Before you start**

- A computer on the same network as the TV, with `adb` (see [Install](README.md#install)) and a bash shell (macOS,
  Linux, or Git Bash on Windows).
- Free My TV built from this repository and in *Free My TV* mode on the TV.
- The TV's IP address. The examples use `192.168.1.50`: use yours.
- **The TV restarts twice** (once to measure, once to compare). Do it when nobody is watching.

## What you can and can't change

| Part of the boot | Typical time | Changeable? |
|---|---|---|
| Bootloader, kernel, early system | 10–15 s | No: the TV's firmware |
| Android starting, scanning apps, boot animation | 15–20 s more | No: Android itself |
| Free My TV's home screen | about 0.5 s of its own code | Already made fast |
| **Apps the TV starts in the background after the home screen** | **the next 30–50 s** | **Yes: turn off the ones you don't use** |

Turning apps off never uninstalls them. Free My TV disables them the same way as its own list (`pm disable-user`),
checks each one against its list of system pieces that must stay on, and *Original* turns them all back on.

## 1. Restart the TV and wait for it

```bash
adb connect 192.168.1.50:5555
adb -s 192.168.1.50:5555 reboot
```

The TV is off for about a minute and drops off the network. When it's back, connect again and wait until it has been
up for 100 seconds, so every app that starts at boot has started:

```bash
until adb connect 192.168.1.50:5555 >/dev/null 2>&1 && \
      [ "$(adb -s 192.168.1.50:5555 shell getprop sys.boot_completed)" = "1" ]; do sleep 5; done
until [ "$(adb -s 192.168.1.50:5555 shell cut -d. -f1 /proc/uptime)" -ge 100 ]; do sleep 5; done
```

If the TV asks *Allow USB debugging?*, tick *Always allow* and accept.

## 2. Read the boot timeline

Android records the main steps of every boot, in milliseconds since the TV was switched on:

```bash
adb -s 192.168.1.50:5555 logcat -b events -d | grep -E 'boot_progress|sf_stop_bootanim'
```

The ones that matter: `boot_progress_start` (Android begins), `boot_progress_ams_ready` (apps can start),
`boot_progress_enable_screen` (the boot animation ends and the screen is ready).

Then when the home screen started, and everything that started during the boot, as seconds since power-on:

```bash
D=192.168.1.50:5555
UP=$(adb -s $D shell cut -d' ' -f1 /proc/uptime); NOW=$(adb -s $D shell date +%s)
adb -s $D logcat -d -v epoch | awk -v n="$NOW" -v u="$UP" '/Start proc/ {
  rest = substr($0, index($0, "Start proc ") + 11); sub(/^[0-9]+:/, "", rest)
  app = rest; sub(/ .*/, "", app)
  why = substr(rest, index(rest, " for ") + 5)
  if (why ~ /^(added application|content provider)/) { split(why, w, " "); why = w[1] " " w[2] } else sub(/ .*/, "", why)
  printf "%6.1f s  %-18s %s\n", u - (n - $1), why, app }'
```

The plain list works too (its times are the TV's clock, not seconds since power-on):

```bash
adb -s 192.168.1.50:5555 logcat -d | grep 'Start proc'
```

Each line says **what** started (`com.example.weather/u0a84`) and **why**:

- `for broadcast`: it asked to wake up at boot. **These are the candidates.**
- `for service`, `added application`, `content provider`: mostly the system and Google's own pieces.
- `for top-activity`: what's on screen, such as Free My TV's home screen (`io.freemytv:home`).

Note the time the home screen started, the number of `Start proc` lines, the number of `for broadcast` lines, and the
time of the last one. Those are your "before" numbers.

## 3. Choose what to turn off

Look up each `for broadcast` app you don't recognise (`adb shell pm list packages -f <name>` shows where it lives; its
name in *Settings* → *Apps* tells you what it is), then sort them:

**Usually safe to turn off**, if you don't use them:

- The TV maker's own weather, news, tips or "content" launcher (Free My TV replaces them).
- The TV maker's screensaver or photo frame (Free My TV is your screensaver).
- Discontinued or duplicate apps, such as Google Play Movies & TV (`com.google.android.videos`).
- Smart-home, shopping or promo apps you never open.
- Calendar sync on a TV nobody reads a calendar on (`com.google.android.syncadapters.calendar`).

**Your call**: apps you might use now and then, such as a file manager (USB drives), DLNA or media sharing (playing
from a phone or PC), a streaming app you rarely watch. Turned off, they don't open until you turn them back on.

**Never turn off**: picture and sound, HDMI inputs and live TV, the tuner, the remote and Bluetooth, the system UI,
Settings, setup and system updates, Google Play services and the Play Store, the keyboard, text-to-speech and
Assistant (unless you mean to), the chip maker's TV services (names with `mediatek`, `realtek`, `amlogic` and the
like) and anything you can't identify. Free My TV refuses many of these anyway (`HomeMode.PROTECTED` and
`mayTurnOff`), but don't rely on that: when in doubt, leave it on.

## 4. Turn them off with `debloat_extra`

Add the packages to your `config.properties` (it stays on your computer and out of git), comma separated:

```properties
debloat_extra=com.google.android.videos,com.example.tv.weather,com.example.tv.launcher
```

Build and install:

```bash
./build.sh
adb -s 192.168.1.50:5555 install -r FreeMyTV.apk
```

An update makes Free My TV complete its mode, so within a few seconds the new apps are turned off. Check:

```bash
adb -s 192.168.1.50:5555 shell pm list packages -d
adb -s 192.168.1.50:5555 logcat -d -s FreeMyTV | grep 'Turned off'
```

An app Free My TV won't turn off (a protected system piece, or an app that saved a Wi-Fi network) stays on, and its
notice in *Free My TV* says why.

## 5. Measure again

Repeat steps 1 and 2 and compare. On a 2 GB TV tested with six apps added, the home screen came up about 2 s sooner,
13 fewer processes started (62 → 49), and the background start-ups ended about 6 s sooner. One boot can vary from the
next by a second or so: for a fair comparison, measure two or three boots each way.

## If adb loses the TV

Restarting the TV (or adb itself) can leave the connection in a stale state: `adb devices` lists the TV as
`offline` or `unauthorized`, or commands hang, even though the TV still trusts this computer and shows no
*Allow USB debugging?* prompt. A clean restart of adb's background server and a fresh connect fixes it:

```bash
adb kill-server
adb connect 192.168.1.50:5555
adb devices
```

`device` means it's back. If it still says `unauthorized`:

- Look at the TV: a prompt may be waiting. Tick *Always allow from this computer* and accept.
- adb identifies your computer with a key in `.android` (in your user folder, or the folder `ANDROID_SDK_HOME` or
  `ANDROID_USER_HOME` points to). A shell that finds a different key looks like a new computer to the TV, so use the
  same one each time.
- After a restart, give the TV a minute to join the network before connecting. If it never answers, check that
  network debugging (or *Wireless debugging*, on newer Google TV) is still on in its developer options.

## Undo

- Everything at once: *Free My TV* → *Home Screen* → *Original* turns every app Free My TV turned off back on,
  `debloat_extra` included.
- One app: take it out of `debloat_extra`, build and install, then `adb shell pm enable <package>` (an app that's
  left the list isn't turned back on by itself).

## The prompt: let an AI assistant do it

Paste this into Claude Code, Codex or a similar coding assistant opened in this repository, with your TV's address:

```text
My Google TV / Android TV is at 192.168.1.50 and runs Free My TV built from this repository.
Help me make its boot faster by turning off apps it starts at boot that I don't use, following HELP.md:

1. Read HELP.md and CLAUDE.md first. Ask me before every step that changes the TV or this computer
   (restarting the TV, building, installing, turning apps off).
2. Restart the TV with adb, wait until it has booted and been up 100 seconds, then measure: the boot_progress
   milestones, when Free My TV's home screen started, every process started during the boot (as seconds since
   power-on) and why, the totals, and when the last one started.
3. Show me the results as a table, and sort the apps that woke up for the boot broadcast into: usually safe to turn
   off, my call, and never turn off (system, picture, sound, inputs, remote, Bluetooth, setup, updates, Google
   services, the Play Store, keyboard, speech, the chip maker's TV services, anything you can't identify).
   Say what each one is. Don't turn anything off yet.
4. When I've chosen, add my choices to debloat_extra in config.properties (never commit that file), build,
   install, and check they are off.
5. Restart again, measure the same way, and show me before and after side by side, with the caveat that one boot
   can differ from the next.
Never uninstall or clear the data of an app; only Free My TV's own way of turning apps off, so Original brings
them back.
```

The assistant does the same thing as steps 1–5 above, explaining each step and waiting for your OK where the TV
changes.
