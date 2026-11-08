package br.com.rbwone.web;

import org.junit.Test;
import static org.junit.Assert.*;

/** Independent boundaries for native presentation; no Android or SDK operation is involved. */
public final class PhoneCallStateBoundaryTest {
    private static final String OWNER_A = "owner-session-00000001";
    private static final String OWNER_B = "owner-session-00000002";

    private static PhoneCallRequest ring(String owner, String id, String label, String accept) throws Exception {
        return PhoneCallRequest.parse("{\"requestId\":\"boundary\",\"action\":\"sync\",\"payload\":{\"ownerKey\":\""
                + owner + "\",\"callId\":\"" + id + "\",\"state\":\"ringing\",\"label\":\"" + label
                + "\",\"acceptLabel\":\"" + accept + "\"}}");
    }

    @Test public void labelAndRoleRefreshKeepNonceAndOriginalMonotonicDeadline() throws Exception {
        PhoneCallState state = new PhoneCallState();
        state.sync(ring(OWNER_A, "call-a", "Pessoa A", "Atender"), 10_000);
        PhoneCallState.Call first = state.current(10_000);
        state.sync(ring(OWNER_A, "call-a", "Nome atualizado", "Ligar"), 40_000);
        PhoneCallState.Call updated = state.current(40_000);
        assertEquals(first.nonce, updated.nonce);
        assertEquals(first.expiresAt, updated.expiresAt);
        assertEquals("Nome atualizado", updated.label);
        assertEquals("Ligar", updated.acceptLabel);
        assertEquals(10_000 + PhoneCallState.MAX_RING_MS, updated.expiresAt);
    }

    @Test public void exactExpiryRejectsBothActionsAndRepeatedRingingCannotReviveIt() throws Exception {
        for (String action : new String[]{"answer", "reject"}) {
            PhoneCallState state = new PhoneCallState();
            state.sync(ring(OWNER_A, "call-a", "Pessoa", "Atender"), 7);
            PhoneCallState.Call first = state.current(7);
            assertNotNull(state.current(first.expiresAt - 1));
            assertNull(state.respond(first.nonce, action, first.expiresAt));
            state.sync(ring(OWNER_A, "call-a", "Pessoa", "Atender"), first.expiresAt + 1);
            assertNull(state.current(first.expiresAt + 1));
        }
    }

    @Test public void anotherCallReplacesNonceAndRetiresTheEarlierCallWithoutDispatching() throws Exception {
        PhoneCallState state = new PhoneCallState();
        state.sync(ring(OWNER_A, "call-a", "A", "Atender"), 0);
        String oldNonce = state.current(0).nonce;
        state.sync(ring(OWNER_A, "call-b", "B", "Atender"), 1);
        PhoneCallState.Call current = state.current(1);
        assertNotEquals(oldNonce, current.nonce);
        assertNull(state.respond(oldNonce, "answer", 2));
        assertNull(state.respond(oldNonce, "reject", 2));
        state.sync(ring(OWNER_A, "call-a", "A atrasada", "Atender"), 3);
        assertEquals("call-b", state.current(3).callId);
        assertEquals(current.nonce, state.current(3).nonce);
    }

    @Test public void sameCallIdInAnotherOwnerCannotReuseOldNonceOrClearTheNewPresentation() throws Exception {
        PhoneCallState state = new PhoneCallState();
        state.sync(ring(OWNER_A, "same-id", "A", "Atender"), 0);
        String oldNonce = state.current(0).nonce;
        state.sync(ring(OWNER_B, "same-id", "B", "Atender"), 1);
        PhoneCallState.Call current = state.current(1);
        state.clear(OWNER_A);
        assertNotNull(state.current(2));
        assertEquals(OWNER_B, state.current(2).ownerKey);
        assertNotEquals(oldNonce, current.nonce);
        assertNull(state.respond(oldNonce, "answer", 2));
        state.sync(ring(OWNER_A, "stale-owner", "A", "Atender"), 3);
        assertEquals(current.nonce, state.current(3).nonce);
    }

    @Test public void unsupportedOrMissingActionNeverConsumesTheLiveCall() throws Exception {
        PhoneCallState state = new PhoneCallState();
        state.sync(ring(OWNER_A, "call-a", "A", "Atender"), 0);
        String nonce = state.current(0).nonce;
        for (String action : new String[]{null, "open", "dial", "video", "accept", "Answer", ""}) {
            assertNull(state.respond(nonce, action, 1));
            assertEquals(nonce, state.current(1).nonce);
        }
        assertNull(state.respond(null, "answer", 1));
        assertNotNull(state.current(1));
    }

    @Test public void terminalStateBeforeRingPreventsAnOutOfOrderAlert() throws Exception {
        for (String terminal : new String[]{"talking", "ended"}) {
            PhoneCallState state = new PhoneCallState();
            PhoneCallRequest ended = PhoneCallRequest.parse("{\"requestId\":\"r\",\"action\":\"sync\",\"payload\":{\"ownerKey\":\""
                    + OWNER_A + "\",\"callId\":\"call-a\",\"state\":\"" + terminal + "\"}}");
            state.sync(ended, 0);
            state.sync(ring(OWNER_A, "call-a", "A", "Atender"), 1);
            assertNull(state.current(1));
        }
    }

    @Test public void resetRevokesPendingNonceAndFutureSameIdReceivesFreshNonce() throws Exception {
        PhoneCallState state = new PhoneCallState();
        state.sync(ring(OWNER_A, "call-a", "A", "Atender"), 0);
        String nonce = state.current(0).nonce;
        state.reset();
        assertFalse(state.isOwner(OWNER_A));
        assertNull(state.respond(nonce, "answer", 1));
        assertNull(state.current(1));
        state.sync(ring(OWNER_A, "call-a", "A", "Atender"), 2);
        assertNotEquals(nonce, state.current(2).nonce);
        assertNull(state.respond(nonce, "reject", 3));
        assertNotNull(state.current(3));
    }
}
