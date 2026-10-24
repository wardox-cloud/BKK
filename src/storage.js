// Settings and history live in this device's localStorage only.

const SETTINGS_KEY = 'murmur.settings';
const HISTORY_KEY = 'murmur.history';
const HISTORY_LIMIT = 100;

export const DEFAULT_SETTINGS = {
  sttProvider: 'gemini',
  sttKeys: { gemini: '', openai: '', groq: '', custom: '' },
  sttModels: {
    gemini: 'gemini-3.6-flash',
    openai: 'gpt-4o-transcribe',
    groq: 'whisper-large-v3-turbo',
    custom: '',
  },
  customBaseUrl: '',
  languageMode: 'roman',
  language: '',
  cleanupEngine: 'claude',
  anthropicKey: '',
  claudeModel: 'claude-opus-5-5',
  style: 'clean',
  vocabulary: '',
  autoCopy: true,
};

function read(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : fallback;
  } catch {
    return fallback;
  }
}

function write(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Private mode or storage full: the app still works for this session.
  }
}

export function loadSettings() {
  const saved = read(SETTINGS_KEY, {});
  return {
    ...DEFAULT_SETTINGS,
    ...saved,
    sttKeys: { ...DEFAULT_SETTINGS.sttKeys, ...saved.sttKeys },
    sttModels: { ...DEFAULT_SETTINGS.sttModels, ...saved.sttModels },
  };
}

export function saveSettings(settings) {
  write(SETTINGS_KEY, settings);
}

export function vocabularyList(settings) {
  return settings.vocabulary
    .split(/[\n,]/)
    .map((term) => term.trim())
    .filter(Boolean);
}

export function loadHistory() {
  return read(HISTORY_KEY, []);
}

export function saveHistory(entries) {
  write(HISTORY_KEY, entries.slice(0, HISTORY_LIMIT));
}
