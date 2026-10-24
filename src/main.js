import './styles.css';
import { Recorder } from './recorder.js';
import { STT_PROVIDERS, transcribe } from './transcribe.js';
import { CLAUDE_MODELS, STYLES, describePolishError, polish } from './polish.js';
import { copyText, copyWhenReady } from './clipboard.js';
import { loadHistory, loadSettings, saveHistory, saveSettings, vocabularyList } from './storage.js';

const HOLD_MS = 350; // a press longer than this is push-to-talk
const MAX_RECORDING_MS = 10 * 60 * 1000; // keeps uploads under providers' 25 MB cap
const TIP_KEY = 'murmur.tipDismissed';

const $ = (id) => document.getElementById(id);
const els = {
  text: $('text'),
  copy: $('copy'),
  share: $('share'),
  toggleRaw: $('toggle-raw'),
  redo: $('redo'),
  status: $('status-text'),
  retry: $('retry'),
  styles: $('styles'),
  mic: $('mic'),
  hint: $('hint-text'),
  cancel: $('cancel'),
  history: $('history'),
  historyList: $('history-list'),
  historyEmpty: $('history-empty'),
  settings: $('settings'),
  settingsForm: $('settings-form'),
  settingsNote: $('settings-note'),
  sttProvider: $('stt-provider'),
  sttBaseUrl: $('stt-base-url'),
  baseUrlRow: $('base-url-row'),
  sttKey: $('stt-key'),
  sttModel: $('stt-model'),
  language: $('language'),
  anthropicKey: $('anthropic-key'),
  claudeModel: $('claude-model'),
  vocabulary: $('vocabulary'),
  autoCopy: $('auto-copy'),
  installTip: $('install-tip'),
};

const recorder = new Recorder();
let settings = loadSettings();
let history = loadHistory();
let current = null; // the history entry shown in the editor
let showingRaw = false;
let state = 'idle'; // idle | starting | recording | working
let lastAudio = null; // kept until transcribed, so a failed upload can be retried
let press = { at: 0, startedRecording: false };
let timer = 0;
let wakeLock = null;

// ---------- UI state ----------

function setState(next) {
  state = next;
  els.mic.dataset.state = next;
  els.mic.disabled = next === 'working';
  els.mic.setAttribute('aria-label', next === 'recording' ? 'Stop and transcribe' : 'Start dictation');
  els.cancel.hidden = next !== 'recording';
  els.hint.textContent = {
    idle: 'Tap to talk, or hold for push-to-talk',
    starting: 'Starting the mic…',
    recording: 'Tap to finish',
    working: 'Working on it…',
  }[next];
  updateActions();
}

function setStatus(message, { error = false, retry = false } = {}) {
  els.status.textContent = message;
  els.status.classList.toggle('error', error);
  els.retry.hidden = !retry;
}

function updateActions() {
  const hasText = Boolean(current) && state !== 'working';
  els.copy.disabled = !hasText;
  els.share.disabled = !hasText;
  els.toggleRaw.disabled = !hasText || current.raw === current.text;
  els.redo.disabled = !hasText || !settings.anthropicKey || settings.style === 'raw';
  els.toggleRaw.textContent = showingRaw ? 'Polished' : 'Original';
}

function showEntry(entry) {
  current = entry;
  showingRaw = false;
  els.text.readOnly = false;
  els.text.value = entry?.text ?? '';
  updateActions();
}

function formatDuration(ms) {
  const seconds = Math.floor(ms / 1000);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

function renderStyles() {
  els.styles.replaceChildren(
    ...Object.entries(STYLES).map(([id, style]) => {
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = 'chip';
      chip.textContent = style.label;
      chip.setAttribute('role', 'radio');
      chip.setAttribute('aria-checked', String(id === settings.style));
      chip.addEventListener('click', () => {
        settings.style = id;
        saveSettings(settings);
        renderStyles();
        updateActions();
      });
      return chip;
    }),
  );
}

// ---------- Dictation ----------

function sttReady() {
  return Boolean(settings.sttKeys[settings.sttProvider]?.trim());
}

async function startRecording() {
  if (!sttReady()) {
    openSettings('Add a speech-to-text API key to start dictating.');
    return;
  }
  setState('starting');
  setStatus('');
  try {
    await recorder.start((level) => els.mic.style.setProperty('--level', level.toFixed(3)));
  } catch (error) {
    setState('idle');
    setStatus(
      error.name === 'NotAllowedError'
        ? 'Microphone access is blocked. Allow it for this site in your browser settings.'
        : `Couldn't start the microphone: ${error.message}`,
      { error: true },
    );
    return;
  }
  setState('recording');
  const startedAt = Date.now();
  setStatus('Listening… 0:00');
  timer = setInterval(() => {
    const elapsed = Date.now() - startedAt;
    setStatus(`Listening… ${formatDuration(elapsed)}`);
    if (elapsed >= MAX_RECORDING_MS) finishRecording();
  }, 250);
  // Keep the screen awake so the phone doesn't lock mid-sentence.
  const lock = await navigator.wakeLock?.request('screen').catch(() => null);
  if (state === 'recording' && !wakeLock) wakeLock = lock;
  else lock?.release().catch(() => {});
}

function endRecordingUi() {
  clearInterval(timer);
  wakeLock?.release().catch(() => {});
  wakeLock = null;
  els.mic.style.setProperty('--level', '0');
}

// Must run synchronously inside the tap that ends recording (see clipboard.js).
function finishRecording() {
  endRecordingUi();
  setState('working');
  run(
    recorder.stop().then((blob) => {
      lastAudio = blob;
      return processAudio(blob);
    }),
  );
}

function cancelRecording() {
  endRecordingUi();
  recorder.cancel();
  setState('idle');
  setStatus('Discarded.');
}

async function processAudio(blob) {
  setStatus('Transcribing…');
  const provider = settings.sttProvider;
  const raw = await transcribe(blob, {
    provider,
    label: STT_PROVIDERS[provider].label,
    apiKey: settings.sttKeys[provider].trim(),
    baseUrl: provider === 'custom' ? settings.customBaseUrl.trim() : STT_PROVIDERS[provider].baseUrl,
    model: settings.sttModels[provider].trim(),
    language: settings.language.trim(),
    vocabulary: vocabularyList(settings),
  });
  if (!raw) throw new Error("Didn't catch any speech. Try again a little closer to the mic.");
  lastAudio = null;

  const entry = { at: Date.now(), raw, text: raw, style: settings.style };
  showEntry(entry);
  const result = await polishText(raw, settings.style);
  entry.text = result.text;
  showEntry(entry);
  history.unshift(entry);
  saveHistory(history);
  return result;
}

async function polishText(raw, style) {
  if (style === 'raw' || !settings.anthropicKey.trim()) return { text: raw };
  setStatus('Polishing…');
  try {
    const text = await polish(
      raw,
      {
        apiKey: settings.anthropicKey.trim(),
        model: settings.claudeModel,
        style,
        vocabulary: vocabularyList(settings),
      },
      (live) => {
        els.text.value = live;
      },
    );
    return text ? { text } : { text: raw, note: 'Claude sent back nothing, so this is the raw transcript.' };
  } catch (error) {
    return { text: raw, note: `Couldn't polish (${describePolishError(error)}), so this is the raw transcript.` };
  }
}

// Runs one dictation or rewrite job. Call synchronously from the tap handler so
// the clipboard write keeps the tap's permission.
async function run(job) {
  const copied = settings.autoCopy ? copyWhenReady(job.then((result) => result.text)) : Promise.resolve(false);
  try {
    const { note } = await job;
    const done = (await copied) ? 'Copied. Paste it anywhere.' : 'Done.';
    setStatus(note ? `${note} ${done}` : done, { error: Boolean(note) });
  } catch (error) {
    setStatus(error.message, { error: true, retry: Boolean(lastAudio) });
  } finally {
    setState('idle');
  }
}

els.mic.addEventListener('pointerdown', (event) => {
  if (event.button !== 0) return;
  els.mic.setPointerCapture(event.pointerId);
  press = { at: performance.now(), startedRecording: state === 'idle' };
  if (state === 'idle') startRecording();
});

function onRelease() {
  if (state !== 'recording') return;
  const held = performance.now() - press.at >= HOLD_MS;
  // A quick tap that started recording keeps listening until the next tap.
  if (press.startedRecording && !held) return;
  finishRecording();
}
els.mic.addEventListener('pointerup', onRelease);
els.mic.addEventListener('pointercancel', onRelease);
els.mic.addEventListener('contextmenu', (event) => event.preventDefault());
els.mic.addEventListener('keydown', (event) => {
  if (event.key !== ' ' && event.key !== 'Enter') return;
  event.preventDefault();
  if (event.repeat) return;
  if (state === 'idle') startRecording();
  else if (state === 'recording') finishRecording();
});

els.cancel.addEventListener('click', cancelRecording);

els.retry.addEventListener('click', () => {
  if (!lastAudio || state !== 'idle') return;
  setState('working');
  run(processAudio(lastAudio));
});

// ---------- Result actions ----------

els.text.addEventListener('input', () => {
  if (!current || showingRaw) return;
  current.text = els.text.value;
  saveHistory(history);
});

els.copy.addEventListener('click', async () => {
  setStatus((await copyText(els.text.value)) ? 'Copied.' : "Couldn't copy. Select the text and copy it by hand.");
});

if (navigator.share) {
  els.share.hidden = false;
  els.share.addEventListener('click', () => navigator.share({ text: els.text.value }).catch(() => {}));
}

els.toggleRaw.addEventListener('click', () => {
  if (!current) return;
  showingRaw = !showingRaw;
  els.text.value = showingRaw ? current.raw : current.text;
  // The original is a reference; edits apply to the polished text.
  els.text.readOnly = showingRaw;
  updateActions();
});

els.redo.addEventListener('click', () => {
  if (!current || state !== 'idle') return;
  const entry = current;
  const style = settings.style;
  setState('working');
  run(
    polishText(entry.raw, style).then((result) => {
      entry.text = result.text;
      entry.style = style;
      showEntry(entry);
      saveHistory(history);
      return result;
    }),
  );
});

// ---------- History ----------

function renderHistory() {
  els.historyEmpty.hidden = history.length > 0;
  els.historyList.replaceChildren(
    ...history.map((entry) => {
      const item = document.createElement('li');

      const open = document.createElement('button');
      open.type = 'button';
      open.className = 'history-text';
      const when = document.createElement('time');
      when.dateTime = new Date(entry.at).toISOString();
      when.textContent = new Date(entry.at).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' });
      const preview = document.createElement('span');
      preview.textContent = entry.text;
      open.append(when, preview);
      open.addEventListener('click', () => {
        showEntry(entry);
        setStatus('');
        els.history.close();
      });

      const copy = document.createElement('button');
      copy.type = 'button';
      copy.className = 'text-btn';
      copy.textContent = 'Copy';
      copy.addEventListener('click', async () => {
        copy.textContent = (await copyText(entry.text)) ? 'Copied' : 'Failed';
      });

      const remove = document.createElement('button');
      remove.type = 'button';
      remove.className = 'text-btn danger';
      remove.textContent = 'Delete';
      remove.addEventListener('click', () => {
        history = history.filter((other) => other !== entry);
        saveHistory(history);
        if (current === entry) showEntry(null);
        renderHistory();
      });

      const tools = document.createElement('div');
      tools.className = 'history-tools';
      tools.append(copy, remove);
      item.append(open, tools);
      return item;
    }),
  );
}

$('open-history').addEventListener('click', () => {
  renderHistory();
  openSheet(els.history);
});

$('clear-history').addEventListener('click', () => {
  if (!history.length || !confirm('Delete all saved dictations from this device?')) return;
  history = [];
  saveHistory(history);
  showEntry(null);
  renderHistory();
});

// ---------- Settings ----------

let draft = null; // per-provider keys and models being edited in the open dialog

function fillProviderFields() {
  const provider = els.sttProvider.value;
  els.baseUrlRow.hidden = provider !== 'custom';
  els.sttKey.value = draft.sttKeys[provider];
  els.sttKey.placeholder = STT_PROVIDERS[provider].keyHint;
  els.sttModel.value = draft.sttModels[provider];
}

function stashProviderFields(provider) {
  draft.sttKeys[provider] = els.sttKey.value.trim();
  draft.sttModels[provider] = els.sttModel.value.trim();
}

function openSettings(note = '') {
  draft = { provider: settings.sttProvider, sttKeys: { ...settings.sttKeys }, sttModels: { ...settings.sttModels } };
  els.settingsNote.textContent = note;
  els.settingsNote.hidden = !note;
  els.sttProvider.value = settings.sttProvider;
  els.sttBaseUrl.value = settings.customBaseUrl;
  els.language.value = settings.language;
  els.anthropicKey.value = settings.anthropicKey;
  els.claudeModel.value = settings.claudeModel;
  els.vocabulary.value = settings.vocabulary;
  els.autoCopy.checked = settings.autoCopy;
  fillProviderFields();
  openSheet(els.settings);
}

els.sttProvider.append(
  ...Object.entries(STT_PROVIDERS).map(([id, provider]) => new Option(provider.label, id)),
);
els.claudeModel.append(...CLAUDE_MODELS.map((model) => new Option(model.label, model.id)));

els.sttProvider.addEventListener('change', () => {
  stashProviderFields(draft.provider);
  draft.provider = els.sttProvider.value;
  fillProviderFields();
});

els.settingsForm.addEventListener('submit', () => {
  stashProviderFields(els.sttProvider.value);
  settings = {
    ...settings,
    sttProvider: els.sttProvider.value,
    sttKeys: draft.sttKeys,
    sttModels: draft.sttModels,
    customBaseUrl: els.sttBaseUrl.value.trim(),
    language: els.language.value.trim(),
    anthropicKey: els.anthropicKey.value.trim(),
    claudeModel: els.claudeModel.value,
    vocabulary: els.vocabulary.value,
    autoCopy: els.autoCopy.checked,
  };
  saveSettings(settings);
  updateActions();
  setStatus(sttReady() ? 'Saved. Tap the mic to start.' : 'Saved.');
});

$('open-settings').addEventListener('click', () => openSettings());

// ---------- Dialogs ----------

// Focus the sheet itself rather than its first field, so phones don't pop up
// the keyboard as soon as it opens.
function openSheet(dialog) {
  dialog.showModal();
  dialog.focus();
}

for (const dialog of [els.history, els.settings]) {
  dialog.addEventListener('click', (event) => {
    if (event.target === dialog || event.target.closest('[data-close]')) dialog.close();
  });
}

// ---------- Install tip and offline support ----------

const standalone = matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
let tipDismissed = false;
try {
  tipDismissed = localStorage.getItem(TIP_KEY) === '1';
} catch {
  // Storage unavailable: show the tip.
}
els.installTip.hidden = standalone || tipDismissed || !matchMedia('(pointer: coarse)').matches;
$('dismiss-tip').addEventListener('click', () => {
  els.installTip.hidden = true;
  try {
    localStorage.setItem(TIP_KEY, '1');
  } catch {
    // Nothing to remember it in; it will show again next time.
  }
});

if ('serviceWorker' in navigator && import.meta.env.PROD) {
  navigator.serviceWorker.register('./sw.js').catch(() => {});
}

// ---------- Start ----------

renderStyles();
setState('idle');
if (history[0]) showEntry(history[0]);
if (!sttReady()) {
  openSettings('Welcome! Add a speech-to-text key to begin. An Anthropic key is optional but turns rambling speech into clean text.');
}
