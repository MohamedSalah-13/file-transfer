package com.hamza.filetransfer.client;

import com.hamza.filetransfer.shared.Discovery.Found;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ServerFinderTest {

    @Test
    void aServerOnThisComputerIsOneRowAt127001() {
        List<Found> answers = List.of(
                new Found("100.114.1.1", 5000, "pc", 1),   // VPN
                new Found("172.21.64.1", 5000, "pc", 1),   // WSL
                new Found("192.168.1.2", 5000, "pc", 1));  // Wi-Fi
        assertEquals(List.of(new Found("127.0.0.1", 5000, "pc", 1)),
                ServerFinder.merge(answers, Set.of("192.168.1.2", "172.21.64.1", "100.114.1.1")));
    }

    @Test
    void aRemoteServerOnSeveralNetworksPrefersItsPrivateLanAddress() {
        List<Found> answers = List.of(
                new Found("100.70.3.4", 5000, "office", 1),
                new Found("192.168.1.20", 5000, "office", 1));
        assertEquals(List.of(new Found("192.168.1.20", 5000, "office", 1)), ServerFinder.merge(answers, Set.of()));
    }

    @Test
    void differentServersStaySeparate() {
        List<Found> answers = List.of(
                new Found("192.168.1.20", 5000, "office", 1),
                new Found("192.168.1.20", 5050, "office", 1),  // second server on the same machine
                new Found("192.168.1.30", 5000, "lab", 1));
        assertEquals(answers, ServerFinder.merge(answers, Set.of()));
    }
}
