# NOVA Contacts

The contacts manager for **NOVA OS** — a full contacts app for Android 10+.

- **Contact list** with letter section headers and a Favorites row
- **Search** including T9 (type 2-ABC, 3-DEF…)
- **In-app add / edit editor** — writes directly to the system contacts provider
- **Contact details** — all phones, emails, organization and notes; star toggle,
  call, message, email, share as vCard, edit, delete (with confirmation)
- **Duplicate finder + merge** — groups contacts sharing a phone number
  (normalized to the last 10 digits, so +880… / 017… / plain forms collide);
  merging keeps the most complete contact and unions phones and emails
- **vCard import / export** — `.vcf` via the system file picker (vCard 3.0,
  with escaping and folded-line support)
- **Dark / light theme**, persisted
- **In-app Error log** — every crash and failed operation is recorded with its
  time, so problems can be reported with the exact error

## Build

This project does **not** use Gradle. It builds with a manual pipeline
(`aapt2` + `kotlinc` + `d8` + `apksigner`), which is what `tools/build-apk.sh` runs.

### Prerequisites

- Android SDK with `platforms/android-35` and `build-tools/35.0.0`
  (set `ANDROID_HOME`, e.g. `~/android-sdk`)
- JDK 17 (set `JAVA_HOME`)
- The NOVA Files toolchain — this build reuses the JDK, kotlinc compiler and
  dependency jars from a NOVA-Files checkout. Check it out next to this repo
  (or set `NOVA_FILES_ROOT` to its path). The Kotlin compiler 2.0.21 jars
  (`kotlin-compiler-embeddable-2.0.21.jar`, the Compose compiler plugin)
  and `trove4j` come from that checkout (see `tools/build-apk.sh`).

### Steps

```bash
# 1. Fetch the dependencies into deps/ (not committed — AARs/JARs)
python3 tools/resolve_deps.py deps/ $(cat tools/deps.txt)

# 2. Extract them into the build tree
python3 tools/prep_deps.py

# 3. Build the APK
bash tools/build-apk.sh
# -> build/apk/nova-contacts-debug.apk
```

The APK is signed with a debug keystore generated on first build.
Minimum SDK 29 (Android 10), target SDK 35.

## Version history

- **v1.5** — Audit bug fixes: the editor no longer corrupts a contact's job
  title into the company name (company and title are now separate fields),
  and phone/email types are no longer flattened to Mobile/Other on every
  edit — the editor has a type picker per row and preserves existing types.
  Merge keeps the losers' phone/email types too, and the detail screen's
  Call button uses `Uri.fromParts` so `#`/`*` in a number can't truncate it.
- **v1.4** — Advanced: Recent calls in the contact detail screen (last 10
  calls with that contact, matched by number, with an on-demand call-log
  permission request — the app works without it) and Merge all duplicates
  (one tap merges every duplicate group, with a confirmation dialog).
- **v1.3** — Full audit fix (data loss): editing a contact deleted EVERY
  detail row except the name — silently destroying the contact's photo,
  group memberships, websites and other data. Only the rows this editor
  manages (phones, emails, organisation, note) are now replaced. Also:
  vCard export skips nameless rows, which produced broken entries.
- **v1.2** — The system back button now steps back to the contact list
  from details, editor, duplicates or the error log, instead of exiting
  the app.
- **v1.1** — Permission-crash fix: the Activity Result API always generates a
  permission request code of 65536 or higher, and `FragmentActivity` rejects
  any code above the lower 16 bits — so tapping "Allow access" always
  crashed. `MainActivity` now extends `ComponentActivity`.
- **v1.0** — Letter-section list, favorites, search + T9, in-app editor,
  details, duplicate finder + merge, vCard import/export, themes, Error log.

## License

MIT — see [LICENSE](LICENSE).
