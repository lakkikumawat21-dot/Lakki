# Hermes Agent — Boot Architecture Reference

> Captured from the live Android backend environment on 2026-11-07.
> This file is the exact reference for reproducing the boot/startup chain on a new APK or server setup.
> Do NOT edit paths or versions — they are the ground truth of the working system.

---

## 1. Exact Startup Command Chain

When the Android wrapper app (`ai.sol.hermes`) opens, the following chain runs in order:

```
[android-host-path]  (libqroot.so bridge binary, launched by Android app)
  └─ /bin/bash -lc
       cd /root/workspace && exec /usr/local/bin/hermes dashboard \
           --host 127.0.0.2 --port 39067 --no-open --skip-build
```

- `/usr/local/bin/hermes`  →  `exec /usr/local/lib/hermes-agent/.hermes/bin/hermes "$@"`
- `/usr/local/lib/hermes-agent/.hermes/bin/hermes`  →  Python 3.14.9 launcher
  (runs the closure-repair bootstrap, then `hermes_cli.main`)
- **Gateway daemon (PID 306):**
  `/root/.hermes/tools/python-3.14.9+202****1021-linux-arm64/bin/python3 -m hermes_cli.main --profile default gateway run --quiet --idle-exit`
- **TUI sidecar (dashboard UI):**
  `/root/.hermes/tools/node-26.12.1-linux-arm64/bin/node --expose-gc /usr/local/lib/hermes-agent/hermes_cli/tui_dist/entry.js`
- **Execution kernel:** `/tmp/hermes_kernel_*/hermes_kernel_runner.py`

No systemd / no init.d master. PID 1 is the qroot bridge (`[android-host-path]`), not an init daemon.

---

## 2. Bridge / Ports

| Item | Value |
|---|---|
| Android wrapper package | `ai.sol.hermes` (QROOT, ~15MB) |
| Bridge binary | `[android-host-path]` (libqroot.so) |
| Loopback namespace | `QROOT_LOOPBACK_NAMESPACE=hermes-agent`, `QROOT_LOOPBACK_UNIX=1` |
| Dashboard HTTP server | `127.0.0.2:39067` |
| TUI sidecar WebSocket | `ws://127.0.0.2:39067/api/pub?internal=...&channel=chat-...` |
| Android capability port | `127.0.0.1:43097` (`HERMES_ANDROID_CAPABILITY_PORT`) |
| android_device.py protocol | HTTP REST `http://127.0.0.1:43097`, `Authorization: Bearer <token>` |
| android_device.py endpoints | `health`, `tools`, `device-info`, `notify`, `alarm`, `toast`, `clipboard-set`, `clipboard-get` |
| Gateway IPC socket | `/root/.hermes/gateway.sock` |
| RPC socket | `/tmp/hermes_rpc_*.sock` |

---

## 3. Key Environment Variables (secrets masked)

```
HERMES_HOME=/root/.hermes
HERMES_AGENT_ROOT=/usr/local/lib/hermes-agent
HERMES_BIN=/usr/local/bin/hermes
HERMES_PYTHON=/root/.hermes/tools/python-3.14.9+202****1021-linux-arm64/bin/python3
HERMES_AGENT_BRIDGE_PYTHON=/usr/local/lib/hermes-agent/venv/bin/python3
HERMES_PYTHON_SRC_ROOT=/usr/local/lib/hermes-agent
HERMES_ANDROID_CAPABILITY_PORT=43097
HERMES_GATEWAY_DETACHED=1
HERMES_DISABLE_LAZY_INSTALLS=1
HERMES_EXEC_ASK=1
HERMES_QUIET=1
HERMES_REAL_HOME=/root
HERMES_STARTUP_WATCHDOG_TIMEOUT_S=300
PORT=39067
QROOT_ANDROID_PACKAGE=ai.sol.hermes
QROOT_GPU=llvmpipe
QROOT_IN_STUB=1
QROOT_LOOPBACK_NAMESPACE=hermes-agent
QROOT_LOOPBACK_UNIX=1
QROOT_STUB_BLOCK_TARGET_SECCOMP=1
LD_PRELOAD=/opt/qroot/lib/libqroot-runtime.so
_HERMES_GATEWAY=1
```

PATH order:
```
/usr/local/lib/hermes-agent/.hermes/bin
/root/.hermes/installs/76f6e7e5145b5f39/environments/30cef379190d4c228f5002a92b695de7/venv/bin
/root/.local/bin
/opt/qroot/bin
/usr/local/lib/hermes-agent/venv/bin
/usr/local/bin
/root/.hermes/node/bin
/root/.hermes/bin
/usr/local/sbin:/usr/sbin:/usr/bin:/sbin:/bin
/opt/homebrew/bin:/opt/homebrew/sbin
/root/.hermes/tools/python-3.14.9+202****1021-linux-arm64/bin
/root/.hermes/tools/agent-browser-0.26.0-linux-arm64/bin
/root/.hermes/tools/cua-driver-0.21.0-linux-arm64
/root/.hermes/tools/ffmpeg-9.0.1-linux-arm64/bin
/root/.hermes/tools/npm-12.0.2-linux-arm64/bin
/root/.hermes/tools/node-26.12.1-linux-arm64/bin
/root/.hermes/tools/ripgrep-15.2.0-linux-arm64
```

---

## 4. Tool Versions in `/root/.hermes/tools/`

| Tool | Version | Path |
|---|---|---|
| Python | 3.14.9 | `tools/python-3.14.9+202****1021-linux-arm64/bin/python3` |
| Node | v26.12.1 | `tools/node-26.12.1-linux-arm64/bin/node` |
| npm | 12.0.2 | `tools/npm-12.0.2-linux-arm64/bin/npm` |
| ffmpeg | n9.0.1 | `tools/ffmpeg-9.0.1-linux-arm64/bin/ffmpeg` |
| ffprobe | (with ffmpeg) | `tools/ffmpeg-9.0.1-linux-arm64/bin/ffprobe` |
| Chromium | 1208 | `tools/chromium-1208/chrome-linux/chrome` |
| agent-browser | 0.26.0 | `tools/agent-browser-0.26.0-linux-arm64` |
| cua-driver | 0.21.0 | `tools/cua-driver-0.21.0-linux-arm64` |
| ripgrep | 15.2.0 (rg 14.1.0) | `tools/ripgrep-15.2.0-linux-arm64` |
| uv | 0.13.5 | `tools/uv-0.13.5-linux-arm64` |
| playwright | (node bin) | `node/bin/playwright`; browsers `chromium-1208`, `chromium_headless_shell-1208`, `ffmpeg-1011` |

System packages (`/usr/bin`): git 2.43.0, curl 8.5.0, sqlite3 3.45.1, jq 1.7, openssl 3.0.13, OpenSSH 9.6, tar, wget.

**NOT installed:** pandoc, libreoffice, wkhtmltopdf (PDF/Office via Hermes built-in tools).

---

## 5. Bind Mounts (qroot bridge)

```
/dev:/dev   /proc:/proc   /sys:/sys
[android-host-path]:/root/.hermes
/root/.hermes/node:/root/.hermes/node
/root/.hermes/bin:/root/.hermes/bin
/root/.hermes/tools:/root/.hermes/tools
/root/.hermes/installs:/root/.hermes/installs
[android-host-path]:/root/workspace
[android-host-path]:/mnt/android/hermes-workspace
```

---

## 6. OS / Kernel

```
Kernel: Linux localhost 5.15.197-android13-8-00017-g6a42d5ffa908-ab15936251
        #1 SMP PREEMPT Fri Jul 24 09:15:28 UTC 2026 aarch64 GNU/Linux
Distro: Ubuntu 24.04.5 LTS (Noble Numbat)  [ID=ubuntu, ID_LIKE=debian]
RootFS: rootfs 47G (27G used) mounted on /   — bind mount: /mnt/android/hermes-workspace
```
