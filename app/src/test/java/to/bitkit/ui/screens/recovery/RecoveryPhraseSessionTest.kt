package to.bitkit.ui.screens.recovery

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RecoveryPhraseSessionTest {
    @Test
    fun `a fresh auth response is one use and never survives revoke`() {
        val session = RecoveryPhraseSession()
        session.begin(true)
        val first = assertIs<RecoveryPhrasePhase.Authenticate>(session.phase)
        session.authorize(first, admitted = false)
        assertEquals(first, session.phase)
        session.authorize(first, admitted = true)
        assertEquals(RecoveryPhrasePhase.Phrase, session.phase)
        session.revoke()
        session.authorize(first, admitted = true)
        assertEquals(RecoveryPhrasePhase.Overview, session.phase)
        session.begin(true)
        val second = assertIs<RecoveryPhrasePhase.Authenticate>(session.phase)
        session.authorize(first, admitted = true)
        assertEquals(second, session.phase)
        session.authorize(second, admitted = true)
        assertEquals(RecoveryPhrasePhase.Phrase, session.phase)
    }

    @Test
    fun `disposing recovery rejects callbacks and new requests`() {
        val session = RecoveryPhraseSession()
        session.begin(true)
        val attempt = assertIs<RecoveryPhrasePhase.Authenticate>(session.phase)
        session.close()
        session.authorize(attempt, admitted = true)
        session.begin(false)
        assertEquals(RecoveryPhrasePhase.Overview, session.phase)
    }

    @Test
    fun `explicit request with pin disabled is also revoked`() {
        val session = RecoveryPhraseSession()
        session.begin(false)
        assertEquals(RecoveryPhrasePhase.Phrase, session.phase)
        session.revoke()
        assertEquals(RecoveryPhrasePhase.Overview, session.phase)
    }
}
