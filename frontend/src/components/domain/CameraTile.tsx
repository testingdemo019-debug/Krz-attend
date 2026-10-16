/**
 * A camera tile (Dashboard card and Live View): the real video (go2rtc MSE), the overlay canvas above it, the clock
 * and the prototype's banners (connecting, reconnecting, offline). The head is the Live View's toolbar.
 */
import { useEffect, useImperativeHandle, useRef, useState, type CSSProperties, type ReactNode, type Ref } from 'react';
import { useOverlayFrame, type LiveTile } from '@/api/live';
import { Icon } from '@/components/icons/Icon';
import { ButtonLink } from '@/components/ui/Button';
import type { LivePillState } from '@/components/ui/LivePill';
import { Spinner } from '@/components/ui/Spinner';
import { fmtTime } from '@/lib/format';
import { MsePlayer, type VideoState } from '@/lib/msePlayer';
import { containRect, drawGeometry, drawTracks, type Hit, type OverlayOptions } from '@/lib/overlays';
import { useCan } from '@/lib/permissions';
import './CameraTile.module.css';

/**
 * The pill for a camera (the prototype's livePill): the camera's status as the server sees it (worker heartbeat), not
 * this browser's video stream, whose state shows in the tile banner. The server's CONNECTING covers reconnecting.
 */
export function cameraPillState(camera: Pick<LiveTile, 'status'>): LivePillState {
  if (camera.status === 'OFFLINE' || camera.status === 'DISABLED') return 'off';
  if (camera.status === 'CONNECTING') return 'rec';
  return 'live';
}

export type TileBanner = 'off' | 'connecting' | 'reconnecting' | 'error' | null;

/**
 * Which banner covers the video: the camera's server status first (offline or disabled: recognition is not running),
 * then this browser's stream. The worker reconnecting to the camera (CONNECTING) shows in the pill; it does not hide a
 * picture that is playing.
 */
export function tileBanner(status: LiveTile['status'], video: VideoState | null): TileBanner {
  if (status === 'OFFLINE' || status === 'DISABLED') return 'off';
  if (video === null || video === 'connecting') return 'connecting';
  if (video === 'live') return null;
  return video;
}

function Banner({ camera, video, detail }: { camera: LiveTile; video: VideoState | null; detail: string | null }) {
  const canSettings = useCan('settings.cameras');
  const banner = tileBanner(camera.status, video);
  if (banner === 'off') {
    return (
      <div className="tile-banner">
        <Icon name="wifiOff" size="lg" />
        <b>
          {camera.status === 'DISABLED'
            ? 'Camera disabled'
            : camera.statusSince
              ? `Offline since ${fmtTime(camera.statusSince)}`
              : 'Offline'}
        </b>
        <span className="xs" style={{ opacity: 0.8 }}>
          Crossings are missed while the camera is offline
        </span>
        {canSettings && (
          <ButtonLink size="sm" to={`/settings/cameras?edit=${camera.code}`}>
            Open camera settings
          </ButtonLink>
        )}
      </div>
    );
  }
  if (banner === 'connecting') {
    return (
      <div className="tile-banner">
        <Spinner />
        Connecting to {camera.name}…
      </div>
    );
  }
  if (banner === 'reconnecting') {
    return (
      <div className="tile-banner">
        <Spinner />
        <b>Reconnecting…</b>
        <span className="xs" style={{ opacity: 0.8 }}>
          {detail ?? 'Showing the last frame'}
        </span>
      </div>
    );
  }
  if (banner === 'error') {
    return (
      <div className="tile-banner">
        <Icon name="wifiOff" size="lg" />
        <b>The live stream isn’t available</b>
        {detail && (
          <span className="xs" style={{ opacity: 0.8 }}>
            {detail}
          </span>
        )}
      </div>
    );
  }
  return null;
}

/** The tile's elements, for the snapshot (video frame + overlay canvas). */
export interface CameraTileHandle {
  video: () => HTMLVideoElement | null;
  canvas: () => HTMLCanvasElement | null;
}

function useClock(): string {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => {
      setNow(Date.now());
    }, 1_000);
    return () => {
      clearInterval(timer);
    };
  }, []);
  return fmtTime(now, true);
}

export function CameraTile({
  camera,
  overlays,
  paused = false,
  head,
  meta,
  style,
  handle,
  onVideoState,
}: {
  camera: LiveTile;
  overlays: OverlayOptions;
  /** "Pause" stops drawing overlays. */
  paused?: boolean;
  head?: ReactNode;
  /** Bottom-left chips (fps, latency). */
  meta?: ReactNode;
  style?: CSSProperties;
  handle?: Ref<CameraTileHandle>;
  onVideoState?: (state: VideoState | null) => void;
}) {
  const video = useRef<HTMLVideoElement>(null);
  const canvas = useRef<HTMLCanvasElement>(null);
  useImperativeHandle(handle, () => ({ video: () => video.current, canvas: () => canvas.current }), []);
  const [state, setState] = useState<VideoState | null>(null);
  const [detail, setDetail] = useState<string | null>(null);
  const hits = useRef<Hit[]>([]);
  const [hover, setHover] = useState<string | undefined>(undefined);
  const streaming = camera.status === 'LIVE' || camera.status === 'CONNECTING';
  const frame = useOverlayFrame(camera.code, streaming && !paused);
  const clock = useClock();

  useEffect(() => {
    const element = video.current;
    if (!element || !streaming) return undefined;
    const player = new MsePlayer(element, camera.code, (next, why) => {
      setState(next);
      setDetail(why ?? null);
    });
    player.start();
    return () => {
      player.stop();
      setState(null);
    };
  }, [camera.code, streaming]);

  useEffect(() => {
    onVideoState?.(state);
  }, [state, onVideoState]);

  useEffect(() => {
    const element = canvas.current;
    const ctx = element?.getContext('2d');
    if (!element || !ctx) return;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const width = Math.max(160, Math.round(element.clientWidth * dpr));
    const height = Math.max(90, Math.round(element.clientHeight * dpr));
    if (element.width !== width || element.height !== height) {
      element.width = width;
      element.height = height;
    }
    ctx.clearRect(0, 0, width, height);
    hits.current = [];
    if (paused || camera.status !== 'LIVE') return;
    const v = video.current;
    const known = v !== null && v.videoWidth > 0;
    const rect = containRect(
      width,
      height,
      known ? v.videoWidth : (frame?.w ?? 16),
      known ? v.videoHeight : (frame?.h ?? 9),
    );
    drawGeometry(ctx, camera, rect, overlays);
    if (frame) {
      hits.current = drawTracks(ctx, frame, rect, overlays, width).map(h => ({
        ...h,
        x0: h.x0 / dpr,
        y0: h.y0 / dpr,
        x1: h.x1 / dpr,
        y1: h.y1 / dpr,
      }));
    }
  }, [camera, frame, overlays, paused]);

  return (
    <div className="tile" style={style}>
      {head}
      <div className="tile-stage">
        <video ref={video} muted playsInline autoPlay aria-label={`${camera.name} live video`} />
        <canvas
          ref={canvas}
          title={hover}
          aria-hidden="true"
          onMouseMove={event => {
            const box = event.currentTarget.getBoundingClientRect();
            const x = event.clientX - box.left;
            const y = event.clientY - box.top;
            setHover(hits.current.find(h => x >= h.x0 && x <= h.x1 && y >= h.y0 && y <= h.y1)?.label);
          }}
        />
        {meta && <div className="tile-meta">{meta}</div>}
        <span className="tile-clock">{clock}</span>
        <Banner camera={camera} video={state} detail={detail} />
      </div>
    </div>
  );
}
