// Microphone capture with a live input level for the UI.

// Chrome/Android records Opus in WebM; iOS Safari records AAC in MP4.
const MIME_TYPES = ['audio/webm;codecs=opus', 'audio/mp4', 'audio/webm', 'audio/ogg;codecs=opus'];

export class Recorder {
  #context = null;
  #stream = null;
  #recorder = null;
  #chunks = [];
  #frame = 0;

  get recording() {
    return this.#recorder?.state === 'recording';
  }

  // Call from a tap handler: iOS only lets audio start inside a user gesture,
  // so the AudioContext is created and resumed before the first await.
  async start(onLevel) {
    this.#context ??= new AudioContext();
    this.#context.resume();

    this.#stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    });

    const mimeType = MIME_TYPES.find((type) => MediaRecorder.isTypeSupported(type));
    this.#recorder = new MediaRecorder(
      this.#stream,
      mimeType ? { mimeType, audioBitsPerSecond: 64000 } : undefined,
    );
    this.#chunks = [];
    this.#recorder.ondataavailable = (event) => {
      if (event.data.size) this.#chunks.push(event.data);
    };
    // No timeslice: Safari writes the most reliable files when it gets one chunk.
    this.#recorder.start();
    this.#watchLevel(onLevel);
  }

  stop() {
    const recorder = this.#recorder;
    if (!recorder || recorder.state === 'inactive') {
      return Promise.reject(new Error('Not recording'));
    }
    return new Promise((resolve, reject) => {
      recorder.onstop = () => {
        const blob = new Blob(this.#chunks, { type: recorder.mimeType || 'audio/webm' });
        this.#release();
        resolve(blob);
      };
      recorder.onerror = (event) => {
        this.#release();
        reject(event.error ?? new Error('Recording failed'));
      };
      recorder.stop();
    });
  }

  cancel() {
    if (this.#recorder && this.#recorder.state !== 'inactive') {
      this.#recorder.onstop = null;
      this.#recorder.stop();
    }
    this.#release();
  }

  #watchLevel(onLevel) {
    const source = this.#context.createMediaStreamSource(this.#stream);
    const analyser = this.#context.createAnalyser();
    analyser.fftSize = 512;
    source.connect(analyser);
    const samples = new Uint8Array(analyser.fftSize);

    const tick = () => {
      analyser.getByteTimeDomainData(samples);
      let sum = 0;
      for (const sample of samples) {
        const centered = (sample - 128) / 128;
        sum += centered * centered;
      }
      // RMS of normal speech sits around 0.02-0.2; stretch it to 0-1.
      onLevel(Math.min(1, Math.sqrt(sum / samples.length) * 6));
      this.#frame = requestAnimationFrame(tick);
    };
    tick();
  }

  #release() {
    cancelAnimationFrame(this.#frame);
    this.#stream?.getTracks().forEach((track) => track.stop());
    this.#stream = null;
    this.#recorder = null;
  }
}
