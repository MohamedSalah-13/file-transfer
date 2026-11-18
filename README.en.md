# File Transfer

[العربية](README.md) | **English**

![Version](https://img.shields.io/badge/version-2.3.0-blue) ![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white) ![JavaFX](https://img.shields.io/badge/JavaFX-21-1F6FB2) ![AtlantaFX](https://img.shields.io/badge/AtlantaFX-2.0-6C4AB6) ![Maven](https://img.shields.io/badge/Maven-wrapper-C71A36?logo=apachemaven&logoColor=white) ![Sockets](https://img.shields.io/badge/Sockets-client--server-555555) ![License](https://img.shields.io/badge/License-MIT-green)

A JavaFX app for sending files and chatting between computers on a local network (LAN), like "WhatsApp for files". One server and any number of clients talk directly over TCP sockets. No internet connection and no database are needed.

## Features

### Chat and files
- **Text chat and file sending** between users. The server keeps the history of every conversation.
- **Drag and drop**: drag one or more files from Explorer onto the open chat to send them right away. While dragging, a frame says what will be sent and to whom, or why it can't be (not connected, no user selected). Folders are skipped.
- **Instant notifications**: the server pushes new messages and files to the recipient as soon as they arrive.
- **Parallel transfers**: each upload or download opens its own connection, so it never blocks the chat, and several transfers can run at once.
- **Transfers panel**: shows the status of every upload and download, and why it failed if it did. The panel can be hidden.
- Limits: **200 MB** per file and **2000 characters** per text message.

### Interface
- **Settings window**: the ⚙ Settings button opens a "Connection" tab (server, port, user, password) and an "Appearance" tab (language, theme). The top bar only keeps the connection status and a Connect/Disconnect button. At start, the Connection tab opens by itself.
- **Flexible side panels**: drag the divider between panels to make the users list or the transfers panel wider or narrower, and hide or show each one with its button in the top bar. The widths and which panels are shown are kept for next time.
- **Find the server automatically**: a "Find servers" button lists the servers on the same network and fills in the address and port with one click.
- **Remembers the last login**: the address, port and username are filled in at start, so you only type the password (which is never saved). The language and theme you picked come back too.
- **Smart user list**: unread counts, a preview of the last message, search, and sorting by unread first, then by latest activity.
- **Readable times** in chat bubbles and in the sidebar: just the time for today's messages, then "Yesterday", then the date. Hovering over a message's time shows the full date, and times update on their own after midnight.
- **Arabic and English UI**, switchable while the app is running, with right-to-left layout for Arabic.
- **Translated error messages**: the server sends only an error code, and the client shows it in the UI language.
- **Several themes** from AtlantaFX: Primer, Nord and Cupertino (light and dark), and Dracula.
- **Server window** with a table of transfers and the progress of each one, in Arabic or English and any of the themes, remembered for next time.

### Accounts
- The first login with a new name registers the account. Passwords are stored as PBKDF2 hashes.
- Usernames are case-insensitive (`Ali` and `ali` are the same account) and may contain Arabic letters.
- One session per user: logging in from a new device closes the old session, so a device that dropped off the network can't lock its owner out.

## Requirements

- JDK 21
- No Maven install needed: the project includes the Maven Wrapper (`mvnw` / `mvnw.cmd`).

## Quick start

```powershell
.\mvnw.cmd -Pserver javafx:run     # start the server (listens on port 5000)
.\mvnw.cmd -Pclient javafx:run     # start a client (run several in separate terminals)
```

On Linux or macOS use `./mvnw` instead of `.\mvnw.cmd`.

### Another port or storage folder

If port 5000 is taken, or you want the data somewhere else:

```powershell
.\mvnw.cmd -Pserver javafx:run "-Djavafx.args=--port 5050 --storage D:\ft-data"
```

Or set them once in a `server.properties` file in the working directory:

```properties
port=5050
storage=D:/ft-data
```

Command-line options win over the file, and the file wins over the defaults (`5000` and `server-storage`). The server window shows the port and folder it actually uses, and says clearly when the port is already in use. In the client, enter the same port in the `Port` field.

Full details (client fields, accounts, network and firewall settings) are in [docs/RUNNING.md](docs/RUNNING.md) (Arabic).

### Windows installer

To give the app to people who don't have Java:

```powershell
.\packaging\package.ps1                  # target\installer\File Transfer-<version>.exe
.\packaging\package.ps1 -Type app-image  # a folder to zip and run without installing
```

The installer has its own Java runtime and needs no admin rights. It adds two Start menu shortcuts: **File Transfer** (the client) and **File Transfer Server**. The installed server keeps its data and `server.properties` in `%USERPROFILE%\.file-transfer\server`, so uninstalling or upgrading keeps the accounts, files and certificate. The app icon (a chat bubble with send and receive arrows; the server's adds a server badge) is drawn by `packaging/IconMaker.java`: run `java packaging/IconMaker.java` after changing it. Building the installer needs JDK 17+ (for `jpackage`) and, for the `.exe`, the [WiX Toolset](https://wixtoolset.org/) 3.x.

## Tests

```powershell
.\mvnw.cmd test                                  # all tests (JUnit 5)
.\mvnw.cmd test -Dtest=FileTransferServerTest    # one test class
```

| Test | What it covers |
|---|---|
| `SafeNamesTest` | Name validation and path safety |
| `AccountStoreTest` | Registration, passwords, case-insensitive names and account lockout |
| `FileTransferServerTest` | A real server on a free port, driven over the raw protocol: uploads and downloads, tokens, session takeover and errors |
| `ClientConnectionTest` | `ClientConnection` against a scripted fake server (deliberate stalls and cut-offs) |

`ClientConnectionTest` starts JavaFX, so it needs a desktop session. It won't run on a headless CI machine.

## Project structure

```
src/main/java/com/hamza/filetransfer/
├── shared/   # Protocol (wire commands), ErrorCode (error codes), Limits, SafeNames (name and path checks), Discovery (LAN search)
├── server/   # FileTransferServer (socket logic), AccountStore (accounts), ServerApp (UI)
└── client/   # ClientConnection (networking), ClientApp (the whole client UI, built in code), I18n (translations)
```

UI text lives in `src/main/resources/messages*.properties` (English, Arabic and the default bundle). These files must stay UTF-8.

### How the connection works

The protocol is a simple binary format over `DataInputStream` / `DataOutputStream`: a command (`writeUTF`) followed by its fields in a fixed order. Each user has two kinds of connection:

- **CONTROL**: one per user. It starts with `HELLO user password`, and the server answers `HELLO_OK` with the account's canonical name and a session token. It is used for listing users and conversations, sending text messages and receiving notifications.
- **TRANSFER**: one extra connection per file upload or download. It starts with `HELLO_TRANSFER user token`. The token is only valid while the CONTROL connection is open.

Both kinds of connection start with `CONNECT <version>` before `HELLO`. If the version differs from the server's `Protocol.VERSION`, the server answers with a `version_mismatch` error that carries both version numbers and closes the connection before any login.

Errors are sent as `ERROR <code> <argCount> <args...>`. The codes are defined in `shared/ErrorCode.java`.

**LAN discovery (UDP 5001):** the client broadcasts a query, and each server answers with its name and TCP port. An answer is only an address: the connection itself still goes through the certificate fingerprint check, so a fake server can't get in. A server never answers with more bytes than the query, so it can't be used to flood another machine.

### Server storage

There is no database. Everything is saved in a `server-storage/` folder inside the working directory (or the folder given with `--storage`):

```
server-storage/
├── accounts.properties                            # accounts (password hashes)
└── users/<user>/
    ├── inbox/<messageId>.bin | .meta              # received files and their metadata
    └── threads/<peer>/<messageId>.meta            # chat history, one file per message
```

## Security

- Every username and file name that comes from the network goes through `SafeNames` before it is used in a path, so nothing can escape the storage folder (path traversal).
- The client cleans up received file names and never overwrites an existing file.
- 5 wrong logins in a row lock the account for 30 seconds.
- **All connections use TLS 1.3.** The server creates its own certificate on first start and shows its fingerprint in its window.
- **Trust on first use (TOFU):** the first time a client connects to a server, it shows the server's fingerprint and asks you; compare it with the server window before you accept. After that the client remembers it, and if the certificate ever changes it blocks the connection and warns you (someone may be impersonating the server). The password is only sent after the server is verified.

## Known limitations

- The server and the clients must use the same protocol version. If they don't, the server refuses the login with a clear message asking to install the same version on both. Since 2.0.0 the connection is encrypted, so it does not work with 1.x servers or clients.
- Deleting `server-storage/tls` creates a new certificate. Every client then blocks the connection and shows the old and new fingerprints. Its "Trust the new certificate" button only works after the user confirms they compared the new fingerprint with the server window.

## Documentation

- [docs/RUNNING.md](docs/RUNNING.md): detailed run guide (Arabic).
- [CLAUDE.md](CLAUDE.md): developer notes on the architecture, the protocol and the security rules.
- `docs/1.md` to `docs/15.md`: the step-by-step design notes the project was built from (Arabic).

## License

Released under the [MIT](LICENSE) license.
