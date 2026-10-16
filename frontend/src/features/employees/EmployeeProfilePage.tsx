/**
 * The prototype's employee profile (/employees/:id): the header with face, tracking and account chips, Enrol / Edit
 * employment and the More menu (deactivate, reactivate, erase, copy object ID), and four tabs (?tab=): Overview
 * (Entra identity and local employment), Face profile & consent, Attendance (last 14 days) and Activity.
 */
import { useState } from 'react';
import { useParams, useSearchParams } from 'react-router';
import { ApiError } from '@/api/client';
import { useEmployee, useFaceAction, type EmployeeProfile } from '@/api/employees';
import { useDetailDrawers } from '@/app/DetailDrawers';
import {
  AccountChip,
  AttendanceStatusChip,
  ConsentChip,
  FaceStatusChip,
  TrackingChip,
} from '@/components/domain/Chips';
import { SourceTag } from '@/components/domain/HrmsBanner';
import { EmployeeIdText } from '@/components/domain/PersonCell';
import { useConfirm } from '@/components/feedback/ConfirmDialog';
import { EmptyState, ErrorState } from '@/components/feedback/EmptyState';
import { toast } from '@/components/feedback/toast';
import { Icon } from '@/components/icons/Icon';
import { Breadcrumbs } from '@/components/layout/Breadcrumbs';
import { Card } from '@/components/layout/Card';
import { ScrollArea } from '@/components/layout/ScrollArea';
import { Avatar } from '@/components/ui/Avatar';
import { Button, ButtonLink, DownloadButton } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { Chip, Tag } from '@/components/ui/Chip';
import { IconButton } from '@/components/ui/IconButton';
import { KeyValueList } from '@/components/ui/KeyValueList';
import { MenuItem, MenuSeparator, Popover } from '@/components/ui/Popover';
import { Skeleton } from '@/components/ui/Skeleton';
import { Tabs } from '@/components/ui/Tabs';
import { tip } from '@/components/ui/Tooltip';
import { copyText } from '@/lib/clipboard';
import { fmtDate, fmtDateTime, fmtDay, fmtDuration, fmtTime } from '@/lib/format';
import { useCan, useNeedsPermissionText } from '@/lib/permissions';
import { usePageTitle } from '@/lib/usePageTitle';
import {
  CONSENT_METHODS,
  CONSENT_STATUS,
  DEACTIVATE_REASONS,
  EMPLOYMENT_TYPES,
  ERASE_REASONS,
  TEMPLATE_POSES,
  TEMPLATE_SOURCES,
  term,
} from '@/lib/vocabulary';
import { EmploymentEditor } from './EmploymentEditor';
import '@/components/domain/CropImage.module.css';
import '@/components/domain/Timeline.module.css';
import '@/components/data/DataTable.module.css';

type Tab = 'overview' | 'face' | 'attendance' | 'activity';
const TABS = [
  { key: 'overview', label: 'Overview' },
  { key: 'face', label: 'Face profile & consent' },
  { key: 'attendance', label: 'Attendance' },
  { key: 'activity', label: 'Activity' },
] as const;
const isTab = (value: string | null): value is Tab => TABS.some(t => t.key === value);

const ACTIVE_FACE = new Set(['ENROLLED', 'NEEDS_REENROL']);
const DASH = '—';

export function EmployeeProfilePage() {
  const { id = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const query = useEmployee(id);
  const profile = query.data;
  usePageTitle(profile?.identity.displayName ?? (query.error ? 'Employee not found' : undefined));

  if (query.isPending) {
    return (
      <div className="card pad">
        <Skeleton className="sk-line" width="40%" />
      </div>
    );
  }
  if (!profile) {
    return query.error instanceof ApiError && query.error.status === 404 ? (
      <div className="card">
        <EmptyState
          icon="user"
          title="Employee not found"
          text="They may have been removed from the directory."
          action={<ButtonLink to="/employees">Back to employees</ButtonLink>}
        />
      </div>
    ) : (
      <div className="card">
        <ErrorState what="this employee" onRetry={() => void query.refetch()} />
      </div>
    );
  }

  const param = params.get('tab');
  const tab: Tab = isTab(param) ? param : 'overview';
  return (
    <Profile
      profile={profile}
      tab={tab}
      onTab={next => {
        setParams(
          p => {
            p.set('tab', next);
            return p;
          },
          { replace: true },
        );
      }}
    />
  );
}

function Profile({ profile: p, tab, onTab }: { profile: EmployeeProfile; tab: Tab; onTab: (tab: Tab) => void }) {
  const canEnrol = useCan('people.enrol');
  const canEdit = useCan('settings.attendance');
  const [editing, setEditing] = useState(false);
  const name = p.identity.displayName;
  const x = p.employment;
  return (
    <>
      <Breadcrumbs crumbs={[{ label: 'Employees', to: '/employees' }, { label: name }]} />
      <div className="page-head">
        <div className="row gap-12">
          <Avatar id={p.id} name={name} size="xl" />
          <div>
            <h1>{name}</h1>
            <div className="sub">
              {x.designation ?? DASH} · {x.department?.name ?? DASH} · <EmployeeIdText employeeId={x.employeeId} />
            </div>
            <div className="row wrap gap-4 mt-8">
              <FaceStatusChip status={p.face.status} />
              <TrackingChip
                tracked={x.trackingMode === 'TRACKED' && p.identity.accountEnabled}
                label={x.trackingLabel}
              />
              <AccountChip enabled={p.identity.accountEnabled} reason={p.identity.disabledReason} />
            </div>
          </div>
        </div>
        <div className="page-actions">
          {p.identity.accountEnabled && canEnrol && p.face.status !== 'DEACTIVATED' && (
            <ButtonLink variant="primary" icon="scanface" to={`/enrol?employee=${p.id}`}>
              {p.face.templates.length > 0 ? 'Re-enrol' : 'Enrol face'}
            </ButtonLink>
          )}
          {canEdit && (
            <Button
              icon="edit"
              onClick={() => {
                setEditing(true);
              }}
            >
              Edit employment
            </Button>
          )}
          <MoreMenu profile={p} />
        </div>
      </div>
      <Tabs items={TABS} active={tab} onSelect={onTab} label="Employee profile" />
      <div>
        {tab === 'overview' && (
          <OverviewTab
            profile={p}
            onEdit={() => {
              setEditing(true);
            }}
          />
        )}
        {tab === 'face' && <FaceTab profile={p} />}
        {tab === 'attendance' && <AttendanceTab profile={p} />}
        {tab === 'activity' && <ActivityTab profile={p} />}
      </div>
      {editing && (
        <EmploymentEditor
          profile={p}
          onClose={() => {
            setEditing(false);
          }}
        />
      )}
    </>
  );
}

/** The prototype's emp-more menu: each action disabled with its reason when it can't run. */
function MoreMenu({ profile: p }: { profile: EmployeeProfile }) {
  const [open, setOpen] = useState(false);
  const allowed: Record<'people.deactivate' | 'people.erase', boolean> = {
    'people.deactivate': useCan('people.deactivate'),
    'people.erase': useCan('people.erase'),
  };
  const needs = useNeedsPermissionText();
  const confirm = useConfirm();
  const action = useFaceAction(p.id);
  const name = p.identity.displayName;
  const first = name.split(' ')[0] ?? name;

  const run = (body: Parameters<typeof action.mutate>[0], done: string): void => {
    action.mutate(body, {
      onSuccess: () => toast(done, { type: 'success', logged: true }),
      onError: error => toast(error.message, { type: 'error' }),
    });
  };
  const deactivate = async (): Promise<void> => {
    const result = await confirm({
      title: `Deactivate ${first}’s face profile?`,
      message: (
        <>
          The camera stops recognising them. Templates are kept, so you can reactivate later. Attendance tracking
          switches to <b>Not tracked — No face profile</b>; HR records their attendance in KRZ HRMS meanwhile.
        </>
      ),
      reasons: DEACTIVATE_REASONS,
      note: 'Note (required for Other)',
      confirmText: 'Deactivate',
    });
    if (result) {
      run(
        { kind: 'deactivate', reason: result.reason, note: result.note.trim() || null },
        `${name}’s face profile deactivated`,
      );
    }
  };
  const erase = async (): Promise<void> => {
    const result = await confirm({
      title: 'Erase biometric data permanently?',
      danger: true,
      message: (
        <>
          All {p.face.templates.length} face templates and stored crops for <b>{name}</b> are deleted. This can’t be
          undone. Their attendance history (punches) is kept.
        </>
      ),
      reasons: ERASE_REASONS,
      note: 'Note (required for Other)',
      typed: 'ERASE',
      confirmText: 'Erase permanently',
    });
    if (result) {
      run(
        { kind: 'erase', reason: result.reason, note: result.note.trim() || null },
        `Biometric data erased for ${name}`,
      );
    }
  };

  const item = (
    permission: keyof typeof allowed,
    icon: 'refresh' | 'ban' | 'trash',
    label: string,
    ok: boolean,
    why: string,
    onSelect: () => void,
    danger = false,
  ) => {
    const reason = !allowed[permission] ? needs(permission) : !ok ? why : null;
    return reason ? (
      <MenuItem icon={icon} disabled {...tip(reason)}>
        {label}
      </MenuItem>
    ) : (
      <MenuItem
        icon={icon}
        danger={danger}
        onClick={() => {
          setOpen(false);
          onSelect();
        }}
      >
        {label}
      </MenuItem>
    );
  };

  return (
    <Popover
      align="right"
      open={open}
      onOpenChange={setOpen}
      trigger={<IconButton icon="more" bordered label="More actions" aria-haspopup="true" />}
    >
      {p.face.status === 'DEACTIVATED'
        ? item(
            'people.deactivate',
            'refresh',
            'Reactivate face profile',
            p.identity.accountEnabled && p.face.templates.length > 0 && p.consent.status === 'GIVEN',
            'Needs an enabled account, templates and consent',
            () => {
              run({ kind: 'reactivate' }, `${name}’s face profile reactivated`);
            },
          )
        : item(
            'people.deactivate',
            'ban',
            'Deactivate face profile',
            ACTIVE_FACE.has(p.face.status),
            'No active face profile',
            () => void deactivate(),
          )}
      {item(
        'people.erase',
        'trash',
        'Erase biometric data…',
        p.face.templates.length > 0,
        'Nothing to erase',
        () => void erase(),
        true,
      )}
      <MenuSeparator />
      <MenuItem
        icon="copy"
        onClick={() => {
          setOpen(false);
          void copyText(p.identity.objectId);
        }}
      >
        Copy object ID
      </MenuItem>
    </Popover>
  );
}

function OverviewTab({ profile: p, onEdit }: { profile: EmployeeProfile; onEdit: () => void }) {
  const { openAllocation } = useDetailDrawers();
  const sensitive = useCan('people.view.sensitive');
  const canEdit = useCan('settings.attendance');
  const i = p.identity;
  const x = p.employment;
  const shift = p.shiftToday;
  return (
    <div className="grid grid-2">
      <Card
        title={
          <>
            <Icon name="lock" size="sm" /> Identity
          </>
        }
        actions={<Tag>Microsoft Entra ID · read-only</Tag>}
      >
        <KeyValueList
          pairs={[
            ['Display name', i.displayName],
            [
              'Work email',
              sensitive ? (
                i.email
              ) : (
                <span className="muted" title="Needs the “View sensitive data” permission">
                  Hidden
                </span>
              ),
            ],
            ['Object ID', <span className="mono xs">{i.objectId}</span>],
            ['Tenant ID', <span className="mono xs">{i.tenantId}</span>],
            [
              'Account',
              i.accountEnabled
                ? 'Enabled'
                : `Disabled — ${i.disabledReason ?? ''}${i.leftOn ? ` (since ${fmtDate(i.leftOn)})` : ''}`,
            ],
            [
              'Last sign-in to KRZ Attend',
              i.lastSignInAt ? fmtDateTime(i.lastSignInAt) : i.hasAccess ? 'Never' : 'No access to KRZ Attend',
            ],
            ['Synced', fmtDateTime(i.syncedAt)],
          ]}
        />
        <p className="muted small mt-12">Change these in Microsoft Entra ID; the next sync picks them up.</p>
      </Card>
      <Card title="Employment" actions={<SourceTag />}>
        <KeyValueList
          pairs={[
            ['Employee ID', <EmployeeIdText employeeId={x.employeeId} />],
            ['Department', x.department?.name ?? DASH],
            ['Designation', x.designation ?? DASH],
            ['Type', term(EMPLOYMENT_TYPES, x.employmentType) ?? x.employmentType ?? DASH],
            ['Location', x.location?.name ?? DASH],
            ['Manager', x.manager?.name ?? DASH],
            ['Joined', fmtDate(x.joinDate)],
            x.endDate ? ['Contract ends', fmtDate(x.endDate)] : null,
            [
              'Shift today',
              shift ? (
                <>
                  <span className="row gap-4">
                    <span className="sw" style={{ background: shift.colour }} />
                    {shift.name} · {shift.timing}
                  </span>
                  <div className="muted xs">{shift.source}</div>
                </>
              ) : (
                DASH
              ),
            ],
            ['Attendance tracking', x.trackingLabel],
          ]}
        />
        {canEdit && (
          <div className="row wrap gap-8 mt-12">
            <Button size="sm" icon="edit" onClick={onEdit}>
              Edit employment
            </Button>
            <Button
              size="sm"
              icon="route"
              onClick={() => {
                openAllocation({ targetType: 'EMPLOYEE', targetIds: [p.id] });
              }}
            >
              Allocate a shift
            </Button>
          </div>
        )}
      </Card>
    </div>
  );
}

function FaceTab({ profile: p }: { profile: EmployeeProfile }) {
  const sensitive = useCan('people.view.sensitive');
  const canEnrol = useCan('people.enrol');
  const needs = useNeedsPermissionText();
  const f = p.face;
  const c = p.consent;
  const erased = f.status === 'ERASED';
  return (
    <div className="grid grid-2">
      <Card title="Face profile" actions={<FaceStatusChip status={f.status} />}>
        {f.reason && (
          <Callout tone="warn" icon="alert" className="mb-12 small">
            {f.reason}
          </Callout>
        )}
        {f.scheduledErasureOn && (
          <Callout tone="neutral" icon="clock" className="mb-12 small">
            Templates are erased automatically on <b>{fmtDate(f.scheduledErasureOn)}</b>.
          </Callout>
        )}
        {f.templates.length > 0 ? (
          <div className="row wrap gap-12">
            {f.templates.map(t => (
              <div key={t.id} className="col" style={{ gap: 4, width: 96 }}>
                {t.thumbnailUrl ? (
                  <span className="crop lg">
                    <img src={t.thumbnailUrl} alt={`${p.identity.displayName} template`} />
                  </span>
                ) : (
                  <span className="crop lg blurred" aria-label="Blurred face template" />
                )}
                <span className="xs">
                  <b>{t.pose ? (term(TEMPLATE_POSES, t.pose) ?? t.pose) : 'Upload'}</b> · Q{t.quality ?? DASH}
                </span>
                <span className="xs muted">
                  {fmtDate(t.createdAt)} · {term(TEMPLATE_SOURCES, t.source) ?? t.source}
                </span>
              </div>
            ))}
          </div>
        ) : (
          <EmptyState
            icon="scanface"
            title={erased ? 'Biometric data erased' : 'No face templates'}
            text={
              erased ? 'Templates and crops were permanently deleted.' : 'Enrol a face to track attendance by camera.'
            }
            action={
              canEnrol && p.identity.accountEnabled ? (
                <ButtonLink to={`/enrol?employee=${p.id}`}>Enrol face</ButtonLink>
              ) : undefined
            }
          />
        )}
      </Card>
      <Card title="Consent" actions={<ConsentChip status={c.status} />}>
        {sensitive ? (
          <KeyValueList
            pairs={[
              ['Status', term(CONSENT_STATUS, c.status)?.label ?? c.status],
              ['Date', fmtDate(c.withdrawnOn ?? c.givenOn)],
              ['Method', c.method ? (term(CONSENT_METHODS, c.method) ?? c.method) : DASH],
              ['Notice version', c.noticeVersion ? `v${c.noticeVersion}` : DASH],
              ['Recorded by', c.recordedBy ?? DASH],
              [
                'Signed form',
                c.documentUrl ? (
                  <DownloadButton size="sm" variant="ghost" icon="file" href={c.documentUrl}>
                    {c.documentName}
                  </DownloadButton>
                ) : (
                  DASH
                ),
              ],
            ]}
          />
        ) : (
          <p className="muted small">{needs('people.view.sensitive')} to see the consent record.</p>
        )}
        <p className="small muted mt-12">Without consent, HR records this employee’s attendance in KRZ HRMS instead.</p>
      </Card>
    </div>
  );
}

function AttendanceTab({ profile: p }: { profile: EmployeeProfile }) {
  const needs = useNeedsPermissionText();
  const { openDay } = useDetailDrawers();
  const days = p.lastDays;
  if (!days) {
    return (
      <div className="card">
        <EmptyState icon="lock" title="Attendance is hidden" text={`${needs('attendance.view.all')}.`} />
      </div>
    );
  }
  return (
    <Card
      title="Last 14 days"
      flush
      actions={
        <ButtonLink size="sm" icon="list" to={`/events?employee=${p.id}`}>
          All crossings
        </ButtonLink>
      }
    >
      {days.length > 0 ? (
        <ScrollArea className="dt-wrap" label="Attendance">
          <table className="dt">
            <thead>
              <tr>
                <th>Shift day</th>
                <th>Shift</th>
                <th>Status</th>
                <th>Check-in</th>
                <th>Check-out</th>
                <th className="num">Worked</th>
              </tr>
            </thead>
            <tbody>
              {days.map(d => (
                <tr
                  key={d.day}
                  className="clickable"
                  tabIndex={0}
                  onClick={() => {
                    openDay(p.id, d.day);
                  }}
                  onKeyDown={event => {
                    if (event.key === 'Enter' && event.target === event.currentTarget) openDay(p.id, d.day);
                  }}
                >
                  <td className="nowrap">{fmtDay(d.day)}</td>
                  <td>{d.shiftName}</td>
                  <td>
                    <AttendanceStatusChip
                      status={d.status}
                      tentative={d.status === 'CHECKED_OUT' && !d.finalised}
                      late={d.late}
                    />
                  </td>
                  <td className="num">{d.checkIn ? fmtTime(d.checkIn) : DASH}</td>
                  <td>
                    {d.checkOut ? (
                      <>
                        <span className="num nowrap">{fmtTime(d.checkOut)}</span>
                        {!d.checkOutFinal && <div className="muted xs">tentative</div>}
                      </>
                    ) : d.missingCheckOut ? (
                      <Chip tone="purple">Missing</Chip>
                    ) : (
                      <span className="muted">{DASH}</span>
                    )}
                  </td>
                  <td className="num">{fmtDuration(d.workedMinutes)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </ScrollArea>
      ) : (
        <EmptyState icon="calendar" title="No attendance yet" />
      )}
    </Card>
  );
}

function ActivityTab({ profile: p }: { profile: EmployeeProfile }) {
  return (
    <Card>
      {p.activity.length > 0 ? (
        <div className="timeline">
          {p.activity.map((a, n) => {
            const detail = a.before || a.after ? `${a.before ? `${a.before} → ` : ''}${a.after ?? ''}` : a.detail;
            return (
              <div key={`${a.at}-${n}`} className="tl-item">
                <div className="small">
                  <b>{a.action}</b> · {a.by}
                </div>
                <div className="muted xs">
                  {fmtDateTime(a.at)}
                  {detail ? ` · ${detail}` : ''}
                </div>
              </div>
            );
          })}
        </div>
      ) : (
        <EmptyState icon="history" title="No activity yet" />
      )}
    </Card>
  );
}
