# Hermes Agent — Master Architecture Plan & Blueprint

> **Date:** 2026-11-07  
> **Target Package:** `ai.sol.hermes`  
> **Source Base:** Standalone QRoot / PRoot Linux Subsystem on Android  
> **Build Target:** All-in-One Standalone Android APK (No Termux / No External CLI Required)

---

## 1. Executive Summary & Objective

The goal is to build and deliver a **100% standalone, self-contained Android application** (`package ai.sol.hermes`) inside `android-app/` that operates identically to the original standalone reference implementation:
1. **In-app First-Launch Setup**: Automatically checks rootfs state. If absent, presents a setup & version selection UI, downloads the rootfs (`arch-ubuntu.7z` or Ubuntu Noble rootfs bundle) from primary CDN or mirror fallbacks with live progress reporting, and extracts the environment into the app's private files directory (`/data/data/ai.sol.hermes/files/`).
2. **In-app PRoot Engine Execution**: Runs an internal PRoot Linux subsystem process (`libproot.so`, `libbusybox.so`) via a foreground `AgentService` without requiring Termux.
3. **Hermes Dashboard Boot**: Executes `/bin/bash -lc "cd /root/workspace && exec /usr/local/bin/hermes dashboard --host 127.0.0.2 --port 39067 --no-open --skip-build"` with full environment variables (`HERMES_HOME=/root/.hermes`, `PORT=39067`, `QROOT_ANDROID_PACKAGE=ai.sol.hermes`).
4. **Local Services & Bridges**:
   - `CapabilityServer`: Runs on `127.0.0.1:43097` providing Android REST capabilities (`/health`, `/device-info`, `/notify`, `/toast`, `/alarm`, `/clipboard`, `/open-url`).
   - `AiForwardProxy`: Internal proxy forwarder.
5. **Seamless WebView UI**: WebView loads `http://127.0.0.2:39067` (with `127.0.0.1:39067` fallback) as soon as the dashboard port is ready, injecting required synchronization and MutationObserver hooks.
6. **Zero Code Touches on Core**: The core Python source tree (`hermes-agent/`) remains 100% frozen.

---

## 2. Reference Smali & Architectural Analysis

Based on the decompiled reference APK (`ai.sol.hermes`, TargetSDK 36, MinSDK 32):

### A. `com.xermes.qroot.UbuntuManager`
- **RootFS Assets**:
  - Primary URL: `https://github.com/goldenduo/XermesRelease/releases/latest/download/arch-ubuntu.7z`
  - Fallback / Mirror Proxies:
    - `https://mirror.ghproxy.com/`
    - `https://gh-proxy.com/`
    - `https://ghproxy.net/`
    - `https://share.126111.xyz/`
    - `https://tshare.126111.xyz/`
    - `https://cdn.jsdelivr.net/gh/`
    - `https://fastly.jsdelivr.net/gh/`
- **Native Binaries**:
  - `libproot.so` (PRoot user-space chroot/mount engine)
  - `libbusybox.so` (Multi-call shell utilities)
  - Linker: `/system/bin/linker64`
- **Lifecycle & State Machine**:
  - State Check: `rootfs backend not found` vs `rootfs backend ready`
  - Process Supervision: `killing existing task`, `task backend started`, `stopping backend task`

### B. `com.xermes.qroot.HermesAgentPaths`
- `bridgePython`: `/usr/local/lib/hermes-agent/venv/bin/python3`
- `hermesBin`: `/usr/local/bin/hermes`
- `home`: `/root/.hermes`
- `root`: `/usr/local/lib/hermes-agent`
- `venvBin`: `/usr/local/lib/hermes-agent/venv/bin`
- `path`: `/opt/qroot/bin:/usr/local/lib/hermes-agent/venv/bin:/usr/local/bin:/root/.hermes/node/bin:/root/.hermes/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`
- `activationCmd`: `source /usr/local/lib/hermes-agent/venv/bin/activate && `

### C. `com.xermes.qroot.HermesDataManager` & `HermesRuntimeManager`
- **Persistence Directories**:
  - `hermes_data`, `hermes_data.migrating`, `/root/.hermes`
  - `hermes_runtime.backup`, `hermes_runtime.staging`, `hermes_runtime.transaction`
- **Subsystem Layout**:
  - `root/.hermes/installs`
  - `root/.local/bin/hermes`
  - `usr/local/lib/hermes-agent`
  - `usr/local/bin/hermes`
  - `usr/local/bin/hermes-agent`
  - `usr/local/bin/hermes-acp`
  - `usr/local/bin/hermes-android-doctor`
  - `root/.hermes/node`
  - `root/.hermes/bin`
  - `root/.hermes/tools`

### D. `com.xermes.xermes.AgentService`
- Android Foreground Service hosting:
  - PRoot child process execution via `ProcessBuilder`.
  - Process stdout/stderr streaming to `hermes_engine.log`.
  - CapabilityServer REST bridge on port `43097`.
  - Android 14 FGS compliance: `FOREGROUND_SERVICE_SPECIAL_USE`.

### E. `com.xermes.xermes.MainActivity`
- Tri-state UI controller:
  1. **Setup State**: Version & profile selection, multi-mirror download of `.7z` / `.tar.gz`, archive decompression with symlink restoration.
  2. **Boot State**: PRoot launch, port `39067` polling, live diagnostic console logs.
  3. **Dashboard State**: Full-screen WebView, swipe-to-refresh, file chooser, JS bridge hooks.

---

## 3. GitHub Releases & Distribution Asset Inventory

Inspection of `https://api.github.com/repos/goldenduo/XermesRelease/releases`:
- Latest release tags:
  - `v2026.11.06-opencode6-isolated-node`: `arm64-v8a-lite.7z` (~696 MB), `arm64-v8a-openchamber.7z` (~551 MB)
  - `v2026.10.31-codex-0.166.0`: `arm64-v8a-ubuntu.7z` (22 MB)
  - `v2026.09.02-model-startup`: `ubuntu.7z` (22 MB)
- Official Ubuntu Cloud RootFS:
  - `https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.3-base-arm64.tar.gz` (28.4 MB)
- Mirror Fallbacks:
  - `https://mirror.ghproxy.com/`
  - `https://gh-proxy.com/`
  - `https://ghproxy.net/`

---

## 4. Native Binaries & Packaging Architecture

Android 10+ (`targetSdk >= 29`) enforces W^X (noexec) on the private app directory (`/data/data/ai.sol.hermes/files/`). To bypass this without root:
1. Native binaries are packaged in `jniLibs/arm64-v8a/`:
   - `libproot.so` (1.02 MB statically linked 64-bit ARM PRoot)
   - `libqroot.so` (1.02 MB alias)
   - `libbusybox.so` (1.5 MB statically linked 64-bit ARM BusyBox)
2. In `build.gradle`, `useLegacyPackaging = true` forces Android Package Manager to extract these libraries into `context.getApplicationInfo().nativeLibraryDir` with executable (`+x`) permissions.
3. `ProcessBuilder` executes `nativeLibraryDir + "/libproot.so"`.

---

## 5. Implementation Roadmap in `android-app/`

1. **`HermesAgentPaths.java`**: Centralized constant definitions matching original smali.
2. **`UbuntuManager.java`**: RootFS discovery, state verification, process lifecycle management.
3. **`HermesDataManager.java` & `HermesRuntimeManager.java`**: Staging, persistence, and profile backup.
4. **`ArchiveExtractor.java`**: Dual `.7z` (SevenZFile + XZ) and `.tar.gz` (TarArchiveInputStream) extractor with POSIX symlink (`Os.symlink`) and chmod (`Os.chmod`) handling.
5. **`AiForwardProxy.java`**: Local forward proxy service.
6. **`CapabilityServer.java`**: Port `43097` HTTP REST service.
7. **`AgentService.java`**: Modern Android 14 foreground service managing PRoot engine and CapabilityServer.
8. **`MainActivity.java`**: Complete Setup / Boot / WebView lifecycle with live console viewer and JS synchronization.
9. **CI / Gradle**: Automated compilation of `app-debug.apk` with `arm64-v8a` filter and release creation.

---

## 6. Build Verification & Release Summary

- **GitHub Repository**: `lakkikumawat21-dot/Lakki`
- **Latest Commit**: [`5742ac31`](https://github.com/lakkikumawat21-dot/Lakki/commit/5742ac317ac9f6915ee8f48afcda0c061f2dec04)
- **GitHub Actions Build Run**: [Run #42373670105](https://github.com/lakkikumawat21-dot/Lakki/actions/runs/42373670105) — **SUCCESS (Green)**
- **Release Tag**: `v1.0.0-apk` -> Upgraded to `v3.10.7-apk`
- **Generated APK**: `app-debug.apk` (9,822,903 bytes / ~9.82 MB)
- **Core Code Freeze**: 100% frozen (0 lines changed in `hermes-agent/`).

---

## 7. REA (Reverse Engineer Anything) & Exact v3.10.7 Replica Architecture

- **Tooling & Intelligence**: Integrated `morluto/rea` (v6.8.0) into the environment.
- **Hermes REA Integration**: Configured Hermes with the `reverse-engineer-anything` skill and registered `rea mcp` server inside `~/.hermes/config.yaml`.
- **In-App Mobile REA Bundle**:
  - Embedded `reverse-engineer-anything` skill prompt and markdown references (`android-applications.md`, `native-and-artifacts.md`, `javascript-applications.md`, etc.) inside `android-app/app/src/main/assets/boot_files/skills/reverse-engineer-anything/`.
  - Automatic deployment into `/root/.hermes/skills/` on first launch.
- **Full Capability Bridge Coverage**:
  - Implemented `/file/list`, `/file/share`, `/file/save`, `/workspace/backup`, and diagnostics in `CapabilityServer.java` for full parity with `skills/android-device/android_device.py`.
  - Exported `HERMES_ANDROID_CAPABILITY_TOKEN=hermes_local_bridge_token` to eliminate unauthorized credential exceptions.
- **Android Manifest & Permissions**: Added `FileProvider`, `ACCESS_WIFI_STATE`, `RECEIVE_BOOT_COMPLETED`, and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- **Release Version**: Upgraded `versionCode 31007`, `versionName "3.10.7"`, and release tag `v3.10.7-apk`.



