# Duplicutter Safety Beta v0.4
Created by PERSPICAL. Developed by Adam F. Amru.
Safety update: one protected keeper per exact SHA-256 duplicate group, explicit Select All EXTRA copies, no deletion of originals, nonduplicates, or revision-only candidates. The app verifies the selected file hash AND protected keeper hash before each deletion, refuses deletions if changed/missing. Safe Auto Cleanup is a preview followed by confirmation; there is no unattended deletion. 
Usability update: compact fixed footer, pager near search, Android 15+ system bar insets, blue-themed About/dialogs, long press for file details. Selected file size/paths available in details.
Full shared-storage scan: Android 11+ opt-in special "All files access" via Settings, then read files from primary shared storage, including Downloads; Android/data, Android/obb and OS-protected locations remain restricted. On older Android versions, use scoped folder picking instead. Scanning time depends on storage/file count; no guaranteed seconds.
Offline: no INTERNET permission. All files access is only for a sideload test build, not an assertion of Play Store policy eligibility.
Unique application ID id.amru.duplicutter.safe to install beside v0.3 given non-persistent GitHub debug signing.
Do not uninstall original until tested. Always start by testing on copies of files.
