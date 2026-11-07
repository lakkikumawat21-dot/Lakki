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
- Latest release tags & asset sizes:
  - `v2026.11.06-opencode6-isolated-node`:
    - `arm64-v8a-lite.7z` (696,914,959 bytes / ~696.9 MB)
    - `arm64-v8a-openchamber.7z` (551,943,707 bytes / ~551.9 MB)
  - `v2026.10.31-codex-0.166.0`:
    - `arm64-v8a-ubuntu.7z` (22,069,276 bytes / ~22.0 MB)
    - `arm64-v8a-standard.7z` (13,973,628 bytes / ~14.0 MB)
    - `arm64-v8a-full.7z` (221,235,444 bytes / ~221.2 MB)
    - `arm64-v8a-lite.7z` (650,880,163 bytes / ~650.9 MB)
  - Primary Latest Redirect:
    - `arch-ubuntu.7z` -> `https://github.com/goldenduo/XermesRelease/releases/latest/download/arch-ubuntu.7z` (~22.0 MB)
- Official Ubuntu Cloud RootFS:
  - `https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.3-base-arm64.tar.gz` (28.4 MB)
- Mirror Fallbacks:
  - `https://mirror.ghproxy.com/`
  - `https://gh-proxy.com/`
  - `https://ghproxy.net/`
  - `https://share.126111.xyz/`
  - `https://tshare.126111.xyz/`
  - `https://cdn.jsdelivr.net/gh/`
  - `https://fastly.jsdelivr.net/gh/`

---

## 4. Native Binaries & Packaging Architecture

Android 10+ (`targetSdk >= 29`) enforces W^X (noexec) on the private app directory (`/data/data/ai.sol.hermes/files/`). To bypass this without root:
1. Native binaries are packaged in `jniLibs/arm64-v8a/`:
   - `libproot.so` (1.02 MB statically linked 64-bit ARM PRoot)
   - `libqroot.so` (1.02 MB alias)
   - `libbusybox.so` (1.5 MB statically linked 64-bit ARM BusyBox)
2. In `build.gradle`, `useLegacyPackaging = true` forces Android Package Manager to extract these libraries into `context.getApplicationInfo().nativeLibraryDir` with executable (`+x`) permissions.
3. Fallback extraction routine in `InstallationManager.findOrExtractProotBinary` unpacks `assets/bin/proot-arm64` into `<filesDir>/bin/proot` and applies `Os.chmod(0755)`.
4. Exact `ProcessBuilder` execution command:
   ```bash
   /data/app/.../lib/arm64/libproot.so \
     -r /data/data/ai.sol.hermes/files/rootfs \
     -0 \
     -b /dev \
     -b /proc \
     -b /sys \
     -b /data/data/ai.sol.hermes/files/workspace:/root/workspace \
     -b /data/data/ai.sol.hermes/files/hermes_data:/root/.hermes \
     -w /root/workspace \
     /bin/bash -lc "cd /root/workspace && exec /usr/local/bin/hermes dashboard --host 127.0.0.2 --port 39067 --no-open --skip-build"
   ```
5. Environment variables supplied to PRoot child process:
   - `HERMES_HOME=/root/.hermes`
   - `HERMES_AGENT_ROOT=/usr/local/lib/hermes-agent`
   - `HERMES_BIN=/usr/local/bin/hermes`
   - `HERMES_ANDROID_CAPABILITY_PORT=43097`
   - `HERMES_ANDROID_CAPABILITY_TOKEN=hermes_local_bridge_token`
   - `HERMES_REAL_HOME=/root`
   - `HERMES_GATEWAY_DETACHED=1`
   - `HERMES_DISABLE_LAZY_INSTALLS=1`
   - `HERMES_EXEC_ASK=1`
   - `HERMES_QUIET=1`
   - `HERMES_STARTUP_WATCHDOG_TIMEOUT_S=300`
   - `PORT=39067`
   - `QROOT_ANDROID_PACKAGE=ai.sol.hermes`
   - `QROOT_GPU=llvmpipe`
   - `QROOT_IN_STUB=1`
   - `QROOT_LOOPBACK_NAMESPACE=hermes-agent`
   - `QROOT_LOOPBACK_UNIX=1`
   - `QROOT_STUB_BLOCK_TARGET_SECCOMP=1`
   - `HOME=/root`
   - `USER=root`
   - `TERM=xterm-256color`
   - `TMPDIR=/tmp`
   - `PATH=/opt/qroot/bin:/usr/local/lib/hermes-agent/.hermes/bin:/usr/local/lib/hermes-agent/venv/bin:/usr/local/bin:/root/.local/bin:/root/.hermes/node/bin:/root/.hermes/bin:/root/.hermes/tools/node-26.12.1-linux-arm64/bin:/root/.hermes/tools/python-3.14.9+20241021-linux-arm64/bin:/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin`

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
- **Latest Commit**: [`0b821bc2`](https://github.com/lakkikumawat21-dot/Lakki/commit/0b821bc2)
- **Standalone APK Compilation**: Built locally with Android SDK 34 (`platforms/android-34`, `build-tools/34.0.0`) and Gradle 8.5 in 1m 59s.
- **Generated Standalone APK**: `app-debug.apk` (15,147,725 bytes / ~15 MB)
- **Packaged Native ARM64 Libraries (`unzip -l` verified)**:
  - `lib/arm64-v8a/lib7za.so` (3.10 MB, statically linked ARM64 7-Zip archiver)
  - `lib/arm64-v8a/lib7z.so` (3.10 MB, statically linked ARM64 7-Zip alias)
  - `lib/arm64-v8a/libbusybox.so` (1.48 MB, statically linked ARM64 BusyBox)
  - `lib/arm64-v8a/libproot.so` (1.02 MB, statically linked ARM64 PRoot)
  - `lib/arm64-v8a/libqroot.so` (1.02 MB, statically linked ARM64 QROOT)
- **Checksums**:
  - SHA256: `5370e4cb1fb8bf2f12ea05cbaf6216c2d25a0b92f5091d3cd2e1488da2b06c8f`
  - MD5: `8a6e83db74e4c9715848a04c03377b70`
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
- **Release Version**: Upgraded `versionCode 31007`, `versionName "3.10.7"`, and release tag `v3.10.7-apk`.

---

## 8. Deep Audit: The 11 MB vs 36 MB+ Mystery, Native Split APK Analysis & Symlink Resolution

### A. The 11 MB vs 36 MB+ Mystery (Without Opening the App)

When `ai.sol.hermes` (v3.10.7) is installed from Google Play and never opened, App Info reports ~36 MB+ despite `base.apk` being only ~11 MB. This occurs due to three Android system mechanisms:

| Mechanism | Description | Disk Impact |
|---|---|---|
| **Android App Bundle (Split APKs)** | Google Play splits apps into `base.apk` (~11 MB) and architecture splits (e.g., `split_config.arm64_v8a.apk` ~10 MB). APK extractors without split support only copy `base.apk`. | +10 MB (downloaded split) |
| **`extractNativeLibs="true"` / `useLegacyPackaging`** | On installation, Android PackageManager extracts compressed `.so` files from the split APK into `/data/app/.../lib/arm64/` with executable (`+x`) permissions. | +9.5 MB (uncompressed binaries) |
| **ART `dex2oat` Background AOT Compilation** | Android's `BackgroundDexoptService` runs during idle maintenance, using `assets/dexopt/baseline.prof` to compile DEX bytecode into native machine code (`.odex`, `.vdex`, `.art`) in `/data/app/.../oat/arm64/`. | +12–18 MB (AOT cache) |
| **Total Disk Footprint Before Launch** | `base.apk` + `split_config` + extracted `.so` + `.odex` / `.vdex` | **~36 MB – 48 MB** |

---

### B. Expected Native Binaries & Library Mapping

`UbuntuManager` and `PRootRunner` require the following 64-bit ARM binaries in `applicationInfo.nativeLibraryDir` (`jniLibs/arm64-v8a/`):

| Binary / Library | Packaging Name | Size | Purpose & Role |
|---|---|---|---|
| **PRoot Engine** | `libproot.so` | 1.02 MB | User-space chroot/mount engine, syscall interception (`ptrace`/`seccomp`) |
| **QROOT Alias** | `libqroot.so` | 1.02 MB | Alias/fallback executable for PRoot engine |
| **BusyBox Shell** | `libbusybox.so` | 1.5 MB | Multi-call binary (`sh`, `tar`, `gzip`, `cp`, `chmod`, `ln`, `mkdir`) |
| **7-Zip Archiver** | `lib7za.so` / `lib7z.so` | 3.0 MB | Static 64-bit ARM 7-Zip (`7zzs`) for native LZMA/LZMA2 extraction with POSIX symlinks |
| **Bundled Assets Fallback** | `assets/bin/*` | 5.4 MB | Unpacked to `<filesDir>/bin/` if nativeLibraryDir extraction fails |

---

### C. Root Cause of Previous Runtime Failure & Missing Elements

1. **What was in Play Store `base.apk` vs our APK:**
   - Play Store `base.apk` included Google Play Billing, Google Mobile Ads (AdMob), Firebase Analytics, and tracking telemetry (~2.5 MB of DEX and resources).
   - Our APK intentionally excluded all ad and tracking bloatware.
   - However, because `base.apk` relied on `split_config.arm64_v8a.apk` for native archivers, our initial standalone build lacked the native 7-Zip executable.

2. **The Exact Runtime Failure Point:**
   - Previous implementation used Java Apache Commons Compress `SevenZFile` in `ArchiveExtractor.extract7z`.
   - `SevenZFile` does **not** restore POSIX symbolic links (`entry.isSymbolicLink()` was unhandled), extracting symlinks as regular plain text files containing the destination path (e.g., `/bin/sh` containing text `"bash"`, or `/lib64/ld-linux-aarch64.so.1` containing `"ld-2.39.so"`).
   - When `PRootRunner` subsequently launched:
     ```bash
     libproot.so -r rootfs -0 /bin/bash -lc "cd /root/workspace && exec hermes dashboard ..."
     ```
     The dynamic linker or shell failed immediately with `Exec format error` or `No such file or directory`.

3. **The Solution Implemented:**
   - Bundled official statically linked 64-bit ARM 7-Zip (`7zzs`) as `lib7za.so` and `lib7z.so` (3.0 MB) in `jniLibs/arm64-v8a/` and `assets/bin/7za-arm64`.
   - Updated `ArchiveExtractor.java` to invoke `lib7za.so x -y -aoa -snl -snh <archive> -o<rootfs>`:
     - `-snl`: Restores true POSIX symbolic links.
     - `-snh`: Restores hard links.
     - `-aoa`: Overwrites without prompt.
   - Added executable bit verification (`InstallationManager.setExecutable`) for `bin/bash` and `bin/sh` prior to PRoot launch in `PRootRunner.java`.




