/**
 * Settings → System: the services on the on-premises server and their status (refreshed every 15 s), the day-close
 * runs ("Run now" closes every shift day that is due; running it again does nothing), and About: versions, the
 * recognition models with their licences (only shown here, F6.8), diagnostics download and the last backup.
 */
import { Link } from 'react-router';
import { apiUrl } from '@/api/client';
import { useAbout, useDayCloseRuns, useRunDayClose, useSystemStatus, type StatusRow } from '@/api/settings';
import { ErrorState } from '@/components/feedback/EmptyState';
import { toast } from '@/components/feedback/toast';
import { ScrollArea } from '@/components/layout/ScrollArea';
import { Button, ButtonLink, DownloadButton } from '@/components/ui/Button';
import { KeyValueList } from '@/components/ui/KeyValueList';
import { SkeletonRows } from '@/components/ui/Skeleton';
import { fmtDate, fmtDateTime } from '@/lib/format';
import { plural } from '@/lib/text';
import '@/components/domain/HealthRow.module.css';

const DOT: Record<StatusRow['level'], string> = { OK: 'ok', WARN: 'warn', BAD: 'bad', DISABLED: 'off' };

export function SystemSettings() {
  const status = useSystemStatus();
  const runs = useDayCloseRuns().data;
  const about = useAbout().data;
  const all = status.data ?? [];
  const backup = all.find(r => r.key === 'backup');
  const disk = all.find(r => r.key === 'disk');
  /** Storage and the last backup are About facts, as in the prototype. */
  const rows = all.filter(r => r !== backup && r !== disk);
  const licences = new Map<string, string[]>();
  for (const m of about?.worker.models ?? []) licences.set(m.licence, [...(licences.get(m.licence) ?? []), m.name]);
  return (
    <div className="card pad">
      <h2>System</h2>
      <p className="muted small mt-4">Services running on the on-premises server. Status refreshes automatically.</p>
      <div className="set-section">
        <h3>Services</h3>
        {status.isError ? (
          <ErrorState what="the services" onRetry={() => void status.refetch()} />
        ) : !status.data ? (
          <SkeletonRows rows={8} columns={3} />
        ) : (
          <ScrollArea className="dt-wrap mt-8" label="Services">
            <table className="dt">
              <tbody>
                {rows.map(r => (
                  <tr key={r.key}>
                    <td>
                      <span className="row gap-8">
                        <span className={`sdot ${DOT[r.level]}`} />
                        <b>{r.label}</b>
                      </span>
                      {r.detail && <div className="muted xs">{r.detail}</div>}
                    </td>
                    <td className="small">{r.text}</td>
                    <td>
                      <ServiceAction row={r} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </ScrollArea>
        )}
      </div>
      <div className="set-section">
        <h3>Day close runs</h3>
        <ScrollArea className="dt-wrap mt-8" label="Day-close runs">
          <table className="dt compact">
            <thead>
              <tr>
                <th>Shift day</th>
                <th>Shift</th>
                <th>Ran</th>
                <th className="num">Employees</th>
                <th className="num">Absent</th>
                <th className="num">Missing check-outs</th>
              </tr>
            </thead>
            <tbody>
              {runs?.length ? (
                runs.map(r => (
                  <tr key={r.code}>
                    <td className="nowrap">{fmtDate(r.shiftDay)}</td>
                    <td>{r.shiftName}</td>
                    <td className="nowrap small">{fmtDateTime(r.ranAt)}</td>
                    <td className="num">{r.employees}</td>
                    <td className="num">{r.absent}</td>
                    <td className="num">
                      {r.missingCheckOuts ? (
                        <Link to={`/attendance/log?day=${r.shiftDay}`}>{r.missingCheckOuts}</Link>
                      ) : (
                        0
                      )}
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={6} className="muted">
                    No runs yet.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </ScrollArea>
      </div>
      <div className="set-section">
        <h3>About</h3>
        <KeyValueList
          pairs={[
            ['Version', about ? `KRZ Attend ${about.appVersion}` : '—'],
            [
              'Models',
              about?.worker.available
                ? about.worker.models.map((m, i) => (
                    <span key={m.name}>
                      {i > 0 && <br />}
                      {m.name} {m.version}
                    </span>
                  ))
                : 'Not available — the recognition worker did not answer',
            ],
            [
              'Licence',
              licences.size
                ? [...licences.entries()].map(([licence, names]) => `${licence} (${names.join(', ')})`).join(' · ')
                : '—',
            ],
            ['Storage', disk?.text ?? '—'],
          ]}
        />
        <div className="row wrap gap-8 mt-12">
          <DownloadButton size="sm" icon="download" href={apiUrl('/system/diagnostics')}>
            Download diagnostics
          </DownloadButton>
          <Button
            size="sm"
            icon="server"
            onClick={() => {
              toast(backup ? `${backup.label}: ${backup.text}` : 'Backup status unknown', {
                type: backup?.level === 'OK' ? 'info' : 'warn',
              });
            }}
          >
            Backups
          </Button>
        </div>
      </div>
    </div>
  );
}

function ServiceAction({ row }: { row: StatusRow }) {
  const run = useRunDayClose();
  if (row.key.startsWith('camera:')) {
    return (
      <ButtonLink size="sm" to={`/settings/cameras?edit=${encodeURIComponent(row.key.slice('camera:'.length))}`}>
        Open
      </ButtonLink>
    );
  }
  if (row.key === 'day-close') {
    return (
      <Button
        size="sm"
        pending={run.isPending}
        onClick={() => {
          run.mutate(undefined, {
            onSuccess: closed => {
              const shifts = new Set(closed.map(c => `${c.shiftCode}|${c.shiftDay}`)).size;
              toast(
                shifts
                  ? `Day close ran for ${plural(shifts, 'shift')}`
                  : 'Nothing to close — every shift whose day has ended is already closed',
                { type: shifts ? 'success' : 'info', logged: shifts > 0 },
              );
            },
            onError: error => toast(error.message, { type: 'error' }),
          });
        }}
      >
        Run now
      </Button>
    );
  }
  if (row.key === 'hrms') {
    return (
      <ButtonLink size="sm" to="/settings/hrms">
        Outbox
      </ButtonLink>
    );
  }
  if (row.key === 'directory') {
    return (
      <ButtonLink size="sm" to="/settings/signin">
        Open
      </ButtonLink>
    );
  }
  return null;
}
