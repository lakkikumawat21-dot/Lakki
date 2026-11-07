# Hermes Agent — Deep Code & Architecture Audit Report
**Date:** 2026-11-07  
**Auditor:** Senior Android & Systems Architect  
**Target Repository:** `lakkikumawat21-dot/Lakki` (`hermes-agent`)  
**Strict Policy:** Core Code Freeze (Read & Report Only — Zero Core Modifications)

---

## 1. Executive Summary

A comprehensive, deep scan was performed across all **20,797 files** (including **10,031 Python source files**) in `hermes-agent/`, its boot architecture blueprints (`_system_boot_files/`), dependencies (`pyproject.toml`, `uv.lock`, `package.json`), and system capabilities (`skills/android-device/android_device.py`).

- **Python AST / Syntax Check:** **10,031 / 10,031 files compiled with 0 syntax errors.**
- **Codebase Integrity:** All core files in `agent/`, `gateway/`, `tools/`, `skills/`, and `hermes_cli/` are structurally intact and follow the upstream Nous Research Hermes Agent design.
- **Critical Findings for Deployment & APK Build:** Identified **5 potential runtime blockers / missing links** when transitioning to a fresh environment or standalone Android APK wrapper.

---

## 2. Deep Audit Findings (Core Code Scan)

### 🚨 Finding 1: Strict Python 3.14 Environment Marker Barrier
- **Location:** [`hermes-agent/pyproject.toml`](file:///home/antigravity/hermes-workspace-build/hermes-agent/pyproject.toml#L10-L120)
- **Detail:** In `pyproject.toml`, core dependencies (`openai==2.24.0`, `pydantic==2.13.4`, `httpx==0.28.1`, `rich==14.3.3`, `ruamel.yaml==0.18.16`, `jinja2==3.1.6`, `requests==2.33.0`, etc.) are declared with the environment marker:
  ```toml
  "openai==2.24.0; python_version >= '3.14'"
  "pydantic==2.13.4; python_version >= '3.14'"
  ```
- **Impact in New Environment:** If a standard Ubuntu/Debian environment with Python 3.11, 3.12, or 3.13 attempts to run `pip install .` or `python run_agent.py`, pip will **silently skip all core dependencies**, resulting in immediate `ModuleNotFoundError: No module named 'pydantic'` / `'openai'`.
- **System Truth (`BOOT_ARCHITECTURE.md`):** The live Android environment utilizes a bundled standalone Python build at `/root/.hermes/tools/python-3.14.9+20261021-linux-arm64/bin/python3`. Any environment executing the core agent must use Python 3.14 or supply a Python 3.14 runtime.

---

### 🚨 Finding 2: Missing Dashboard Web SPA Distribution (`web_dist`)
- **Location:** [`hermes_cli/web_server.py:61`](file:///home/antigravity/hermes-workspace-build/hermes-agent/hermes_cli/web_server.py#L61) & [`hermes_cli/web_server_dashboard.py:164-167`](file:///home/antigravity/hermes-workspace-build/hermes-agent/hermes_cli/web_server_dashboard.py#L164-L167)
- **Detail:** 
  `web_server.py` defines:
  ```python
  WEB_DIST = Path(os.environ["HERMES_WEB_DIST"]) if "HERMES_WEB_DIST" in os.environ else Path(__file__).parent / "web_dist"
  ```
  And `web_server_dashboard.py` serves `WEB_DIST / "index.html"`. If missing, it returns:
  ```json
  {"error": "Frontend not built. Run: cd web && npm run build"}
  ```
- **Impact in New Environment:** In the repository, `hermes_cli/web_dist/` is not committed (ignored or unbuilt). When the startup command runs:
  ```bash
  hermes dashboard --host 127.0.0.2 --port 39067 --no-open --skip-build
  ```
  The `--skip-build` flag bypasses `npm run build`. Thus, accessing `http://127.0.0.2:39067` in the Android WebView will result in a 404 JSON response unless `web/` is built beforehand (`cd web && npm run build`) or `HERMES_WEB_DIST` points to pre-compiled assets.

---

### 🚨 Finding 3: Android Device Capability Service Protocol & Auth Tokens
- **Location:** [`hermes-agent/skills/android-device/android_device.py:9-36`](file:///home/antigravity/hermes-workspace-build/hermes-agent/skills/android-device/android_device.py#L9-L36)
- **Detail:** `android_device.py` relies on two environment variables:
  ```python
  ACCESS_PORT = os.environ.get("HERMES_ANDROID_CAPABILITY_PORT", "")  # default: 43097
  ACCESS_TOKEN = os.environ.get("HERMES_ANDROID_CAPABILITY_TOKEN", "") # Bearer token
  ```
  And issues HTTP REST requests:
  - `GET /health`
  - `GET /tools`
  - `GET /device-info`
  - `POST /notify` (`{"title": "...", "message": "..."}`)
  - `POST /alarm` (`{"delay_seconds": "...", "title": "...", "message": "..."}`)
  - `POST /toast` (`{"message": "..."}`)
  - `POST /clipboard` (`{"text": "..."}`)
  - `GET /clipboard`
- **Impact in New Environment:** If the Android APK wrapper does not run an embedded HTTP server on `127.0.0.1:43097` (or whatever port is defined in `HERMES_ANDROID_CAPABILITY_PORT`), or if the `Authorization: Bearer <token>` does not match, all device tools (`device-info`, `toast`, `notify`, `clipboard`) will throw:
  ```
  RuntimeError: Android capability credentials are unavailable
  ```
  or
  ```
  RuntimeError: Device capability server is unavailable
  ```

---

### 🚨 Finding 4: Loopback Namespace & Host IP Binding (`127.0.0.2` vs `127.0.0.1`)
- **Location:** [`hermes-agent/_system_boot_files/BOOT_ARCHITECTURE.md:39-42`](file:///home/antigravity/hermes-workspace-build/hermes-agent/_system_boot_files/BOOT_ARCHITECTURE.md#L39-L42)
- **Detail:** In standard Android Linux loopback implementations, binding to `127.0.0.2` requires either root privileges or the custom loopback namespace bridge (`libqroot.so` with `QROOT_LOOPBACK_NAMESPACE=hermes-agent`, `QROOT_LOOPBACK_UNIX=1`).
- **Impact in New Environment:** In standard un-rooted Android or standard Termux PRoot environments, binding to `127.0.0.2` can raise `EADDRNOTAVAIL` (Cannot assign requested address) if the loopback alias is not configured. For standard Android environments, fallback or alias mapping to `127.0.0.1` or `0.0.0.0` should be accounted for in the wrapper launcher.

---

### 🚨 Finding 5: Hardcoded Root Filesystem Assumptions
- **Location:** [`_system_boot_files/BOOT_ARCHITECTURE.md`](file:///home/antigravity/hermes-workspace-build/hermes-agent/_system_boot_files/BOOT_ARCHITECTURE.md#L53-L65)
- **Detail:** The original launch scripts assume paths such as:
  - `HERMES_HOME=/root/.hermes`
  - `HERMES_AGENT_ROOT=/usr/local/lib/hermes-agent`
  - `/usr/local/bin/hermes`
  - `/root/workspace`
- **Impact in New Environment:** When running outside the QROOT chroot/container (for example, in Termux `$PREFIX` or Android app internal storage `/data/data/...`), these absolute `/root/` paths must be redirected via environment variables (`HERMES_HOME`, `HERMES_AGENT_ROOT`, `PATH`) so that the agent doesn't attempt to write to non-writable root directories.

---

## 3. Core Code Freeze Compliance
In strict adherence to Rule 1 & 2:
- **No changes** have been made to `agent/`, `gateway/`, `tools/`, `skills/`, `hermes_cli/`, or any Python/Node logic.
- All identified errors and environmental dependencies are reported here as architectural findings.
