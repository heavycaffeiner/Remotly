// Deterministic frame vectors, computed identically to the Go side, and a
// self-check of handshake key agreement.
'use strict';

const crypto = require('crypto');
const { CipherKey, CHANNEL_CTRL, CHANNEL_TERM, CHANNEL_FILE } = require('./frame');
const { noise, initState, runHandshake } = require('./noise');

function frameVectors() {
  const key = crypto.createHash('sha256').update('remotly-frame-vector-key').digest();
  const cases = [
    { chType: CHANNEL_CTRL, chId: 0, payload: Buffer.from('hello') },
    { chType: CHANNEL_TERM, chId: 7, payload: Buffer.alloc(0) },
    { chType: CHANNEL_FILE, chId: 300, payload: Buffer.from('a longer payload for a file channel') },
    { chType: CHANNEL_CTRL, chId: 0, payload: Buffer.from('wide chars: \uD55C\uAE00') },
  ];
  const out = [];
  for (const c of cases) {
    const ck = new CipherKey(key, 0);
    const frame = ck.sealFrame(c.chType, c.chId, c.payload);
    out.push({
      channel_type: String(c.chType),
      channel_id: String(c.chId),
      plaintext: c.payload.toString('hex'),
      frame: frame.toString('hex'),
    });
  }
  return out;
}

// Runs a live XX and IK handshake between two in-process states and returns the
// derived keys, to confirm the noise-protocol lib derives keys in the same
// direction as the Go side.
function handshakeKeys(pattern) {
  const iStat = noise.keygen();
  const rStat = noise.keygen();
  let init, resp;
  if (pattern === 'IK') {
    init = initState('IK', true, iStat, rStat.publicKey);
    resp = initState('IK', false, rStat);
  } else {
    init = initState('XX', true, iStat);
    resp = initState('XX', false, rStat);
  }
  const keys = runHandshake(init, resp);
  noise.destroy(init);
  noise.destroy(resp);
  return {
    pattern,
    init_send: keys.initSend.keyHex(),
    init_recv: keys.initRecv.keyHex(),
    resp_send: keys.respSend.keyHex(),
    resp_recv: keys.respRecv.keyHex(),
  };
}

if (require.main === module) {
  const cmd = process.argv[2];
  if (cmd === 'frame-vectors') {
    console.log(JSON.stringify(frameVectors(), null, 2));
  } else if (cmd === 'handshake-keys') {
    console.log(JSON.stringify([handshakeKeys('XX'), handshakeKeys('IK')], null, 2));
  } else {
    console.error('usage: node vectors.js {frame-vectors|handshake-keys}');
    process.exit(2);
  }
}

module.exports = { frameVectors, handshakeKeys };
