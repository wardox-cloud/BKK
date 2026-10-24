// Builds prompts from shared/prompts.json. android/core/.../Prompts.kt builds
// the same strings for the keyboard; keep the two in step.
import prompts from '../shared/prompts.json';

const lines = (value) => (Array.isArray(value) ? value.join('\n') : value);
const fill = (text, values) => text.replace(/\{(\w+)\}/g, (_, key) => values[key] ?? '');
const speaker = lines(prompts.speaker);

export const LANGUAGES = Object.fromEntries(
  Object.entries(prompts.languages).map(([id, language]) => [id, language.label]),
);
export const STYLES = Object.fromEntries(
  Object.entries(prompts.styles).map(([id, style]) => [id, style.label]),
);

// For models that take instructions with the audio (Gemini).
export function transcriptionInstruction(language, vocabulary) {
  const terms = vocabulary.join(', ');
  return fill(lines(prompts.transcribe.instruction), {
    speaker,
    language: prompts.languages[language].transcribe,
    vocabulary: terms ? fill(prompts.transcribe.vocabulary, { terms }) : '',
  })
    .split('\n')
    .filter((line) => line.trim())
    .join('\n');
}

// For Whisper-style models, whose prompt is example text in the style the
// transcript should follow, not instructions.
export function transcriptionPrimer(language, vocabulary) {
  const terms = vocabulary.join(', ');
  const parts = [prompts.languages[language].primer];
  if (terms) parts.push(fill(prompts.transcribe.primerVocabulary, { terms }));
  return parts.join(' ');
}

// System prompt for the cleanup step. With fromAudio, the model gets the
// recording instead of a transcript and does both steps at once.
export function cleanupPrompt({ language, style, vocabulary, fromAudio = false }) {
  const { cleanup } = prompts;
  const terms = vocabulary.join(', ');
  const parts = [
    lines(cleanup.base),
    lines(cleanup.accent),
    prompts.languages[language].cleanup,
    prompts.styles[style].cleanup,
  ];
  if (terms) parts.push(fill(cleanup.vocabulary, { terms }));
  if (fromAudio) parts.push(fill(cleanup.oneStep, { speaker }));
  parts.push(cleanup.finish);
  return parts.join('\n\n');
}

export function transcriptMessage(raw) {
  return `<transcript>\n${raw}\n</transcript>`;
}
