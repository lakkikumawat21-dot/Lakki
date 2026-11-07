---
name: android-device
description: Device actions bridge for Android (notifications, clipboard, files).
version: 1.0.0
author: Hermes Agent
license: MIT
platforms: [linux, android]
metadata:
  hermes:
    tags: [android, device, notifications, clipboard, bridge]
    related_skills: []
---

# Device Actions

Use this skill when the user asks Hermes Agent to interact with local device capabilities, including notifications, reminder alarms, toast messages, clipboard access, URL opening, and device information. In the in-app chat, treat the current environment as the device by default; natural requests such as "send a notification" or "remind me in one minute" should use this skill.

Decision: use this skill instead of MCP for the local device capability bridge. The app already exposes a loopback HTTP server inside the foreground service, and Hermes Agent can use this skill from the bundled Ubuntu environment with only Python stdlib. This avoids adding another MCP server registration/lifecycle layer while still giving Hermes Agent callable local device actions.

The app provides an authenticated loopback server whose port and access token are injected into the Hermes process. Always use the helper script instead of calling the endpoint directly (fallback to `/root/.hermes/skills/android-device/android_device.py` if `$HERMES_HOME` is unset):

```bash
python3 "${HERMES_HOME:-/root/.hermes}/skills/android-device/android_device.py" health
python3 "${HERMES_HOME:-/root/.hermes}/skills/android-device/android_device.py" tools
python3 "${HERMES_HOME:-/root/.hermes}/skills/android-device/android_device.py" device-info
python3 "${HERMES_HOME:-/root/.hermes}/skills/android-device/android_device.py" notify --title "Hermes Agent" --message "Task completed"
python3 "${HERMES_HOME:-/root/.hermes}/skills/android-device/android_device.py" alarm --delay-seconds 60 --title "Reminder" --message "Check the task"
python3 "${HERMES_HOME:-/root/.hermes}/skills/android-device/android_device.py" toast --message "Hermes Agent is running"
python3 "${HERMES_HOME:-/root/.hermes}/skills/android-device/android_device.py" clipboard-set --text "Copied from Hermes Agent"
python3 "${HERMES_HOME:-/root/.hermes}/skills/android-device/android_device.py" clipboard-get
python3 "${HERMES_HOME:-/root/.hermes}/skills/android-device/android_device.py" open-url --url "https://example.com"
python3 "${HERMES_HOME:-/root/.hermes}/skills/android-device/android_device.py" file-list --path .
python3 "${HERMES_HOME:-/root/.hermes}/skills/android-device/android_device.py" file-share --path report.md
python3 "${HERMES_HOME:-/root/.hermes}/skills/android-device/android_device.py" file-save --path report.md --destination downloads
python3 "${HERMES_HOME:-/root/.hermes}/skills/android-device/android_device.py" file-pick --target-dir inbox
python3 "${HERMES_HOME:-/root/.hermes}/skills/android-device/android_device.py" file-pick --target-dir inbox --mime "image/*"
python3 "${HERMES_HOME:-/root/.hermes}/skills/android-device/android_device.py" file-capture --target-dir inbox
python3 "${HERMES_HOME:-/root/.hermes}/skills/android-device/android_device.py" file-list --path . --recursive
python3 "${HERMES_HOME:-/root/.hermes}/skills/android-device/android_device.py" file-events
python3 "${HERMES_HOME:-/root/.hermes}/skills/android-device/android_device.py" file-events --since 1750000000000
python3 "${HERMES_HOME:-/root/.hermes}/skills/android-device/android_device.py" file-diagnostics
python3 "${HERMES_HOME:-/root/.hermes}/skills/android-device/android_device.py" file-backup
```

Available actions:

- `health`: verify the local capability server is reachable.
- `tools`: list server-side device actions.
- `device-info`: read basic device version, model, and battery information.
- `notify`: show a system notification. Requires `--title` and `--message`.
- `alarm`: schedule a notification after `--delay-seconds`. Optional `--title` and `--message` customize the alarm notification.
- `toast`: show a short toast. Requires `--message`.
- `clipboard-set`: set clipboard plain text. Requires `--text`.
- `clipboard-get`: read clipboard plain text.
- `open-url`: open `http`, `https`, `mailto`, or `tel` URL with the device default handler. Requires `--url`.
- `file-list`: list files and folders inside the Hermes workspace (`/root/workspace`). Optional `--path` is a workspace-relative directory; optional `--recursive` includes every nested file with its relative path.
- `file-share`: share a workspace file through the Android share sheet. Requires `--path` (workspace-relative).
- `file-save`: save a workspace file to the device. `--destination downloads` saves directly to the Downloads folder; `--destination pick` opens the system save dialog. Requires `--path`; optional `--name` renames the exported file.
- `file-pick`: open the system file picker and import the selected files into the workspace. Optional `--target-dir` selects the destination folder (default: workspace root); optional `--mime` filters the picker (for example `image/*`, `application/pdf`). The command waits until the user finishes picking or cancels (up to 3 minutes).
- `file-capture`: open the camera app and save the photo directly into the workspace. Optional `--target-dir` selects the destination folder (default: workspace root); optional `--name` sets the file name. The command waits until the user captures or cancels (up to 3 minutes).
- `file-events`: list recent external file events (imports, captures, syncs,
  LAN uploads, file-manager changes). Optional `--since` (epoch ms), `--type`
  (import/capture/link_sync/copy_from_android/lan_upload/file_manager/backup)
  and `--limit` filters the result.
- `file-diagnostics`: check workspace writability, event log state, configured Android folder links, and free device storage.
- `file-backup`: create a ZIP backup of the workspace and save it to the device Downloads folder.

Workspace paths in this skill are relative to `/root/workspace`. Files shared into Hermes from other Android apps land in `inbox/` automatically.

If `notify` or `alarm` fails with a permission error, ask the user to grant notification permission to the app in system settings.
