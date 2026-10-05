package com.bomberman.room;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoomManagerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Mock session that records every payload sent to it. */
    private static class Client {
        final WebSocketSession session = mock(WebSocketSession.class);
        final List<String> received = new ArrayList<>();

        Client(String id) throws Exception {
            when(session.getId()).thenReturn(id);
            when(session.isOpen()).thenReturn(true);
            org.mockito.Mockito.doAnswer(inv -> {
                received.add(inv.<TextMessage>getArgument(0).getPayload());
                return null;
            }).when(session).sendMessage(any(TextMessage.class));
        }

        JsonNode last() throws Exception {
            return MAPPER.readTree(received.get(received.size() - 1));
        }
    }

    private RoomManager manager;

    @BeforeEach
    void setUp() {
        manager = new RoomManager();
    }

    private String createRoom(Client host) throws Exception {
        manager.createRoom(host.session, "p-" + host.session.getId(), "Host", "bomberman", 4);
        return host.last().get("room").asText();
    }

    @Test
    void createRoomReturnsValidUniqueCode() throws Exception {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            Client c = new Client("s" + i);
            String code = createRoom(c);
            assertEquals("room_created", c.last().get("type").asText());
            assertTrue(code.matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{6}"), code);
            assertTrue(codes.add(code), "duplicate code " + code);
        }
        assertEquals(200, manager.roomCount());
    }

    @Test
    void joinRoomSendsRoomJoinedAndNotifiesOthers() throws Exception {
        Client host = new Client("a");
        Client guest = new Client("b");
        String code = createRoom(host);

        manager.joinRoom(guest.session, code, "p-b", "Guest");

        JsonNode joined = guest.last();
        assertEquals("room_joined", joined.get("type").asText());
        assertEquals(code, joined.get("room").asText());
        assertEquals("bomberman", joined.get("game").asText());
        assertEquals(2, joined.get("players").size());
        assertEquals("p-a", joined.get("players").get(0).get("id").asText());
        assertEquals("Host", joined.get("players").get(0).get("name").asText());

        JsonNode announce = host.last();
        assertEquals("player_joined", announce.get("type").asText());
        assertEquals("p-b", announce.get("id").asText());
        assertEquals("Guest", announce.get("name").asText());
    }

    @Test
    void joinAcceptsLowercaseCode() throws Exception {
        Client host = new Client("a");
        Client guest = new Client("b");
        String code = createRoom(host);
        manager.joinRoom(guest.session, code.toLowerCase(), "p-b", "Guest");
        assertEquals("room_joined", guest.last().get("type").asText());
    }

    @Test
    void fullRoomIsRejected() throws Exception {
        Client host = new Client("a");
        manager.createRoom(host.session, "p-a", "Host", "bomberman", 2);
        String code = host.last().get("room").asText();
        Client b = new Client("b");
        Client c = new Client("c");

        manager.joinRoom(b.session, code, "p-b", "B");
        manager.joinRoom(c.session, code, "p-c", "C");

        assertEquals("error", c.last().get("type").asText());
        assertEquals("ROOM_FULL", c.last().get("code").asText());
        assertFalse(manager.isInRoom(c.session));
    }

    @Test
    void unknownRoomIsRejected() throws Exception {
        Client c = new Client("c");
        manager.joinRoom(c.session, "ZZZZZZ", "p-c", "C");
        assertEquals("ROOM_NOT_FOUND", c.last().get("code").asText());
    }

    @Test
    void invalidRequestsAreBadRequest() throws Exception {
        Client c = new Client("c");
        manager.createRoom(c.session, null, "n", "bomberman", 4);
        assertEquals("BAD_REQUEST", c.last().get("code").asText());
        manager.createRoom(c.session, "id", "n", "bomberman", 99);
        assertEquals("BAD_REQUEST", c.last().get("code").asText());
        manager.joinRoom(c.session, null, "id", "n");
        assertEquals("BAD_REQUEST", c.last().get("code").asText());
        assertEquals(0, manager.roomCount());
    }

    @Test
    void leavingNotifiesRemainingAndEmptyRoomIsDeleted() throws Exception {
        Client host = new Client("a");
        Client guest = new Client("b");
        String code = createRoom(host);
        manager.joinRoom(guest.session, code, "p-b", "Guest");

        manager.leave(guest.session);
        JsonNode msg = host.last();
        assertEquals("player_disconnect", msg.get("type").asText());
        assertEquals("p-b", msg.get("id").asText());
        assertTrue(manager.roomExists(code));

        manager.leave(host.session);
        assertFalse(manager.roomExists(code));
        assertEquals(0, manager.roomCount());

        Client late = new Client("c");
        manager.joinRoom(late.session, code, "p-c", "C");
        assertEquals("ROOM_NOT_FOUND", late.last().get("code").asText());
    }

    @Test
    void relayGoesUnchangedToSameRoomOnly() throws Exception {
        Client a1 = new Client("a1");
        Client a2 = new Client("a2");
        Client b1 = new Client("b1");
        Client b2 = new Client("b2");
        String codeA = createRoom(a1);
        String codeB = createRoom(b1);
        manager.joinRoom(a2.session, codeA, "p-a2", "A2");
        manager.joinRoom(b2.session, codeB, "p-b2", "B2");
        int a1Before = a1.received.size();
        int b1Before = b1.received.size();
        int b2Before = b2.received.size();

        String payload = "{\"type\":\"move\",\"id\":\"p-a1\",\"x\":3,  \"y\":4}";
        manager.relay(a1.session, payload);

        assertEquals(payload, a2.received.get(a2.received.size() - 1));
        assertEquals(a1Before, a1.received.size(), "sender must not get its own message");
        assertEquals(b1Before, b1.received.size());
        assertEquals(b2Before, b2.received.size());
    }

    @Test
    void relayWithoutRoomIsNotInRoom() throws Exception {
        Client c = new Client("c");
        manager.relay(c.session, "{\"type\":\"move\"}");
        assertEquals("NOT_IN_ROOM", c.last().get("code").asText());
    }

    @Test
    void duplicatePlayerIdInRoomIsRejected() throws Exception {
        Client host = new Client("a");
        Client dup = new Client("b");
        String code = createRoom(host);
        manager.joinRoom(dup.session, code, "p-a", "Dup");
        assertEquals("BAD_REQUEST", dup.last().get("code").asText());
    }
}
