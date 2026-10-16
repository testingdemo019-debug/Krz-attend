/**
 * A minimal player for go2rtc's MSE over WebSocket (F7), instead of vendoring video-rtc.js: connect to
 * /media/api/ws?src=<camera>, offer the codecs this browser plays, then append the fMP4 segments it sends. It keeps
 * the picture at the live edge (trimming what is behind) and reconnects with back-off when the stream drops, when no
 * picture arrives (the relay connects to the camera only when someone watches) and when a playing stream stalls.
 */

export type VideoState = 'connecting' | 'live' | 'reconnecting' | 'error';

const CODECS = [
  'avc1.640029',
  'avc1.64002A',
  'avc1.640033',
  'hvc1.1.6.L153.B0',
  'mp4a.40.2',
  'mp4a.40.5',
  'flac',
  'opus',
] as const;
const BACKOFF_SECONDS = [1, 2, 5, 10, 30] as const;
/** Behind the live edge by more than this, the video jumps forward. */
const MAX_LAG_SECONDS = 2;
/** What stays buffered behind the playhead. */
const KEEP_SECONDS = 15;
/** No picture this long after connecting: the relay or the camera did not answer, so try again. */
export const FIRST_PICTURE_SECONDS = 15;
/** Playing, but no new video this long: the stream stalled, so reconnect. */
export const STALL_SECONDS = 10;

export function streamUrl(camera: string, location: Location = window.location): string {
  const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${scheme}//${location.host}/media/api/ws?src=${encodeURIComponent(camera)}`;
}

export class MsePlayer {
  private socket: WebSocket | null = null;
  private source: MediaSource | null = null;
  private buffer: SourceBuffer | null = null;
  private queue: ArrayBuffer[] = [];
  private attempt = 0;
  private retry: ReturnType<typeof setTimeout> | null = null;
  private watchdog: ReturnType<typeof setTimeout> | null = null;
  /** Why the last attempt failed, shown while reconnecting. */
  private reason: string | undefined;
  private playing = false;
  private stopped = false;
  private readonly onPlaying = (): void => {
    this.attempt = 0;
    this.reason = undefined;
    this.playing = true;
    this.onState('live');
    this.watch(STALL_SECONDS, 'The picture stopped. Trying again.');
  };

  private readonly video: HTMLVideoElement;
  private readonly camera: string;
  private readonly onState: (state: VideoState, detail?: string) => void;

  constructor(video: HTMLVideoElement, camera: string, onState: (state: VideoState, detail?: string) => void) {
    this.video = video;
    this.camera = camera;
    this.onState = onState;
  }

  start(): void {
    if (typeof MediaSource === 'undefined') {
      this.onState('error', 'This browser can’t play the live stream (no Media Source Extensions).');
      return;
    }
    this.stopped = false;
    this.video.addEventListener('playing', this.onPlaying);
    this.connect('connecting');
  }

  stop(): void {
    this.stopped = true;
    if (this.retry) clearTimeout(this.retry);
    if (this.watchdog) clearTimeout(this.watchdog);
    this.video.removeEventListener('playing', this.onPlaying);
    this.teardown();
  }

  private connect(state: 'connecting' | 'reconnecting'): void {
    this.onState(state, state === 'reconnecting' ? this.reason : undefined);
    this.teardown();
    this.watch(FIRST_PICTURE_SECONDS, 'No picture from the camera yet. Trying again.');
    const source = new MediaSource();
    this.source = source;
    this.video.src = URL.createObjectURL(source);
    source.addEventListener(
      'sourceopen',
      () => {
        URL.revokeObjectURL(this.video.src);
        this.openSocket();
      },
      { once: true },
    );
  }

  private openSocket(): void {
    const socket = new WebSocket(streamUrl(this.camera));
    socket.binaryType = 'arraybuffer';
    this.socket = socket;
    socket.onopen = () => {
      const supported = CODECS.filter(c => MediaSource.isTypeSupported(`video/mp4; codecs="${c}"`));
      socket.send(JSON.stringify({ type: 'mse', value: supported.join(',') }));
    };
    socket.onmessage = event => {
      if (typeof event.data === 'string') this.onText(event.data);
      else this.append(event.data as ArrayBuffer);
    };
    socket.onclose = () => {
      if (!this.stopped) this.scheduleReconnect();
    };
  }

  private onText(text: string): void {
    let message: { type?: string; value?: string };
    try {
      message = JSON.parse(text) as { type?: string; value?: string };
    } catch {
      return;
    }
    if (message.type === 'error') {
      const why = relayError(message.value);
      if (isCodecMismatch(message.value)) {
        // Retrying cannot help until the camera setting changes.
        if (this.watchdog) clearTimeout(this.watchdog);
        this.onState('error', why);
      } else {
        this.scheduleReconnect(why);
      }
      return;
    }
    if (message.type !== 'mse' || !message.value || !this.source) return;
    const buffer = this.source.addSourceBuffer(message.value);
    buffer.mode = 'segments';
    buffer.addEventListener('updateend', () => {
      this.flush();
    });
    this.buffer = buffer;
    void this.video.play().catch(() => undefined);
  }

  private append(data: ArrayBuffer): void {
    if (!this.buffer) return;
    if (this.playing) this.watch(STALL_SECONDS, 'The picture stopped. Trying again.');
    if (this.buffer.updating || this.queue.length) this.queue.push(data);
    else this.appendNow(data);
  }

  private appendNow(data: ArrayBuffer): void {
    try {
      this.buffer?.appendBuffer(data);
    } catch {
      this.scheduleReconnect();
    }
  }

  /** After each append: the next queued segment, else trim the past and catch up with the live edge. */
  private flush(): void {
    const buffer = this.buffer;
    if (!buffer || buffer.updating) return;
    const next = this.queue.shift();
    if (next) {
      this.appendNow(next);
      return;
    }
    if (!buffer.buffered.length) return;
    const end = buffer.buffered.end(buffer.buffered.length - 1);
    const start = buffer.buffered.start(0);
    if (end - this.video.currentTime > MAX_LAG_SECONDS) this.video.currentTime = end - 0.3;
    if (end - KEEP_SECONDS > start) {
      try {
        buffer.remove(start, end - KEEP_SECONDS);
      } catch {
        // Trimming is an optimisation; the next segment tries again.
      }
    }
  }

  /** (Re)starts the timer that reconnects when nothing arrives in time. */
  private watch(seconds: number, why: string): void {
    if (this.watchdog) clearTimeout(this.watchdog);
    this.watchdog = setTimeout(() => {
      this.scheduleReconnect(why);
    }, seconds * 1000);
  }

  private scheduleReconnect(why?: string): void {
    if (this.stopped) return;
    this.teardown();
    const delay = BACKOFF_SECONDS[Math.min(this.attempt, BACKOFF_SECONDS.length - 1)] ?? 30;
    this.attempt += 1;
    this.reason = why;
    this.onState('reconnecting', why);
    if (this.retry) clearTimeout(this.retry);
    this.retry = setTimeout(() => {
      this.connect('reconnecting');
    }, delay * 1000);
  }

  private teardown(): void {
    if (this.watchdog) clearTimeout(this.watchdog);
    this.watchdog = null;
    this.playing = false;
    if (this.socket) {
      this.socket.onclose = null;
      this.socket.close();
      this.socket = null;
    }
    this.queue = [];
    this.buffer = null;
    this.source = null;
  }
}

/**
 * go2rtc's own wording for the one failure an administrator can fix at the camera: the stream's video codec is not
 * one this browser plays (typically H.265, which most browsers cannot decode).
 */
const CODEC_MISMATCH = /codecs not matched: video:(\w+)/;

function isCodecMismatch(text: string | undefined): boolean {
  return CODEC_MISMATCH.test(text ?? '');
}

export function relayError(text: string | undefined): string | undefined {
  const codec = CODEC_MISMATCH.exec(text ?? '')?.[1];
  if (!codec) return text;
  return `The camera sends ${codec === 'H265' ? 'H.265' : codec} video, which this browser can’t play. Set the camera’s sub stream to H.264.`;
}
