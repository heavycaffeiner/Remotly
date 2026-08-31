// Noise handshake wrapper over noise-protocol (libsodium), mirroring
// spikes/m0-06-transport/go/noise.go. Supports XX and IK with
// Noise_*_25519_ChaChaPoly_BLAKE2b.
//
// The underlying library returns a split as {tx, rx}, but the buffer order
// differs depending on which message completed the handshake: writeMessage
// returns (c1, c2) as {tx, rx} while readMessage returns (c2, c1) as {tx, rx},
// where c1 is initiator-to-responder and c2 is responder-to-initiator. This
// module normalizes to fixed (c1, c2) and maps them to per-role send/recv.
'use strict';

const noise = require('noise-protocol');
const { CipherKey } = require('./frame');

const PROLOGUE = Buffer.from('remotly-m0-06');

function initState(pattern, initiator, staticKeys, remoteStaticKey) {
  return noise.initialize(
    pattern,
    initiator,
    PROLOGUE,
    staticKeys,
    null,
    remoteStaticKey || null
  );
}

const keyOf = (buf) => buf.subarray(0, 32);
const nonceOf = (buf) => buf.readBigUInt64LE(32);
const cipherOf = (buf) => new CipherKey(keyOf(buf), nonceOf(buf));

// deriveKeys maps a split to per-role send and receive CipherKeys, given which
// message completed the handshake for this role.
function deriveKeys(split, initiator, completedByRead) {
  const c1 = completedByRead ? split.rx : split.tx; // initiator -> responder
  const c2 = completedByRead ? split.tx : split.rx; // responder -> initiator
  if (initiator) {
    return { send: cipherOf(c1), recv: cipherOf(c2) };
  }
  return { send: cipherOf(c2), recv: cipherOf(c1) };
}

// runHandshake drives two states to completion and returns each side's send and
// receive CipherKeys.
function runHandshake(initStateObj, respStateObj) {
  let initTurn = true;
  let initKeys, respKeys;
  for (;;) {
    if (initTurn) {
      const out = Buffer.alloc(512);
      const initSplit = noise.writeMessage(initStateObj, Buffer.alloc(0), out);
      const msg = out.subarray(0, noise.writeMessage.bytes);
      if (initSplit) initKeys = deriveKeys(initSplit, true, false);
      const respSplit = noise.readMessage(respStateObj, msg, Buffer.alloc(0));
      if (respSplit) respKeys = deriveKeys(respSplit, false, true);
    } else {
      const out = Buffer.alloc(512);
      const respSplit = noise.writeMessage(respStateObj, Buffer.alloc(0), out);
      const msg = out.subarray(0, noise.writeMessage.bytes);
      if (respSplit) respKeys = deriveKeys(respSplit, false, false);
      const initSplit = noise.readMessage(initStateObj, msg, Buffer.alloc(0));
      if (initSplit) initKeys = deriveKeys(initSplit, true, true);
    }
    if (initKeys && respKeys) {
      return { initSend: initKeys.send, initRecv: initKeys.recv, respSend: respKeys.send, respRecv: respKeys.recv };
    }
    initTurn = !initTurn;
  }
}

module.exports = { noise, initState, runHandshake, deriveKeys, PROLOGUE };
