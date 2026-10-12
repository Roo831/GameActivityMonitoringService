package com.poptsov.gameactivitymonitoringservice.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.util.HashSet;
import java.util.Set;

public class SourceServerQuery {

    private final String host;
    private final int port;
    private final int timeoutMs;
    public SourceServerQuery(String host, int port, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
    }

    public Set<String> getPlayers() throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            InetSocketAddress address = new InetSocketAddress(host, port);
            socket.connect(address);

            // Этап 1: Отправляем A2S_PLAYER с challenge = 0xFFFFFFFF
            byte[] request1 = buildA2SPlayerRequest(0xFFFFFFFF);
            socket.send(new DatagramPacket(request1, request1.length, address));

            // Этап 2: Получаем S2C_CHALLENGE
            byte[] buffer = new byte[65507];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);

            if (response.getLength() >= 5 && buffer[4] == 0x44) return parsePlayers(buffer, response.getLength());

            int challenge = parseChallenge(buffer, response.getLength());

            // Этап 3: Отправляем A2S_PLAYER с реальным challenge
            byte[] request2 = buildA2SPlayerRequest(challenge);
            socket.send(new DatagramPacket(request2, request2.length, address));

            // Этап 4: Получаем S2A_PLAYER
            response.setLength(buffer.length);
            socket.receive(response);

            return parsePlayers(buffer, response.getLength());
        }
    }

    private byte[] buildA2SPlayerRequest(int challenge) {
        return ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(-1).put((byte) 0x55).putInt(challenge).array();
    }

    private int parseChallenge(byte[] buffer, int length) throws IOException {
        if (length != 9) throw new IOException("Invalid A2S challenge length.");
        ByteBuffer bb = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        if (bb.getInt() != -1 || bb.get() != 0x41) throw new IOException("Invalid A2S challenge header.");
        return bb.getInt();
    }

    private Set<String> parsePlayers(byte[] buffer, int length) throws IOException {
        if (length < 6) throw new IOException("Incomplete A2S player header.");
        Set<String> players = new HashSet<>();
        ByteBuffer bb = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN);

        // shortcut: split/compressed A2S replies are rejected, add reassembly if a server requires it.
        if (bb.getInt() != -1 || bb.get() != 0x44) throw new IOException("Unsupported or invalid A2S player header.");

        int playerCount = bb.get() & 0xFF;

        for (int i = 0; i < playerCount; i++) {
            if (bb.remaining() < 10) throw new IOException("Incomplete A2S player entry.");
            bb.get(); // index
            String name = readNullTerminatedString(bb);

            if (bb.remaining() < 8) throw new IOException("Incomplete A2S player score or duration.");
            bb.getInt();
            float duration = bb.getFloat();
            if (!Float.isFinite(duration) || duration < 0) {
                throw new IOException("Invalid A2S player entry.");
            }
            if (!name.trim().isEmpty() && name.chars().noneMatch(Character::isISOControl)) players.add(name.trim());
        }
        if (bb.hasRemaining()) throw new IOException("Unexpected bytes after A2S player entries.");
        return players;
    }

    private String readNullTerminatedString(ByteBuffer bb) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        while (bb.hasRemaining()) {
            byte b = bb.get();
            if (b == 0) return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(baos.toByteArray())).toString();
            baos.write(b);
        }
        throw new IOException("Unterminated A2S player nickname.");
    }
}
