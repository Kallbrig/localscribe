# Contributing

Bug reports and focused pull requests are welcome. By contributing, you agree that your work
is licensed under MIT.

1. `git clone --recurse-submodules` this repo (or run `git submodule update --init
   --recursive` after a plain clone) -- `whisper.cpp` and `llama.cpp` are submodules and the
   native modules won't build without them.
2. Point `local.properties`' `sdk.dir` at an Android SDK install with API 36, NDK, and CMake
   available (JDK 17 required).
3. Keep platform code behind the three services/UI layer and engine code behind the
   `Transcriber`/`Cleaner` interfaces in `domain/` -- see `docs/ARCHITECTURE.md`.
4. Run `./gradlew test` (unit tests) before opening a PR. Anything touching the overlay,
   accessibility service, or native bridges needs manual verification on a device or
   emulator, since those can't run as JVM unit tests.
5. Describe privacy or permission implications in the pull request.

Do not introduce telemetry or cloud inference into the default application. New network
access must be opt-in, clearly documented, and isolated behind an interface -- this app's
whole premise is that dictation never leaves the device.
