// Negative tests for the M0-06 transport spike. Two modes:
//
//   node negative-tests.js capture --pattern IK --peer <hex> [--addr ws://...]
//     Performs one IK handshake, sends one sealed frame, and saves msg1 and the
//     frame to capture.json in this directory.
//
//   node negative-tests.js attack --pattern IK --peer <fresh-server-hex> [--addr ws://...]
//     Against a fresh server instance (new static key):
//       1. sends version byte 2, expects close 4000 (version rejection)
//       2. replays the captured handshake msg1, expects close 4000
//          (handshake bound to the old server's static fails MAC)
//       3. completes a fresh IK handshake and replays the captured transport
//          frame, expects close 4000 (fresh session keys fail the tag)
//
// All three must be rejected with close code 4000 before any plaintext is
// delivered.
'use strict';

const fs = require('fs');
const path = require('path');
const WebSocket = require('ws');
const { VERSION, CipherKey, CHANNEL_TERM } = require('./frame');
const { noise, initState, deriveKeys } = require('./noise');

const CAPTURE = path.join(__dirname, 'capture.json');

function parseArgs(argv) {
  const a = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--pattern') a.pattern = argv[++i];
    else if (argv[i] === '--addr') a.addr = argv[++i];
    else if (argv[i] === '--peer') a.peer = Buffer.from(argv[++i], 'hex');
  }
  return a;
}

function connect(addr) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(addr);
    ws.binaryType = 'nodebuffer';
    ws.on('open', () => resolve(ws));
    ws.on('error', reject);
  });
}

function expectClose(ws, wantCode, wantReasonSubstr) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('timeout waiting for close')), 5000);
    ws.on('close', (code, reason) => {
      clearTimeout(timer);
      const got = reason.toString();
      const ok = code === wantCode && got.includes(wantReasonSubstr);
      console.log(`  close code=${code} reason="${got}" -> ${ok ? 'PASS' : 'FAIL'}`);
      resolve(ok);
    });
  });
}

async function capture(a) {
  const ws = await connect(a.addr);
  const state = initState(a.pattern, true, noise.keygen(), a.peer);
  const buf = Buffer.alloc(512);
  noise.writeMessage(state, Buffer.alloc(0), buf);
  const msg1 = Buffer.concat([Buffer.from([VERSION]), buf.subarray(0, noise.writeMessage.bytes)]);
  ws.send(msg1);

  const result = await new Promise((resolve, reject) => {
    let done = false;
    ws.on('message', (data) => {
      if (done) return;
      const s = noise.readMessage(state, data.subarray(1), Buffer.alloc(0));
      if (!s) return reject(new Error('unexpected extra handshake message'));
      const keys = deriveKeys(s, true, true);
      const frame = keys.send.sealFrame(CHANNEL_TERM, 1, Buffer.from('captured payload'));
      done = true;
      ws.send(frame);
      resolve({ msg1: msg1.toString('hex'), frame: frame.toString('hex') });
      ws.close();
    });
    ws.on('error', reject);
  });
  fs.writeFileSync(CAPTURE, JSON.stringify(result, null, 2));
  console.log('captured msg1 and frame -> capture.json');
  ws.close();
}

async function attack(a) {
  const cap = JSON.parse(fs.readFileSync(CAPTURE, 'utf8'));
  let allOk = true;

  // 1. Version rejection.
  console.log('test 1: version byte 2');
  {
    const ws = await connect(a.addr);
    ws.send(Buffer.from([0x02, 0x00]));
    allOk = (await expectClose(ws, 4000, 'unsupported protocol version')) && allOk;
  }

  // 2. Handshake replay against a fresh server static.
  console.log('test 2: replay captured handshake msg1');
  {
    const ws = await connect(a.addr);
    ws.send(Buffer.from(cap.msg1, 'hex'));
    allOk = (await expectClose(ws, 4000, 'handshake failed')) && allOk;
  }

  // 3. Transport frame replay on a fresh session.
  console.log('test 3: replay captured transport frame');
  {
    const ws = await connect(a.addr);
    const state = initState(a.pattern, true, noise.keygen(), a.peer);
    const buf = Buffer.alloc(512);
    noise.writeMessage(state, Buffer.alloc(0), buf);
    ws.send(Buffer.concat([Buffer.from([VERSION]), buf.subarray(0, noise.writeMessage.bytes)]));
    await new Promise((resolve, reject) => {
      ws.on('message', (data) => {
        const s = noise.readMessage(state, data.subarray(1), Buffer.alloc(0));
        if (!s) return reject(new Error('unexpected extra handshake message'));
        resolve(s);
      });
      ws.on('error', reject);
    });
    ws.send(Buffer.from(cap.frame, 'hex'));
    allOk = (await expectClose(ws, 4000, 'frame failed')) && allOk;
  }

  console.log(allOk ? 'ALL NEGATIVE TESTS PASSED' : 'NEGATIVE TEST FAILURES');
  process.exit(allOk ? 0 : 1);
}

const mode = process.argv[2];
const a = parseArgs(process.argv.slice(3));
a.addr = a.addr || 'ws://127.0.0.1:8777';
if (mode === 'capture') capture(a).catch((e) => { console.error(e); process.exit(1); });
else if (mode === 'attack') attack(a).catch((e) => { console.error(e); process.exit(1); });
else { console.error('usage: node negative-tests.js {capture|attack} --pattern IK --peer <hex>'); process.exit(2); }
