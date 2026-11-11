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

- **v1.0** — Letter-section list, favorites, search + T9, in-app editor,
  details, duplicate finder + merge, vCard import/export, themes, Error log.

## License

MIT — see [LICENSE](LICENSE).
