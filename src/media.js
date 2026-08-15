import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { resolveBearer } from './credentials.js';

const XAI_STT_URL = 'https://api.x.ai/v1/stt';
const XAI_TTS_URL = 'https://api.x.ai/v1/tts';
const TTS_CACHE_DIR = process.env.TTS_CACHE_DIR || '/data/media/tts';

function ensureDir(dir) {
  if (!existsSync(dir)) {
    mkdirSync(dir, { recursive: true });
  }
}

export async function speechToText(audioBuffer, mimeType) {
  const { bearer, source } = resolveBearer();
  
  if (!bearer) {
    return {
      error: 'credential_unavailable',
      detail: 'No xAI OAuth or Grok auth credential found. Mount a secret at /data/secrets/oauth.json or /home/node/.grok/auth.json',
    };
  }

  const formData = new FormData();
  const blob = new Blob([audioBuffer], { type: mimeType || 'audio/wav' });
  formData.append('file', blob, 'audio.wav');
  formData.append('model', 'grok-stt');

  try {
    const response = await fetch(XAI_STT_URL, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${bearer}`,
      },
      body: formData,
    });

    if (!response.ok) {
      const errorText = await response.text();
      return {
        error: 'stt_failed',
        detail: `xAI STT returned ${response.status}: ${errorText}`,
      };
    }

    const result = await response.json();
    return { text: result.text || result.transcription || '' };
  } catch (err) {
    return {
      error: 'stt_failed',
      detail: `xAI STT request failed: ${err.message}`,
    };
  }
}

export async function textToSpeech(messageId, text) {
  const { bearer, source } = resolveBearer();
  
  if (!bearer) {
    return {
      error: 'credential_unavailable',
      detail: 'No xAI OAuth or Grok auth credential found. Mount a secret at /data/secrets/oauth.json or /home/node/.grok/auth.json',
    };
  }

  ensureDir(TTS_CACHE_DIR);
  const cachePath = join(TTS_CACHE_DIR, `${messageId}.mp3`);

  if (existsSync(cachePath)) {
    return { audioBuffer: readFileSync(cachePath), cached: true };
  }

  try {
    const response = await fetch(XAI_TTS_URL, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${bearer}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: 'grok-tts',
        text: text,
        voice: 'eve',
      }),
    });

    if (!response.ok) {
      const errorText = await response.text();
      return {
        error: 'tts_failed',
        detail: `xAI TTS returned ${response.status}: ${errorText}`,
      };
    }

    const audioBuffer = Buffer.from(await response.arrayBuffer());
    
    try {
      writeFileSync(cachePath, audioBuffer);
    } catch {
      // Cache write failed, continue without caching
    }

    return { audioBuffer, cached: false };
  } catch (err) {
    return {
      error: 'tts_failed',
      detail: `xAI TTS request failed: ${err.message}`,
    };
  }
}
