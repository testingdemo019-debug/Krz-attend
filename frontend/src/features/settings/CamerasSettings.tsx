/**
 * Settings → Cameras: the cameras and their editor (?edit=CAM-nn, ?add=1). One camera at the main entrance records
 * both directions. A new camera is added with its details first (the server gives it a starting zone and tripwire);
 * its password, the connection test, the tripwire and zone (drawn on the camera's latest frame), the calibration test
 * walk and the 7-day health follow in its editor. The password is entered in its own dialog and never shown again.
 */
import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { request } from '@/api/client';
import { useAllLocations } from '@/api/organisation';
import {
  useCalibration,
  useCamera,
  useCameraHealth,
  useCameraPassword,
  useCameras,
  useDiscoverCameras,
  useSaveCamera,
  useStartCalibration,
  useTestCamera,
  type CameraInput,
  type CameraTest,
  type CameraView,
  type Wire,
} from '@/api/settings';
import { queryKeys } from '@/api/queryKeys';
import { cameraPillState } from '@/components/domain/CameraTile';
import { ErrorState } from '@/components/feedback/EmptyState';
import { Modal } from '@/components/feedback/Modal';
import { toast } from '@/components/feedback/toast';
import { Switch } from '@/components/form/Switch';
import { Icon } from '@/components/icons/Icon';
import { ScrollArea } from '@/components/layout/ScrollArea';
import { Button, ButtonLink } from '@/components/ui/Button';
import { IconButton } from '@/components/ui/IconButton';
import { Callout } from '@/components/ui/Callout';
import { Chip } from '@/components/ui/Chip';
import { LivePill } from '@/components/ui/LivePill';
import { Segmented } from '@/components/ui/Segmented';
import { SkeletonRows } from '@/components/ui/Skeleton';
import { Spinner } from '@/components/ui/Spinner';
import { fmtDate } from '@/lib/format';
import { drawGeometry } from '@/lib/overlays';
import { useDraft, UnsavedChangesGuard } from '@/lib/useDraft';
import { plural } from '@/lib/text';
import { DIRECTION_MODES, term } from '@/lib/vocabulary';
import { useQuery } from '@tanstack/react-query';
import { SettingField } from './SettingsCard';
import './Cameras.module.css';
import '@/components/feedback/UnsavedChangesBar.module.css';
import '@/components/ui/StatStrip.module.css';

const IPV4 = /^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/;
const CANVAS_W = 960;
const CANVAS_H = 540;
const NEAR = 0.035;

interface Geometry {
  zone: number[][];
  wire: Wire | null;
  confirmFrames: number;
  ignoreUturnSec: number;
}

interface Draft {
  input: CameraInput;
  geometry: Geometry;
}

export function CamerasSettings() {
  const [params] = useSearchParams();
  const edit = params.get('edit');
  if (edit) return <ExistingCamera code={edit} />;
  if (params.get('add')) return <CameraEditor camera={null} />;
  return <CameraList />;
}

function CameraList() {
  const cameras = useCameras();
  const navigate = useNavigate();
  return (
    <div className="card pad">
      <div className="row between wrap gap-8">
        <div>
          <h2>Cameras</h2>
          <p className="muted small mt-4">
            One camera at the main entrance records both directions: walking towards it is IN (face), walking away is
            OUT (back view, Re-ID). More cameras can be added for other doors.
          </p>
        </div>
        <ButtonLink variant="primary" icon="plus" to="/settings/cameras?add=1">
          Add camera
        </ButtonLink>
      </div>
      {cameras.isError ? (
        <ErrorState what="the cameras" onRetry={() => void cameras.refetch()} />
      ) : !cameras.data ? (
        <SkeletonRows rows={2} columns={6} />
      ) : (
        <div className="dt-wrap mt-12">
          <table className="dt">
            <thead>
              <tr>
                <th>Camera</th>
                <th>Entry point</th>
                <th>Direction</th>
                <th>Status</th>
                <th className="num">Frame rate</th>
                <th className="num">Uptime (7 days)</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {cameras.data.map(c => {
                const open = () => void navigate(`/settings/cameras?edit=${c.code}`);
                return (
                  <tr
                    key={c.code}
                    className="clickable"
                    tabIndex={0}
                    onClick={e => {
                      if (!(e.target as Element).closest('a,button')) open();
                    }}
                    onKeyDown={e => {
                      if (e.key === 'Enter' && e.target === e.currentTarget) open();
                    }}
                  >
                    <td>
                      <b>{c.name}</b> <span className="mono muted xs">{c.code}</span>
                      <div className="muted xs">
                        {c.model ?? '—'} · {c.host}
                      </div>
                    </td>
                    <td>{c.entryPoint.name}</td>
                    <td>{term(DIRECTION_MODES, c.directionMode)?.short ?? c.directionMode}</td>
                    <td>
                      <LivePill state={cameraPillState(c)} />
                      {!c.enabled && (
                        <>
                          {' '}
                          <span className="tag">Disabled</span>
                        </>
                      )}
                    </td>
                    <td className="num">{c.status === 'LIVE' && c.fps !== null ? `${String(c.fps)} fps` : '—'}</td>
                    <td className="num">{c.uptime7d === null ? '—' : `${String(c.uptime7d)}%`}</td>
                    <td>
                      <ButtonLink size="sm" icon="edit" to={`/settings/cameras?edit=${c.code}`}>
                        Edit
                      </ButtonLink>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function ExistingCamera({ code }: { code: string }) {
  const camera = useCamera(code);
  if (camera.isError) return <ErrorState what="the camera" onRetry={() => void camera.refetch()} />;
  if (!camera.data) {
    return (
      <div className="card pad">
        <SkeletonRows rows={8} columns={2} />
      </div>
    );
  }
  return <CameraEditor key={camera.data.version} camera={camera.data} />;
}

const inputOf = (c: CameraView): CameraInput => ({
  name: c.name,
  entryPoint: c.entryPoint.code,
  directionMode: c.directionMode,
  mountNote: c.mountNote,
  facesVisibleWhen: c.facesVisibleWhen,
  model: c.model,
  host: c.host,
  rtspPort: c.rtspPort,
  onvifPort: c.onvifPort,
  mainPath: c.mainPath,
  subPath: c.subPath,
  username: c.username,
  enabled: c.enabled,
  version: c.version,
});

const geometryOf = (c: CameraView): Geometry => ({
  zone: c.zone,
  wire: c.wire,
  confirmFrames: c.confirmFrames,
  ignoreUturnSec: c.ignoreUturnSec,
});

function CameraEditor({ camera }: { camera: CameraView | null }) {
  const isNew = camera === null;
  const navigate = useNavigate();
  const locations = useAllLocations().data ?? [];
  const entryPoints = locations.flatMap(l =>
    l.entryPoints.filter(p => p.status === 'ACTIVE').map(p => ({ code: p.code, name: p.name, location: l.name })),
  );
  const saveCamera = useSaveCamera();
  const created = useRef<string | null>(null);
  const [leaveTo, setLeaveTo] = useState<string | null>(null);
  const source: Draft = camera
    ? { input: inputOf(camera), geometry: geometryOf(camera) }
    : {
        input: {
          name: '',
          entryPoint: entryPoints[0]?.code ?? '',
          directionMode: 'BOTH',
          mountNote: 'Inside, facing the door',
          facesVisibleWhen: 'ENTERING',
          model: null,
          host: '',
          rtspPort: 554,
          onvifPort: 80,
          mainPath: '/video/live?channel=1&subtype=0',
          subPath: '/video/live?channel=1&subtype=1',
          username: null,
          enabled: true,
          version: null,
        },
        geometry: { zone: [], wire: null, confirmFrames: 3, ignoreUturnSec: 5 },
      };
  const draft = useDraft({
    title: camera?.name ?? 'Camera',
    source,
    validate: d => {
      if (!d.input.name.trim()) return 'Enter a camera name';
      if (!IPV4.test(d.input.host)) return 'Enter a valid IP address';
      if (!d.input.entryPoint) return 'Choose the entry point';
      if (camera && !camera.passwordSet) return 'Set the camera password';
      if (camera && d.input.directionMode !== 'MONITOR' && !d.geometry.wire)
        return 'Add a tripwire — it decides IN and OUT';
      return null;
    },
    save: async d => {
      const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);
      const input = { ...d.input, name: d.input.name.trim() };
      const saved = await saveCamera.mutateAsync({
        code: camera?.code ?? null,
        version: camera?.version ?? null,
        input: !camera || !same(d.input, source.input) ? input : null,
        geometry: camera && !same(d.geometry, source.geometry) ? d.geometry : null,
      });
      created.current = saved?.code ?? null;
    },
  });
  const d = draft.value;
  const setInput = (patch: Partial<CameraInput>) => {
    draft.set(current => ({ ...current, input: { ...current.input, ...patch } }));
  };
  const setGeometry = (patch: Partial<Geometry>) => {
    draft.set(current => ({ ...current, geometry: { ...current.geometry, ...patch } }));
  };

  useEffect(() => {
    if (leaveTo && !draft.dirty) void navigate(leaveTo);
  }, [leaveTo, draft.dirty, navigate]);

  const submit = () => {
    if (!isNew && !draft.dirty) {
      void navigate('/settings/cameras');
      return;
    }
    void draft.save().then(ok => {
      if (!ok) return;
      if (isNew && created.current) {
        toast('Set the camera password next', { type: 'info' });
        setLeaveTo(`/settings/cameras?edit=${created.current}`);
      } else {
        setLeaveTo('/settings/cameras');
      }
    });
  };

  const streamPrefix = `rtsp://${d.input.username ?? ''}${d.input.username ? ':••••@' : ''}${d.input.host || '…'}:${String(d.input.rtspPort)}`;

  return (
    <div className="card pad">
      <div className="row between">
        <h2>{camera ? camera.name : 'Add camera'}</h2>
        <ButtonLink size="sm" icon="arrowLeft" to="/settings/cameras">
          All cameras
        </ButtonLink>
      </div>
      <div className="set-section">
        <h3>Details</h3>
        <div className="form-grid mt-8">
          <SettingField label="Name" htmlFor="ce-name">
            <input
              id="ce-name"
              className="input"
              value={d.input.name}
              placeholder="e.g. Main entrance"
              onChange={e => {
                setInput({ name: e.target.value });
              }}
            />
          </SettingField>
          <SettingField label="Code" help="Sent to KRZ HRMS as the device ID.">
            <input className="input mono" value={camera?.code ?? 'Given when added'} disabled aria-label="Code" />
          </SettingField>
          <SettingField label="Entry point" htmlFor="ce-ep">
            <select
              id="ce-ep"
              className="select"
              value={d.input.entryPoint}
              onChange={e => {
                setInput({ entryPoint: e.target.value });
              }}
            >
              {entryPoints.map(p => (
                <option key={p.code} value={p.code}>
                  {p.name} · {p.location}
                </option>
              ))}
            </select>
          </SettingField>
          <SettingField label="Processing">
            <Switch
              checked={d.input.enabled}
              label={d.input.enabled ? 'Enabled' : 'Disabled'}
              onChange={e => {
                setInput({ enabled: e.target.checked });
              }}
            />
          </SettingField>
        </div>
      </div>
      <div className="set-section">
        <h3>Direction</h3>
        <div className="form-grid mt-8">
          <SettingField
            label="What this camera records"
            htmlFor="ce-dir"
            help={
              d.input.directionMode === 'BOTH'
                ? 'Facing the camera = IN, matched by face. Walking away = OUT, matched by body (Re-ID) against people who came in today.'
                : d.input.directionMode === 'MONITOR'
                  ? 'Crossings are shown in Live View but never create punches.'
                  : 'Only one direction creates punches; the other is recorded as a movement.'
            }
          >
            <select
              id="ce-dir"
              className="select"
              value={d.input.directionMode}
              onChange={e => {
                setInput({ directionMode: e.target.value as CameraInput['directionMode'] });
              }}
            >
              {Object.entries(DIRECTION_MODES).map(([value, m]) => (
                <option key={value} value={value}>
                  {m.label}
                </option>
              ))}
            </select>
          </SettingField>
          <SettingField
            label="Mounting"
            htmlFor="ce-mount"
            help="Recommended: inside the lobby, 2.4–2.8 m high, facing people as they come in."
          >
            <input
              id="ce-mount"
              className="input"
              value={d.input.mountNote ?? ''}
              onChange={e => {
                setInput({ mountNote: e.target.value || null });
              }}
            />
          </SettingField>
          {camera && (
            <>
              <SettingField
                label="Confirm a crossing after"
                htmlFor="ce-cf"
                help="Avoids false crossings from people standing on the line."
              >
                <select
                  id="ce-cf"
                  className="select"
                  value={d.geometry.confirmFrames}
                  onChange={e => {
                    setGeometry({ confirmFrames: Number(e.target.value) });
                  }}
                >
                  {[2, 3, 4, 5].map(n => (
                    <option key={n} value={n}>
                      {n} frames past the line
                    </option>
                  ))}
                </select>
              </SettingField>
              <SettingField
                label="Ignore U-turns within"
                htmlFor="ce-ut"
                help="Someone who crosses and comes straight back creates no punch."
              >
                <select
                  id="ce-ut"
                  className="select"
                  value={d.geometry.ignoreUturnSec}
                  onChange={e => {
                    setGeometry({ ignoreUturnSec: Number(e.target.value) });
                  }}
                >
                  {[3, 5, 8, 10].map(n => (
                    <option key={n} value={n}>
                      {n} seconds
                    </option>
                  ))}
                </select>
              </SettingField>
            </>
          )}
        </div>
      </div>
      <Connection camera={camera} input={d.input} setInput={setInput} streamPrefix={streamPrefix} />
      {camera && (
        <>
          <Geometry camera={camera} geometry={d.geometry} setGeometry={setGeometry} />
          <CalibrationSection camera={camera} />
          <Health code={camera.code} />
        </>
      )}
      <div className="savebar">
        <span className="dirty" style={{ visibility: isNew || draft.dirty ? 'visible' : 'hidden' }}>
          <Icon name="alertCircle" size="sm" />
          Unsaved changes
        </span>
        <ButtonLink to="/settings/cameras">Cancel</ButtonLink>
        <Button variant="primary" pending={draft.saving} onClick={submit}>
          {isNew ? 'Add camera' : 'Save changes'}
        </Button>
      </div>
      <UnsavedChangesGuard draft={draft} />
    </div>
  );
}

function Connection({
  camera,
  input,
  setInput,
  streamPrefix,
}: {
  camera: CameraView | null;
  input: CameraInput;
  setInput: (patch: Partial<CameraInput>) => void;
  /** The RTSP URL up to the path, the password masked (the prototype's preview line). */
  streamPrefix: string;
}) {
  const [discovering, setDiscovering] = useState(false);
  const [password, setPassword] = useState(false);
  return (
    <div className="set-section">
      <div className="row between wrap">
        <h3>Connection</h3>
        <Button
          size="sm"
          icon="search"
          onClick={() => {
            setDiscovering(true);
          }}
        >
          Find cameras on the network
        </Button>
      </div>
      <div className="form-grid mt-8">
        <SettingField label="IP address" htmlFor="ce-ip">
          <input
            id="ce-ip"
            className="input mono"
            value={input.host}
            onChange={e => {
              setInput({ host: e.target.value.trim() });
            }}
          />
        </SettingField>
        <SettingField label="Model" htmlFor="ce-model">
          <input
            id="ce-model"
            className="input"
            value={input.model ?? ''}
            onChange={e => {
              setInput({ model: e.target.value || null });
            }}
          />
        </SettingField>
        <SettingField label="Username" htmlFor="ce-user">
          <input
            id="ce-user"
            className="input"
            autoComplete="off"
            value={input.username ?? ''}
            onChange={e => {
              setInput({ username: e.target.value.trim() || null });
            }}
          />
        </SettingField>
        <SettingField
          label="Password"
          help={
            !camera
              ? 'Set it after adding the camera.'
              : camera.passwordSet
                ? `Stored encrypted on the server${camera.passwordUpdatedAt ? ` · changed ${fmtDate(camera.passwordUpdatedAt)}` : ''}. It is never shown again.`
                : 'Required.'
          }
        >
          <div className="row gap-8">
            <input
              className="input"
              value={camera?.passwordSet ? '••••••••••' : ''}
              placeholder="Not set"
              disabled
              aria-label="Password (hidden)"
            />
            <Button
              disabled={!camera}
              onClick={() => {
                setPassword(true);
              }}
            >
              {camera?.passwordSet ? 'Change' : 'Set password'}
            </Button>
          </div>
        </SettingField>
        <SettingField label="Main stream (faces)" htmlFor="ce-main" full>
          <StreamPath
            id="ce-main"
            prefix={streamPrefix}
            value={input.mainPath}
            onChange={mainPath => {
              setInput({ mainPath });
            }}
          />
        </SettingField>
        <SettingField label="Sub stream (detection and Re-ID)" htmlFor="ce-sub" full>
          <StreamPath
            id="ce-sub"
            prefix={streamPrefix}
            value={input.subPath}
            onChange={subPath => {
              setInput({ subPath });
            }}
          />
        </SettingField>
      </div>
      {camera && <ConnectionTest camera={camera} />}
      {discovering && (
        <DiscoverDialog
          current={camera?.code ?? null}
          onClose={() => {
            setDiscovering(false);
          }}
          onUse={(host, model) => {
            setDiscovering(false);
            setInput({ host, ...(model && !input.model ? { model } : {}) });
            toast(`Filled in ${host}`, { type: 'success' });
          }}
        />
      )}
      {password && camera && (
        <PasswordDialog
          camera={camera}
          onClose={() => {
            setPassword(false);
          }}
        />
      )}
    </div>
  );
}

/** A stream's RTSP URL: the masked prefix as text, then the path, which is editable. */
function StreamPath({
  id,
  prefix,
  value,
  onChange,
}: {
  id: string;
  prefix: string;
  value: string;
  onChange: (path: string) => void;
}) {
  return (
    <div className="input mono row stream-path" style={{ gap: 0, paddingRight: 0 }}>
      <span className="muted nowrap">{prefix}</span>
      <input
        id={id}
        className="mono grow"
        style={{ border: 0, outline: 0, background: 'transparent', minWidth: 0, height: '100%', padding: 0 }}
        value={value}
        spellCheck={false}
        onChange={e => {
          onChange(e.target.value.trim());
        }}
      />
    </div>
  );
}

function ConnectionTest({ camera }: { camera: CameraView }) {
  const test = useTestCamera(camera.code);
  const [result, setResult] = useState<CameraTest | null>(null);
  const connected = (
    r: { resolution: string | null; codec: string | null; latencyMs: number | null },
    offset: number | null,
  ) => (
    <>
      <Chip tone="green" icon="check">
        Connected
      </Chip>{' '}
      {r.resolution ?? '—'} · {r.codec ?? '—'} · {r.latencyMs ?? '—'} ms · <ClockOffset seconds={offset} />
    </>
  );
  return (
    <div className="row gap-8 mt-8">
      <Button
        icon="activity"
        iconSize="sm"
        pending={test.isPending}
        onClick={() => {
          test.mutate(undefined, {
            onSuccess: r => {
              setResult(r);
              if (r.ok) toast('Connection OK — snapshot updated', { type: 'success', logged: true });
            },
            onError: error => toast(error.message, { type: 'error' }),
          });
        }}
      >
        Test connection
      </Button>
      <span className="small">
        {test.isPending ? (
          <span className="row gap-4">
            <Spinner dark />
            Connecting…
          </span>
        ) : result ? (
          result.ok ? (
            connected(result, result.clockOffsetSec)
          ) : (
            <>
              <Chip tone="red" icon="x">
                {result.errorCode === 'AUTH_FAILED' ? 'Authentication failed' : 'Not connected'}
              </Chip>{' '}
              {result.message ?? ''}
            </>
          )
        ) : camera.status === 'LIVE' ? (
          connected(camera, camera.clockOffsetSec)
        ) : (
          <span className="muted">Not tested yet</span>
        )}
      </span>
    </div>
  );
}

/**
 * The camera clock's offset from the server. Attendance uses the server's time, so a wrong camera clock only shows
 * in the date printed on the picture; beyond a minute it is pointed out so it can be set.
 */
function ClockOffset({ seconds }: { seconds: number | null }) {
  if (seconds === null) return <>clock offset —</>;
  const off = Math.abs(seconds);
  if (off <= 60) return <>clock offset {String(seconds)} s</>;
  const [n, unit] =
    off >= 86_400
      ? [Math.round(off / 86_400), 'day']
      : off >= 3_600
        ? [Math.round(off / 3_600), 'hour']
        : [Math.round(off / 60), 'minute'];
  return (
    <span style={{ color: 'var(--amber)' }}>
      camera clock {plural(n, unit)} {seconds < 0 ? 'behind' : 'ahead'} — set its date and time (NTP)
    </span>
  );
}

function PasswordDialog({ camera, onClose }: { camera: CameraView; onClose: () => void }) {
  const save = useCameraPassword(camera.code);
  const [first, setFirst] = useState('');
  const [second, setSecond] = useState('');
  const [error, setError] = useState('');
  return (
    <Modal
      open
      size="sm"
      title={`${camera.passwordSet ? 'Change' : 'Set'} camera password`}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            pending={save.isPending}
            onClick={() => {
              const problem =
                first.length < 8 ? 'Use at least 8 characters.' : first !== second ? 'The passwords don’t match.' : '';
              setError(problem);
              if (problem) return;
              save.mutate(first, {
                onSuccess: () => {
                  onClose();
                  toast('Camera password saved', { type: 'success', logged: true });
                },
                onError: e => {
                  setError(e.message);
                },
              });
            }}
          >
            Save password
          </Button>
        </>
      }
    >
      <div className="col">
        <div className="field">
          <label htmlFor="cp-1">New password</label>
          <input
            id="cp-1"
            type="password"
            className="input"
            autoComplete="new-password"
            value={first}
            onChange={e => {
              setFirst(e.target.value);
            }}
          />
        </div>
        <div className="field">
          <label htmlFor="cp-2">Confirm password</label>
          <input
            id="cp-2"
            type="password"
            className="input"
            autoComplete="new-password"
            value={second}
            onChange={e => {
              setSecond(e.target.value);
            }}
          />
          <div className="err-text">{error}</div>
        </div>
        <p className="help">Stored encrypted on the server. Nobody can read it back — not even administrators.</p>
      </div>
    </Modal>
  );
}

function DiscoverDialog({
  current,
  onClose,
  onUse,
}: {
  current: string | null;
  onClose: () => void;
  onUse: (host: string, model: string | null) => void;
}) {
  const discover = useDiscoverCameras();
  const started = useRef(false);
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    discover.mutate();
  }, [discover]);
  return (
    <Modal open title="Find cameras on the network" sub="Searching the camera network…" onClose={onClose}>
      {discover.isPending || discover.isIdle ? (
        <div className="row gap-8">
          <Spinner dark />
          <span className="muted small">Searching…</span>
        </div>
      ) : discover.isError ? (
        <Callout tone="danger" icon="alertCircle" className="small">
          {discover.error.message}
        </Callout>
      ) : (
        <ScrollArea className="dt-wrap" label="Cameras found">
          <table className="dt">
            <thead>
              <tr>
                <th>IP</th>
                <th>Model</th>
                <th>Manufacturer</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {discover.data.map(f => (
                <tr key={f.ip}>
                  <td className="mono">{f.ip}</td>
                  <td>{f.model ?? '—'}</td>
                  <td className="xs">{f.manufacturer ?? '—'}</td>
                  <td>
                    {f.inUseBy && f.inUseBy !== current ? (
                      <span className="muted small">In use: {f.inUseBy}</span>
                    ) : (
                      <Button
                        size="sm"
                        variant="primary"
                        onClick={() => {
                          onUse(f.ip, f.model);
                        }}
                      >
                        Use
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
              {discover.data.length === 0 && (
                <tr>
                  <td colSpan={4} className="muted">
                    No cameras answered.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </ScrollArea>
      )}
    </Modal>
  );
}

/** The camera's latest frame as an image, asked again on "Refresh snapshot". */
function useSnapshot(code: string, round: number) {
  return useQuery({
    queryKey: [...queryKeys.cameras, 'snapshot', code, round],
    queryFn: async ({ signal }) => {
      const blob = await request<Blob>(`/settings/cameras/${code}/snapshot`, { signal, responseType: 'blob' });
      return createImageBitmap(blob);
    },
    staleTime: Infinity,
    retry: false,
  });
}

function Geometry({
  camera,
  geometry: g,
  setGeometry,
}: {
  camera: CameraView;
  geometry: Geometry;
  setGeometry: (patch: Partial<Geometry>) => void;
}) {
  const [mode, setMode] = useState<'wire' | 'zone'>('wire');
  const [round, setRound] = useState(0);
  const snapshot = useSnapshot(camera.code, round);
  const canvas = useRef<HTMLCanvasElement>(null);
  const drag = useRef<{ kind: 'a' | 'b' } | { kind: 'z'; i: number } | null>(null);

  useEffect(() => {
    const ctx = canvas.current?.getContext('2d');
    if (!ctx) return;
    ctx.fillStyle = '#0b1220';
    ctx.fillRect(0, 0, CANVAS_W, CANVAS_H);
    if (snapshot.data) ctx.drawImage(snapshot.data, 0, 0, CANVAS_W, CANVAS_H);
    else {
      ctx.fillStyle = 'rgba(255,255,255,.6)';
      ctx.font = '600 16px system-ui';
      ctx.textAlign = 'center';
      ctx.fillText(
        snapshot.isPending ? 'Loading the latest frame…' : 'No picture from the camera yet',
        CANVAS_W / 2,
        CANVAS_H / 2,
      );
      ctx.textAlign = 'start';
    }
    const r = { x: 0, y: 0, w: CANVAS_W, h: CANVAS_H };
    drawGeometry(ctx, { zone: g.zone, wire: g.wire }, r, { zone: true, wire: true });
    ctx.fillStyle = '#facc15';
    for (const [x = 0, y = 0] of g.zone) {
      ctx.beginPath();
      ctx.arc(x * CANVAS_W, y * CANVAS_H, 7, 0, Math.PI * 2);
      ctx.fill();
    }
    if (g.wire) {
      for (const [x = 0, y = 0] of [g.wire.a, g.wire.b]) {
        ctx.fillStyle = '#38bdf8';
        ctx.beginPath();
        ctx.arc(x * CANVAS_W, y * CANVAS_H, 10, 0, Math.PI * 2);
        ctx.fill();
        ctx.strokeStyle = '#0b1220';
        ctx.lineWidth = 2;
        ctx.stroke();
      }
    }
  }, [g, snapshot.data, snapshot.isPending]);

  const pos = (e: ReactPointerEvent<HTMLCanvasElement>): [number, number] => {
    const r = e.currentTarget.getBoundingClientRect();
    const clamp = (v: number) => Math.min(1, Math.max(0, v));
    return [clamp((e.clientX - r.left) / r.width), clamp((e.clientY - r.top) / r.height)];
  };
  const near = (p: readonly number[], px: number, py: number) =>
    Math.hypot(((p[0] ?? 0) - px) * (16 / 9), (p[1] ?? 0) - py) < NEAR;

  return (
    <div className="set-section">
      <div className="row between wrap gap-8">
        <h3>Tripwire and detection zone</h3>
        <div className="row gap-8 wrap">
          <Segmented
            label="Edit"
            segments={[
              { value: 'wire', label: 'Tripwire' },
              { value: 'zone', label: 'Zone' },
            ]}
            value={mode}
            onChange={setMode}
          />
          {mode === 'zone' ? (
            <Button
              size="sm"
              onClick={() => {
                setGeometry({ zone: [] });
              }}
            >
              Clear zone
            </Button>
          ) : (
            <>
              <Button
                size="sm"
                icon="arrowUpDown"
                disabled={!g.wire}
                onClick={() => {
                  if (g.wire) setGeometry({ wire: { ...g.wire, inSide: -g.wire.inSide } });
                }}
              >
                Flip inside direction
              </Button>
              {g.wire ? (
                <Button
                  size="sm"
                  onClick={() => {
                    setGeometry({ wire: null });
                  }}
                >
                  Remove tripwire
                </Button>
              ) : (
                <Button
                  size="sm"
                  onClick={() => {
                    setGeometry({ wire: { a: [0.08, 0.66], b: [0.92, 0.66], inSide: 1 } });
                  }}
                >
                  Add tripwire
                </Button>
              )}
            </>
          )}
        </div>
      </div>
      <p className="help mb-8">
        {mode === 'zone'
          ? 'Click to add points; drag a point to move it. People are detected only inside the zone.'
          : 'Drag the ends of the line across the doorway. The arrow points inside: crossing with the arrow is IN, against it is OUT.'}
      </p>
      <div className="editor-wrap">
        <IconButton
          icon="refresh"
          size="sm"
          bordered
          label="Refresh snapshot"
          tooltip="Refresh snapshot"
          style={{ position: 'absolute', top: 8, right: 8, zIndex: 1, background: 'var(--surface)' }}
          onClick={() => {
            setRound(n => n + 1);
          }}
        />
        <canvas
          ref={canvas}
          width={CANVAS_W}
          height={CANVAS_H}
          aria-label="Camera snapshot with tripwire and zone"
          onPointerDown={e => {
            const [px, py] = pos(e);
            if (mode === 'zone') {
              const i = g.zone.findIndex(p => near(p, px, py));
              if (i >= 0) drag.current = { kind: 'z', i };
              else setGeometry({ zone: [...g.zone, [px, py]] });
            } else if (g.wire) {
              if (near(g.wire.a, px, py)) drag.current = { kind: 'a' };
              else if (near(g.wire.b, px, py)) drag.current = { kind: 'b' };
            }
            if (drag.current) e.currentTarget.setPointerCapture(e.pointerId);
          }}
          onPointerMove={e => {
            const current = drag.current;
            if (!current) return;
            const p = pos(e);
            if (current.kind === 'z') setGeometry({ zone: g.zone.map((z, j) => (j === current.i ? p : z)) });
            else if (g.wire) setGeometry({ wire: { ...g.wire, [current.kind]: p } });
          }}
          onPointerUp={() => {
            drag.current = null;
          }}
        />
      </div>
      <p className="muted xs mt-4">
        {g.zone.length} zone points · tripwire{' '}
        {g.wire
          ? `inside is ${g.wire.inSide > 0 ? 'towards the camera (bottom of the image)' : 'away from the camera (top of the image)'}`
          : 'not set'}
      </p>
    </div>
  );
}

function CalibrationSection({ camera }: { camera: CameraView }) {
  const start = useStartCalibration(camera.code);
  const [runId, setRunId] = useState<number | null>(null);
  const run = useCalibration(camera.code, runId).data;
  const running = start.isPending || (runId !== null && (!run || run.status === 'RUNNING'));
  return (
    <div className="set-section">
      <div className="row between">
        <h3>Calibration</h3>
        <Button
          size="sm"
          icon="route"
          disabled={running}
          onClick={() => {
            start.mutate(undefined, {
              onSuccess: r => {
                setRunId(r.runId);
              },
              onError: error => toast(error.message, { type: 'error' }),
            });
          }}
        >
          Run a test walk
        </Button>
      </div>
      <p className="help">
        Walk in and out once. The camera should record IN when you face it and OUT when you walk away.
      </p>
      <div className="mt-8 small">
        {running ? (
          <span className="row gap-4">
            <Spinner dark />
            Walk towards the camera now, then away from it…
          </span>
        ) : run?.verdict === 'PASSED' ? (
          <Callout tone="ok" icon="checkCircle">
            <b>Test walk passed.</b> Towards the camera → <b>IN</b>. Away → <b>OUT</b>. No punches were created.
          </Callout>
        ) : run?.verdict === 'REVERSED' ? (
          <Callout tone="danger" icon="alertCircle">
            <b>Directions are reversed.</b> Walking towards the camera was read as OUT. Use “Flip inside direction”,
            save, and run the test walk again.
          </Callout>
        ) : run?.verdict === 'NO_CROSSINGS' ? (
          <Callout tone="warn" icon="alert">
            <b>No crossings were seen.</b> Walk through the doorway within a minute and try again.
          </Callout>
        ) : run ? (
          <Callout tone="warn" icon="alert">
            <b>The result is unclear.</b> Walk in and out once more, slowly, and try again.
          </Callout>
        ) : (
          <span className="muted">Not run yet</span>
        )}
      </div>
    </div>
  );
}

function Health({ code }: { code: string }) {
  const health = useCameraHealth(code).data;
  if (!health) return null;
  return (
    <div className="set-section">
      <h3>Health (7 days)</h3>
      <div className="stat-strip mb-12">
        <div className="stat">
          <div className="v">{health.uptimePct === null ? '—' : `${String(health.uptimePct)}%`}</div>
          <div className="l">Uptime</div>
        </div>
        <div className="stat">
          <div className="v">{health.reconnects}</div>
          <div className="l">Reconnects</div>
        </div>
        <div className="stat">
          <div className="v">{health.fps ?? '—'}</div>
          <div className="l">Frames per second</div>
        </div>
      </div>
      <ScrollArea className="dt-wrap" label="Camera health by day">
        <table className="dt compact">
          <thead>
            <tr>
              <th>Date</th>
              <th>Uptime</th>
              <th className="num">Reconnects</th>
            </tr>
          </thead>
          <tbody>
            {[...health.days].reverse().map(h => (
              <tr key={h.day}>
                <td>{fmtDate(h.day)}</td>
                <td className="num">{h.uptimePct === null ? '—' : `${String(h.uptimePct)}%`}</td>
                <td className="num">{h.reconnects}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </ScrollArea>
    </div>
  );
}
