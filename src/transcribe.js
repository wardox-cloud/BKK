// Speech-to-text through any OpenAI-compatible /audio/transcriptions endpoint.

export const STT_PROVIDERS = {
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

export async function transcribe(blob, { provider, label, apiKey, baseUrl, model, language, vocabulary }) {
  const form = new FormData();
  form.append('file', blob, `dictation.${extensionFor(blob.type)}`);
  form.append('model', model);
  form.append('response_format', 'json');
  if (language) form.append('language', language);
  // Seeding the transcriber with names and jargon makes it spell them right.
  if (vocabulary.length) form.append('prompt', `Vocabulary: ${vocabulary.join(', ')}.`);

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
