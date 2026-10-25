// Google Gemini: transcribes speech from instructions, which lets it handle
// mixed English and Urdu and write the Urdu in the script you choose.
import { toBase64, toWav } from './wav.js';

const ENDPOINT = 'https://generativelanguage.googleapis.com/v1beta/models';

// Rolling alias for the newest Flash-Lite, used when the list of models isn't available.
const FREE_FALLBACK_MODEL = 'gemini-flash-lite-latest';

// The free tier gives each model its own allowance of requests per day and per
// minute. Murmur remembers which models have used theirs up, until it comes
// back, and goes straight to one that still has some. Same as the Android app.
const QUOTA_KEY = 'murmur.geminiQuota';
const PACIFIC = new Intl.DateTimeFormat('en-CA', { timeZone: 'America/Los_Angeles' });

function loadQuota() {
  try {
    return JSON.parse(localStorage.getItem(QUOTA_KEY)) ?? {};
  } catch {
    return {};
  }
}

function saveQuota(quota) {
  try {
    localStorage.setItem(QUOTA_KEY, JSON.stringify(quota));
  } catch {
    // Private mode: Murmur just forgets between visits.
  }
}

/** When daily allowances come back: midnight in California. */
export function nextReset(now = Date.now()) {
  const today = PACIFIC.format(now);
  let low = now;
  let high = now + 26 * 3600_000;
  // Clocks change, so find the moment the date there turns over.
  while (high - low > 1) {
    const middle = Math.floor((low + high) / 2);
    if (PACIFIC.format(middle) === today) low = middle;
    else high = middle;
  }
  return high;
}

function resting(model, now = Date.now()) {
  return (loadQuota().rests?.[model]?.until ?? 0) > now;
}

/** Reads a 429 reply: a used-up daily allowance rests the model until the reset, a per-minute one briefly. */
function rest(model, body) {
  const details = body.error?.details ?? [];
  const daily = details.flatMap((detail) => detail.violations ?? []).find((v) => v.quotaId?.includes('PerDay'));
  const retry = parseFloat(details.find((detail) => detail.retryDelay)?.retryDelay ?? '60');
  const until = daily ? nextReset() : Date.now() + (Number.isFinite(retry) ? retry : 60) * 1000;
  const quota = loadQuota();
  quota.rests = { ...quota.rests, [model]: { until, daily: Boolean(daily), perDay: Number(daily?.quotaValue) || undefined } };
  saveQuota(quota);
  return { daily: Boolean(daily) };
}

/** Flash before Flash-Lite, newer before older; previews and special versions are left out. */
export function rankModels(names) {
  return names
    .map((name) => ({ name, match: /^gemini-(\d+)\.(\d+)-flash(-lite)?$/.exec(name) }))
    .filter(({ match }) => match)
    .sort((a, b) => Boolean(a.match[3]) - Boolean(b.match[3]) || b.match[1] - a.match[1] || b.match[2] - a.match[2])
    .map(({ name }) => name)
    .slice(0, 8);
}

/** Every Flash model this key can use, looked up once a day. */
async function flashModels(apiKey) {
  const quota = loadQuota();
  const today = PACIFIC.format(Date.now());
  if (quota.modelsDay === today && quota.models) return quota.models;
  try {
    const response = await fetch(`${ENDPOINT}?pageSize=1000`, { headers: { 'x-goog-api-key': apiKey } });
    if (!response.ok) return [FREE_FALLBACK_MODEL];
    const names = ((await response.json()).models ?? [])
      .filter((model) => model.supportedGenerationMethods?.includes('generateContent'))
      .map((model) => model.name.replace(/^models\//, ''));
    const ranked = rankModels(names);
    if (!ranked.length) return [FREE_FALLBACK_MODEL];
    saveQuota({ ...loadQuota(), models: ranked, modelsDay: today });
    return ranked;
  } catch {
    return [FREE_FALLBACK_MODEL];
  }
}

async function call({ apiKey, model, system, parts }) {
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
        ...(model.startsWith('gemini-2.5-flash') && {
          generationConfig: { thinkingConfig: { thinkingBudget: 0 } },
        }),
      }),
    });
  } catch {
    throw new Error("Couldn't reach Gemini. Check your connection, then try again.");
  }
  return { response, body: await response.json().catch(() => ({})) };
}

export async function geminiGenerate(request) {
  const { apiKey, model } = request;
  const first = !resting(model);
  const candidates = first ? [model] : [];
  let response;
  let body;
  let lastRest;
  let others;
  for (let i = 0; ; i++) {
    if (i === candidates.length) {
      // The chosen model couldn't answer: try the other Flash models that have requests left.
      if (others) break;
      others = (await flashModels(apiKey)).filter((name) => name !== model);
      candidates.push(...others.filter((name) => !resting(name)));
      // Everything looks used up, but what Murmur remembers may be out of date.
      if (!first && candidates.length === i) candidates.push(model);
      if (i === candidates.length) break;
    }
    ({ response, body } = await call({ ...request, model: candidates[i] }));
    if (response.ok) {
      return (body.candidates?.[0]?.content?.parts ?? [])
        .filter((part) => part.text && !part.thought)
        .map((part) => part.text)
        .join('')
        .trim();
    }
    if (response.status === 429) lastRest = rest(candidates[i], body);
    else if (response.status !== 404 && response.status < 500) break;
  }
  const keyRejected =
    response.status === 401 ||
    response.status === 403 ||
    body.error?.details?.some((detail) => detail.reason === 'API_KEY_INVALID');
  if (keyRejected) throw new Error('Gemini rejected the API key. Check it in Settings.');
  if (response.status === 429) {
    if (lastRest?.daily) {
      const time = new Date(nextReset()).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
      throw new Error(`You've used today's free Gemini requests on every model Murmur can use. They come back at ${time}.`);
    }
    throw new Error("Gemini's free tier allows only so many requests a minute. Wait a minute, then try again.");
  }
  if (response.status >= 500) throw new Error('Gemini is busy right now. Try again in a moment.');
  throw new Error(`Gemini couldn't do that: ${body.error?.message ?? response.status}`);
}

export async function audioPart(blob) {
  return { inlineData: { mimeType: 'audio/wav', data: await toBase64(await toWav(blob)) } };
}
