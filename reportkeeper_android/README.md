# Duplicutter Android v0.3.0 beta
Created by PERSPICAL • Developed by Adam F. Amru.
Branding: selected glossy blue-cyan diagonal-cut icon, integrated as a WebP drawable and launch icon.
Native offline Android file manager for Samsung / Android 8.0+.
Features: SHA-256 byte-for-byte duplicate detection for photos, videos, documents, audio, archives, and other files; old report revision suggestions preserving three recognized revisions; multi-folder SAF selection, copy/verify/delete move to multiple destinations and category subfolders, restore history, and manual review.
Interface: dark navy/cyan app branding, first-launch onboarding, source/destination cards, scan category selection, storage summary, searchable paginated 80-row results, and fixed action bar.
Build debug APK in GitHub Actions on isolated branch. Application ID id.amru.duplicutter, side-by-side install with older ReportKeeper beta.
Safety: source folders require user selection, no internet permission; no file deletion without explicit user confirmation; move verifies SHA-256 and records restore journal before original removal. Same-storage moves do not free space. Test on copies before using originals.
