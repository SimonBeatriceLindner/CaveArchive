# Changelog - CaveArchive

All notable changes to this project will be documented in this file.

## [1.1.2] - 2026-06-03
### Added
- **Survey Upload Feature**: Users can now upload local surveys to Google Drive directly from the app.
- **Junction Point Management (Join Branch)**: New mode for uploading secondary branches with a searchable junction point selector to connect new surveys to existing ones in the cloud.
- **Dynamic Schema Support**: The upload process now dynamically clones the SQLite table structure from TopoDroid, ensuring compatibility with all versions.
- **Geopoint Validation**: Added safety checks to ensure surveys uploaded in "Geopoint" mode contain valid coordinates.
- **User Guide**: Added a comprehensive English user guide (`GUIDA_UTENTE.md`) accessible within the project files.

### Changed
- **Robust Download System**: Re-engineered the synchronization process to use chunked downloads (8KB blocks). This makes the app more responsive and allows for near-instant cancellation.
- **Enhanced Error Handling**: Improved log messages with detailed error reporting for Google Drive operations (e.g., specific 403 Forbidden details).
- **Network Resilience**: Implemented Exponential Backoff for retries during sync, significantly improving stability on weak mountain/cave entrance networks.
- **UI Improvements**: Replaced checkboxes with Radio Buttons in the Upload dialog for better process validation and added searchable dropdowns for handling surveys with thousands of stations.
- **Permission Management**: Optimized Google Drive scopes to use the more secure `DRIVE_FILE` permission where possible.

### Fixed
- **App Crash on Start**: Fixed a critical issue where the app would crash if folder permissions were revoked by the system or cache was cleared.
- **Duplicate File Issue**: Improved the "Delete and Recreate" strategy to handle file ownership issues on Google Drive.
- **JSON Metadata Corruption**: Fixed an issue where the `Cave.json` file could be overwritten or lose data during concurrent updates.
- **Extension Glitch**: Fixed a bug that caused `.tdconfig` files to be uploaded with an incorrect `.txt` extension.
