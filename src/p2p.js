import { createLibp2p } from 'libp2p';
import { tcp } from '@libp2p/tcp';
import { noise } from '@chainsafe/libp2p-noise';
import { yamux } from '@libp2p/yamux';
import { identify } from '@libp2p/identify';
import { dcutr } from '@libp2p/dcutr';
import { circuitRelayTransport } from '@libp2p/circuit-relay-v2';
import { gossipsub } from '@chainsafe/libp2p-gossipsub';
import { multiaddr } from '@multiformats/multiaddr';
import { pipe } from 'it-pipe';
import * as lp from 'it-length-prefixed';
import { fromString, toString } from 'uint8arrays';
import { randomBytes } from 'node:crypto';

const INBOX_PROTOCOL = '/glass/inbox/v0';
const INVITE_VALIDITY_MS = 15 * 60 * 1000;
const PAIR_TOPIC_PREFIX = '/glass/pair/';

let node = null;
let allowedPeers = new Set();
let pairingComplete = false;
let invite = null;
let pairTopic = null;
let topicCleanupTimer = null;

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

async function cleanupPairTopic() {
  if (pairTopic && node && node.services.pubsub) {
    try {
      node.services.pubsub.unsubscribe(pairTopic);
      console.log(`Unsubscribed from topic: ${pairTopic}`);
    } catch (err) {
      console.warn(`Failed to unsubscribe from topic: ${err.message}`);
    }
    pairTopic = null;
  }
  if (topicCleanupTimer) {
    clearTimeout(topicCleanupTimer);
    topicCleanupTimer = null;
  }
}

async function publishInviteOnce() {
  if (!pairTopic || !node || !node.services.pubsub || !invite) return;
  
  try {
    const inviteJson = JSON.stringify(invite);
    const data = fromString(inviteJson);
    await node.services.pubsub.publish(pairTopic, data);
    console.log(`Published invite on topic: ${pairTopic}`);
  } catch (err) {
    console.warn(`Failed to publish invite: ${err.message}`);
  }
}

export async function createP2PNode(options = {}) {
  const {
    relayAddrs = [],
    announceAddrs = [],
    listenPort = 4001,
    onInboxRequest,
    pubsubImpl,
  } = options;

  const code = generateCode(8);
  const psk = generatePsk();
  const exp = new Date(Date.now() + INVITE_VALIDITY_MS).toISOString();

  const relayMultiaddrs = relayAddrs
    .filter(a => a && a.trim())
    .map(a => multiaddr(a.trim()));

  const hasRelay = relayMultiaddrs.length > 0;

  const transports = [tcp()];
  
  if (hasRelay) {
    transports.push(circuitRelayTransport());
  }

  const services = {
    identify: identify(),
    dcutr: dcutr(),
  };

  if (hasRelay) {
    services.pubsub = pubsubImpl || gossipsub({
      allowPublishToZeroTopicPeers: true,
      emitSelf: false,
    });
  }

  node = await createLibp2p({
    addresses: {
      listen: [`/ip4/0.0.0.0/tcp/${listenPort}`],
    },
    transports,
    connectionEncrypters: [noise()],
    streamMuxers: [yamux()],
    services,
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
          
          await cleanupPairTopic();
          
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

  if (hasRelay) {
    for (const addr of relayMultiaddrs) {
      try {
        await node.dial(addr);
        console.log(`Connected to relay: ${addr.toString()}`);
      } catch (err) {
        console.warn(`Failed to connect to relay ${addr.toString()}: ${err.message}`);
      }
    }
  }

  const peerId = node.peerId.toString();
  
  let inviteAddrs;
  if (announceAddrs.length > 0) {
    inviteAddrs = announceAddrs.map(a => {
      if (a.includes('/p2p/')) return a;
      return `${a}/p2p/${peerId}`;
    });
  } else {
    inviteAddrs = node.getMultiaddrs().map(a => a.toString());
  }
  
  invite = {
    v: 0,
    peer: peerId,
    addrs: inviteAddrs,
    proto: INBOX_PROTOCOL,
    code: code,
    psk: pskToHex(psk),
    exp: exp,
  };

  if (hasRelay && node.services.pubsub) {
    pairTopic = `${PAIR_TOPIC_PREFIX}${code}`;
    
    node.services.pubsub.subscribe(pairTopic);
    console.log(`Subscribed to topic: ${pairTopic}`);
    
    setTimeout(() => publishInviteOnce(), 1000);
    
    const expMs = new Date(exp).getTime() - Date.now();
    topicCleanupTimer = setTimeout(() => {
      console.log('Invite expired, cleaning up topic');
      cleanupPairTopic();
    }, expMs);
  }

  return node;
}

export function getNode() {
  return node;
}

export function getPairTopic() {
  return pairTopic;
}

export async function stopP2PNode() {
  await cleanupPairTopic();
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
    pairTopic,
  };
}
