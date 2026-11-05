# CaveArchive

**CaveArchive** è l'applicazione Android progettata per speleologi e rilevatori che utilizzano **TopoDroid**. Permette di sincronizzare, condividere e archiviare facilmente i rilievi ipogei e i dati topografici su repository remoti (come **Microsoft OneDrive**, server **FTP** e altri servizi cloud), garantendo la massima continuità operativa anche in ambienti privi di rete.

---

## 🌟 Caratteristiche Principali

* **Sincronizzazione Semplice ed Efficace**: Sincronizza i rilievi speleologici tra il tuo dispositivo locale e il cloud con un solo tap.
* **Supporto Multi-Repository**: Gestisci molteplici archivi remoti supportando provider come **Microsoft OneDrive** e server **FTP**.
* **Condivisione Rapida tramite QR Code**:
  * **Genera e Condividi**: Crea un QR Code dell'archivio da mostrare o inviare via WhatsApp, Telegram, Email, ecc.
  * **Scansione Fotocamera**: Inquadra il QR Code di un compagno per autocompilare all'istante le impostazioni di un nuovo archivio senza digitare nulla.
* **Upload di Rilievi e DEM/DTM**:
  * Carica nuovi rilievi sul cloud integrando punti fissi (geopunti) e connessioni di giunzione (*Join branch*).
  * Invia modelli digitali del terreno (DEM/DTM).
* **Compressione Automatica ZIP per i DEM**: I file del terreno (.asc) vengono automaticamente compressi in formato `.zip` prima dell'invio sul server, riducendo l'uso di dati mobili e lo spazio occupato nel cloud.
* **Sincronizzazione in Background**: Pianifica il controllo automatico degli aggiornamenti in background.
* **Cancellazione Sicura dei Dati Gestiti (Wipe)**: Elimina solo i dati e i file sincronizzati da CaveArchive, mantenendo al sicuro i tuoi rilievi personali non associati all'archivio.

---

## 📱 Guida all'Uso

### 1. Configurazione Iniziale della Cartella
All'apertura dell'applicazione, imposta la cartella di lavoro di TopoDroid presente sul tuo dispositivo (solitamente situata all'interno dei documenti di sistema).

### 2. Gestione e Aggiunta degli Archivi
Apri il menu delle impostazioni per gestire i tuoi archivi:
* **Aggiunta Manuale**:
  1. Tocca **"Add Archive"**.
  2. Scegli il **Tipo di Repository** (*Microsoft OneDrive* o *FTP Server*).
  3. Inserisci il **Nome dell'Archivio** e l'**URL / Indirizzo del Server**.
  4. Per *OneDrive*: Effettua l'accesso toccando il pulsante di login.
  5. Per *FTP*: Inserisci Username e Password del server.
* **Aggiunta Rapida tramite QR Code**:
  1. Tocca l'icona dello **Scanner QR** in alto a destra nella finestra di nuovo archivio.
  2. Inquadra il QR Code dell'archivio generato da un altro rilevatore.
  3. Tutti i campi (Nome, Tipo e Indirizzo) si compileranno automaticamente!

### 3. Sincronizzazione degli Archivi
Seleziona l'archivio desiderato dal menu a tendina sulla pagina principale e premi **SYNC**:
* CaveArchive scaricherà gli aggiornamenti, le battute e i modelli del terreno inviati dai tuoi compagni di spedizione.
* Se il terreno scaricato è compresso in formato `.zip`, l'app lo estrarrà automaticamente collocandolo nella cartella corretta di TopoDroid.

### 4. Caricamento di Rilievi e Terreni (Upload)
Dal menu in alto a destra, seleziona **Upload**:
* **Upload Rilievo**: Seleziona il rilievo locale da inviare e imposta eventuale giunzione o punto fisso.
* **Upload DEM**: Seleziona un file del terreno (.asc). L'app lo zipperà e lo invierà sul server remoto associandolo all'archivio attivo.

### 5. Condivisione di un Archivio
1. Apri la finestra di modifica di un archivio esistente.
2. Tocca l'icona del **QR Code** in basso a sinistra.
3. Verrà mostrato il QR Code dell'archivio. Puoi farlo inquadrare direttamente ad un altro telefono o toccare il pulsante **Condividi** per inviare l'immagine tramite messaggistica o email.

### 6. Eliminazione Sicura di un Archivio e dei Dati
* Per eliminare un archivio dalla lista, apri la finestra di modifica e **mantieni premuto il pulsante del cestino per 5 secondi**.
* L'anello di caricamento circolare indicherà l'avanzamento della pressione fino al completamento della cancellazione sicura.

---

## 🔒 Privacy e Sicurezza
* La generazione dei QR Code condivide esclusivamente il Nome, l'Indirizzo e il Tipo di Repository dell'archivio, escludendo sempre password o token personali.
