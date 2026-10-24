// Speech-to-text: Gemini, or any OpenAI-compatible /audio/transcriptions endpoint.
import { audioPart, geminiGenerate } from './gemini.js';
import { transcriptionInstruction, transcriptionPrimer } from './prompts.js';

export const STT_PROVIDERS = {
  gemini: { label: 'Google Gemini (best for Urdu + English)', keyHint: 'AIza...' },
  openai: { label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', keyHint: 'sk-...' },
  groq: { label: 'Groq', baseUrl: 'https://api.groq.com/openai/v1', keyHint: 'gsk_...' },
  custom: { label: 'Custom (OpenAI-compatible)', baseUrl: '', keyHint: 'API key' },
};

function extensionFor(mimeType) {
  if (mimeType.includes('mp4')) return 'm4a';
  if (mimeType.includes('ogg')) return 'ogg';
  return 'webm';
}

async function errorMessage(response) {
  try {
    const body = await response.json();
    return body.error?.message ?? JSON.stringify(body);
  } catch {
    return `${response.status} ${response.statusText}`;
  }
}

// mode is a key of LANGUAGES (how the speaker talks); languageCode is an
// optional ISO hint such as "en" for Whisper-style models.
export async function transcribe(blob, { provider, label, apiKey, baseUrl, model, mode, languageCode, vocabulary }) {
  if (provider === 'gemini') {
    return geminiGenerate({
      apiKey,
      model,
      system: transcriptionInstruction(mode, vocabulary),
      parts: [await audioPart(blob), { text: 'Transcribe this recording.' }],
    });
  }

  const form = new FormData();
  form.append('file', blob, `dictation.${extensionFor(blob.type)}`);
  form.append('model', model);
  form.append('response_format', 'json');
  if (languageCode) form.append('language', languageCode);
  // Example text in the target style steers the script (Roman Urdu or Urdu)
  // and the spelling of names.
  form.append('prompt', transcriptionPrimer(mode, vocabulary));

  const init = { method: 'POST', headers: { Authorization: `Bearer ${apiKey}` }, body: form };
  const unreachable = new Error(`Couldn't reach ${label}. Check your connection, then try again.`);
  let response;
  try {
    response = await fetch(`${baseUrl.replace(/\/$/, '')}/audio/transcriptions`, init);
  } catch {
    // The browser couldn't reach the provider (offline, or CORS). When the app
    // is deployed on Vercel, api/transcribe.js can relay the same request.
    if (provider === 'custom' || !navigator.onLine) throw unreachable;
    response = await fetch(`./api/transcribe?provider=${provider}`, init).catch(() => null);
    if (!response || response.status === 404) throw unreachable;
  }

  if (response.status === 401) {
    throw new Error(`${label} rejected the API key. Check it in Settings.`);
  }
  if (!response.ok) {
    throw new Error(`${label} couldn't transcribe that: ${await errorMessage(response)}`);
  }
  const { text } = await response.json();
  return (text ?? '').trim();
}
