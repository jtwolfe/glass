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
import { randomBytes } from 'node:crypto';

const INBOX_PROTOCOL = '/glass/inbox/v0';

let node = null;
let allowedPeers = new Set();
let pairingComplete = false;
let pairCode = null;
let pairingPayload = null;

function generatePairCode() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const bytes = randomBytes(6);
  let code = '';
  for (let i = 0; i < 6; i++) {
    code += chars[bytes[i] % chars.length];
  }
  return code;
}

export function getPairCode() {
  return pairCode;
}

export function getPairingPayload() {
  return pairingPayload;
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

export async function createP2PNode(options = {}) {
  const {
    code,
    relayAddrs = [],
    listenPort = 4001,
    onInboxRequest,
  } = options;

  pairCode = code || generatePairCode();
  
  if (pairCode.length < 6) {
    throw new Error('Pair code must be at least 6 characters');
  }

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
        if (request.code === pairCode && !pairingComplete) {
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
  
  pairingPayload = {
    v: 0,
    peer: node.peerId.toString(),
    addrs: addrs,
    proto: INBOX_PROTOCOL,
    code: pairCode,
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
    pairCode = null;
    pairingPayload = null;
  }
}

export function getNodeInfo() {
  if (!node) return null;
  return {
    peerId: node.peerId.toString(),
    addrs: node.getMultiaddrs().map(a => a.toString()),
    pairCode,
    pairingComplete,
    allowedPeers: Array.from(allowedPeers),
    pairingPayload,
  };
}
