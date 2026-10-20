# ReportKeeper Beta v0.2.0

Native offline Android file manager. Created by PERSPICAL; developed by Adam F. Amru.

- Scan any enabled file categories (documents, photos, videos, audio, archives, other)
- Byte-for-byte duplicate groups by SHA-256, independently of filenames
- Report revision recommendations with three latest recognized revision groups retained; versions are inferred and should be reviewed
- Fast ListView virtualized rows, pages of 80, searchable/sortable file browsing with fixed header and actions
- Multiple named move destinations, category subfolders, SHA-256 verified transfer, and restore history
- Tap checkbox to select, long-press a file to open using an installed app, confirm before irreversible deletion
- Folder-scoped Android Storage Access Framework; no INTERNET permission, analytics, cloud processing or automatic deletion.

Version 0.2 uses a separate **id.amru.reportkeeper.beta** package to run alongside v0.1 because GitHub debug signing certificates are ephemeral. The previous version's move history remains in the previous app. When promoting to stable, establish a persistent signing key.

Known limitations: Exact SHA-256 matches do not identify visually similar but recompressed photos or modified files. File version guesses are based on filenames. Some Android locations (e.g., restricted system folders and root Downloads) are inaccessible with the folder picker. Same-device moves do not free storage capacity; deletion might bypass Recycle Bin. Test with copies of files first.

Source code is hosted on a build branch inside an existing public repository; **no user's actual files are included in source code or uploaded by the app**.
