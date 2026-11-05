# Sealed Updater

Silent, automatic updates for the sideloaded apps on a locked-down phone,
built around three rules: the phone trusts exactly one signed list, the list
only ever names updates to already-installed allowlisted apps, and every
file is verified against the list and against the installed app's signer
before it is installed.

## Pieces

- **App** (`app/`): fetches `feed/list.json` + `feed/list.json.sig` from this
  repo (raw URLs), verifies the ECDSA P-256 signature against a public key
  compiled into the app, then for each entry checks, in order: allowlisted,
  installed, newer, APK sha256 matches the list, APK package/version match,
  APK signer matches the list AND matches the installed copy. Survivors go
  to PackageInstaller. Without the device owner's install delegation it
  shows Android's normal prompt (testing); with it, installs are silent.
  A persisted job runs the check about twice a day. There is no settings UI.
- **Watcher** (`tools/watch.py`): run by `.github/workflows/watch.yml`
  (manual trigger until the phone test passes, then scheduled). Checks each
  app's official source (GitHub releases; F-Droid repo), inspects the real
  APK (package, versionCode, sha256, signer cert), refuses to publish if a
  signer ever changes, signs the updated list with `LIST_SIGNING_KEY`
  (GitHub secret), and commits it. APKs themselves are never re-hosted;
  the phone downloads them from the original sources.
- **Feed** (`feed/list.json`): plain JSON, one entry per app:
  package, versionCode, versionName, url, sha256, certSha256.

## Phone test (unsealed)

1. Install the built APK.
2. Install an older ClearSMS (e.g. v0.24.1) from its GitHub releases.
3. Open Sealed Updater, tap "Check now": it should offer/install the
   v0.25.0 update (with the system prompt in this phase).
4. Negative tests: an app not in the list is ignored; editing any byte of
   list.json without re-signing makes the app refuse the whole feed.

## Remaining setup-day steps (later phases)

- Grant the app the device owner's DELEGATION_PACKAGE_INSTALLATION scope
  (or make it the owner) so installs become silent.
- Add the LIST_SIGNING_KEY secret; enable the watcher schedule.
- Network slit for this app in Rethink: raw.githubusercontent.com,
  github.com, objects.githubusercontent.com, f-droid.org.

## Two builds (same app, same key)

- **Normal build** ("Build APK" workflow): updates only. This is the sealed build.
- **SETUP build** ("Build SETUP APK" workflow): additionally installs the first
  copy of each allowlisted app when it is missing - same signature, hash and
  signer verification, nothing else changes. It exists for setup day only: it
  first-installs the eight apps so this app becomes their installer of record
  (which is what makes later updates silent), then the normal build is
  installed over it and the setup capability is gone. The launcher label of
  the setup build reads "Sealed Updater (Setup)" so the two can never be
  confused on the phone.

When the signed list names an earlier version of an app ("previous"), the
SETUP build installs that earlier version first instead of the current one -
so the update to current can be exercised afterwards, under the MDM
restrictions, before anything is sealed.
