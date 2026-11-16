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
- **Cloud downloads** — paste magnet or video links, optionally name each one, and PikPak downloads
  them to your drive; progress stays visible while the transfer runs on PikPak's servers.
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

The session is kept until PikPak invalidates it or you tap **Log out of PikPak** and confirm.
Logging out also clears the PikPak page session, so the next login starts signed out. If the session
becomes invalid, the extension reports that it was rejected and asks you to log in again; a stale
token found in the browser storage is never imported.

The **Session** row shows whether you are logged in and how long the current link is valid.

Extension settings survive updates, so reinstalling over an older build keeps you logged in. Session
tokens are encrypted with a key that stays in the Android Keystore.

Quota: the extension only reads your drive, adds the links you submit as cloud downloads, and
generates playback links; it never uploads your local files or deletes anything.

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

### Cloud downloads

**PikPak → Add Cloud Download** sends links to PikPak's offline downloader:

- **Links** accepts one link per line — magnets, direct file URLs and links PikPak itself supports,
  such as share links.
- **Names (optional, one per link)** names the downloads line by line with the Links box. Leave a
  line blank to keep that download's original name; lines without a name at all do the same.
- **Download to** is a folder ID or path like the extra-folders field. It defaults to `My Pack`, and
  an unknown path falls back to `My Pack` instead of failing the download.

**Download** closes the dialog and submits the links in the background. Each link becomes a row under
**Cloud downloads** showing its progress, and the row changes to *Download complete* or an error
message when the transfer finishes. PikPak performs the transfer, so closing the settings page or the
app does not stop it; reopening the settings shows the current progress again. Finished entries are
cleared automatically after a day.

Tap any row to rename it or to move it into another folder. A finished download is renamed straight
away. A running one cannot be renamed at PikPak yet, so the name is kept on the row (*rename queued*)
and written to the file as soon as the transfer completes — the app keeps following the download in
the background for a while, and the queued edit is also applied the next time the cloud downloads
list or the catalog is opened.

The **Cloud downloads** entry in the catalog exists only to host the dialog: closing the dialog, by
Cancel, back/outside tap, or **Download**, also leaves that entry, so it does not strand you on an
empty anime screen.

### Catalog options

- **Sort** and **Type** filters order the catalog by newest, oldest, or name, and can hide single files.
- Search matches the whole catalog, not just the first page.
- **Detect flat folders** probes the folders of a catalog page so a folder without sub-folders opens
  straight into episodes. Each folder is probed once and the answer is remembered, so later loads are
  free; disable it to skip the probe entirely and show one synthetic season for flat folders.
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
  and alternate releases (`v2`, `v03`) are kept and ordered deterministically. Release versions do
  not appear in the episode names shown in the app.

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
