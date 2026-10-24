// Turns a raw transcript into clean, ready-to-paste text, with Claude or Gemini.
import Anthropic from '@anthropic-ai/sdk';
import { audioPart, geminiGenerate } from './gemini.js';
import { cleanupPrompt, transcriptMessage } from './prompts.js';

export const CLAUDE_MODELS = [
  { id: 'claude-opus-5-5', label: 'Claude Opus 5.5 (best quality)' },
  { id: 'claude-sonnet-5-5', label: 'Claude Sonnet 5.5' },
  { id: 'claude-haiku-5-5', label: 'Claude Haiku 5.5 (fastest, cheapest)' },
];

export function describePolishError(error) {
  if (error instanceof Anthropic.AuthenticationError) return 'Anthropic rejected the API key';
  if (error instanceof Anthropic.PermissionDeniedError) return 'this key can\'t use that model';
  if (error instanceof Anthropic.RateLimitError) return 'Anthropic rate limit hit, wait a moment';
  if (error instanceof Anthropic.APIConnectionError) return 'couldn\'t reach Anthropic';
  if (error instanceof Anthropic.APIError) return error.error?.error?.message ?? error.message;
  return error.message;
}

// Streams the polished text through onText as it arrives. Resolves to the
// final text, or null when Claude returned nothing usable (the caller then
// keeps the raw transcript).
export async function polishWithClaude(raw, { apiKey, model, mode, style, vocabulary }, onText) {
  // Keys stay on this device and go straight to Anthropic; there is no server.
  const client = new Anthropic({ apiKey, dangerouslyAllowBrowser: true });

  // If a safety classifier declines, the API retries on Anthropic's recommended
  // fallback model within the same request. Haiku doesn't offer this.
  const fallback = model.startsWith('claude-haiku')
    ? {}
    : { betas: ['server-side-fallback-2026-07-01'], fallbacks: 'default' };

  const stream = client.beta.messages.stream({
    model,
    max_tokens: 16000,
    // Cleanup is light work; low effort keeps the wait after you stop talking short.
    output_config: { effort: 'low' },
    system: cleanupPrompt({ language: mode, style, vocabulary }),
    messages: [{ role: 'user', content: transcriptMessage(raw) }],
    ...fallback,
  });

  let live = '';
  stream.on('text', (delta) => {
    live += delta;
    onText(live);
  });

  const message = await stream.finalMessage();
  if (message.stop_reason === 'refusal') return null;
  const text = message.content
    .filter((block) => block.type === 'text')
    .map((block) => block.text)
    .join('')
    .trim();
  return text || null;
}

export async function polishWithGemini(raw, { apiKey, model, mode, style, vocabulary }) {
  const text = await geminiGenerate({
    apiKey,
    model,
    system: cleanupPrompt({ language: mode, style, vocabulary }),
    parts: [{ text: transcriptMessage(raw) }],
  });
  return text || null;
}

// One call instead of two: Gemini listens to the recording and writes the
// cleaned-up text directly.
export async function dictateWithGemini(blob, { apiKey, model, mode, style, vocabulary }) {
  return geminiGenerate({
    apiKey,
    model,
    system: cleanupPrompt({ language: mode, style, vocabulary, fromAudio: true }),
    parts: [await audioPart(blob), { text: 'Here is the recording.' }],
  });
}
