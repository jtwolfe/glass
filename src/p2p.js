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
import { createHash } from 'node:crypto';

const PAIR_PROTOCOL = '/glass/pair/1.0.0';
const INBOX_PROTOCOL = '/glass/inbox/1.0.0';

let node = null;
let allowedPeers = new Set();
let pairingComplete = false;
let pairCode = null;

function derivePsk(pairSecret) {
  const hash = createHash('sha256').update(pairSecret).digest();
  return hash;
}

export function getPairCode() {
  return pairCode;
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
    pairSecret,
    relayAddrs = [],
    listenPort = 4001,
    onInboxRequest,
  } = options;

  if (!pairSecret || pairSecret.length < 8) {
    throw new Error('GLASS_PAIR_CODE must be set (at least 8 hex characters)');
  }

  pairCode = pairSecret.slice(0, 8).toLowerCase();
  const psk = derivePsk(pairSecret);

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
      denyDialPeer: async (peerId) => {
        if (!pairingComplete && allowedPeers.size === 0) {
          return false;
        }
        return !isPeerAllowed(peerId);
      },
      denyInboundConnection: async () => false,
      denyOutboundConnection: async () => false,
      denyInboundEncryptedConnection: async (peerId) => {
        if (!pairingComplete && allowedPeers.size === 0) {
          return false;
        }
        return !isPeerAllowed(peerId);
      },
      denyOutboundEncryptedConnection: async (peerId) => {
        if (!pairingComplete && allowedPeers.size === 0) {
          return false;
        }
        return !isPeerAllowed(peerId);
      },
      denyInboundUpgradedConnection: async (peerId) => {
        if (!pairingComplete && allowedPeers.size === 0) {
          return false;
        }
        return !isPeerAllowed(peerId);
      },
      denyOutboundUpgradedConnection: async (peerId) => {
        if (!pairingComplete && allowedPeers.size === 0) {
          return false;
        }
        return !isPeerAllowed(peerId);
      },
    },
    connectionManager: {
      maxConnections: 10,
    },
  });

  node.handle(PAIR_PROTOCOL, async ({ connection, stream }) => {
    const remotePeer = connection.remotePeer.toString();
    
    try {
      const chunks = [];
      for await (const chunk of pipe(stream.source, lp.decode)) {
        chunks.push(chunk);
      }
      
      const data = JSON.parse(toString(chunks[0]));
      
      if (data.psk && createHash('sha256').update(data.psk).digest().equals(psk)) {
        allowPeer(connection.remotePeer);
        pairingComplete = true;
        
        await pipe(
          [fromString(JSON.stringify({ ok: true, peerId: node.peerId.toString() }))],
          lp.encode,
          stream.sink
        );
        
        console.log(`Paired with peer: ${remotePeer}`);
      } else {
        await pipe(
          [fromString(JSON.stringify({ ok: false, error: 'invalid_psk' }))],
          lp.encode,
          stream.sink
        );
      }
    } catch (err) {
      console.error('Pairing error:', err.message);
    }
  });

  node.handle(INBOX_PROTOCOL, async ({ connection, stream }) => {
    const remotePeer = connection.remotePeer;
    
    if (!isPeerAllowed(remotePeer)) {
      await pipe(
        [fromString(JSON.stringify({ error: 'Unauthorized', status: 401 }))],
        lp.encode,
        stream.sink
      );
      return;
    }
    
    try {
      const chunks = [];
      for await (const chunk of pipe(stream.source, lp.decode)) {
        chunks.push(chunk);
      }
      
      const request = JSON.parse(toString(chunks[0]));
      
      let response;
      if (onInboxRequest) {
        response = await onInboxRequest(request);
      } else {
        response = { error: 'Not implemented', status: 501 };
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
          [fromString(JSON.stringify({ error: 'Internal error', status: 500 }))],
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
  console.log(`libp2p node started. Peer ID: ${node.peerId.toString()}`);
  console.log(`Listening on: ${addrs.join(', ')}`);
  console.log(`Pair code (first 8 hex): ${pairCode}`);

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
  };
}
