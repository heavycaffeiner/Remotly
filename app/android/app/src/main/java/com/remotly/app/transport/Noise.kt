package com.remotly.app.transport

import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

// The single supported Noise cipher suite: Noise_*_25519_ChaChaPoly_BLAKE2b.
// This is a direct port of the handshake the daemon runs (flynn/noise), so the
// two sides are byte-compatible. Only the tokens the app uses appear here.

enum class Token { E, S, DHEE, DHES, DHSE, DHSS, PSK }

class HandshakePattern(
    val name: String,
    val initiatorPre: List<Token> = emptyList(),
    val responderPre: List<Token> = emptyList(),
    val messages: List<List<Token>>,
)

object Patterns {
    // Reconnect of a paired device. The responder pre-seeds its static key.
    val IK = HandshakePattern(
        name = "IK",
        responderPre = listOf(Token.S),
        messages = listOf(
            listOf(Token.E, Token.DHES, Token.S, Token.DHSS),
            listOf(Token.E, Token.DHEE, Token.DHSE),
        ),
    )

    // First-time pairing; the PSK placement (0) is applied by the state, not
    // baked into the pattern.
    val XX = HandshakePattern(
        name = "XX",
        messages = listOf(
            listOf(Token.E),
            listOf(Token.E, Token.DHEE, Token.S, Token.DHES),
            listOf(Token.S, Token.DHSE),
        ),
    )
}

object X25519 {
    fun pub(priv: ByteArray): ByteArray =
        X25519PrivateKeyParameters(priv, 0).generatePublicKey().encoded

    fun dh(priv: ByteArray, peerPub: ByteArray): ByteArray {
        val out = ByteArray(32)
        X25519PrivateKeyParameters(priv, 0)
            .generateSecret(X25519PublicKeyParameters(peerPub, 0), out, 0)
        return out
    }
}

object Blake2b {
    // BLAKE2b-512: 64-byte output, the hash the Noise suite uses. The no-arg
    // constructor is required; the int constructor in this BouncyCastle build
    // does not map 64 to a 64-byte output.
    fun hash(data: ByteArray): ByteArray {
        val d = Blake2bDigest()
        d.update(data, 0, data.size)
        val out = ByteArray(64)
        d.doFinal(out, 0)
        return out
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val m = HMac(Blake2bDigest())
        m.init(KeyParameter(key))
        m.update(data, 0, data.size)
        val out = ByteArray(64)
        m.doFinal(out, 0)
        return out
    }
}

object ChaChaPoly {
    // 12-byte nonce: four zero bytes then the counter little-endian.
    fun nonce12(counter: Long): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) n[4 + i] = ((counter ushr (8 * i)) and 0xff).toByte()
        return n
    }

    fun seal(key: ByteArray, nonce: ByteArray, ad: ByteArray, plaintext: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, ParametersWithIV(KeyParameter(key), nonce))
        c.processAADBytes(ad, 0, ad.size)
        val out = ByteArray(c.getOutputSize(plaintext.size))
        var len = c.processBytes(plaintext, 0, plaintext.size, out, 0)
        len += c.doFinal(out, len)
        return out.copyOf(len)
    }

    fun open(key: ByteArray, nonce: ByteArray, ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(false, ParametersWithIV(KeyParameter(key), nonce))
        c.processAADBytes(ad, 0, ad.size)
        val out = ByteArray(c.getOutputSize(ciphertext.size))
        var len = c.processBytes(ciphertext, 0, ciphertext.size, out, 0)
        len += c.doFinal(out, len)
        return out.copyOf(len)
    }
}

internal fun joinArrays(parts: List<ByteArray>): ByteArray {
    val total = parts.sumOf { it.size }
    val out = ByteArray(total)
    var off = 0
    for (p in parts) {
        p.copyInto(out, off)
        off += p.size
    }
    return out
}

// RFC 5869 HKDF with the chaining key as salt and zero info. Returns the full
// 64-byte outputs; callers truncate to 32 where a key is needed.
fun hkdf(ck: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
    val tempKey = Blake2b.hmac(ck, ikm)
    val out1 = Blake2b.hmac(tempKey, byteArrayOf(0x01))
    val res = ArrayList<ByteArray>(outputs)
    res.add(out1)
    if (outputs >= 2) {
        val out2 = Blake2b.hmac(tempKey, out1 + byteArrayOf(0x02))
        res.add(out2)
    }
    if (outputs >= 3) {
        val out3 = Blake2b.hmac(tempKey, res[1] + byteArrayOf(0x03))
        res.add(out3)
    }
    return res
}

// The handshake's symmetric state: handshake hash (h), chaining key (ck), and
// the in-handshake encryption key/nonce.
class SymmetricState {
    val h = ByteArray(64)
    val ck = ByteArray(64)
    val k = ByteArray(32)
    var n = 0L
    var hasK = false

    fun initializeSymmetric(name: ByteArray) {
        if (name.size <= 64) {
            h.fill(0)
            name.copyInto(h, 0, 0, name.size)
        } else {
            Blake2b.hash(name).copyInto(h)
        }
        h.copyInto(ck)
    }

    fun mixKey(dhOut: ByteArray) {
        n = 0
        hasK = true
        val outs = hkdf(ck, dhOut, 2)
        outs[0].copyInto(ck)
        outs[1].copyInto(k, 0, 0, 32)
    }

    fun mixHash(data: ByteArray) {
        Blake2b.hash(h + data).copyInto(h)
    }

    // PSK token: 3-output HKDF, the middle output is folded into the hash.
    fun mixKeyAndHash(data: ByteArray) {
        val outs = hkdf(ck, data, 3)
        outs[0].copyInto(ck)
        mixHash(outs[1])
        outs[2].copyInto(k, 0, 0, 32)
        n = 0
        hasK = true
    }

    // Appends the (optionally encrypted) payload to out and folds it into h.
    fun encryptAndHash(out: ByteArray, plaintext: ByteArray): ByteArray {
        if (!hasK) {
            mixHash(plaintext)
            return out + plaintext
        }
        val ct = ChaChaPoly.seal(k, ChaChaPoly.nonce12(n), h, plaintext)
        n++
        mixHash(ct)
        return out + ct
    }

    fun decryptAndHash(out: ByteArray, data: ByteArray): ByteArray {
        if (!hasK) {
            mixHash(data)
            return out + data
        }
        val pt = ChaChaPoly.open(k, ChaChaPoly.nonce12(n), h, data)
        n++
        mixHash(data)
        return out + pt
    }

    // Splits into the two direction keys: first is the initiator's send key,
    // second its receive key (the responder uses them the other way round).
    fun split(): Pair<ByteArray, ByteArray> {
        val outs = hkdf(ck, ByteArray(0), 2)
        return outs[0].copyOf(32) to outs[1].copyOf(32)
    }
}

class HandshakeResult(val message: ByteArray, val c1: ByteArray?, val c2: ByteArray?)

// One side of a Noise handshake. The own and (for IK) peer static keys and the
// own ephemeral are supplied up front; the peer ephemeral is learned from the
// first message.
class HandshakeState(
    val initiator: Boolean,
    pattern: HandshakePattern,
    prologue: ByteArray,
    private val staticPriv: ByteArray,
    peerStatic: ByteArray?,
    psk: ByteArray?,
    pskPlacement: Int,
    private val ephPriv: ByteArray,
) {
    val ss = SymmetricState()
    private val sPub = X25519.pub(staticPriv)
    private var re: ByteArray? = null
    private var rs: ByteArray? = peerStatic

    val willPsk: Boolean
    private val psk: ByteArray?
    private val messagePatterns: List<List<Token>>
    private var msgIdx = 0
    private var shouldWrite = initiator

    init {
        willPsk = psk != null || pskPlacement >= 2
        this.psk = psk
        val msgs = pattern.messages.map { it.toMutableList() }
        if (willPsk) {
            if (pskPlacement == 0) msgs[0].add(0, Token.PSK) else msgs[pskPlacement - 1].add(Token.PSK)
        }
        messagePatterns = msgs
        val pskMod = if (willPsk) "psk$pskPlacement" else ""
        val name = "Noise_${pattern.name}${pskMod}_25519_ChaChaPoly_BLAKE2b"
        ss.initializeSymmetric(name.encodeToByteArray())
        ss.mixHash(prologue)
        for (m in pattern.initiatorPre) {
            when {
                initiator && m == Token.S -> ss.mixHash(sPub)
                !initiator && m == Token.S -> ss.mixHash(requireNotNull(rs))
                else -> throw IllegalArgumentException("unsupported pre-message")
            }
        }
        for (m in pattern.responderPre) {
            when {
                !initiator && m == Token.S -> ss.mixHash(sPub)
                initiator && m == Token.S -> ss.mixHash(requireNotNull(rs))
                else -> throw IllegalArgumentException("unsupported pre-message")
            }
        }
    }

    private fun dh(token: Token): ByteArray = when (token) {
        Token.DHEE -> X25519.dh(ephPriv, requireNotNull(re))
        Token.DHES -> if (initiator) X25519.dh(ephPriv, requireNotNull(rs)) else X25519.dh(staticPriv, requireNotNull(re))
        Token.DHSE -> if (initiator) X25519.dh(staticPriv, requireNotNull(re)) else X25519.dh(ephPriv, requireNotNull(rs))
        Token.DHSS -> X25519.dh(staticPriv, requireNotNull(rs))
        else -> throw IllegalStateException("not a DH token")
    }

    fun writeMessage(payload: ByteArray): HandshakeResult {
        if (!shouldWrite) throw IllegalStateException("out of sync: should read")
        if (msgIdx > messagePatterns.size - 1) throw IllegalStateException("no messages left")
        val pattern = messagePatterns[msgIdx]
        val parts = ArrayList<ByteArray>()
        for (token in pattern) {
            when (token) {
                Token.E -> {
                    val eP = X25519.pub(ephPriv)
                    parts.add(eP)
                    ss.mixHash(eP)
                    if (willPsk) ss.mixKey(eP)
                }
                Token.S -> parts.add(ss.encryptAndHash(ByteArray(0), sPub))
                Token.DHEE, Token.DHES, Token.DHSE, Token.DHSS -> ss.mixKey(dh(token))
                Token.PSK -> ss.mixKeyAndHash(requireNotNull(psk))
            }
        }
        val body = ss.encryptAndHash(joinArrays(parts), payload)
        shouldWrite = false
        msgIdx++
        if (msgIdx >= messagePatterns.size) {
            val (c1, c2) = ss.split()
            return HandshakeResult(body, c1, c2)
        }
        return HandshakeResult(body, null, null)
    }

    fun readMessage(message: ByteArray): HandshakeResult {
        if (shouldWrite) throw IllegalStateException("out of sync: should write")
        if (msgIdx > messagePatterns.size - 1) throw IllegalStateException("no messages left")
        val pattern = messagePatterns[msgIdx]
        var msg = message
        for (token in pattern) {
            when (token) {
                Token.E -> {
                    if (msg.size < 32) throw IllegalStateException("short message")
                    val peerEph = msg.copyOfRange(0, 32)
                    re = peerEph
                    ss.mixHash(peerEph)
                    if (willPsk) ss.mixKey(peerEph)
                    msg = msg.copyOfRange(32, msg.size)
                }
                Token.S -> {
                    val expected = if (ss.hasK) 48 else 32
                    if (msg.size < expected) throw IllegalStateException("short message")
                    if (rs != null) throw IllegalStateException("static already set")
                    rs = ss.decryptAndHash(ByteArray(0), msg.copyOfRange(0, expected))
                    msg = msg.copyOfRange(expected, msg.size)
                }
                Token.DHEE, Token.DHES, Token.DHSE, Token.DHSS -> ss.mixKey(dh(token))
                Token.PSK -> ss.mixKeyAndHash(requireNotNull(psk))
            }
        }
        val payload = ss.decryptAndHash(ByteArray(0), msg)
        shouldWrite = true
        msgIdx++
        if (msgIdx >= messagePatterns.size) {
            val (c1, c2) = ss.split()
            return HandshakeResult(payload, c1, c2)
        }
        return HandshakeResult(payload, null, null)
    }

    // The peer's static public key, once learned (responder static for both
    // IK and XX).
    fun peerStatic(): ByteArray = requireNotNull(rs)
}
