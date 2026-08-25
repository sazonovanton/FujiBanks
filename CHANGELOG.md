# Changelog

## 1.0.0

First public release. The app has been in daily use against an X-T30 III; this
is the point at which the source was opened rather than the point at which it
started working.

Requires Android 8.0 (API 26) and a phone with USB host support. It asks for no
permissions at all — USB access is granted per device by Android's own dialog.

**Camera**

- Read all seven recipe banks C1–C7 over PTP and open any of them.
- Install a set of recipes into the banks, with a full backup taken first, a
  slot-by-slot diff shown before anything is sent, a byte-for-byte read-back
  afterwards, and Undo.
- Save what is currently in the camera to the library, one bank at a time or all
  seven as a set.

**Recipes**

- Paste a recipe as text — Fuji X Weekly's format, or anything close to it — with
  a preview of what was recognised and what was ignored.
- Build one by hand, group recipes into sets, attach a sample frame taken with
  the recipe.
- Generated artwork per recipe, computed from the tone curve, film simulation,
  white balance and grain, so two recipes that differ look different.

**Develop**

- Push a RAF into the camera and let its own processor render it, then save the
  JPEG or keep the recipe.

**Photos**

- Browse the card as a thumbnail grid and copy frames to the gallery or to a
  folder of your choosing. Needs `USB CARD READER` mode.

**Backup**

- Snapshot any subset of banks, restore any snapshot back, with a confirmation
  that names exactly which slots move.

**Debug** — long-press the title

- Property dump, object listing, property sweep with a diff against a saved
  baseline, and a copyable log.

### Known limits

- **Tested on an X-T30 III and on no other body.** The property map came from
  FilmKit, measured on a different camera, and the two already disagree in two
  places — so the map is known to vary. On anything else, run Dump first and
  check it before trusting a write. See the README.
- **Develop cannot raise dynamic range above what the frame was shot at.** DR is
  underexposure at capture plus a shadow lift, so DR200% needs a stop of
  headroom in the exposure and DR400% needs two. Ask a frame for more and the
  camera returns crushed reds and blues rather than an error. Develop detects
  the mismatch and explains it; the conversion is still sent as the recipe
  asks, because that choice is yours.
- Develop does not force a white balance *mode* on a conversion — shifts and
  colour temperature are applied, the mode is inherited from the frame. The
  profile field that would carry it has not been identified.
- FS1–FS3, the film simulation dial slots, are not reachable over PTP by any
  software, Fujifilm's own included.
- The release build is not minified, so the APK is larger than it needs to be.
