# Releasing

Two channels, distinguished by the tag alone. There is no separate branch, no manual step in
the GitHub UI, and nothing to remember beyond the tag name.

| Tag | Channel | GitHub | Obtainium |
|---|---|---|---|
| `v0.2.0-beta.3` | prerelease | published, **not** marked Latest | skipped unless the user opts into prereleases |
| `v0.2.0-rc.1` | prerelease | published, **not** marked Latest | skipped unless the user opts into prereleases |
| `v0.2.0` | stable | published and marked **Latest** | installed |

Anything else with a hyphen (`-alpha.1`, `-snapshot`, a bare `-beta` with no number) is
rejected by the workflow rather than guessed at. Numbering betas matters: `beta.10` must sort
after `beta.9`, and a bare `-beta` cannot be incremented.

## The shape of a release

Work accumulates as betas under the version it is heading for, then ships once under a name
that means something:

```
v0.2.0-beta.1   backup opt-in, export and import
v0.2.0-beta.2   recording limit
v0.2.0-beta.3   CPU support gate
v0.2.0          "The Settings Update"
```

The alternative — a stable release per change — is what produced `0.1.4` through `0.1.7` in a
single day, none of which described anything a user would recognise as a release.

## Cutting a beta

1. Add the change to `CHANGELOG.md` under `## [Unreleased]`.
2. In `app/build.gradle.kts`, increment `versionCode` and set `versionName` to the full tag
   without the `v`, e.g. `0.2.0-beta.3`. The workflow refuses to publish if they disagree.
3. Commit, tag, push:

```bash
git tag -a v0.2.0-beta.3 -m "LocalScribe v0.2.0-beta.3" && git push origin master v0.2.0-beta.3
```

`versionCode` increments on **every** build, beta or stable. It is what Android compares when
deciding whether an APK is an upgrade, so a beta that shares a code with the release before it
cannot be installed over it.

## Promoting to stable

1. Rename `## [Unreleased]` in `CHANGELOG.md` to `## [0.2.0] - YYYY-MM-DD` and, if the release
   has a name, say so in the first line.
2. Set `versionName` to `0.2.0` and increment `versionCode` once more.
3. Tag `v0.2.0` and push.

The release notes are worth writing by hand for a stable release. `generate_release_notes`
lists commits, which is fine for a beta and thin for something a user is being asked to
install.

## What the workflow enforces

Every tag, both channels, runs the same gates in this order:

1. The tag's channel is recognised, or the build stops.
2. The tag matches `versionName` exactly.
3. The release keystore decodes and matches its known SHA-256, and the password and alias
   open it — this runs *before* the ~8 minute native build, so a bad secret fails in seconds
   and names which one.
4. Unit tests pass and the release APK builds.
5. `apksigner` confirms the APK is not debug-signed.

A beta is not a lower standard. It is the same build, published somewhere that does not reach
people who did not ask for it.

## Installing betas yourself

In Obtainium, enable **Include prereleases** on the LocalScribe app. Every release shares a
signing certificate, so betas and stable builds install over one another freely.
