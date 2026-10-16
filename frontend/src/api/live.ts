/** Cameras as Live View and the Dashboard show them: tiles, the overlay stream and recent crossings. */
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { API_BASE, request } from './client';
import { queryKeys } from './queryKeys';
import type { components } from './schema';

export type LiveTile = components['schemas']['CameraViews.LiveTile'];
export type CameraStatus = LiveTile['status'];
export type EventRow = components['schemas']['CrossingViews.EventRow'];

/** One overlay frame of the worker (Redis contract §2), relayed by the backend as an SSE "overlay" event. */
export interface OverlayTrack {
  id: number;
  /** x, y, width, height, normalised 0–1 to the frame. */
  bbox: [number, number, number, number];
  label: string;
  state: 'identified' | 'checking' | 'unknown' | 'liveness' | 'reid';
  direction: 'IN' | 'OUT' | null;
  conf_band: 'high' | 'medium' | 'low' | null;
  quality: 'good' | 'fair' | 'poor' | null;
}

export interface OverlayFrame {
  v: number;
  camera: string;
  frame_ts: string;
  w: number;
  h: number;
  tracks: OverlayTrack[];
  in_view: { count: number; names: string[] };
  fps: number | null;
}

export function useLiveCameras(enabled = true) {
  return useQuery({
    queryKey: [...queryKeys.live, 'cameras'],
    queryFn: ({ signal }) => request<LiveTile[]>('/live/cameras', { signal }),
    enabled,
    staleTime: 5_000,
  });
}

export function useRecentCrossings(camera: string | null, limit: number) {
  return useQuery({
    queryKey: [...queryKeys.live, 'recent', camera, limit],
    queryFn: ({ signal }) => request<EventRow[]>('/live/recent-crossings', { signal, query: { camera, limit } }),
    staleTime: 5_000,
  });
}

/**
 * The newest overlay frame of a camera (SSE /realtime/overlays?camera=): the browser reconnects the stream by itself;
 * a frame older than 2 s is treated as gone, so stale boxes disappear when the worker stops.
 */
export function useOverlayFrame(camera: string | null, enabled: boolean): OverlayFrame | null {
  const [frame, setFrame] = useState<OverlayFrame | null>(null);
  useEffect(() => {
    if (!camera || !enabled) return undefined;
    const source = new EventSource(`${API_BASE}/realtime/overlays?camera=${encodeURIComponent(camera)}`, {
      withCredentials: true,
    });
    let stale: ReturnType<typeof setTimeout> | null = null;
    source.addEventListener('overlay', event => {
      try {
        setFrame(JSON.parse((event as MessageEvent<string>).data) as OverlayFrame);
      } catch {
        return;
      }
      if (stale) clearTimeout(stale);
      stale = setTimeout(() => {
        setFrame(null);
      }, 2_000);
    });
    return () => {
      if (stale) clearTimeout(stale);
      source.close();
      setFrame(null);
    };
  }, [camera, enabled]);
  return frame;
}
