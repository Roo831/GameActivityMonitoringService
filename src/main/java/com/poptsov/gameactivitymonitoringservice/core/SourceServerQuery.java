package com.poptsov.gameactivitymonitoringservice.core;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class SourceServerQuery {

    private final String host;
    private final int port;
    private final int timeoutMs;
    private final float minDurationSeconds; // Минимальное время на сервере (фильтр ботов)

    public SourceServerQuery(String host, int port, int timeoutMs, float minDurationSeconds) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
        this.minDurationSeconds = minDurationSeconds;
    }

    public Set<String> getPlayers() throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            InetSocketAddress address = new InetSocketAddress(host, port);

            // Этап 1: Отправляем A2S_PLAYER с challenge = 0xFFFFFFFF
            byte[] request1 = buildA2SPlayerRequest(0xFFFFFFFF);
            socket.send(new DatagramPacket(request1, request1.length, address));

            // Этап 2: Получаем S2C_CHALLENGE
            byte[] buffer = new byte[8192];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);

            int challenge = parseChallenge(buffer, response.getLength());

            // Этап 3: Отправляем A2S_PLAYER с реальным challenge
            byte[] request2 = buildA2SPlayerRequest(challenge);
            socket.send(new DatagramPacket(request2, request2.length, address));

            // Этап 4: Получаем S2A_PLAYER
            socket.receive(response);

            return parsePlayers(buffer, response.getLength());
        }
    }

    private byte[] buildA2SPlayerRequest(int challenge) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}, 0, 4);
        baos.write(0x55);
        baos.write(new byte[]{
                (byte) (challenge & 0xFF),
                (byte) ((challenge >> 8) & 0xFF),
                (byte) ((challenge >> 16) & 0xFF),
                (byte) ((challenge >> 24) & 0xFF)
        }, 0, 4);
        return baos.toByteArray();
    }

    private int parseChallenge(byte[] buffer, int length) {
        ByteBuffer bb = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        bb.getInt(); // FF FF FF FF
        bb.get();    // 'A' (0x41)
        return bb.getInt();
    }

    private Set<String> parsePlayers(byte[] buffer, int length) {
        Set<String> players = new HashSet<>();
        ByteBuffer bb = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN);

        bb.getInt(); // FF FF FF FF
        bb.get();    // 'D' (0x44)

        int playerCount = bb.get() & 0xFF;
        int filteredOut = 0;

        for (int i = 0; i < playerCount && bb.hasRemaining(); i++) {
            bb.get(); // index
            String name = readNullTerminatedString(bb);

            bb.getInt();

            float duration = bb.getFloat();

            // ФИЛЬТРАЦИЯ: пропускаем ботов с малым duration
            if (name != null && !name.trim().isEmpty() && duration >= minDurationSeconds) {
                players.add(name.trim());
            } else {
                filteredOut++;
            }
        }

        System.out.println("[DEBUG] Server reports " + playerCount + " player entries");
        System.out.println("[DEBUG] Filtered out " + filteredOut + " bots (duration < " + minDurationSeconds + "s)");
        System.out.println("[DEBUG] Real players after filtering: " + players.size());

        return players;
    }

    private String readNullTerminatedString(ByteBuffer bb) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        while (bb.hasRemaining()) {
            byte b = bb.get();
            if (b == 0) break;
            baos.write(b);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}