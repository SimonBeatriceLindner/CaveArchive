# CaveArchive User Guide

CaveArchive is a specialized application designed for speleologists and surveyors who use **TopoDroid**. The app acts as an intelligent bridge between your Android device and **Google Drive**, allowing you to synchronize, archive, and manage underground surveys in a secure, organized, and collaborative way.

---

## 1. Initial Configuration

### Required Permissions (File Access)
Upon first launch, the app will request the **"Allow access to manage all files"** permission.
*   **Why is this necessary?** TopoDroid saves data in a SQLite database (`distox14.sqlite`) located in a shared folder on the phone. To integrate surveys downloaded from the cloud without manual import, CaveArchive must be able to write directly to that database and manage technical project folders.

### Selecting the Work Folder
The app attempts to automatically locate the folder where TopoDroid saves data (usually `Documents/TDX/TopoDroid`). If the folder is not found or if you use a custom one:
1.  Open **Settings** (gear icon at the bottom).
2.  Click on **Change** next to "Storage Folder".
3.  Use the Android system picker to locate and confirm your main TopoDroid folder.

### Google Login and Privacy
Click the **Google Login** button to connect your Drive account.
*   **Security**: The app requests the `DRIVE_FILE` permission. This means CaveArchive can see all files for reading purposes, but can only modify or delete **files that it created itself** or that were uploaded through the app. It has no access to your personal documents, photos, or other private data on your Drive.

---

## 2. Managing Archives

An "Archive" in CaveArchive corresponds to a specific folder on Google Drive dedicated to a speleological project (e.g., "Campo dei Fiori Project" or "Wind Cave").

*   **Multi-project Support**: You can configure unlimited archives. This allows you to keep surveys from different geographic areas or research groups separate.
*   **Configuration**: To add an archive, enter a name of your choice (e.g., "Mount Campo") and the Google Drive folder URL. The URL can be copied directly from your browser's address bar or via the "Copy link" function in the Google Drive app.

---

## 3. Synchronization (Sync - Cloud to Phone)

The **Sync** button is the heart of the application. When pressed, CaveArchive compares the data on the cloud with the data on your phone and downloads only the new updates.

### What is downloaded:
1.  **Topographic Data**: It reads the `Cave.json` file on the cloud and automatically inserts new surveys, complete with measurements (`shots`) and geographic fixed points (`fixeds`), into the TopoDroid database.
2.  **Configurations (.tdconfig / .thconfig)**: It downloads configuration files for Therion or TopoDroid, which are essential for the correct visualization of underground systems.
3.  **Elevation Data (.asc)**: If present, it downloads Digital Terrain Models (DTM) needed to view the depth of branches relative to the surface.

### Safety and Robustness:
*   **Date Check**: The app does not re-download files you have already updated, saving time and data traffic.
*   **Stop Sync**: If the connection is unstable, you can stop the process at any time. The app manages downloads in small "chunks," so it will stop immediately without leaving corrupted files or freezing your phone.

---

## 4. Uploading (Upload - Phone to Cloud)

This function (menu **⋮** -> **Upload**) allows you to share field work with the rest of the team in the cloud.

### Choosing the Correct Mode:

#### A. Upload Geopoint (Main Survey)
Use this for the first survey of a cave or for branches that have a fixed point positioned via GPS.
*   **Requirement**: The survey must have at least one "Fixed" point (geographic coordinate) set in TopoDroid.
*   **Result**: It uploads the measurement database and sets the cave on the project's global cloud map.

#### B. Join Branch (Side Branches / Extensions)
Use this to upload branches that connect to existing surveys already in the cloud.
*   **No Coordinates**: In this mode, local geographic data is ignored, avoiding positioning conflicts.
*   **Intelligent Connection**:
    1.  **First junction point**: Select the station of your new survey (e.g., "10").
    2.  **Survey to connect**: Choose which survey already on the cloud you want to connect to (e.g., "Main Branch").
    3.  **Second junction point**: Select the corresponding station on the existing survey (e.g., "S42").
*   **Automation**: The app will upload the data and automatically write the connection command (`equate`) into the cloud project file, making the connection visible to all team members.

---

## 5. Console Log and Diagnostics

The black window in the center of the app is your control panel. Colors help you immediately understand the status of operations:
*   **Cyan/Light Blue**: The app is communicating with Google or preparing files.
*   **Green**: Operation successful! (e.g., "File saved correctly").
*   **Gray**: Routine information (e.g., "File is already up to date, skipping download").
*   **Yellow (WARNING)**: Something requires attention. Often indicates "duplicate" files on the cloud that the app cannot delete automatically because they were created by other users (see Troubleshooting).
*   **Red (ERROR)**: Critical error (e.g., missing connection or permission denied).

---

## 6. Maintenance and Cleaning (Wipe Data)

The **Wipe Data** function helps keep your TopoDroid folder clean, especially if you work on many different projects.

*   **Wipe by Archive**: Deletes only the surveys belonging to a specific cloud project from your phone.
*   **Wipe All**: Cleans all data managed by CaveArchive.
*   **Personal Data Protection**: CaveArchive internally marks the files it downloads. **The Wipe function will never delete your personal surveys** created manually in TopoDroid that were not downloaded through the app.

---

## 7. Troubleshooting

### "WARNING: Duplicate remains" Message
This happens because CaveArchive, for security reasons, cannot delete files uploaded manually by other users or other apps.
*   **Solution**: Go to your Google Drive, manually delete the reported file (e.g., `Cave.json` or a `.sqlite` file), and run the operation again from the app. From that point on, CaveArchive will become the "owner" of the new file and will be able to manage it without further warnings.

### Slow or Failed Synchronization
If you are in areas with poor reception (mountains, cave entrances), the app will automatically attempt to resume the download 3 times, waiting longer between each attempt. If it still fails, check that you have an active data connection.

---
*Developed for the speleological community by Simon Beatrice Lidner*
