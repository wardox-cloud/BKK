// Google Gemini: transcribes speech from instructions, which lets it handle
// mixed English and Urdu and write the Urdu in the script you choose.
import { toBase64, toWav } from './wav.js';

const ENDPOINT = 'https://generativelanguage.googleapis.com/v1beta/models';

export async function geminiGenerate({ apiKey, model, system, parts }) {
  let response;
  try {
    response = await fetch(`${ENDPOINT}/${encodeURIComponent(model)}:generateContent`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-goog-api-key': apiKey },
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: system }] },
        contents: [{ role: 'user', parts }],
        // Transcribing and tidying text need little reasoning; less is faster.
        ...(model.startsWith('gemini-3') && {
          generationConfig: { thinkingConfig: { thinkingLevel: 'low' } },
        }),
      }),
    });
  } catch {
    throw new Error("Couldn't reach Gemini. Check your connection, then try again.");
  }

  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const keyRejected =
      response.status === 401 ||
      response.status === 403 ||
      body.error?.details?.some((detail) => detail.reason === 'API_KEY_INVALID');
    if (keyRejected) throw new Error('Gemini rejected the API key. Check it in Settings.');
    throw new Error(`Gemini couldn't do that: ${body.error?.message ?? response.status}`);
  }
  return (body.candidates?.[0]?.content?.parts ?? [])
    .filter((part) => part.text && !part.thought)
    .map((part) => part.text)
    .join('')
    .trim();
}

export async function audioPart(blob) {
  return { inlineData: { mimeType: 'audio/wav', data: await toBase64(await toWav(blob)) } };
}
