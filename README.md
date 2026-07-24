# Task Manager (Material You 3, Android)

Shows every installed app — including hidden/disabled system ones — with which are
active in the background, a details view, and a kill/force-stop action.

**Reality check on "killing" apps:** since Android 5.0, apps run sandboxed and a normal
app cannot force-kill another app's process directly (no root). This app uses
`killBackgroundProcesses()` (kills cached/background processes where allowed) and, for a
real force-stop, deep-links into the system App Info screen's Force Stop button — that's
the only way without root/device-owner privileges.

## Get the APK
No local build needed — GitHub Actions builds it on every push.
1. Go to the **Actions** tab of this repo.
2. Open the latest **Build APK** run.
3. Download the `task-manager-debug-apk` artifact, or grab it from **Releases**.
4. Install on your phone (enable "install unknown apps" for your browser/file manager).

## First run
Grant **Usage Access** when prompted (for accurate "last used" times) — Android requires
this be granted manually in Settings, no app can request it as a normal permission.
