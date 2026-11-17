# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

LAN file-transfer + chat app ("WhatsApp for files"): one JavaFX **server** app and any number of JavaFX **client** apps talking over raw TCP sockets. Java 21, JavaFX 21, AtlantaFX themes, Maven (wrapper included). Docs and comments are partly in Arabic; `docs/1.md`–`docs/15.md` are the step-by-step design notes the code was built from.

## Commands

Windows (PowerShell); use `./mvnw` on Unix shells.

```powershell
.\mvnw.cmd compile                 # build
.\mvnw.cmd -Pserver javafx:run     # run server (GUI, listens on port 5000)
.\mvnw.cmd -Pclient javafx:run     # run a client (start several in separate terminals)
```

- The `server` / `client` Maven profiles only switch `mainClass` (`server.ServerLauncher` / `client.ClientLauncher`). Without a profile `javafx:run` starts the server.
- There is no `src/test` yet. JUnit 5 is declared in the pom, but no Surefire version is pinned, and the project is a JPMS module (`module-info.java`). Adding tests needs a modern `maven-surefire-plugin` (3.x) in the pom first.
- Runtime data goes to `server-storage/` in the working directory (git-ignored).

## Architecture

Packages under `src/main/java/com/hamza/filetransfer/`:
- `shared/`: wire constants (`Protocol`), limits (`Limits`: 200 MB max file, 64 KB buffer), and `SafeNames` (validation/sanitizing for anything that becomes a path).
- `server/`: `FileTransferServer` (socket logic, no JavaFX UI code), `AccountStore` (passwords), `ServerApp` (table of `TransferSession`s).
- `client/`: `ClientConnection` (socket logic + callbacks) and `ClientApp` (the whole client UI in one class, built in code — no FXML).
- `HelloApplication` / `HelloController` / `Launcher` / `hello-view.fxml` are unused template leftovers.

### Wire protocol
- **Framing:** `DataInputStream`/`DataOutputStream` with `writeUTF` commands followed by positional fields. There is no length/version envelope, so **client and server must read and write exactly the same fields in the same order**. Any protocol change means editing `Protocol`, the server handler, and the `ClientConnection` reader together. The comments in `Protocol.java` document the handshake.
- **Two kinds of socket per user:**
  - **CONTROL:** `HELLO user password` → `HELLO_OK canonicalName token`. Only one CONTROL socket per user is allowed (`online` map). It carries listing commands, uploads (`SEND_FILE`/`SEND_TEXT`), and server pushes (`INCOMING_FILE`, `INCOMING_TEXT`).
  - **TRANSFER:** `HELLO_TRANSFER user token`. These are extra parallel sockets used for `DOWNLOAD`. The token is only valid while the issuing CONTROL socket is online.
- **Write locking:** every write to a socket's output goes inside `synchronized (lock)`. The server writes to *other* users' CONTROL streams to push notifications, which is why `OnlineClient` carries `out` + `lock`.
- **Responses are async on the client:** `ClientConnection.listenLoop` dispatches each server message to a callback via `Platform.runLater`. `SEND_OK` is ignored, and after sending, the UI simply re-requests the thread (`requestThread`) to refresh.

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
- Traffic is not encrypted (no TLS), so passwords cross the network in clear text.

### Known issues
- `TransferSession` JavaFX properties are updated from server socket threads, not the FX thread.
- Uploads run on the CONTROL socket while holding the write lock, which blocks chat and notifications for that user until the upload finishes.
- `messages*.properties` must stay UTF-8: Java 9+ reads resource bundles as UTF-8. The Arabic file was once saved with a non-UTF-8 encoding, which turned every character into `?`. Client UI text goes through `client.I18n` (`I18n.bind(key)` for live bindings that follow language switches); add new strings to all three `messages*.properties` files.
- The port (5000) and storage path are hard-coded in `ServerApp`; client defaults are in `ClientApp`.
