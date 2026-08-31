package com.remotly.app.transport

import org.bouncycastle.crypto.InvalidCipherTextException

// Seals and opens transport frames. The frame is:
//   channel_type(1) | varint(channel_id) | varint(ciphertext_len) | ciphertext
// The header is the AEAD associated data, so the channel type, id, and length
// are authenticated. ciphertext_len is the plaintext length plus the 16-byte
// Poly1305 tag. Nonces are per-direction 64-bit counters (four zero bytes then
// the counter little-endian), starting at zero.
class FrameCipher(sendKey: ByteArray, recvKey: ByteArray) {
    companion object {
        const val OVERHEAD = 16
        const val MAX_PAYLOAD = 1 shl 20 // 1 MiB, matches the daemon
        const val CHANNEL_COUNT = 3
    }

    private val sendKey = sendKey.copyOf()
    private val recvKey = recvKey.copyOf()
    private var sendNc = 0L
    private var recvNc = 0L

    private val sendExhausted get() = sendNc == -1L
    private val recvExhausted get() = recvNc == -1L

    // Returns the complete wire frame (header + ciphertext).
    fun sealFrame(chType: Int, chId: Long, payload: ByteArray): ByteArray {
        require(chType in 0 until CHANNEL_COUNT) { "bad channel: $chType" }
        require(payload.size <= MAX_PAYLOAD) { "frame too large" }
        if (sendExhausted) throw IllegalStateException("nonce exhausted")
        val varId = Varint.encode(chId)
        val varLen = Varint.encode((payload.size + OVERHEAD).toLong())
        val header = ByteArray(1 + varId.size + varLen.size)
        header[0] = chType.toByte()
        varId.copyInto(header, 1)
        varLen.copyInto(header, 1 + varId.size)
        val ct = ChaChaPoly.seal(sendKey, ChaChaPoly.nonce12(sendNc), header, payload)
        sendNc++
        return header + ct
    }

    // Authenticates and decrypts a wire frame, returning the channel type, id,
    // and plaintext.
    fun openFrame(data: ByteArray): Frame {
        if (data.isEmpty()) throw FrameException("frame truncated")
        val chType = data[0].toInt() and 0xff
        if (chType >= CHANNEL_COUNT) throw FrameException("bad channel: $chType")
        var off = 1
        val (idv, n) = Varint.decode(data, off)
        off += n
        if (idv > 0xFFFFFFFFL) throw FrameException("varint too long")
        val (ctLen, m) = Varint.decode(data, off)
        off += m
        if (ctLen < OVERHEAD) throw FrameException("frame too small")
        if (ctLen > MAX_PAYLOAD + OVERHEAD) throw FrameException("frame too large")
        if (data.size.toLong() - off != ctLen) throw FrameException("frame truncated")
        if (recvExhausted) throw IllegalStateException("nonce exhausted")
        val header = data.copyOfRange(0, off)
        val ct = data.copyOfRange(off, data.size)
        val plain = try {
            ChaChaPoly.open(recvKey, ChaChaPoly.nonce12(recvNc), header, ct)
        } catch (e: InvalidCipherTextException) {
            throw FrameException("authentication failed")
        }
        recvNc++
        return Frame(chType, idv, plain)
    }
}

class Frame(val chType: Int, val chId: Long, val payload: ByteArray)

class FrameException(message: String) : Exception(message)
