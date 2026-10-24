// Fallback relay for speech-to-text, used only when the browser can't call the
// provider directly (for example a CORS change). Vercel serves it as
// /api/transcribe. It stores nothing: the caller's own key is passed through,
// and only the known providers below can be reached.
const UPSTREAMS = {
  openai: 'https://api.openai.com/v1/audio/transcriptions',
  groq: 'https://api.groq.com/openai/v1/audio/transcriptions',
};

export async function POST(request) {
  const target = UPSTREAMS[new URL(request.url).searchParams.get('provider')];
  if (!target) return Response.json({ error: { message: 'Unknown provider' } }, { status: 400 });

  const upstream = await fetch(target, {
    method: 'POST',
    headers: {
      authorization: request.headers.get('authorization') ?? '',
      'content-type': request.headers.get('content-type') ?? '',
    },
    body: await request.arrayBuffer(),
  });
  return new Response(upstream.body, {
    status: upstream.status,
    headers: { 'content-type': upstream.headers.get('content-type') ?? 'application/json' },
  });
}
