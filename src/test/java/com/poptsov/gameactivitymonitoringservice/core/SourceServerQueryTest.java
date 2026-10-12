package com.poptsov.gameactivitymonitoringservice.core;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SourceServerQueryTest {
    @Test
    void readsFullResponseAndIncludesNewlyConnectedNamedPlayers() throws Exception {
        byte[] name = "Новый игрок".getBytes(StandardCharsets.UTF_8);
        var payload = ByteBuffer.allocate(6 + 1 + name.length + 1 + 8).order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt(-1).put((byte) 0x44).put((byte) 1).put((byte) 0).put(name).put((byte) 0).putInt(0).putFloat(2);
        assertEquals(Set.of("Новый игрок"), query(challenge(-1, 0x41), payload.array()));
    }

    @Test
    void rejectsDeclaredPlayersMissingFromPacket() {
        assertThrows(Exception.class, () -> query(challenge(-1, 0x41), new byte[]{-1, -1, -1, -1, 0x44, 1}));
    }

    @Test
    void rejectsInvalidChallengeHeader() {
        assertThrows(Exception.class, () -> query(challenge(123, 0x41), new byte[]{-1, -1, -1, -1, 0x44, 0}));
    }

    @Test
    void rejectsInvalidPlayerResponseHeader() {
        assertThrows(Exception.class, () -> query(challenge(-1, 0x41), new byte[]{-1, -1, -1, -1, 0x45, 0}));
    }

    @Test
    void disallowedNicknameDoesNotSuppressOtherPlayersInCompletePacket() throws Exception {
        byte[] first = "Normal".getBytes(StandardCharsets.UTF_8);
        byte[] second = "Name\tWithTab".getBytes(StandardCharsets.UTF_8);
        var payload = ByteBuffer.allocate(6 + 20 + first.length + second.length).order(ByteOrder.LITTLE_ENDIAN);
        payload.putInt(-1).put((byte) 0x44).put((byte) 2);
        payload.put((byte) 0).put(first).put((byte) 0).putInt(0).putFloat(2);
        payload.put((byte) 1).put(second).put((byte) 0).putInt(0).putFloat(2);
        assertEquals(Set.of("Normal"), query(challenge(-1, 0x41), payload.array()));
    }

    private byte[] challenge(int header, int type) {
        return ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).putInt(header).put((byte) type).putInt(1234).array();
    }

    private Set<String> query(byte[] challenge, byte[] players) throws Exception {
        try (var server = new DatagramSocket(0); var executor = Executors.newSingleThreadExecutor()) {
            server.setSoTimeout(1000);
            var responder = executor.submit(() -> {
                try {
                    var request = new DatagramPacket(new byte[64], 64);
                    server.receive(request);
                    server.send(new DatagramPacket(challenge, challenge.length, request.getSocketAddress()));
                    server.receive(request);
                    server.send(new DatagramPacket(players, players.length, request.getSocketAddress()));
                } catch (Exception error) {
                    // An invalid challenge may end the client before its second request.
                }
            });
            try {
                return new SourceServerQuery("127.0.0.1", server.getLocalPort(), 1000).getPlayers();
            } finally {
                responder.get(3, TimeUnit.SECONDS);
            }
        }
    }
}
