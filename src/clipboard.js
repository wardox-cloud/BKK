// iOS Safari only allows clipboard writes during a tap, but the text isn't
// ready until transcription finishes. Handing the clipboard a promise inside
// the tap handler lets it wait for the text without losing permission.
export function copyWhenReady(textPromise) {
  if (typeof ClipboardItem !== 'undefined' && navigator.clipboard?.write) {
    try {
      const item = new ClipboardItem({
        'text/plain': textPromise.then((text) => new Blob([text], { type: 'text/plain' })),
      });
      return navigator.clipboard.write([item]).then(
        () => true,
        () => textPromise.then(copyText, () => false),
      );
    } catch {
      // Older browsers reject promise-valued ClipboardItems; fall through.
    }
  }
  return textPromise.then(copyText, () => false);
}

export async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}
