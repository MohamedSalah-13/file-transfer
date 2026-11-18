package com.hamza.filetransfer.server;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * Where the server listens and keeps its data.
 *
 * <p>Each setting comes from the first place that has it:
 * <ol>
 *   <li>command-line options: {@code --port 5050} / {@code --port=5050}, {@code --storage D:\ft-data};</li>
 *   <li>{@value #CONFIG_FILE} in the working directory: {@code port=5050}, {@code storage=D:/ft-data};</li>
 *   <li>defaults: port {@value #DEFAULT_PORT}, storage {@code server-storage}.</li>
 * </ol>
 * A relative storage path is resolved against the working directory.
 *
 * @param port       TCP port, 1-65535
 * @param storageDir absolute folder for accounts, the TLS certificate and user data
 */
public record ServerConfig(int port, Path storageDir) {

    public static final int DEFAULT_PORT = 5000;
    public static final String DEFAULT_STORAGE = "server-storage";
    public static final String CONFIG_FILE = "server.properties";

    public ServerConfig {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535, got " + port);
        }
        storageDir = storageDir.toAbsolutePath().normalize();
    }

    /**
     * Reads the settings for a server started in {@code workDir} with these command-line arguments.
     *
     * @throws IllegalArgumentException for an unknown option, a missing value or a bad port; the
     *                                  message is meant for the user
     * @throws IOException              if {@value #CONFIG_FILE} exists but can't be read
     */
    public static ServerConfig load(List<String> args, Path workDir) throws IOException {
        Properties file = new Properties();
        Path configFile = workDir.resolve(CONFIG_FILE);
        if (Files.isRegularFile(configFile)) {
            try (Reader r = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                file.load(r);
            }
        }
        String port = file.getProperty("port");
        String storage = file.getProperty("storage");
        String portSource = CONFIG_FILE;

        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            String name = arg;
            String value = null;
            int eq = arg.indexOf('=');
            if (arg.startsWith("--") && eq > 0) {
                name = arg.substring(0, eq);
                value = arg.substring(eq + 1);
            }
            if (!name.equals("--port") && !name.equals("--storage")) {
                throw new IllegalArgumentException("Unknown option \"" + arg + "\". Use --port <number> and --storage <folder>.");
            }
            if (value == null) {
                if (i + 1 >= args.size()) throw new IllegalArgumentException("Missing value after " + name + ".");
                value = args.get(++i);
            }
            if (name.equals("--port")) {
                port = value;
                portSource = "--port";
            } else {
                storage = value;
            }
        }

        int portNumber = DEFAULT_PORT;
        if (port != null) {
            try {
                portNumber = Integer.parseInt(port.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("The port in " + portSource + " must be a number, got \"" + port.trim() + "\".");
            }
        }
        String folder = storage == null || storage.isBlank() ? DEFAULT_STORAGE : storage.trim();
        return new ServerConfig(portNumber, workDir.resolve(folder));
    }

    /** Set by the installed app's launcher ({@code packaging/package.ps1}). */
    public static final String PACKAGED_PROPERTY = "filetransfer.packaged";

    /**
     * The folder {@link #load} reads {@value #CONFIG_FILE} from and puts relative storage in.
     * Run from source it is the working directory. The installed app's working directory is its
     * install folder (maybe not writable, and replaced on upgrade or removed on uninstall), so it
     * uses {@code ~/.file-transfer/server} instead.
     */
    public static Path workDir(boolean packaged, Path currentDir, Path userHome) {
        return packaged ? userHome.resolve(".file-transfer").resolve("server") : currentDir;
    }

    /** {@link #workDir(boolean, Path, Path)} for this process. */
    public static Path workDir() {
        return workDir(Boolean.getBoolean(PACKAGED_PROPERTY), Path.of("").toAbsolutePath(),
                Path.of(System.getProperty("user.home")));
    }

    public Path accountsFile() {
        return storageDir.resolve("accounts.properties");
    }

    public Path tlsDir() {
        return storageDir.resolve("tls");
    }

    /** Holds {@code storage.key}, the key that encrypts everything under {@link #usersDir()}. */
    public Path keysDir() {
        return storageDir.resolve("keys");
    }

    public Path usersDir() {
        return storageDir.resolve("users");
    }
}
