package br.com.rbwone.web;

import org.junit.Test;
import static org.junit.Assert.*;

/** Behavior contracts for incoming presentation and delayed actions, independent of Android UI stubs. */
public final class PhoneCallPresentationTest {
    private static final String OWNER_A = "owner-session-00000001";
    private static final String OWNER_B = "owner-session-00000002";
    private static PhoneCallRequest request(String owner, String call, String state, String action) throws Exception {
        return PhoneCallRequest.parse("{\"requestId\":\"presentation\",\"action\":\"sync\",\"payload\":{\"ownerKey\":\""
                + owner + "\",\"callId\":\"" + call + "\",\"state\":\"" + state
                + "\",\"acceptLabel\":\"" + action + "\"}}");
    }
    private static PhoneCallState ringing() throws Exception {
        PhoneCallState state = new PhoneCallState();
        state.sync(request(OWNER_A, "call-a", "ringing", "Atender"), 0);
        return state;
    }

    @Test public void previewValidatesOwnerNonceAndFreshnessWithoutConsumingOrClaiming() throws Exception {
        PhoneCallState state = ringing();
        PhoneCallState.Call call = state.current(0);
        assertNull(state.peek(OWNER_B, call.nonce, 1));
        assertNull(state.peek(OWNER_A, "wrong", 1));
        assertNull(state.peek(null, null, 1));
        assertSame(call, state.peek(OWNER_A, call.nonce, 1));
        assertSame(call, state.current(1));
        assertTrue(state.claimPresentation(1));
        assertSame(call, state.peek(OWNER_A, call.nonce, 2));
        assertNull(state.peek(OWNER_A, call.nonce, call.expiresAt));
    }

    @Test public void presentationIsClaimedOnceAcrossRepeatedRefreshAndForegroundCycles() throws Exception {
        PhoneCallState state = ringing();
        String nonce = state.current(0).nonce;
        // The caller claims even in foreground. Later lifecycle refresh cannot steal focus.
        assertTrue(state.claimPresentation(0));
        assertFalse(state.claimPresentation(1));
        state.sync(request(OWNER_A, "call-a", "ringing", "Atender"), 2);
        assertEquals(nonce, state.current(2).nonce);
        assertFalse(state.claimPresentation(2));
        state.sync(request(OWNER_A, "call-b", "ringing", "Atender"), 3);
        assertTrue(state.claimPresentation(3));
        assertFalse(state.claimPresentation(4));
    }

    @Test public void callbackConfirmationCannotBecomeANativeIncomingScreen() throws Exception {
        PhoneCallState state = new PhoneCallState();
        state.sync(request(OWNER_A, "callback", "ringing", "Ligar"), 0);
        PhoneCallState.Call call = state.current(0);
        assertFalse(state.claimPresentation(0));
        assertNull(state.peek(OWNER_A, call.nonce, 1));
        assertNotNull(state.current(1));
        // Explicit confirmation remains possible through the existing action path.
        PhoneCallState.Action action = state.respond(call.nonce, "answer", 2);
        assertNotNull(action);
        assertEquals("answer", action.action);
    }

    @Test public void delayedActionKeepsItsExactOwnerNonceAndOriginalDeadlineUntilCompleted() throws Exception {
        PhoneCallState state = ringing();
        PhoneCallState.Call call = state.current(0);
        PhoneCallState.Action action = state.respond(call.nonce, "answer", 5);
        assertNotNull(action);
        assertEquals(OWNER_A, action.ownerKey);
        assertEquals(call.nonce, action.nonce);
        assertEquals(call.expiresAt, action.expiresAt);
        assertNull(state.current(5));
        assertTrue(state.pendingActionCurrent(action, 6));
        assertTrue(state.pendingActionCurrent(action, call.expiresAt - 1));
        assertNull(state.respond(call.nonce, "answer", 6));
        state.completeAction(action);
        assertFalse(state.pendingActionCurrent(action, 7));
        state.completeAction(action);
        assertFalse(state.pendingActionCurrent(action, 8));
    }

    @Test public void expirationWhileUnlockIsPendingInvalidatesBothAnswerAndReject() throws Exception {
        for (String response : new String[]{"answer", "reject"}) {
            PhoneCallState state = ringing();
            PhoneCallState.Call call = state.current(0);
            PhoneCallState.Action action = state.respond(call.nonce, response, 1);
            assertTrue(state.pendingActionCurrent(action, call.expiresAt - 1));
            assertFalse(state.pendingActionCurrent(action, call.expiresAt));
            assertFalse(state.pendingActionCurrent(action, call.expiresAt + 1));
        }
    }

    @Test public void clearAndTerminalEventRevokeAnActionAlreadyTakenFromTheNotification() throws Exception {
        for (String terminal : new String[]{"clear", "talking", "ended"}) {
            PhoneCallState state = ringing();
            PhoneCallState.Action action = state.respond(state.current(0).nonce, "answer", 1);
            assertTrue(state.pendingActionCurrent(action, 2));
            if (terminal.equals("clear")) state.clear(OWNER_A);
            else state.sync(request(OWNER_A, "call-a", terminal, "Atender"), 3);
            assertFalse(state.pendingActionCurrent(action, 4));
        }
    }

    @Test public void foreignClearCannotInvalidateCurrentActionButOwnerChangeDoes() throws Exception {
        PhoneCallState state = ringing();
        PhoneCallState.Action action = state.respond(state.current(0).nonce, "answer", 1);
        state.clear(OWNER_B);
        assertTrue(state.pendingActionCurrent(action, 2));
        state.sync(request(OWNER_B, "call-b", "ringing", "Atender"), 3);
        assertFalse(state.pendingActionCurrent(action, 4));
        assertTrue(state.claimPresentation(4));
        assertEquals(OWNER_B, state.current(4).ownerKey);
    }

    @Test public void newCallCannotReceiveAnOldQueuedActionOrAnOldCompletion() throws Exception {
        PhoneCallState state = ringing();
        PhoneCallState.Action oldAction = state.respond(state.current(0).nonce, "answer", 1);
        state.sync(request(OWNER_A, "call-b", "ringing", "Atender"), 2);
        assertFalse(state.pendingActionCurrent(oldAction, 2));
        PhoneCallState.Action newAction = state.respond(state.current(2).nonce, "reject", 3);
        assertTrue(state.pendingActionCurrent(newAction, 3));
        state.completeAction(oldAction);
        assertTrue(state.pendingActionCurrent(newAction, 4));
        state.completeAction(newAction);
        assertFalse(state.pendingActionCurrent(newAction, 5));
    }

    @Test public void processResetRevokesBothPresentationAndQueuedAction() throws Exception {
        PhoneCallState state = ringing();
        PhoneCallState.Call call = state.current(0);
        assertTrue(state.claimPresentation(0));
        PhoneCallState.Action action = state.respond(call.nonce, "answer", 1);
        state.reset();
        assertNull(state.peek(OWNER_A, call.nonce, 2));
        assertFalse(state.pendingActionCurrent(action, 2));
        assertFalse(state.claimPresentation(2));
        assertFalse(state.isOwner(OWNER_A));
        state.sync(request(OWNER_A, "call-a", "ringing", "Atender"), 3);
        assertTrue(state.claimPresentation(3));
        assertNull(state.respond(call.nonce, "answer", 4));
    }

    @Test public void outOfOrderUnrelatedEndDoesNotRevokeTheCurrentQueuedAction() throws Exception {
        PhoneCallState state = ringing();
        PhoneCallState.Action action = state.respond(state.current(0).nonce, "answer", 1);
        state.sync(request(OWNER_A, "different-call", "ended", "Atender"), 2);
        assertTrue(state.pendingActionCurrent(action, 3));
        assertFalse(state.pendingActionCurrent(null, 3));
    }
}
