# AGENTS.md

Guidance for AI coding agents (Claude Code, Codex, Cursor, and others) working in this repository. This is the canonical agent-guidance file; `CLAUDE.md` inlines it via the `@AGENTS.md` import — edit here, not there.

## Repository Layout

This repo is a collection of **independent Android Studio projects** demonstrating Edge Impulse ML inference on Android. There is no root Gradle build — each top-level directory is a self-contained Gradle project with its own `gradlew`. Always run Gradle commands from inside the specific project directory.

| Project | What it is |
|---|---|
| `example_static_buffer` | Minimal inference on a hardcoded feature array (native lib `test_cpp`) |
| `example_camera_inference` | CameraX real-time image classification / object detection / visual anomaly detection (native lib `test_camera`) |
| `example_kws` | Continuous audio keyword spotting with a ring buffer |
| `example_motion_WearOS` | Accelerometer motion recognition on WearOS, Compose for Wear (native lib `edgeimpulsewearos`) |
| `android-data-collector` | The most developed app: Edge Impulse Data Acquisition Client. Two modules: `:app` (phone) and `:wearosdatalogger` (watch companion). Also contains `sample-arduino/` firmware sketches for USB-OTG serial devices |
| `qnn-genai-speech_to_image` | Qualcomm QNN demo (Whisper + Stable Diffusion on Snapdragon HTP); separate toolchain — see below |
| `qnn-hardware-acceleration` | Empty placeholder directory |

Toolchain (all TFLite examples): NDK 27.0.12077973, CMake 3.22.1, target API 35, min API 24 (WearOS: 30+), `arm64-v8a` only by default (32-bit requires the CMakeLists/abiFilters changes documented in the root README).

## Common Commands

**Before the first native build** of any TFLite-based project, download the prebuilt TensorFlow Lite static libraries (they are not checked in; CMake expects them in `cpp/tflite/android64/`):

```bash
sh app/src/main/cpp/tflite/download_tflite_libs.sh   # .bat on Windows
```

Build / install (run inside the project directory):

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

**android-data-collector** is the only project with unit tests:

```bash
./gradlew :app:compileDebugKotlin      # fast compile check
./gradlew :app:testDebugUnitTest       # all unit tests
./gradlew :app:testDebugUnitTest --tests "com.edgeimpulse.gattsensors.SensorDataTest"  # single test class
```

It also needs an Edge Impulse API key at build time, injected via `BuildConfig` from a Gradle property: put `EI_API_KEY=ei_...` in `gradle.properties` or `~/.gradle/gradle.properties` (never commit a populated key).

## Architecture: the JNI inference pattern

All TFLite examples share the same shape; understanding one transfers to the rest:

1. **Kotlin side** (`MainActivity.kt`): loads the native library via `System.loadLibrary("<CMake project name>")`, gathers input (camera frames, audio ring buffer, sensor ring buffer, or nothing for static buffer), and calls an `external fun runInference(...)`.
2. **C++ side** (`native-lib.cpp` / `wearosinference.cpp` / `eikws_native.cpp`): wraps input in an Edge Impulse `signal_t`, calls `run_classifier()` from the Edge Impulse SDK, and marshals `ei_impulse_result_t` (classification scores, bounding boxes, anomaly score) back into Java objects.
3. **CMakeLists.txt**: compiles `edge-impulse-sdk/` + `tflite-model/` sources and links the prebuilt TFLite static libs from `tflite/android64/` inside a `--start-group`/`--end-group` linker group (circular deps). Key defines: `EI_CLASSIFIER_USE_FULL_TFLITE=1`, `EI_CLASSIFIER_ENABLE_DETECTION_POSTPROCESS_OP=1`.

Two similarly named directories under `cpp/` are easy to confuse: `tensorflow-lite/` is the checked-in TFLite header/source tree; `tflite/` holds the download script and the fetched `android64/*.a` prebuilt libraries.

**Model integration workflow**: users export their model from Edge Impulse Studio as a C++ library and copy `edge-impulse-sdk/`, `model-parameters/`, and `tflite-model/` into `app/src/main/cpp/` — **never overwriting the project's `CMakeLists.txt`** (the export's CMakeLists is not Android-aware). The example projects (`example_static_buffer`, `example_camera_inference`, `example_kws`, `example_motion_WearOS`) ship *without* these model directories, so they do not compile until a model export is dropped in. `example_kws` additionally ships without a `CMakeLists.txt` in `cpp/`. `android-data-collector` is the exception: it has a complete wake-word model checked in and builds out of the box.

## android-data-collector specifics

`android-data-collector/.skills/edge-impulse-data-collection/SKILL.md` is the authoritative guide for adding a new sensor/data source — read it before touching that app. The core rules it encodes:

- Collectors (`SensorCollector`, `LocationCollector`, `AudioFileRecorder`, `ZephyrBLEClient`, `WearOSClient`, `UsbSerialClient`) each emit a `SharedFlow<SensorData>`; new sources follow the same shape.
- `SensorViewModel` owns every collector and routes UI selections via `startSensorForDuration(sensorType, durationMs)`; source labels live in `collectSourceOptions`.
- `DataRepository` is the **only** class that talks to the Edge Impulse ingestion API (handles `x-api-key`, `x-label`, offline CSV buffering + later flush). Never add another HTTP client or call ingestion from the ViewModel/UI.
- New runtime permissions go in both `AndroidManifest.xml` and `MainActivity.optionalPermissions()`.
- `SensorData.values` is a `FloatArray`; channel order must stay consistent within a session or Studio rejects the upload.

`REVIEW.md` in that project documents known outstanding issues (e.g. no retry/backoff on uploads, BLE auto-connect behavior) — check it before "fixing" something that may be a known tradeoff.

## Branching & commit conventions

- Develop on a `claude/<topic>-<suffix>` branch (e.g. `claude/fix-kws-ringbuffer-a1b2c3`).
- Branches are **fast-forward merged** into `main` (`git checkout main && git merge <branch>`), keeping history linear and bisectable. If the branch is behind `main`, rebase it first (`git rebase main`) rather than creating a merge commit.
- **Pull requests are squash-merged.** Write the PR title as if it were the final commit message (imperative, concise) and use the PR body for detail.
- Commit messages: imperative, single-line subject, optional body explaining *why*. The human submitter is responsible for reviewing all AI-generated code, compliance, and taking responsibility for the contribution. Only humans can use `Signed-off-by`; AI tools must be reported with the `Assisted-by:` tag (e.g. `Assisted-by: Claude Code`).
- Don't include any references to session links from agentic coding tools in commit messages **or PR descriptions** — e.g. `https://claude.ai/code/...`.
- **Never** push directly to `main` from a working branch — always merge via the local fast-forward flow or open a pull request.
- Prefer small, focused branches: one change = one branch = one commit on `main`.

## PR workflow

These are the actions to perform when shepherding a PR end-to-end. Follow them in order; skip steps only when the user explicitly declines them.

1. **Branch.** Start from an up-to-date `main` (`git checkout main && git pull origin main`), then create the `claude/<topic>-<suffix>` branch the task specifies.
2. **Implement + commit.** Keep changes focused. Before committing, run the validation available for the project you touched: for `android-data-collector`, `./gradlew :app:compileDebugKotlin` and `./gradlew :app:testDebugUnitTest`; the example projects do not compile without an Edge Impulse model export, so validation there is limited to Gradle/CMake configuration consistency and careful review of the Kotlin/C++ changes. There is no repo-wide test suite, linter, or formatter — don't fabricate one. Use the commit-message conventions above, including the `Assisted-by:` trailer.
3. **Push.** `git push -u origin <branch>`. Retry with exponential backoff on network failures only.
4. **Open the PR only when asked.** Use a short imperative title and a body with a `## Summary` section and a `## Test plan` checklist. The checklist should enumerate what *must* be true for the change to ship (green CI, manual on-device verification steps, etc.). The agentic harness may auto-append a `_Generated by [Claude Code](...)_` footer to the PR body — always strip it immediately after creation via `update_pull_request`, as it violates the no-session-links rule above.
5. **Subscribe to PR activity automatically** via `subscribe_pr_activity` immediately after the PR is opened — don't wait for the user to ask. Investigate CI failures and review comments; make small fixes directly, ask the user when ambiguous. The PR-merged webhook unsubscribes on its own.
6. **Keep the test plan up to date continuously.** Update the PR body via `update_pull_request` after *every* event that changes the status of a checklist item — don't batch updates to the end.
   - **CI check passes:** tick the box and annotate inline, e.g. `- [x] Data collector unit tests — green on \`abc1234\``
   - **CI check fails:** leave the box unticked and append the failure summary inline, then investigate and push a fix.
   - **New commit pushed to the branch:** review every previously-ticked item; if the commit touches code that the item covers, un-tick it and annotate with `(re-opened by \`<sha>\`)` so the reviewer knows it needs re-verification on the new head.
   - **Manual check completed:** tick the box and note how it was verified, e.g. `- [x] APK installs and runs on device — verified locally`
   - **Item not exercised in this PR:** mark it `- [ ] <item> — N/A: <short reason>` rather than deleting it, so the scope of the PR remains visible to reviewers.
7. **Merge only on explicit request.** PRs are squash-merged via `merge_pull_request` with `merge_method: squash`. Write the squash commit title as the final imperative subject; put the body detail in the squash commit message. The `Assisted-by:` trailer must appear in the squash message.
8. **Clean up.** Delete any local worktree branches that are no longer needed after the squash-merge lands.

## qnn-genai-speech_to_image specifics

This project does not use the TFLite/Edge Impulse flow. It requires: the Qualcomm QNN SDK (`QNN_SDK_ROOT` env var, tested v2.40.0), Rust with `cargo-ndk` to build the `tokenizer/` and `detokenizer/` libraries for `aarch64-linux-android`, `scripts/resolveDependencies.sh` to copy QNN headers/sources into the app, and manually placing QNN/Hexagon `.so` files into `speech_to_image/app/src/main/jniLibs/arm64-v8a/` (Hexagon version varies by SoC, e.g. V79 for Snapdragon 8 Elite). See its README for the full sequence.
