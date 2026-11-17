package com.hamza.filetransfer.client;

import com.hamza.filetransfer.shared.Discovery;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds servers on the local network (see {@link Discovery}). Blocks for the whole wait:
 * call it off the FX thread.
 */
public final class ServerFinder {

    private ServerFinder() {}

    /** Broadcasts a query on every network and collects the answers that arrive within {@code wait}. */
    public static List<Discovery.Found> find(int udpPort, Duration wait) throws IOException {
        Map<String, Discovery.Found> found = new LinkedHashMap<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            byte[] query = Discovery.query();
            for (InetAddress target : targets()) {
                try {
                    socket.send(new DatagramPacket(query, query.length, target, udpPort));
                } catch (IOException e) {
                    // an interface that can't send (e.g. disconnected): try the others
                }
            }

            byte[] buf = new byte[Discovery.QUERY_SIZE];
            long deadline = System.nanoTime() + wait.toNanos();
            while (true) {
                long leftMillis = (deadline - System.nanoTime()) / 1_000_000;
                if (leftMillis <= 0) break;
                socket.setSoTimeout((int) leftMillis);
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    break;
                }
                Discovery.Found f = Discovery.parseReply(packet.getData(), packet.getLength(), packet.getAddress());
                if (f != null) found.putIfAbsent(f.host() + ":" + f.port(), f); // one answer per query sent
            }
        }
        List<Discovery.Found> list = merge(found.values(), localAddresses());
        list.sort(Comparator.comparing(Discovery.Found::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Discovery.Found::host).thenComparingInt(Discovery.Found::port));
        return list;
    }

    /**
     * 255.255.255.255 (Windows may send it on one network only), each network's own broadcast
     * address, and 127.0.0.1 for a server on this computer.
     */
    private static Set<InetAddress> targets() throws IOException {
        Set<InetAddress> targets = new LinkedHashSet<>();
        targets.add(InetAddress.getByName("255.255.255.255"));
        for (NetworkInterface nic : NetworkInterface.networkInterfaces().toList()) {
            try {
                if (!nic.isUp() || nic.isLoopback()) continue;
            } catch (IOException e) {
                continue;
            }
            for (InterfaceAddress a : nic.getInterfaceAddresses()) {
                if (a.getBroadcast() != null) targets.add(a.getBroadcast());
            }
        }
        targets.add(InetAddress.getLoopbackAddress());
        return targets;
    }

    /**
     * One row per server. A server answers once per network it shares with us (Wi-Fi, VPN, WSL...),
     * so answers with the same name and port are merged. A server on this computer becomes
     * 127.0.0.1; otherwise a private LAN address (192.168.x, 10.x, 172.16-31.x) is preferred.
     */
    static List<Discovery.Found> merge(Iterable<Discovery.Found> all, Set<String> localAddresses) {
        Map<String, List<Discovery.Found>> byServer = new LinkedHashMap<>();
        for (Discovery.Found f : all) {
            byServer.computeIfAbsent(f.name() + "|" + f.port() + "|" + f.version(), k -> new ArrayList<>()).add(f);
        }
        List<Discovery.Found> result = new ArrayList<>();
        for (List<Discovery.Found> copies : byServer.values()) {
            Discovery.Found first = copies.getFirst();
            boolean onThisComputer = copies.stream().anyMatch(f -> isLoopback(f.host()) || localAddresses.contains(f.host()));
            String host = onThisComputer ? "127.0.0.1" : copies.stream().map(Discovery.Found::host)
                    .filter(ServerFinder::isPrivateLan).findFirst().orElse(first.host());
            result.add(new Discovery.Found(host, first.port(), first.name(), first.version()));
        }
        return result;
    }

    private static Set<String> localAddresses() {
        Set<String> local = new LinkedHashSet<>();
        try {
            for (NetworkInterface nic : NetworkInterface.networkInterfaces().toList()) {
                nic.inetAddresses().forEach(a -> local.add(a.getHostAddress()));
            }
        } catch (IOException e) {
            // without the list, a local server just shows one of its LAN addresses
        }
        return local;
    }

    static boolean isLoopback(String host) {
        return host.startsWith("127.") || host.equals("0:0:0:0:0:0:0:1");
    }

    private static boolean isPrivateLan(String host) {
        if (host.startsWith("10.") || host.startsWith("192.168.")) return true;
        if (host.startsWith("172.")) {
            String[] parts = host.split("\\.");
            try {
                int second = Integer.parseInt(parts[1]);
                return second >= 16 && second <= 31;
            } catch (RuntimeException e) {
                return false;
            }
        }
        return false;
    }
}
