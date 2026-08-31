package com.remotly.app.ssh

import java.net.IDN
import java.security.MessageDigest
import java.util.Base64

// Endpoint canonicalization and identity.
//
// The host field is a trust boundary: it comes from user input and from a file
// that survives upgrades. Canonicalizing it here means the same endpoint always
// produces the same id, and that the id is derived from an unambiguous byte
// encoding rather than a lossy ASCII conversion of arbitrary Unicode.
object SshEndpoint {

    const val MAX_HOST = 255

    /** A canonical host plus the form to show the user. */
    data class Canonical(val host: String, val kind: Kind)

    enum class Kind { DNS, IPV4, IPV6 }

    /**
     * Canonicalizes a host for storage and connection.
     *
     * DNS names are IDN-encoded to ASCII and lowercased with locale-independent
     * rules. IP literals keep their textual form; IPv6 is stored without
     * brackets, which is the single canonical form this store uses.
     */
    fun canonicalize(raw: String): Canonical {
        // Only surrounding spaces and tabs are forgiven. A control character
        // anywhere, including a trailing newline, is rejected rather than
        // trimmed away, because it means the field carries something extra.
        val trimmed = raw.trim(' ', '\t')
        if (trimmed.isEmpty() || trimmed.length > MAX_HOST) {
            throw SshHostStoreException("host out of range")
        }
        rejectControl(trimmed)

        // Bracketed IPv6, optionally with a zone id.
        if (trimmed.startsWith('[')) {
            if (!trimmed.endsWith(']')) throw SshHostStoreException("host has an unclosed bracket")
            val inner = trimmed.substring(1, trimmed.length - 1)
            if (!isIpv6(inner)) throw SshHostStoreException("host is not a valid IPv6 literal")
            return Canonical(inner.lowercase(), Kind.IPV6)
        }
        // Bare IPv6 is recognizable by having more than one colon; a single
        // colon would be a host:port string, which this field never accepts.
        if (trimmed.count { it == ':' } > 1) {
            if (!isIpv6(trimmed)) throw SshHostStoreException("host is not a valid IPv6 literal")
            return Canonical(trimmed.lowercase(), Kind.IPV6)
        }
        if (isIpv4(trimmed)) return Canonical(trimmed, Kind.IPV4)

        return Canonical(canonicalDns(trimmed), Kind.DNS)
    }

    private fun canonicalDns(raw: String): String {
        val ascii = try {
            // Without USE_STD3_ASCII_RULES, because that rejects the underscore
            // that appears in real internal names. The per-label check below is
            // the actual character policy.
            IDN.toASCII(raw)
        } catch (e: IllegalArgumentException) {
            throw SshHostStoreException("host is not a valid domain name")
        }
        if (ascii.isEmpty() || ascii.length > MAX_HOST) {
            throw SshHostStoreException("host out of range")
        }
        val lowered = ascii.lowercase()
        // A single trailing dot is the fully qualified form and is kept; the
        // labels around it still have to be well formed.
        val body = if (lowered.endsWith('.')) lowered.dropLast(1) else lowered
        if (body.isEmpty()) throw SshHostStoreException("host is not a valid domain name")
        for (label in body.split('.')) {
            if (label.isEmpty() || label.length > 63) {
                throw SshHostStoreException("host has an invalid label")
            }
            if (label.startsWith('-') || label.endsWith('-')) {
                throw SshHostStoreException("host label starts or ends with a hyphen")
            }
            for (c in label) {
                val ok = (c in 'a'..'z') || (c in '0'..'9') || c == '-' || c == '_'
                if (!ok) throw SshHostStoreException("host has an invalid character")
            }
        }
        return lowered
    }

    fun isIpv4(s: String): Boolean {
        val parts = s.split('.')
        if (parts.size != 4) return false
        for (p in parts) {
            if (p.isEmpty() || p.length > 3) return false
            if (p.any { it !in '0'..'9' }) return false
            // A leading zero makes the value ambiguous (some resolvers read it
            // as octal), so it is not accepted as canonical.
            if (p.length > 1 && p[0] == '0') return false
            if (p.toInt() > 255) return false
        }
        return true
    }

    /** Validates an IPv6 literal, including `::` compression and a zone id. */
    fun isIpv6(raw: String): Boolean {
        if (raw.isEmpty()) return false
        val percent = raw.indexOf('%')
        val zone = if (percent >= 0) raw.substring(percent + 1) else null
        val addr = if (percent >= 0) raw.substring(0, percent) else raw
        if (zone != null) {
            // The zone id is an interface name; keep it strict but permit the
            // percent-encoded form a URI carries.
            if (zone.isEmpty() || zone.length > 64) return false
            if (zone.any { !it.isLetterOrDigit() && it != '.' && it != '_' && it != '-' && it != '%' }) {
                return false
            }
        }
        if (addr.isEmpty()) return false

        val doubleColon = addr.indexOf("::")
        if (doubleColon != addr.lastIndexOf("::")) return false

        val (headText, tailText) = if (doubleColon >= 0) {
            addr.substring(0, doubleColon) to addr.substring(doubleColon + 2)
        } else {
            addr to null
        }

        val head = if (headText.isEmpty()) emptyList() else headText.split(':')
        val tail = when {
            tailText == null -> null
            tailText.isEmpty() -> emptyList()
            else -> tailText.split(':')
        }

        var groups = 0
        var sawEmbeddedIpv4 = false

        fun countPart(parts: List<String>, isTail: Boolean): Boolean {
            for ((i, part) in parts.withIndex()) {
                if (part.isEmpty()) return false
                val last = i == parts.size - 1
                if (last && isTail && part.contains('.')) {
                    if (!isIpv4(part)) return false
                    sawEmbeddedIpv4 = true
                    groups += 2
                    continue
                }
                if (part.length > 4) return false
                if (part.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return false
                groups += 1
            }
            return true
        }

        // Without a `::` the whole address is one run and may end in IPv4.
        if (!countPart(head, tail == null)) return false
        if (tail != null && !countPart(tail, true)) return false

        return if (doubleColon >= 0) {
            // `::` stands for at least one zero group.
            groups <= 7 || (sawEmbeddedIpv4 && groups <= 8)
        } else {
            groups == 8
        }
    }

    /**
     * The stable id for an endpoint.
     *
     * Hashes a length-prefixed UTF-8 host and a big-endian port, so no two
     * distinct endpoints can produce the same preimage and no byte is lost to
     * an ASCII conversion.
     */
    fun endpointId(canonicalHost: String, port: Int): String {
        val hostBytes = canonicalHost.toByteArray(Charsets.UTF_8)
        val buf = ByteArray(4 + hostBytes.size + 2)
        buf[0] = (hostBytes.size ushr 24 and 0xff).toByte()
        buf[1] = (hostBytes.size ushr 16 and 0xff).toByte()
        buf[2] = (hostBytes.size ushr 8 and 0xff).toByte()
        buf[3] = (hostBytes.size and 0xff).toByte()
        hostBytes.copyInto(buf, 4)
        buf[4 + hostBytes.size] = (port ushr 8 and 0xff).toByte()
        buf[5 + hostBytes.size] = (port and 0xff).toByte()
        val digest = MessageDigest.getInstance("SHA-256").digest(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    /**
     * The schema 1 id derivation.
     *
     * Kept so records written by the previous version still validate after an
     * upgrade. Re-deriving their ids would fail the identity check on load and
     * quarantine every existing host.
     */
    fun legacyEndpointId(host: String, port: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$host:$port".toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun validatePort(port: Int) {
        if (port !in 1..65535) throw SshHostStoreException("port out of range")
    }

    private fun rejectControl(s: String) {
        for (c in s) {
            if (c.code < 0x20 || c.code == 0x7f || c == ' ') {
                throw SshHostStoreException("host has an invalid character")
            }
        }
    }
}
