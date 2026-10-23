# App updates

Every commit on `main` becomes a signed release on GitHub. The app finds it and asks before installing anything.

## How it works

1. **CI** (`.github/workflows/release.yml`) runs on every push to `main` and on `v*` tags:
   - runs the unit tests, then builds a signed release APK;
   - sets the version code to the number of commits on `main` (`1.0.<count>`), so each new commit is a higher version;
   - publishes a release `build-<count>` with `Diligent-1.0.<count>.apk` and `update.json` (version, commit, APK link, SHA-256, size, commit title), marked as the latest release.
2. **The app** (release builds only) reads `https://github.com/ArindamJaiman/Diligent/releases/latest/download/update.json`:
   - when you open the app, at most once every 6 hours, plus **Settings → App updates → Check for updates**;
   - nothing runs in the background, and the check is one small request.
3. If the version is higher, it asks **Update found** with the version, commit and size:
   - **LATER** hides that version for 24 hours. Settings can still install it.
   - **UPDATE** downloads the APK, checks its SHA-256 against `update.json`, and gives it to Android's installer, which shows its own confirmation.
4. Android only installs it if it is signed with the same key as the installed app. If it isn't, the app says so and nothing changes.

Debug builds (`dev.diligent.app.debug`) are a separate app and never update themselves.

## One-time setup

### 1. Create the release key

Run this in Git Bash. `keytool` comes with the JDK.

```bash
keytool -genkeypair -v -keystore diligent-release.jks -alias diligent -keyalg RSA -keysize 4096 -validity 10000
```

- It asks for a password. Use the same one when it asks for the key password.
- **Keep `diligent-release.jks` and the password somewhere safe, outside the repo.** `*.jks` is git-ignored. If you lose either, no future build can update the installed app, and you'd have to uninstall and reinstall.

### 2. Add the GitHub secrets

```bash
base64 -w0 diligent-release.jks | gh secret set KEYSTORE_BASE64 -R ArindamJaiman/Diligent
```

```bash
gh secret set KEYSTORE_PASSWORD -R ArindamJaiman/Diligent
```

```bash
gh secret set KEY_ALIAS -R ArindamJaiman/Diligent --body diligent
```

```bash
gh secret set KEY_PASSWORD -R ArindamJaiman/Diligent
```

The two password commands prompt for the value, so it isn't saved in your shell history. Without these secrets, CI stops with an error and doesn't publish an APK that couldn't update your app.

### 3. Install the release app once

After the next merge into `main`, open the new `build-…` release on GitHub and install `Diligent-1.0.<count>.apk`. It installs next to the debug app you have now. Updates come to this one from then on.

### 4. Move your data from the debug app

The debug app writes backups to its private folder, which other apps can't open. Copy the backup over with USB debugging:

1. In the debug app: **Settings → Backup to JSON**.
2. On the PC:

```bash
adb pull /sdcard/Android/data/dev.diligent.app.debug/files/backups/ diligent-backups
```

```bash
adb push diligent-backups/. /sdcard/Download/
```

3. In the release app: **Settings → Restore from JSON**, then pick the file from Downloads.

The backup covers activities, progress, settings, reminders and GitHub developers. LeetCode, QuotaPulse and the Hub tools keep their own settings and need setting up again in the new app.

## Not verified on a device yet

- The install confirmation and the update on a phone. CI has never run, because the secrets don't exist yet.
- The first CI run on GitHub's runners.
