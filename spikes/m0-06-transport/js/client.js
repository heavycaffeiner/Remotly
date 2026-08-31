// WebSocket client (the app side) for the live cross-runtime round trip.
// Initiates an XX or IK handshake with the Go server, then sends a sealed
// frame and verifies the server's echo decrypts to the same payload.
'use strict';

const WebSocket = require('ws');
const { VERSION, CipherKey, CHANNEL_TERM } = require('./frame');
const { noise, initState, deriveKeys } = require('./noise');

function parseArgs(argv) {
  const a = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--pattern') a.pattern = argv[++i];
    else if (argv[i] === '--addr') a.addr = argv[++i];
    else if (argv[i] === '--peer') a.peer = Buffer.from(argv[++i], 'hex');
    else if (argv[i] === '--payload') a.payload = argv[++i];
  }
  return a;
}

function main() {
  const a = parseArgs(process.argv.slice(2));
  const pattern = a.pattern || 'XX';
  const addr = a.addr || 'ws://127.0.0.1:8777';
  const payload = Buffer.from(a.payload || 'hello from node');

  const initStateObj = initState(pattern, true, noise.keygen(), a.peer);
  const ws = new WebSocket(addr);
  ws.binaryType = 'nodebuffer';

  let keys = null;
  const printKeys = process.argv.includes('--print-keys');

  function sendFrame() {
    if (printKeys) console.log(`handshake complete (${pattern}), derived send key ${keys.send.keyHex()}`);
    ws.send(keys.send.sealFrame(CHANNEL_TERM, 1, payload));
  }

  function handleEcho(data) {
    const { chType, chId, payload: echoed } = keys.recv.openFrame(data);
    const ok = chType === CHANNEL_TERM && echoed.equals(payload);
    console.log(`echo: channel=${chType} id=${chId} match=${ok}`);
    console.log(`round-trip ${ok ? 'PASSED' : 'FAILED'}`);
    ws.close();
    process.exit(ok ? 0 : 1);
  }

  ws.on('open', () => {
    const buf = Buffer.alloc(512);
    noise.writeMessage(initStateObj, Buffer.alloc(0), buf);
    const msg1 = buf.subarray(0, noise.writeMessage.bytes);
    ws.send(Buffer.concat([Buffer.from([VERSION]), msg1]));
  });

  ws.on('message', (data) => {
    if (keys) {
      handleEcho(data);
      return;
    }
    if (data[0] !== VERSION) {
      console.error(`server rejected version ${data[0]}`);
      process.exit(1);
    }
    const s = noise.readMessage(initStateObj, data.subarray(1), Buffer.alloc(0));
    if (s) {
      // IK: initiator completes by reading the responder's message.
      keys = deriveKeys(s, true, true);
      sendFrame();
      return;
    }
    // XX: initiator completes by writing its third message.
    const buf3 = Buffer.alloc(512);
    const s3 = noise.writeMessage(initStateObj, Buffer.alloc(0), buf3);
    const msg3 = buf3.subarray(0, noise.writeMessage.bytes);
    if (s3) keys = deriveKeys(s3, true, false);
    ws.send(msg3);
    if (s3) sendFrame();
  });

  ws.on('close', (code, reason) => {
    if (code === 4000) {
      console.error('server closed with error:', reason.toString());
      process.exit(1);
    }
  });

  ws.on('error', (err) => {
    console.error('websocket error:', err.message);
    process.exit(1);
  });
}

main();
