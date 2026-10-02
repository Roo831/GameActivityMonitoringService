package com.poptsov.gameactivitymonitoringservice.core;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class RustServerQuery {

    private final String host;
    private final int port;
    private final int timeout;

    public RustServerQuery(String host, int port, int timeout) {
        this.host = host;
        this.port = port;
        this.timeout = timeout;
    }

    public Set<String> getPlayers() throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeout);
            InetSocketAddress address = new InetSocketAddress(host, port);

            // Этап 1: Получаем challenge
            byte[] challengeRequest = createA2SPlayerRequest(0xFFFFFFFF);
            socket.send(new DatagramPacket(challengeRequest, challengeRequest.length, address));

            byte[] buffer = new byte[4096];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);

            // Парсим challenge из ответа
            int challenge = parseChallenge(buffer, response.getLength());

            // Этап 2: Отправляем запрос с challenge
            byte[] playerRequest = createA2SPlayerRequest(challenge);
            socket.send(new DatagramPacket(playerRequest, playerRequest.length, address));

            socket.receive(response);

            // Парсим список игроков
            return parsePlayers(buffer, response.getLength());
        }
    }

    private byte[] createA2SPlayerRequest(int challenge) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);

        // Header: FF FF FF FF
        dos.writeInt(0xFFFFFFFF);
        // Type: 'U' (0x55) для A2S_PLAYER
        dos.writeByte(0x55);
        // Challenge
        dos.writeInt(Integer.reverseBytes(challenge));

        return baos.toByteArray();
    }

    private int parseChallenge(byte[] buffer, int length) {
        ByteBuffer bb = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        bb.getInt(); // Skip header (FF FF FF FF)
        bb.get();    // Skip type ('A' = 0x41)
        return bb.getInt();
    }

    private Set<String> parsePlayers(byte[] buffer, int length) {
        Set<String> players = new HashSet<>();
        ByteBuffer bb = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN);

        bb.getInt(); // Skip header (FF FF FF FF)
        bb.get();    // Skip type ('D' = 0x44)

        // Читаем количество игроков как UNSIGNED byte (исправляет баг!)
        int playerCount = bb.get() & 0xFF; // <-- КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ

        for (int i = 0; i < playerCount; i++) {
            bb.get(); // Skip index
            String name = readString(bb);
            if (name != null && !name.trim().isEmpty()) {
                players.add(name.trim());
            }
            bb.getInt(); // Skip score
            bb.getFloat(); // Skip duration
        }

        return players;
    }

    private String readString(ByteBuffer bb) {
        StringBuilder sb = new StringBuilder();
        while (bb.hasRemaining()) {
            byte b = bb.get();
            if (b == 0) break; // Null terminator
            sb.append((char) b);
        }
        return sb.toString();
    }
}