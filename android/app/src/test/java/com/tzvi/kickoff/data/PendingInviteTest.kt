package com.tzvi.kickoff.data

import com.tzvi.kickoff.data.predict.PendingInvite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingInviteTest {

    @Test
    fun `a matchup join link yields its code`() {
        assertEquals("3YJK2CYK", PendingInvite.codeIn("matchup", "join", "3YJK2CYK"))
        // Android lower-cases the scheme it matched, and a friend may well have typed the
        // host in whatever case their keyboard felt like.
        assertEquals("3YJK2CYK", PendingInvite.codeIn("MATCHUP", "JOIN", "3YJK2CYK"))
    }

    @Test
    fun `other links are left alone`() {
        // The match deep link shares this activity, so it has to fall straight through.
        assertNull(PendingInvite.codeIn("kickoff", "match", "12345"))
        assertNull(PendingInvite.codeIn("https", "join", "3YJK2CYK"))
        assertNull(PendingInvite.codeIn("matchup", "join", null))
        assertNull(PendingInvite.codeIn("matchup", "join", "   "))
    }

    @Test
    fun `a code is redeemable exactly once`() {
        val invite = PendingInvite()
        invite.offer("3yjk 2cyk")
        // Upper-cased and de-spaced on the way in, because it gets read aloud and retyped.
        assertEquals("3YJK2CYK", invite.code.value)
        assertEquals("3YJK2CYK", invite.consume())
        // Redeeming twice would be a second join request for the same invitation.
        assertNull(invite.consume())
    }

    @Test
    fun `the link round-trips`() {
        val link = PendingInvite.linkFor("3YJK2CYK")
        assertEquals("matchup://join/3YJK2CYK", link)
    }
}
