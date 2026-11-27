# M-Film Darkroom for Android

Develops the CR2s a **Magic Lantern M-Film camera** shot, on a phone, at full print size, in
seconds instead of the camera's minute a frame. Any other RAW and any photo the phone can read
develops too, with the same film look.

It is a port of the **camera's own darkroom**, not of the closed-source desktop program: the
sources are the GPL-2.0 ones in [`ml-dev/filmos/`](../ml-dev/filmos), copied into
`app/src/main/cpp/mfilm/`, and the film colour is the same `LOOKnn.LUT` tables the release puts on
the card in `ML/DARKROOM`. Nothing from *Darkrooms* is used or reverse engineered.

## What it does

* pick RAW files or photos (one or many) with Android's file picker — from the phone, an SD card,
  or a USB card reader, the folder `DCIM/100CANON` on a card that came out of the camera
* or open a CR2 from a file manager and share one to it from another app — the frame is added to
  the queue and developed with the film and size already chosen
* see the frame **before** you develop it: the preview panel runs the same engine at its 720 × 480
  print, so the look on screen is the look DEVELOP writes — tap the preview to go through the
  picked frames one at a time. The frame is decoded once and kept, so a slider or another film
  redraws it in a moment instead of reading the RAW again
* choose one of the **68 film stocks** on a shelf like the camera's — COLOUR / SLIDE / B&W / CINE,
  canisters in their box colours, with the film's blurb, ISO and process under the name
* **open a roll**: point it at the folder holding `ROLL_NNN.TXT` and the app reads the log, finds
  the frames it lists, takes the film the roll was shot on, and shows them as a contact sheet —
  tap one for a full preview, tick what you want (or select all) and they land in the darkroom
* **shadows and glow**: a Shadows slider lifts the dark end of the frame (some photos come out
  darker than the phone showed them), and a Glow panel with Lightroom's five styles — Bloom,
  Diffusion, Halation, Motion and Streak — each with Amount, Range (how far down the tones it is
  gathered from), Spread (how far it reaches) and Warmth (its tint). They start at 50, 50, 50, 0
  and Off; every preview and every DEVELOP goes through the values on screen
* **keep a darkroom by name**: *Save this*, above the sliders, stores the film, every knob, the
  print size and the stamp under a name; one tap later puts all of it back, a long press forgets it
* **before and after in the hand**: hold the preview to see the same frame with the Shadows and the
  glow taken off, let go for the print; the bar under it is the frame's histogram, its clipped ends
  marked in red
* **straighten and crop on the preview**: a Straighten slider turns the frame up to 10° either way,
  a Crop row picks the shape (whole frame, 1:1, 4:5, 3:2 or 16:9) and a Zoom slider tightens it. The
  preview turns as the finger moves — no develop — and DEVELOP cuts the print to the same window, so
  what the panel shows is the print that comes out. The crop is the biggest one of that shape that
  still lies inside the turned frame: no corner of the frame is left as a black wedge
* develop into `Pictures/M-Film`, JPEG with the film in its EXIF and the frame's own — the camera,
  the lens, the ISO, the shutter and the focal length the CR2 was shot with go into the print, and
  its caption (the film) is in the XMP too. A roll's prints go into their own
  `Pictures/M-Film/ROLL_NNN`, and every batch adds its frames to a `manifest.csv` beside them (the
  film, every knob, the size, the stamp, the time and the file it wrote)
* **share the print** — the last print DEVELOP wrote goes to whichever app the phone offers
  (messages, mail, Drive, a photo editor) from one button under the log
* **come back to where you were**: the film, the Shadows and Glow values, the print size, the date
  stamp and the frames picked last time are kept, so a roll shot on Gold 200 opens on Gold 200
  with its trail values where you left them
* **leave the app while it works**: a roll develops in the foreground with a wake lock, so the
  notification shade shows the frame being developed and going to the gallery doesn't stop it

| Print size | Pixels | What it is |
| **Medium** | 1728 × 1152 | a third of the sensor each way |
| **Large** | 2592 × 1728 | half of it, the biggest the camera's engine makes |

These are the camera's two bigger sizes (`FOS_SIZE_M`, `FOS_SIZE_L`). The camera takes about a minute
a frame for Large; a phone does it in a few seconds. The desktop *Darkrooms* goes to the full RAW
(5184 × 3456) — the camera engine keeps its sums in bands of a megabyte built for DIGIC memory, so
full resolution is a bigger change than this port (`BIG_BANDS` in `fos_dev.c`).

## Any file, not just a CR2

A CR2 is decoded by the engine sample by sample, as on the camera. Every other file is developed
from the picture it carries:

* an image (JPEG, PNG, WebP, HEIF) is the picture itself, turned upright by its EXIF
* a RAW of another make (CR3, NEF, ARW, DNG, RAF, ORF, RW2, PEF and the rest) carries a JPEG
  preview inside it; the app finds the largest one — the frame, not the thumbnail — and develops
  that. It is 8-bit, so a CR2 still has the most in it, but the film, the grain and the glow are
the engine's own either way.

Printed sizes for a picture are its own: Large is half its width and Medium a third (and never
smaller than the screen's print). The picture is decoded at its own size -- a 12 MP phone photo
develops from all twelve million pixels, so its Large print is 2000 × 1500 -- and only a file too
big for the phone's heap is brought down first.

## The roll log

The camera's filmroll module leaves a `ROLL_NNN.TXT` in the folder beside the frames:

```
# ML film mode roll log (filmroll module)
roll = 013
film = redscale100
exposures = 24
loaded = 2026-11-25 16:07:36

1 IMG_3657.CR2 2026-11-25 16:08:08 f/4.5
...
```

*Open a roll* asks for that folder (the folder itself, not the text file: Android hands an app a
folder and the app lists what is in it). It matches each listed frame by name and sets the film
from the `film =` line, so a roll imports with the stock it was shot on instead of whatever was on
the shelf.

Frames are matched by their **number**, not their whole name: with the camera in Adobe RGB,
Canon writes `_MG_3687.CR2` while the log records `IMG_3687.CR2` (the module logs Magic Lantern's
file prefix), and the number is what the two have in common. The sheet shows the name the card
has.

A card keeps every log it has written, so a folder can hold several `ROLL_NNN.TXT` — some of them
for rolls whose frames have already been copied off it. The sheet therefore names the log it read
(in the line under the title) and starts with the newest one that still has frames in the folder.
**ROLL LOG** lists all of them, each with how many frames it lists and how many of those are here,
so the right roll is one tap away.

## Build it

No PC needed: push this folder to a GitHub repository (or a fork of
`HNGSeries/mfilm-canon-film-simulation`) and the workflow
[`.github/workflows/build-apk.yml`](../.github/workflows/build-apk.yml) builds
`M-Film-Darkroom.apk` on every push — download it from the run's **Artifacts**.

Locally, with JDK 17 and the Android SDK (`platforms;android-34`, `build-tools;34.0.0`,
`ndk;26.1.10909125`, `cmake;3.22.1`):

```sh
cd android
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

**The APK is signed with one key, and it has to be.** Android refuses to install an update whose
signature changed; a debug build is signed with a key the build machine makes up, so every CI run
would hand out an APK the phone treats as a different app, and the old one has to be uninstalled
first. The key therefore lives in the repository's secrets and nowhere else:

| Secret | What it is |
| `MFILM_KEYSTORE_BASE64` | the PKCS#12 keystore, base64 |
| `MFILM_STORE_PASSWORD` | its password (the key's is the same) |
| `MFILM_KEY_ALIAS` | `mfilm` |

The workflow writes the keystore out of them, builds with them set as `MFILM_KEYSTORE`,
`MFILM_STORE_PASSWORD` and `MFILM_KEY_ALIAS`, and then checks the finished APK's certificate:
`apksigner verify --print-certs` must show the one signer, `25:5B:C4:…:2F:47:8D`. Without the
secrets it fails rather than ship an APK the phone can't update over.

Builds from **1.2.6** on carry that key. The 1.2.5 APK and earlier were debug-signed with a key made
up on each run, so installing 1.2.6 over one of those means uninstalling it first — copy the prints
out of `Pictures/M-Film` before that, because Android removes the files an app created when the app
goes.

## How it works

```
MainActivity.java ──► MfilmNative.java ──► mfilm_jni.c ──► fos_dev.c  (the camera's darkroom)
   pick, choose,        JNI declarations     the bridge      decode CR2, white balance,
   save, progress                                             tone, film table, grain,
                                                              highlight halation, JPEG
                                          port.c: fopen/clock/malloc, as the camera's filmroll.c
```

* `fos_dev_start` reads the CR2 (Canon lossless JPEG in vertical slices), applies the camera's
  white balance, exposes it as a scanner would, and reads the film's `LOOKnn.LUT`.
* `fos_dev_step(40)` does a slice of work at a time, which is what gives the progress bar.
* `fos_dev_big(FOS_SIZE_L)` makes the larger JPEG; `fos_jpeg` writes it with EXIF.
* The film tables are baked at build time from the film model and the Film-Luts LUTs
  (`ml-dev/filmos/tools/gentables.py`): 53 from LUTs, 9 from spektrafilm, 6 from looks.

## Licence

The camera side and this port are **GPL-2.0** (`LICENSE`), as Magic Lantern is. The film colour
tables are CC BY-SA 4.0 from Pat David's LUTs and spektrafilm — see `THIRD_PARTY.md`. Both are
inside the APK and shown in the app under *Licences*. Not affiliated with Canon, Kodak, Fujifilm or
any film maker.
