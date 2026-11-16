# Aniyomi PikPak Extension

A direct PikPak extension for Aniyomi-based apps.
It lists your PikPak cloud drive as an anime catalog and streams episodes straight from PikPak — no
StremThru, Real-Debrid or other intermediary service.

[![Build](https://github.com/Irobrine/Aniyomi-PikPak-Extension/actions/workflows/build.yml/badge.svg)](https://github.com/Irobrine/Aniyomi-PikPak-Extension/actions/workflows/build.yml)

> **Built on [Yuzono's anime-extensions](https://github.com/yuzono/anime-extensions)** — the Gradle
> setup, module layout and shared helpers this extension is developed with. Thanks to its authors and
> contributors.

## Features

- **Direct PikPak API** — log in once through PikPak's own web page and browse `My Pack`, including
  recursive folder listings.
- **Seasons** — top-level folders are anime; their sub-folders are seasons. Numeric labels
  (`Season 1`, `S01`, `第2季`, `2기`, `Temporada 2`) become `Anime S01`, parts and cours keep
  their label (`Anime S01 Part 2`), and free-form labels become `Anime <label>`. Disc, quality,
  subtitle and release-group folders are ignored instead of becoming fake seasons.
- **Automatic flattening** — an anime folder without season folders opens straight into its
  episode list, with no synthetic season screen. Seasons carried only by file names
  (`Show S01E01`, `Show S02E01`) are detected too.
- **Extra folders** — add folders by ID or path (for example `My Shared/Anime`) and their anime are
  pinned to the top of the catalog. Loose video files next to series folders are kept as entries.
- **AniList metadata** — cover, banner, studio (shown as author), synopsis, characters,
  recommendations and related entries. Candidates are ranked by title, aliases, year, format and
  season, and weak matches are rejected. TMDB is used as a fallback for non-anime movies and TV
  shows, and for missing fields, when an API key is provided.
- **Episode names** — `E01` style names, with numbers read from `S01E01`, `1x01`, `EP01`, `E01`,
  `Episode 9`, `第01话`, `Folge 5`, `Tập 4`, ranges (`E01-E03`), decimals (`05.5`) and absolute
  numbering. Years, resolutions, codecs, bit depths, audio channels, discs and part numbers are
  rejected as episode numbers. Files without a number are numbered in name order.
- **Classification** — folders and files are classified as series, movie, episode, special, OVA,
  ONA or short, so single movies and specials do not look like ordinary seasons.
- **Direct playback** — episodes resolve to PikPak's own stream links.

## Requirements

- Aniyomi build that supports extensions-lib **16**.
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

### Log in with PikPak

**PikPak → Log in with PikPak (web)** opens PikPak's own login page inside the app. Sign in there as
you would on the website; the extension imports the session when login completes and closes the page.
The login page is the only way to authenticate — there is no password or token field.

Because that web page is destroyed after the import, nothing else rotates the token: the extension
refreshes and stores it on its own from then on, so the login is a one-time step.

The session is kept until PikPak invalidates it or you tap **Log out of PikPak**. Logging out also
clears the PikPak page session, so the next login starts signed out. If the session becomes invalid,
the extension reports that it was rejected and asks you to log in again; a stale token found in the
browser storage is never imported.

The **Session** row shows whether you are logged in and how long the current link is valid.

Extension settings survive updates, so reinstalling over an older build keeps you logged in. Session
tokens are encrypted with a key that stays in the Android Keystore.

Quota: the extension only reads your drive and generates playback links; it never uploads or deletes.

### Extra folders

**PikPak → Extra folders** accepts folder IDs or paths, one per line, for folders outside
`My Pack` (for example shared folders). Commas inside a folder name are preserved:

```
My Shared/Anime
/My Shared/Movies, Documentaries
```

Values written by older versions (comma separated) are converted automatically on first use.

The anime folders inside them appear at the top of the catalog. Paths are matched by name and are
case-insensitive; a folder with no sub-folders is kept as a single entry. **Check extra folders**
resolves the configured entries and reports how many anime each one contains.

### Catalog options

- **Sort** and **Type** filters order the catalog by newest, oldest, or name, and can hide single files.
- Search matches the whole catalog, not just the first page.
- **Detect flat folders** probes folders so a folder without sub-folders opens straight into episodes.
  Disable it on very large drives to make the catalog load faster; flat folders then show one
  synthetic season instead.
- Episodes expose every PikPak stream variant (for example `PikPak 1080p`), so you can pick a
  lower-bitrate transcode when the original buffers.
- **Preferred resolution** offers only one quality (Automatic, Original, 1080p, 720p, 480p, 360p).
  If the chosen quality is missing, the closest higher resolution is used, then lower ones, and the
  same stream is used for downloads.

## Organizing PikPak folders

The extension maps folders to how Aniyomi expects anime to look:

| PikPak layout | What you get |
| --- | --- |
| `My Pack/Anime Name/episode files` | One anime with a flat episode list |
| `My Pack/Anime Name/Season 1/...`, `.../Season 2/...` | Seasons `Anime Name S01`, `Anime Name S02` |
| `My Pack/Anime Name/Season 1 Part 2/...` | Season `Anime Name S01 Part 2` |
| `My Pack/Anime Name/Cour 2/...` | Season `Anime Name Cour 2` |
| `My Pack/Anime Name/Dear Sister/...` | Seasons `Anime Name Dear Sister` |
| `My Pack/Anime Name/Disc 1/...`, `.../Videos/...` | Ignored as seasons; files stay in the flat episode list |
| `My Pack/Collection/Show A/Season 1/...` | Qualified season `Collection Show A S01`; `Show B` stays separate |
| `My Pack/Movie (2020).mkv` | A movie entry, not a series |
| `My Pack/Anime Name/Extras/...` | Specials season `Anime Name Extras` |

Tips:

- Keep release-group, quality, and ID tags out of anime folder names, e.g.
  `My Pack/[Group] Horimiya [1080p]/` becomes `Horimiya` in the catalog.
- Anime folder names are matched against AniList, so using the official title gives the best result.
- Episodes are numbered from their file names; files without a number are numbered in name order
  and alternate releases (`v2`) are kept and ordered deterministically.

## Metadata

- **AniList** is queried first; all returned candidates are ranked by title, synonyms, year,
  format and season relationship instead of trusting the first search hit. Weak matches are
  rejected, so a folder called `Monster` does not silently resolve to an unrelated release.
- **TMDB** is used when AniList has no confident match, when fields are missing, and especially for
  non-anime movies and TV. Add a free API key in **TMDB API key (optional)**. TMDB seasons are
  resolved using the detected season number, including season 0 specials.
- Titles are normalised for Unicode width, accents, punctuation, release tags, episode markers,
  years in parentheses, multiple series sharing a parent, and common aliases. Multilingual season
  labels and native titles are searched as-is.
- Aniyomi has no dedicated fields for characters or suggestions, so they are appended to the
  description under **Characters**, **Recommendations**, and **Related**.

## Troubleshooting

| Problem | Fix |
| --- | --- |
| `PikPak is not logged in` | Tap **Log in with PikPak (web)** in the extension settings. |
| `PikPak rejected the saved session` | The session was invalidated externally. Log in again with the web login. |
| `PikPak is already logged in` / login page will not load | Close and reopen the extension settings, then retry the web login. |
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
./gradlew :src:all:pikpak:testDebugUnitTest # unit tests (parsing, filters, caches)
```

Version bumps: increase `versionCode` in `src/all/pikpak/build.gradle.kts` by one per change that
affects users. `versionName` is generated as `<libVersion>.<versionCode>`.

## Credits

- This extension is built on [Yuzono's anime-extensions](https://github.com/yuzono/anime-extensions)
  — its Gradle build setup, module layout, shared helpers and extension template. Many thanks to the
  Yuzono authors and contributors.
- Extension API from the
  [Aniyomi extensions-lib](https://github.com/komikku-app/aniyomi-extensions-lib).
- Metadata from [AniList](https://anilist.co) and [TMDB](https://www.themoviedb.org).
- PikPak is a trademark of its respective owner; this project is not affiliated with PikPak.

## License

[Apache License 2.0](LICENSE)
