// Frame layer: mirrors spikes/m0-06-transport/go/protocol.go exactly.
// One channel type byte, LEB128 varint channel id, LEB128 varint ciphertext
// length, then ChaCha20-Poly1305 ciphertext. The header is the authenticated
// data, so channel type, id, and length cannot be tampered.
'use strict';

const sodium = require('sodium-universal');

const VERSION = 1;
const CHANNEL_CTRL = 0;
const CHANNEL_TERM = 1;
const CHANNEL_FILE = 2;
const CHANNEL_COUNT = 3;
const MAX_PAYLOAD = 1 << 20;
const TAG = 16;

const errors = {
  varintTooLong: new Error('varint too long'),
  varintTruncated: new Error('varint truncated'),
  badChannel: new Error('unsupported channel type'),
  frameTooLarge: new Error('frame exceeds size limit'),
  frameTooSmall: new Error('frame smaller than tag'),
  frameTruncated: new Error('frame truncated'),
  decrypt: new Error('authentication failed'),
  nonceExhausted: new Error('nonce exhausted'),
};

function appendVarint(dst, v) {
  v = BigInt(v);
  for (;;) {
    let b = Number(v & 0x7fn);
    v >>= 7n;
    if (v !== 0n) b |= 0x80;
    dst.push(b);
    if (v === 0n) return dst;
  }
}

function consumeVarint(buf, off) {
  let v = 0n;
  for (let i = 0; i < 5; i++) {
    if (off + i >= buf.length) throw errors.varintTruncated;
    const c = buf[off + i];
    v |= BigInt(c & 0x7f) << BigInt(7 * i);
    if ((c & 0x80) === 0) return { value: v, bytes: i + 1 };
  }
  throw errors.varintTooLong;
}

// 96-bit AEAD nonce: four zero bytes followed by the 64-bit counter in
// little-endian order, matching the Noise ChaChaPoly convention.
function nonce12(counter) {
  const n = Buffer.alloc(12);
  n.writeBigUInt64LE(counter, 4);
  return n;
}

class CipherKey {
  constructor(key, nonce) {
    if (Buffer.isBuffer(key)) this.key = Buffer.from(key);
    else {
      this.key = Buffer.alloc(32);
      this.key.set(key);
    }
    this.nonce = BigInt(nonce === undefined ? 0 : nonce);
  }

  sealFrame(chType, chId, payload) {
    if (chType >= CHANNEL_COUNT) throw errors.badChannel;
    if (payload.length > MAX_PAYLOAD) throw errors.frameTooLarge;
    if (this.nonce === 0xffffffffffffffffn) throw errors.nonceExhausted;
    const header = [chType];
    appendVarint(header, chId);
    appendVarint(header, payload.length + TAG);
    const ad = Buffer.from(header);
    const ct = Buffer.alloc(payload.length + TAG);
    sodium.crypto_aead_chacha20poly1305_ietf_encrypt(ct, payload, ad, null, nonce12(this.nonce), this.key);
    this.nonce++;
    return Buffer.concat([ad, ct]);
  }

  openFrame(data) {
    if (data.length < 1) throw errors.frameTruncated;
    const chType = data[0];
    if (chType >= CHANNEL_COUNT) throw errors.badChannel;
    const id = consumeVarint(data, 1);
    const len = consumeVarint(data, 1 + id.bytes);
    const headerEnd = 1 + id.bytes + len.bytes;
    const ctLen = len.value;
    if (ctLen < TAG) throw errors.frameTooSmall;
    if (ctLen > BigInt(MAX_PAYLOAD + TAG)) throw errors.frameTooLarge;
    if (BigInt(data.length - headerEnd) !== ctLen) throw errors.frameTruncated;
    if (this.nonce === 0xffffffffffffffffn) throw errors.nonceExhausted;
    const ad = data.subarray(0, headerEnd);
    const ct = data.subarray(headerEnd);
    const plain = Buffer.alloc(Number(ctLen) - TAG);
    sodium.crypto_aead_chacha20poly1305_ietf_decrypt(plain, null, ct, ad, nonce12(this.nonce), this.key);
    this.nonce++;
    return { chType, chId: Number(id.value), payload: plain };
  }

  keyHex() { return this.key.toString('hex'); }
  nonceValue() { return this.nonce; }
}

module.exports = {
  VERSION,
  CHANNEL_CTRL,
  CHANNEL_TERM,
  CHANNEL_FILE,
  CipherKey,
  errors,
  nonce12,
};
