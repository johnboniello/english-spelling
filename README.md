# Spelling EN — English spelling practice

A small Android app for practising English spelling words. It is a port of
[Dictée FR](https://github.com/johnboniello/french-dictee) (`~/coding/french-spelling`)
with the same games, scanning and sync; only the language-specific parts differ.
Keep the two in step: a fix to one usually belongs in the other.

The web version lives in `johnboniello.github.io/src/spelling/` (served at
`/spelling/`), next to the French one in `src/dictee/`.

## Home screen

- **🔤 Scrambled Letters** — drag the scrambled letter tiles into place
- **🎯 Pick the Spelling** — hear the word, pick the correct spelling out of four
- **✏️ Write the Word** — listen → write on paper → type it in → get corrected
- **🔁 Words to review** — appears once she has missed words; they come back
  until she gets them right a few times
- **📚 Manage Words** — enter / paste / delete the words, family-code sync
- **📷 Scan a List** — photograph a word list and add it automatically

## What differs from Dictée FR

| | Dictée FR | Spelling EN |
|---|---|---|
| Voice | `Locale.FRANCE` | `Locale.US` |
| Letter names | "a", "espace", "trait d'union" | capitals ("A" is read "ay", lowercase "a" as "uh"), "space", "hyphen" |
| Keyboard | a–z + accents, œ, ç | a–z, `'`, `-`, space |
| Wrong choices (`SpellingVariants.kt`) | accents, French endings (er/é/ez, eau/au…) | vowel teams (ie/ei, ee/ea…), silent letters (kn, wr, gh, final e), doubled consonants, endings (tion/sion, le/el, y/ey…), c/k, s/z |
| Mascot | rooster | owl (placeholder emoji art) |
| Package | `com.johnb.frenchspelling` | `com.johnb.englishspelling` |

## Sync

Uses the same Cloudflare Worker as Dictée FR (`worker/` in the french-dictee
repo — nothing to deploy). Every code is stored server-side as `en-<code>`, so
an English list never merges with a French one even if the family uses the same
code in both apps. Because the worker caps keys at 40 characters, codes here are
8–37 characters.

## Requirements on the device

- Android 8.0 (API 26) or newer.
- **English text-to-speech voice** — nearly every device has one.
- Works offline (games, TTS, OCR). First use of the camera prompts for permission.

## Building

Self-contained toolchain lives under `~/android-sdk` (JDK 17 + Android SDK).

```bash
./build-apk.sh
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (installs as
`com.johnb.englishspelling.debug`, "Spelling TEST", next to a release build).

**Release builds:** `./gradlew :app:assembleRelease` signs with
`keystore/english-spelling.jks`, read from `keystore.properties`. Both are
git-ignored and exist only on this machine — **back them up**. Every future
update must be signed with the same key, or phones that installed the APK
can't update without uninstalling (and losing her word list).

Publishing a release: bump `versionCode`/`versionName` in `app/build.gradle.kts`,
build, then attach `app-release.apk` as **`spelling-en.apk`** — the site's
install page links to `releases/latest/download/spelling-en.apk`.

## Installing on the device

```bash
~/android-sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the phone and open it (allow "install unknown apps").
