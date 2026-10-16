/**
 * Live View (F7, F8; the prototype's liveHTML): one tile per camera with the real video and the overlays, the
 * overlays popover (all tiles) and per-tile overrides, pause, snapshot and full screen; the side panel with "Inside
 * now", "In view now" (from the overlay stream) and the last 10 crossings. The prototype's webcam test is gone (F5).
 */
import { useMutation } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { request } from '@/api/client';
import { useTodayStats } from '@/api/attendance';
import { useLiveCameras, useOverlayFrame, useRecentCrossings, type LiveTile } from '@/api/live';
import { Legend } from '@/components/charts/Legend';
import { CameraTile, cameraPillState, type CameraTileHandle } from '@/components/domain/CameraTile';
import { DirectionChip } from '@/components/domain/Chips';
import { EventFeedItem } from '@/components/domain/EventFeedItem';
import { ErrorState } from '@/components/feedback/EmptyState';
import { toast } from '@/components/feedback/toast';
import { Check } from '@/components/form/Check';
import { Icon } from '@/components/icons/Icon';
import { Card, CardBody } from '@/components/layout/Card';
import { PageHead } from '@/components/layout/PageHead';
import { Badge } from '@/components/ui/Badge';
import { Button, ButtonLink } from '@/components/ui/Button';
import { IconButton } from '@/components/ui/IconButton';
import { LivePill } from '@/components/ui/LivePill';
import { MenuItem, MenuLabel, MenuSeparator, Popover } from '@/components/ui/Popover';
import { cx } from '@/lib/cx';
import { dayOf, zonedParts } from '@/lib/format';
import { containRect, OVERLAY_OPTIONS, readOverlayPrefs, writeOverlayPrefs, type OverlayOptions } from '@/lib/overlays';
import { usePermissionCheck } from '@/lib/permissions';
import { readPreference, writePreference } from '@/lib/storage';
import { DIRECTION_MODES, term } from '@/lib/vocabulary';
import './LiveView.module.css';

/** The prototype's BOX_COLOURS, for the legend. */
const LEGEND = [
  { label: 'Recognised face', colour: '#2fbf71' },
  { label: 'Back view (Re-ID)', colour: '#a07cf5' },
  { label: 'Unknown', colour: '#9aa3af' },
  { label: 'Checking / to review', colour: '#f0a945' },
  { label: 'Liveness failed', colour: '#f06a5e' },
  { label: 'Tripwire — arrow points inside', colour: '#38bdf8' },
  { label: 'Confidence shown as High / Medium / Low' },
] as const;

function OptionList({
  options,
  onChange,
}: {
  options: OverlayOptions;
  onChange: (key: keyof OverlayOptions, on: boolean) => void;
}) {
  return (
    <div className="overlay-menu" style={{ padding: '0 6px' }}>
      {OVERLAY_OPTIONS.map(([key, label]) => (
        <Check
          key={key}
          label={label}
          checked={options[key]}
          onChange={e => {
            onChange(key, e.target.checked);
          }}
        />
      ))}
    </div>
  );
}

/** "krz-attend_CAM-01_2026-10-12_104205.png" (the prototype's snapshot name). */
function snapshotName(code: string, now: number): string {
  const p = zonedParts(now);
  const hms = [p.h, p.mi, p.s].map(n => String(n).padStart(2, '0')).join('');
  return `krz-attend_${code}_${dayOf(now)}_${hms}.png`;
}

function Tile({
  camera,
  overlays,
  override,
  paused,
  onOverride,
}: {
  camera: LiveTile;
  overlays: OverlayOptions;
  override: boolean;
  paused: boolean;
  onOverride: (options: Partial<OverlayOptions> | null) => void;
}) {
  const handle = useRef<CameraTileHandle>(null);
  const tile = useRef<HTMLDivElement>(null);
  const audit = useMutation({
    mutationFn: () => request<undefined>('/live/snapshot-audit', { method: 'POST', body: { camera: camera.code } }),
  });

  const snapshot = (): void => {
    const video = handle.current?.video();
    const overlay = handle.current?.canvas();
    if (!video || !overlay) return;
    const out = document.createElement('canvas');
    out.width = overlay.width;
    out.height = overlay.height;
    const ctx = out.getContext('2d');
    if (!ctx) return;
    ctx.fillStyle = '#0b0d10';
    ctx.fillRect(0, 0, out.width, out.height);
    if (video.videoWidth) {
      const r = containRect(out.width, out.height, video.videoWidth, video.videoHeight);
      ctx.drawImage(video, r.x, r.y, r.w, r.h);
    }
    ctx.drawImage(overlay, 0, 0);
    out.toBlob(blob => {
      if (!blob) {
        toast('Could not save the snapshot', { type: 'error' });
        return;
      }
      const link = document.createElement('a');
      link.href = URL.createObjectURL(blob);
      link.download = snapshotName(camera.code, Date.now());
      link.click();
      setTimeout(() => {
        URL.revokeObjectURL(link.href);
      }, 500);
      audit.mutate(undefined, {
        onSuccess: () => toast('Snapshot saved as PNG (with overlays)', { type: 'success', logged: true }),
        onError: () => toast('Snapshot saved as PNG (with overlays)', { type: 'success' }),
      });
    }, 'image/png');
  };

  const fullscreen = (): void => {
    if (document.fullscreenElement) {
      void document.exitFullscreen().catch(() => undefined);
      return;
    }
    tile.current
      ?.closest('.tile')
      ?.requestFullscreen()
      .then(() => toast('Press Esc to leave full screen', { timeout: 1800 }))
      .catch(() => toast('Full screen isn’t available in this browser', { type: 'warn' }));
  };

  const head = (
    <div className="tile-head" ref={tile}>
      <span className="t-name grow" title={camera.name}>
        {camera.name}{' '}
        <span className="muted xs">
          {camera.code} · {term(DIRECTION_MODES, camera.directionMode)?.short}
        </span>
      </span>
      <span>
        <LivePill state={cameraPillState(camera)} />
      </span>
      <Popover
        align="right"
        trigger={
          <IconButton
            icon="layers"
            iconSize="sm"
            size="sm"
            label="Overlays for this tile"
            tooltip="Tile overlays"
            aria-haspopup="true"
          />
        }
      >
        <MenuLabel>Overlays — {camera.name}</MenuLabel>
        <OptionList
          options={overlays}
          onChange={(key, on) => {
            onOverride({ [key]: on });
          }}
        />
        <MenuSeparator />
        <MenuItem
          disabled={!override}
          onClick={() => {
            onOverride(null);
          }}
        >
          Use global settings
        </MenuItem>
      </Popover>
      <IconButton icon="camera" iconSize="sm" size="sm" label="Save snapshot" tooltip="Snapshot" onClick={snapshot} />
      <IconButton
        icon="maximize"
        iconSize="sm"
        size="sm"
        label="Full screen"
        tooltip="Full screen"
        onClick={fullscreen}
      />
    </div>
  );

  return (
    <CameraTile
      camera={camera}
      overlays={overlays}
      paused={paused}
      head={head}
      handle={handle}
      meta={
        <>
          <span>{camera.fps !== null && camera.status === 'LIVE' ? `${camera.fps} fps` : '— fps'}</span>
          <span>{camera.latencyMs !== null && camera.status === 'LIVE' ? `${camera.latencyMs} ms` : '— ms'}</span>
          {camera.resolution && <span>{camera.resolution}</span>}
        </>
      }
    />
  );
}

/** "In view now": the tracks of the newest overlay frame of the first live camera (names truncated, F6.4). */
function InView({ camera }: { camera: LiveTile | undefined }) {
  const frame = useOverlayFrame(camera?.code ?? null, camera?.status === 'LIVE');
  const tracks = frame?.tracks ?? [];
  return (
    <Card title="In view now" flush actions={<Badge count={tracks.length} tone="neutral" />}>
      {tracks.length ? (
        tracks.map(track => {
          const name = track.state === 'checking' ? 'Checking…' : track.label;
          return (
            <div key={track.id} className="feed-item" style={{ cursor: 'default' }}>
              <span className="crop">
                <span className="grid-center" style={{ width: '100%', height: '100%', color: 'var(--text-3)' }}>
                  <Icon name={track.state === 'reid' ? 'body' : 'user'} size="sm" />
                </span>
              </span>
              <span className="grow" style={{ minWidth: 0 }}>
                <b className="ellipsis" style={{ display: 'block' }} title={name}>
                  {name}
                </b>
                <span className="feed-meta">
                  #{track.id}
                  {track.direction ? ` · ${track.direction === 'IN' ? 'walking in' : 'walking out'}` : ''}
                </span>
              </span>
              {track.direction && <DirectionChip direction={track.direction} />}
            </div>
          );
        })
      ) : (
        <CardBody className="muted small">No one in view right now.</CardBody>
      )}
    </Card>
  );
}

function Panel({ cameras }: { cameras: readonly LiveTile[] }) {
  const can = usePermissionCheck();
  const stats = useTodayStats(can('attendance.view.all')).data;
  const recent = useRecentCrossings(null, 10).data ?? [];
  return (
    <>
      {stats && (
        <Card pad>
          <div className="row between">
            <div>
              <div className="muted small">Inside now</div>
              <div style={{ fontSize: 28, fontWeight: 700 }} className="num">
                {stats.inside}
              </div>
            </div>
            <ButtonLink size="sm" icon="printer" to="/attendance/today?filter=inside">
              Evacuation list
            </ButtonLink>
          </div>
          <p className="help mt-4">
            From IN and OUT crossings today ({stats.checkedIn} checked in · {stats.checkedOut} checked out).
          </p>
        </Card>
      )}
      <InView camera={cameras.find(c => c.status === 'LIVE') ?? cameras[0]} />
      <Card
        title="Last 10 crossings"
        flush
        bodyClassName="feed"
        actions={
          can('events.view') ? (
            <ButtonLink size="sm" to="/events">
              All
            </ButtonLink>
          ) : undefined
        }
      >
        {recent.length ? (
          recent.map(row => <EventFeedItem key={row.code} row={row} />)
        ) : (
          <CardBody className="muted small">No crossings yet.</CardBody>
        )}
      </Card>
    </>
  );
}

export function LiveViewPage() {
  const cameras = useLiveCameras();
  const [prefs, setPrefs] = useState(readOverlayPrefs);
  const [paused, setPaused] = useState(() => readPreference('overlays-paused', false));
  const list = cameras.data ?? [];
  const live = list.filter(c => c.status === 'LIVE').length;
  const single = list.length === 1;

  const save = (next: typeof prefs): void => {
    setPrefs(next);
    writeOverlayPrefs(next.global, next.tiles);
  };

  return (
    <>
      <PageHead
        title="Live View"
        sub={
          single
            ? `One camera detects IN and OUT by crossing direction · ${live ? 'live' : 'offline'}`
            : `${live} of ${list.length} cameras live`
        }
        actions={
          <>
            <Popover
              trigger={
                <Button icon="layers" iconSize="default" aria-haspopup="true">
                  Overlays
                </Button>
              }
            >
              <MenuLabel>Overlays — all tiles</MenuLabel>
              <OptionList
                options={prefs.global}
                onChange={(key, on) => {
                  save({ global: { ...prefs.global, [key]: on }, tiles: {} });
                }}
              />
            </Popover>
            <Button
              variant={paused ? 'primary' : 'default'}
              icon={paused ? 'play' : 'pause'}
              iconSize="default"
              aria-pressed={paused}
              onClick={() => {
                const next = !paused;
                setPaused(next);
                writePreference('overlays-paused', next);
                toast(next ? 'Overlays paused' : 'Overlays resumed', { timeout: 1600 });
              }}
            >
              {paused ? 'Resume overlays' : 'Pause overlays'}
            </Button>
          </>
        }
      />
      {cameras.isError ? (
        <ErrorState what="the cameras" onRetry={() => void cameras.refetch()} />
      ) : (
        <div className={single ? 'focus-layout' : undefined}>
          <div>
            <div className={cx('live-grid', single ? 'l1' : 'l2')} id="live-grid">
              {list.map(camera => (
                <Tile
                  key={camera.code}
                  camera={camera}
                  overlays={{ ...prefs.global, ...prefs.tiles[camera.code] }}
                  override={camera.code in prefs.tiles}
                  paused={paused}
                  onOverride={options => {
                    const tiles = Object.fromEntries(
                      Object.entries(prefs.tiles).filter(([code]) => code !== camera.code),
                    );
                    if (options) tiles[camera.code] = { ...prefs.tiles[camera.code], ...options };
                    save({ global: prefs.global, tiles });
                  }}
                />
              ))}
            </div>
            <div className="mt-12">
              <Legend entries={LEGEND} />
            </div>
          </div>
          <div className={cx('col gap-12', !single && 'mt-16')} id="live-panel">
            <Panel cameras={list} />
          </div>
        </div>
      )}
    </>
  );
}
