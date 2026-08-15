import { createLibp2p } from 'libp2p';
import { tcp } from '@libp2p/tcp';
import { noise } from '@chainsafe/libp2p-noise';
import { yamux } from '@libp2p/yamux';
import { identify } from '@libp2p/identify';
import { dcutr } from '@libp2p/dcutr';
import { circuitRelayTransport } from '@libp2p/circuit-relay-v2';
import { multiaddr } from '@multiformats/multiaddr';
import { pipe } from 'it-pipe';
import * as lp from 'it-length-prefixed';
import { fromString, toString } from 'uint8arrays';
import { randomBytes, createHash } from 'node:crypto';

const INBOX_PROTOCOL = '/glass/inbox/v0';
const INVITE_VALIDITY_MS = 15 * 60 * 1000;

let node = null;
let allowedPeers = new Set();
let pairingComplete = false;
let invite = null;

const CROCKFORD_ALPHABET = 'ABCDEFGHJKMNPQRSTVWXYZ2345679';

function generateCode(length = 8) {
  const bytes = randomBytes(length);
  let code = '';
  for (let i = 0; i < length; i++) {
    code += CROCKFORD_ALPHABET[bytes[i] % CROCKFORD_ALPHABET.length];
  }
  return code;
}

function generatePsk() {
  return randomBytes(32);
}

function pskToHex(psk) {
  return psk.toString('hex');
}

function hexToPsk(hex) {
  return Buffer.from(hex, 'hex');
}

export function getInvite() {
  return invite;
}

export function isPairingComplete() {
  return pairingComplete;
}

export function isPeerAllowed(peerId) {
  return allowedPeers.has(peerId.toString());
}

export function allowPeer(peerId) {
  allowedPeers.add(peerId.toString());
}

function isInviteValid() {
  if (!invite) return false;
  return new Date(invite.exp) > new Date();
}

function verifyPsk(providedPsk) {
  if (!invite || !isInviteValid()) return false;
  if (pairingComplete) return false;
  return providedPsk === invite.psk;
}

export async function createP2PNode(options = {}) {
  const {
    relayAddrs = [],
    listenPort = 4001,
    onInboxRequest,
  } = options;

  const code = generateCode(8);
  const psk = generatePsk();
  const exp = new Date(Date.now() + INVITE_VALIDITY_MS).toISOString();

  const relayMultiaddrs = relayAddrs
    .filter(a => a && a.trim())
    .map(a => multiaddr(a.trim()));

  const transports = [tcp()];
  
  if (relayMultiaddrs.length > 0) {
    transports.push(circuitRelayTransport());
  }

  node = await createLibp2p({
    addresses: {
      listen: [`/ip4/0.0.0.0/tcp/${listenPort}`],
    },
    transports,
    connectionEncrypters: [noise()],
    streamMuxers: [yamux()],
    services: {
      identify: identify(),
      dcutr: dcutr(),
    },
    connectionGater: {
      denyDialPeer: async () => false,
      denyInboundConnection: async () => false,
      denyOutboundConnection: async () => false,
      denyInboundEncryptedConnection: async () => false,
      denyOutboundEncryptedConnection: async () => false,
      denyInboundUpgradedConnection: async () => false,
      denyOutboundUpgradedConnection: async () => false,
    },
    connectionManager: {
      maxConnections: 10,
    },
  });

  node.handle(INBOX_PROTOCOL, async ({ connection, stream }) => {
    const remotePeer = connection.remotePeer;
    
    try {
      const chunks = [];
      for await (const chunk of pipe(stream.source, lp.decode)) {
        chunks.push(chunk);
      }
      
      const request = JSON.parse(toString(chunks[0]));
      
      if (!isPeerAllowed(remotePeer)) {
        if (request.psk && verifyPsk(request.psk)) {
          allowPeer(remotePeer);
          pairingComplete = true;
          console.log(`Paired with peer: ${remotePeer.toString()}`);
          
          await pipe(
            [fromString(JSON.stringify({ status: 200, body: { paired: true } }))],
            lp.encode,
            stream.sink
          );
          return;
        }
        
        if (request.psk && !isInviteValid()) {
          await pipe(
            [fromString(JSON.stringify({ status: 410, body: { error: 'Invite expired' } }))],
            lp.encode,
            stream.sink
          );
          return;
        }
        
        if (request.psk && pairingComplete) {
          await pipe(
            [fromString(JSON.stringify({ status: 410, body: { error: 'Invite already used' } }))],
            lp.encode,
            stream.sink
          );
          return;
        }
        
        await pipe(
          [fromString(JSON.stringify({ status: 401, body: { error: 'Unauthorized' } }))],
          lp.encode,
          stream.sink
        );
        return;
      }
      
      let response;
      if (onInboxRequest) {
        response = await onInboxRequest(request);
      } else {
        response = { status: 501, body: { error: 'Not implemented' } };
      }
      
      await pipe(
        [fromString(JSON.stringify(response))],
        lp.encode,
        stream.sink
      );
    } catch (err) {
      console.error('Inbox protocol error:', err.message);
      try {
        await pipe(
          [fromString(JSON.stringify({ status: 500, body: { error: 'Internal error' } }))],
          lp.encode,
          stream.sink
        );
      } catch {}
    }
  });

  await node.start();

  if (relayMultiaddrs.length > 0) {
    for (const addr of relayMultiaddrs) {
      try {
        await node.dial(addr);
        console.log(`Connected to relay: ${addr.toString()}`);
      } catch (err) {
        console.warn(`Failed to connect to relay ${addr.toString()}: ${err.message}`);
      }
    }
  }

  const addrs = node.getMultiaddrs().map(a => a.toString());
  
  invite = {
    v: 0,
    peer: node.peerId.toString(),
    addrs: addrs,
    proto: INBOX_PROTOCOL,
    code: code,
    psk: pskToHex(psk),
    exp: exp,
  };

  return node;
}

export function getNode() {
  return node;
}

export async function stopP2PNode() {
  if (node) {
    await node.stop();
    node = null;
    allowedPeers.clear();
    pairingComplete = false;
    invite = null;
  }
}

export function getNodeInfo() {
  if (!node) return null;
  return {
    peerId: node.peerId.toString(),
    addrs: node.getMultiaddrs().map(a => a.toString()),
    invite,
    pairingComplete,
    allowedPeers: Array.from(allowedPeers),
  };
}
