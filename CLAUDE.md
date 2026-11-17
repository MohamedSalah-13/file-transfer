# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

LAN file-transfer + chat app ("WhatsApp for files"): one JavaFX **server** app and any number of JavaFX **client** apps talking over raw TCP sockets. Java 21, JavaFX 21, AtlantaFX themes, Maven (wrapper included). Docs and comments are partly in Arabic; `docs/1.md`–`docs/15.md` are the step-by-step design notes the code was built from.

## Commands

Windows (PowerShell); use `./mvnw` on Unix shells.

```powershell
.\mvnw.cmd compile                 # build
.\mvnw.cmd -Pserver javafx:run     # run server (GUI, listens on port 5000)
.\mvnw.cmd -Pserver javafx:run "-Djavafx.args=--port 5050 --storage D:\ft-data"   # other port / storage
.\mvnw.cmd -Pclient javafx:run     # run a client (start several in separate terminals)
.\mvnw.cmd test                    # run all tests
.\mvnw.cmd test -Dtest=FileTransferServerTest   # one test class
```

- The `server` / `client` Maven profiles only switch `mainClass` (`server.ServerLauncher` / `client.ClientLauncher`). Without a profile `javafx:run` starts the server.
- Tests (JUnit 5, Surefire 3.x) live in `src/test/java` in the same packages as the code, so Surefire patches them into the JPMS module and they can use package-private members.
  - `SafeNamesTest`, `AccountStoreTest`, `KnownServersTest`, `LastLoginTest`, `AppearanceTest`: plain unit tests.
  - `FileTransferServerTest`: starts a real TLS server on a free port in a `@TempDir` and speaks the raw wire protocol, so a protocol change must update its `Wire` helper too. Creating the test certificate runs `keytool` once per class.
  - `ClientConnectionTest`: runs `ClientConnection` against a scripted fake server (deterministic stalls and cut-offs). It starts the JavaFX toolkit (`Platform.startup`), so it needs a desktop session, not a headless CI box.
- Runtime data goes to `server-storage/` in the working directory (git-ignored), unless `--storage` or `server.properties` says otherwise.

## Architecture

Packages under `src/main/java/com/hamza/filetransfer/`:
- `shared/`: wire constants (`Protocol`), limits (`Limits`: 200 MB max file, 64 KB buffer), `SafeNames` (validation/sanitizing for anything that becomes a path), and `Discovery` (LAN discovery datagrams).
- **LAN discovery (UDP `Discovery.PORT` = 5001):** `client.ServerFinder` broadcasts a query (to 255.255.255.255, each interface's broadcast address and 127.0.0.1) and `server.DiscoveryResponder` (started by `ServerApp`, optional) answers with its protocol version, TCP port and host name. The responder uses `SO_REUSEADDR` so several servers on one machine can all answer, and never sends a reply larger than the query (no amplification). `ServerFinder.merge` folds the answers a server sends from each of its networks into one row, shown as 127.0.0.1 when the server is on this computer. An answer is only a hint: TLS pinning still decides trust.
- `server/`: `FileTransferServer` (socket logic, no JavaFX UI code), `AccountStore` (passwords), `ServerApp` (table of `TransferSession`s). The server updates a `TransferSession` from socket threads; `ServerApp` calls `deliverUpdatesOn(Platform::runLater)` so its properties change only on the FX thread (progress updates are coalesced).
- `client/`: `ClientConnection` (socket logic + callbacks) and `ClientApp` (the whole client UI in one class, built in code — no FXML).

### Wire protocol
- **TLS 1.3 on every socket** (CONTROL and TRANSFER; settings in `shared.Tls`). Since 2.0.0 there is no plain-TCP mode, so 1.x clients and servers can't connect.
  - **Server:** `ServerTls.loadOrCreate` makes a self-signed EC certificate with the JDK's `keytool` on first start and keeps it in `server-storage/tls/` (`server.p12` + password file). `ServerApp` shows its SHA-256 fingerprint. Deleting that folder creates a new identity, and every client will then refuse to connect until its user re-trusts it.
  - **Client, trust on first use:** `TofuTrustManager` accepts only the fingerprint trusted for `host:port` in `KnownServers` (`~/.file-transfer/known_servers.properties`). An unknown server fails with `untrusted_server` (arg: fingerprint) before the password is sent; `ClientApp` shows the fingerprint, saves it if the user trusts it, and reconnects. A changed fingerprint fails with `server_cert_changed` (args: new, trusted, file) and is blocked; `ClientApp.askToTrustChanged` shows both fingerprints, keeps "Close" as the default button, and enables "Trust the new certificate" only after the user ticks that they compared it with the server window. Every retry re-checks the certificate against the one just saved. A server without TLS gives `tls_failed`.
- **Framing:** `DataInputStream`/`DataOutputStream` with `writeUTF` commands followed by positional fields. There is no length envelope, so **client and server must read and write exactly the same fields in the same order**. Any protocol change means editing `Protocol`, the server handler, and the `ClientConnection` reader together, **and bumping `Protocol.VERSION`**.
- **Version check:** every socket starts with `CONNECT <Protocol.VERSION:int>`, followed right away by `HELLO`/`HELLO_TRANSFER` (no reply in between). On a mismatch the server answers `AUTH_FAILED version_mismatch <serverVersion> <clientVersion>` and closes. A bare `HELLO` (a client from before versioning) counts as version 0. A server from before versioning answers `CONNECT` with `ERROR bad_handshake`, which `ClientConnection.loginError` reports as `version_mismatch 0 <ours>`. Never change the `CONNECT` format itself; it is how any two versions recognize each other. The comments in `Protocol.java` document the handshake.
- **Two kinds of socket per user:**
  - **CONTROL:** `HELLO user password` → `HELLO_OK canonicalName token`. One CONTROL socket per user (`online` map): a new successful login replaces and closes the old one, so a client that vanished without closing can't lock its user out (the server no longer sends `HELLO_TAKEN`). It carries listing commands, text messages (`SEND_TEXT`), and server pushes (`INCOMING_FILE`, `INCOMING_TEXT`).
  - **TRANSFER:** `HELLO_TRANSFER user token`. One extra socket per file transfer: `SEND_FILE` (reply `SEND_OK`/`ERROR` on that socket) or `DOWNLOAD`, then the client closes it (`ClientConnection.openTransfer`). So uploads never block chat, and several transfers run in parallel. The token is only valid while the issuing CONTROL socket is online. The server still accepts `SEND_FILE` on CONTROL, for 1.0.0 clients.
- **Write locking:** every write to a socket's output goes inside `synchronized (lock)`. The server pushes notifications to *other* users' CONTROL streams through `FileTransferServer.push`, which runs on the recipient's own `pusher` thread (`OnlineClient`), so a slow or dead recipient never blocks or disconnects the sender.
- **Responses are async on the client:** `ClientConnection.listenLoop` dispatches each server message to a callback via `Platform.runLater`. `SEND_OK` is ignored, and after sending, the UI simply re-requests the thread (`requestThread`) to refresh. `THREAD_MESSAGES` echoes the peer; the UI only shows it if that peer is still the open chat.
- **Client threading:** never touch the socket from the FX thread. The constructor (connect + login) runs on a background thread; `requestThread`/`requestUsers`/`requestMyFiles` are queued on the `client-requests` thread. Set callbacks before calling `ClientConnection.start()`.
- **Users can't message themselves:** the client hides its own name and the server rejects it (`self_recipient`); otherwise the IN and OUT `.meta` copies would be the same file.
- **Errors are codes, not text:** `ERROR` / `AUTH_FAILED` carry `<code> <argCount> <args...>` (codes in `shared.ErrorCode`; client-only codes in `client.AppError`). The client shows them through `I18n.error(...)`, so a new code needs an `error.<code>` entry in all three `messages*.properties`. English values with `{0}` args go through `MessageFormat`, so avoid apostrophes in them.

### Server storage layout (`server-storage/`)
```
accounts.properties                         # key = lowercase username; value = iterations:salt:hash:displayName (PBKDF2)
users/<user>/inbox/<messageId>.bin|.meta    # received file bytes + metadata (download source)
users/<user>/threads/<peer>/<messageId>.meta  # chat history, one Properties file per message
```
- Each message is written twice: once IN for the recipient and once OUT for the sender. Only the IN copy is `downloadable=true`.
- `.meta` files are `java.util.Properties`, always written with `Properties.store` (never string concatenation) so values can't inject keys.
- There is no database; listing a thread reads every `.meta` file in the folder and sorts by `timestampMillis`.

### Security invariants (keep these when changing code)
- Every username, peer, file id, or file name from the network goes through `SafeNames` before touching the file system.
- Build paths with `SafeNames.resolveInside(base, child)`, never a raw `Path.resolve`.
- Usernames are NFC-normalized and case-insensitive. After login, always use the canonical name returned by `AccountStore.authenticate`.
- The client sanitizes received file names and never overwrites an existing file when saving into a folder (`ClientConnection.uniqueTarget`).
- All traffic is TLS 1.3. Clients pin the server certificate by fingerprint (trust on first use), so the first connection is only safe if the user compares the fingerprint with the server window. Never add a trust-all fallback.

### Known issues
- `messages*.properties` must stay UTF-8: Java 9+ reads resource bundles as UTF-8. The Arabic file was once saved with a non-UTF-8 encoding, which turned every character into `?`. Client UI text goes through `client.I18n` (`I18n.bind(key)` for live bindings that follow language switches); add new strings to all three `messages*.properties` files.
- Server port and storage come from `ServerConfig.load`: `--port`/`--storage` args, then `server.properties` in the working directory (git-ignored), then 5000 / `server-storage`. `FileTransferServer.startAsync` binds before returning, so a busy port throws `BindException` and `ServerApp` shows it. The client fills host, port and user from `LastLogin` (`~/.file-transfer/client.properties`, saved after each successful login with the canonical name, never the password); a missing or broken file gives the defaults in `LastLogin.DEFAULT` (127.0.0.1:5000, user1). The same file keeps the picked language and theme (`Appearance`, saved when a picker changes). Both go through `PrefsFile.update`, which re-reads the file and replaces only its own keys, so neither save drops the other.
- The server certificate is created with `keytool` from `java.home/bin`. A jpackage/jlink runtime built with `--strip-native-commands` has no `keytool`, so a packaged server would need the certificate created another way.
