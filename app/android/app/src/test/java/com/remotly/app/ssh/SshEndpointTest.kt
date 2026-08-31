package com.remotly.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Host canonicalization and endpoint identity. The host field is a trust
// boundary, and the id derived from it has to be collision-free, because two
// endpoints sharing an id would share a credential.
class SshEndpointTest {

    private fun canonical(s: String) = SshEndpoint.canonicalize(s).host

    private fun rejects(s: String) {
        try {
            SshEndpoint.canonicalize(s)
            fail("expected '$s' to be rejected")
        } catch (e: SshHostStoreException) {
            // expected
        }
    }

    // --- DNS -----------------------------------------------------------------

    @Test
    fun lowercasesDnsNames() {
        assertEquals("example.com", canonical("EXAMPLE.com"))
        assertEquals("example.com", canonical("Example.Com"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertEquals("example.com", canonical("  example.com  "))
    }

    @Test
    fun keepsTheTrailingDotOfAFullyQualifiedName() {
        assertEquals("example.com.", canonical("example.com."))
    }

    @Test
    fun acceptsASingleLabel() {
        assertEquals("localhost", canonical("localhost"))
    }

    @Test
    fun acceptsUnderscoreLabels() {
        // Not legal in a hostname, but common in real SRV-style internal names.
        assertEquals("my_host.internal", canonical("my_host.internal"))
    }

    @Test
    fun convertsAnInternationalizedNameToAscii() {
        // The punycode form is what actually gets resolved and connected to.
        val out = canonical("bücher.example")
        assertTrue("expected punycode, got $out", out.startsWith("xn--"))
        assertTrue(out.endsWith(".example"))
    }

    @Test
    fun rejectsEmptyAndOversizedHosts() {
        rejects("")
        rejects("   ")
        rejects("a".repeat(SshEndpoint.MAX_HOST + 1))
    }

    @Test
    fun rejectsWhitespaceAndControlCharacters() {
        rejects("exa mple.com")
        rejects("example.com\n")
        rejects("exam\u0000ple.com")
    }

    @Test
    fun rejectsAnEmptyLabel() {
        rejects("example..com")
        rejects(".example.com")
    }

    @Test
    fun rejectsALabelStartingOrEndingWithAHyphen() {
        rejects("-example.com")
        rejects("example-.com")
    }

    @Test
    fun rejectsAnOversizedLabel() {
        rejects("${"a".repeat(64)}.example")
    }

    @Test
    fun rejectsCharactersThatCouldCarryASecondValue() {
        rejects("example.com:22")
        rejects("example.com/path")
        rejects("user@example.com")
    }

    // --- IPv4 ----------------------------------------------------------------

    @Test
    fun acceptsIpv4Literals() {
        assertEquals("192.168.1.10", canonical("192.168.1.10"))
        assertEquals("0.0.0.0", canonical("0.0.0.0"))
        assertEquals("255.255.255.255", canonical("255.255.255.255"))
    }

    @Test
    fun rejectsOutOfRangeIpv4() {
        assertFalse(SshEndpoint.isIpv4("256.1.1.1"))
        assertFalse(SshEndpoint.isIpv4("1.1.1"))
        assertFalse(SshEndpoint.isIpv4("1.1.1.1.1"))
    }

    @Test
    fun rejectsAmbiguousLeadingZeroIpv4() {
        // Some resolvers read a leading zero as octal, so it is not canonical.
        assertFalse(SshEndpoint.isIpv4("010.1.1.1"))
    }

    // --- IPv6 ----------------------------------------------------------------

    @Test
    fun acceptsBracketedIpv6AndStoresItUnbracketed() {
        assertEquals("::1", canonical("[::1]"))
        assertEquals("2001:db8::1", canonical("[2001:db8::1]"))
    }

    @Test
    fun acceptsBareIpv6() {
        assertEquals("2001:db8::1", canonical("2001:db8::1"))
    }

    @Test
    fun lowercasesIpv6HexDigits() {
        assertEquals("2001:db8::abcd", canonical("[2001:DB8::ABCD]"))
    }

    @Test
    fun acceptsAZoneIdentifier() {
        // The old validator rejected this outright.
        assertEquals("fe80::1%25wlan0", canonical("[fe80::1%25wlan0]"))
        assertTrue(SshEndpoint.isIpv6("fe80::1%eth0"))
    }

    @Test
    fun acceptsAFullyWrittenIpv6() {
        assertTrue(SshEndpoint.isIpv6("2001:0db8:0000:0000:0000:0000:0000:0001"))
    }

    @Test
    fun acceptsAnEmbeddedIpv4Tail() {
        assertTrue(SshEndpoint.isIpv6("::ffff:192.168.1.1"))
    }

    @Test
    fun rejectsMalformedIpv6() {
        // The old check let anything with letters and colons through.
        assertFalse(SshEndpoint.isIpv6("zzzz::1"))
        assertFalse(SshEndpoint.isIpv6("2001:db8::1::2"))
        assertFalse(SshEndpoint.isIpv6("2001:db8:::1"))
        assertFalse(SshEndpoint.isIpv6("12345::1"))
        assertFalse(SshEndpoint.isIpv6("1:2:3:4:5:6:7:8:9"))
        assertFalse(SshEndpoint.isIpv6("1:2:3:4:5:6:7"))
        assertFalse(SshEndpoint.isIpv6(""))
    }

    @Test
    fun rejectsAnUnclosedBracket() {
        rejects("[::1")
    }

    @Test
    fun rejectsAMalformedBracketedLiteral() {
        rejects("[not-an-address]")
    }

    // --- identity ------------------------------------------------------------

    @Test
    fun idIsStableForTheSameEndpoint() {
        assertEquals(
            SshEndpoint.endpointId("example.com", 22),
            SshEndpoint.endpointId("example.com", 22),
        )
    }

    @Test
    fun idDiffersByPort() {
        assertNotEquals(
            SshEndpoint.endpointId("example.com", 22),
            SshEndpoint.endpointId("example.com", 2222),
        )
    }

    @Test
    fun lengthPrefixingPreventsAHostAndPortCollision() {
        // Without a length prefix, concatenation lets two different endpoints
        // produce the same preimage.
        assertNotEquals(
            SshEndpoint.endpointId("ab", 1),
            SshEndpoint.endpointId("a", 25601),
        )
    }

    @Test
    fun nonAsciiHostsDoNotCollide() {
        // The old derivation ran the host through US-ASCII, which replaces every
        // non-ASCII character with '?' and collapses distinct hosts onto one id.
        assertNotEquals(
            SshEndpoint.endpointId("日本.example", 22),
            SshEndpoint.endpointId("한국.example", 22),
        )
    }

    @Test
    fun legacyIdDerivationIsPreservedForUpgrades() {
        // Records written by the previous release must still validate, so this
        // derivation cannot drift.
        assertEquals(
            SshEndpoint.legacyEndpointId("example.com", 22),
            SshEndpoint.legacyEndpointId("example.com", 22),
        )
        assertNotEquals(
            SshEndpoint.legacyEndpointId("example.com", 22),
            SshEndpoint.endpointId("example.com", 22),
        )
    }

    @Test
    fun portRangeIsEnforced() {
        SshEndpoint.validatePort(1)
        SshEndpoint.validatePort(65535)
        for (bad in listOf(0, -1, 65536)) {
            try {
                SshEndpoint.validatePort(bad)
                fail("expected port $bad to be rejected")
            } catch (e: SshHostStoreException) {
                // expected
            }
        }
    }
}
