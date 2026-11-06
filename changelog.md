# Registro delle Modifiche (Changelog) - CaveArchive

Tutti i cambiamenti significativi a questo progetto saranno documentati in questo file.

---

## [Versione 1.2.1] - 2026-02-27

### 🌟 Nuove Funzionalità
- **Generazione e Condivisione QR Code per Archivi**:
  - Generazione di QR Code ad alta risoluzione (512x512) contenenti i metadati dell'archivio (Nome, URL/Host, Tipo Repository).
  - Pulsante di condivisione nativa dell'immagine PNG del QR Code tramite le altre applicazioni installate sul dispositivo (WhatsApp, Telegram, Email, Quick Share) con `FileProvider` sicuro.
- **Scansione Fotocamera con Autocompilazione (ZXing Integration)**:
  - Scanner fotocamera integrato per inquadrare il QR Code di un archivio.
  - Orientamento della fotocamera forzato e bloccato in modalità **Verticale (Portrait)**.
  - Riconoscimento ed esecuzione dello switch automatico del tipo di provider (es. da OneDrive a FTP) e compilazione istantanea dei campi Nome, URL e credenziali.
- **Architettura Astratta Multi-Provider (`RepositoryProvider`)**:
  - Architettura ad adattatori astratti per isolare la gestione remota dei file.
  - Supporto nativo per **Microsoft OneDrive** (MSAL + Graph API v1.0) e **Server FTP** (autenticazione Username/Password con Apache Commons Net).
- **Compressione e Decompressione Automatica ZIP per DEM/DTM**:
  - Compressione nativa dei modelli del terreno (.asc) in archivi `.zip` prima dell'upload per ridurre il consumo di banda dati e lo spazio cloud.
  - Decompressione automatica durante il download e salvataggio del file estrapolato nella cartella `DTM/` di TopoDroid.
  - Piena retrocompatibilità per i file del terreno non compressi.

### 🛠️ Miglioramenti e Correzioni di Bug
- **Risoluzione Blocchi durante la Sincronizzazione**:
  - Eliminati i caricamenti infiniti riorganizzando la sincronizzazione in 2 Fasi (Fase 1: Pre-fetch di rete; Fase 2: Transazione batch SQLite in memoria).
  - Rimossa la chiamata bloccante `runBlocking` dai flussi di scaricamento e progresso.
- **Gestione Reindirizzamenti HTTP (302 Found) e Socket**:
  - Gestione manuale dei reindirizzamenti 302 di Microsoft Graph verso le URL pre-firmate di Azure CDN senza inoltrare token di autorizzazione non necessari.
  - Chiusura garantita dei socket HTTP/FTP tramite blocchi `finally { conn.disconnect() }`.
- **Riorganizzazione dell'Interfaccia Grafica (UX/UI)**:
  - Spostato il modulo di login direttamente all'interno della finestra di configurazione dell'archivio.
  - Salvataggio immediato al tap sul pulsante OK nella finestra di modifica con aggiornamento istantaneo del nome dell'archivio nella homepage.
  - Tasto cestino con pressione prolungata per 5 secondi e indicatore circolare rosso dinamico.

---

## [Versione 1.2.0] - 2026-02-15
- Migrazione completa dall'autenticazione Google Drive a Microsoft OneDrive (MSAL & Microsoft Graph API v1.0).
- Abilitata la sincronizzazione e l'upload in background tramite `WorkManager` e `UploadWorker`.
- Rimozione totale di tutte le dipendenze Google GMS/Drive.
