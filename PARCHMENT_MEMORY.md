
## User Workflow & Hard Rules
- **NEVER overwrite or copy-paste local builds into the user's local installation directory** (`%LOCALAPPDATA%\Programs\Parchment` or similar). The user updates their installed app exclusively via in-app OTA from GitHub Releases.
- **Three-Command Workflow**:
  1. `plan`: Plan extensively — analyze edge cases, loopholes, developer POV, and end-user POV to design the best UI & UX possible. Do not modify code during `plan`.
  2. `execute`: Implement the planned code changes and verify builds/tests locally (without touching the local installation directory).
  3. `lock`: Commit and push the changes to the GitHub repository (`baros4294/parchment`).
