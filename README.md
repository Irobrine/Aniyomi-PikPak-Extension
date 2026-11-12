# Aniyomi PikPak Extension

A direct [PikPak](https://mypikpak.com) extension for [Anikku](https://github.com/komikku-app/anikku) and Aniyomi-based apps.
It lists your PikPak cloud drive as an anime catalog and streams episodes straight from PikPak — no
StremThru, Real-Debrid or other intermediary service.

[![Build](https://github.com/Irobrine/Aniyomi-PikPak-Extension/actions/workflows/build.yml/badge.svg)](https://github.com/Irobrine/Aniyomi-PikPak-Extension/actions/workflows/build.yml)

## Features

- **Direct PikPak API** — sign in once and browse `My Pack`, including recursive folder listings.
- **Seasons** — top-level folders are anime; their sub-folders are seasons. Numeric labels
  (`Season 1`, `S01`) are renamed to `Anime S01`, free-form labels become `Anime <label>`.
- **Automatic flattening** — an anime folder without sub-folders opens straight into its episode list,
  with no synthetic season screen.
- **Extra folders** — add folders by ID or path (for example `My Shared/Anime`) and their anime are
  pinned to the top of the catalog.
- **AniList metadata** — cover, banner, studio (shown as author), synopsis, characters,
  recommendations and related entries. TMDB is used as a fallback, especially for non-anime movies
  and TV shows, when an API key is provided.
- **Episode names** — `E01` style names, with numbers read from `E09`, `- 09`, `[09]`,
  `Episode 9`, and API episode data as a fallback.
- **Direct playback** — episodes resolve to PikPak's own stream links.

## Requirements

- Anikku, or an Aniyomi build that supports extensions-lib **16**.
- A PikPak account with the files already in `My Pack` (or in an extra folder you add).
- This extension is not part of a repository index yet, so install the APK manually.

## Install

### Build the APK

```bash
./gradlew :src:all:pikpak:assembleDebug
```

The APK is written to:

```
src/all/pikpak/build/outputs/apk/debug/aniyomi-all.pikpak-v<version>.apk
```

A JDK 17 or newer and the Android SDK are required. Point Gradle at the SDK with `ANDROID_HOME` or a
`local.properties` file containing `sdk.dir=...`.

### Sideload

Open the APK on your device and install it, then enable the **PikPak** source in
**Settings → Browse → Extensions**. Reinstalling over an older build keeps your settings.

## Setup

### Account

Enter `email:password` in **Extensions → PikPak → PikPak account**.

The extension signs in through PikPak's web client. PikPak rate-limits repeated password logins
(`Your operation is too frequent`); when that happens, wait a while before trying again.

### Refresh token (recommended when logins are rate-limited)

PikPak refresh tokens are single-use and rotate on every exchange, so the extension saves the rotated
token after first use.

1. Log in at [mypikpak.com](https://mypikpak.com).
2. Open the browser dev tools (`F12`) → **Application** → **Local Storage** → `https://mypikpak.com`.
3. Open the **`credentials`** entry and copy the **`refresh_token`** value (pasting the whole JSON also works).
4. Close the tab **without logging out** — an open web app refreshes and rotates the token in the
   background, invalidating the copy.
5. Paste it into **PikPak refresh token (optional)**.

Quota: the extension only reads your drive and generates playback links; it never uploads or deletes.

### Extra folders

**PikPak → Extra folders** accepts folder IDs or paths, separated by commas, for folders outside
`My Pack` (for example shared folders):

```
My Shared/Anime, /My Shared/Movies
```

The anime folders inside them appear at the top of the catalog. Paths are matched by name and are
case-insensitive; a folder with no sub-folders is kept as a single entry.

## Organizing PikPak folders

The extension maps folders to how Aniyomi expects anime to look:

| PikPak layout | What you get |
| --- | --- |
| `My Pack/Anime Name/episode files` | One anime with a flat episode list |
| `My Pack/Anime Name/Season 1/...`, `.../Season 2/...` | Seasons `Anime Name S01`, `Anime Name S02` |
| `My Pack/Anime Name/Dear Sister/...` | Seasons `Anime Name Dear Sister` |
| `My Pack/Anime Name/...` with files in nested folders | Sub-folders are treated as seasons |

Tips:

- Keep release-group, quality, and ID tags out of anime folder names, e.g.
  `My Pack/[Group] Horimiya [1080p]/` becomes `Horimiya` in the catalog.
- Anime folder names are matched against AniList, so using the official title gives the best result.
- Episodes are numbered from their file names; files without a number are numbered in name order.

## Metadata

- **AniList** is queried first, with an optional season suffix (`Anime Name Season 2`).
- **TMDB** is used when AniList has no match. Add a free API key in **TMDB API key (optional)**
  to enable it, especially for non-anime movies and TV.
- Aniyomi has no dedicated fields for characters or suggestions, so they are appended to the
  description under **Characters**, **Recommendations**, and **Related**.

## Troubleshooting

| Problem | Fix |
| --- | --- |
| `PikPak is rate-limiting logins` | Wait a few minutes, or use a refresh token. |
| `PikPak rejected the refresh token` | Tokens rotate on every refresh. Copy a fresh one with the web tab closed. |
| `PikPak sign in HTTP 400: invalid_account_or_password` | Check the account. Accounts created via Google/Facebook may need a password set in PikPak. |
| Metadata missing for a folder | Use the official title as the folder name; check `adb logcat -s PikPak PikPakMetadata`. |
| No playback link | PikPak did not return a stream link for the file; check that the account can play it in the PikPak app. |

Logs are available with:

```bash
adb logcat -s PikPak PikPakAuth PikPakMetadata
```

## Development

Project layout:

```
src/all/pikpak/    extension module (sources, resources, build metadata)
core/              shared keiyoushi helpers used by the extension
compiler/          KSP code generation
common/            shared manifest and ProGuard rules
gradle/build-logic build plugins and version catalogs
```

Useful commands:

```bash
./gradlew :src:all:pikpak:assembleDebug    # build an installable APK
./gradlew :src:all:pikpak:compileDebugKotlin
./gradlew :src:all:pikpak:spotlessCheck    # lint
```

Version bumps: increase `versionCode` in `src/all/pikpak/build.gradle.kts` by one per change that
affects users. `versionName` is generated as `<libVersion>.<versionCode>`.

## Credits

- Built on the [Yuzono anime extensions](https://github.com/yuzono/anime-extensions) Gradle setup and
  the [Aniyomi extensions-lib](https://github.com/komikku-app/aniyomi-extensions-lib).
- Metadata from [AniList](https://anilist.co) and [TMDB](https://www.themoviedb.org).
- PikPak is a trademark of its respective owner; this project is not affiliated with PikPak.

## License

[Apache License 2.0](LICENSE)
