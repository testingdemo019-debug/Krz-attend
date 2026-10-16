/**
 * Settings → Users & roles: people sign in with their Microsoft work account; what they can do comes from the roles
 * given here. Three tabs (?tab=): Users (the shared table), Roles (cards and the role editor) and Access requests.
 * There must always be a System Administrator, and nobody can remove their own administrator role (the server checks
 * both; its message is shown).
 */
import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { apiUrl } from '@/api/client';
import { useMe, usePermissionCatalogue } from '@/api/me';
import {
  useAccessIds,
  useAccessRequests,
  useDecideRequest,
  useDeleteRole,
  useRemoveAccess,
  useRoles,
  useSaveRole,
  useSetRoles,
  useUnsynced,
  useUserFacets,
  useUsers,
  type AccessRequestView,
  type RoleView,
  type UserView,
} from '@/api/settings';
import { DataTable, type Column } from '@/components/data/DataTable';
import { useTableState, type SortState, type TableFilter } from '@/components/data/tableState';
import { PersonCell } from '@/components/domain/PersonCell';
import { useConfirm } from '@/components/feedback/ConfirmDialog';
import { Drawer } from '@/components/feedback/Drawer';
import { EmptyState, ErrorState } from '@/components/feedback/EmptyState';
import { Modal } from '@/components/feedback/Modal';
import { toast } from '@/components/feedback/toast';
import { EmployeePicker } from '@/components/form/EmployeePicker';
import { Icon } from '@/components/icons/Icon';
import { Button } from '@/components/ui/Button';
import { Callout } from '@/components/ui/Callout';
import { Chip } from '@/components/ui/Chip';
import { SkeletonRows } from '@/components/ui/Skeleton';
import { Tabs } from '@/components/ui/Tabs';
import { exportFileName, fmtDate, fmtDateTime } from '@/lib/format';
import { navigationPreview } from '@/lib/nav';
import type { PermissionKey } from '@/lib/permissions';
import { plural } from '@/lib/text';
import { useNow } from '@/lib/useNow';
import '@/components/form/Field.module.css';

const SYSTEM_ADMIN = 'ROLE-SYSADMIN';
const ENROLLER = 'ROLE-ENROLLER';
const DECLINE_REASONS = [
  'Not needed for their job',
  'Ask your manager to request it',
  'Use KRZ HRMS for your own attendance',
  'Other',
] as const;

type Tab = 'users' | 'roles' | 'requests';
const TABS: readonly Tab[] = ['users', 'roles', 'requests'];
const DEFAULT_SORT: SortState = { key: 'user', dir: 'asc' };

/** Someone to give roles to: from the table, the picker or an access request. */
interface Person {
  id: string;
  name: string;
  email: string | null;
}

interface Assigning {
  person: Person;
  current: string[];
  /** The access entry's version; null gives access. */
  version: number | null;
  request?: AccessRequestView;
}

/** The role editor: an existing role, or a new one (possibly a duplicate's copy). */
interface Editing {
  role: RoleView | null;
  preset?: { name: string; description: string; permissions: string[] };
}

export function UsersSettings() {
  const [params, setParams] = useSearchParams();
  const tab: Tab = TABS.find(t => t === params.get('tab')) ?? 'users';
  const roles = useRoles();
  const requests = useAccessRequests(true);
  const facets = useUserFacets();
  const [picking, setPicking] = useState(false);
  const [assigning, setAssigning] = useState<Assigning | null>(null);
  const [editing, setEditing] = useState<Editing | null>(null);
  const pending = (requests.data ?? []).filter(r => r.status === 'PENDING').length;
  const userCount = (facets.data?.account ?? []).reduce((sum, o) => sum + o.count, 0);

  const giveAccess = (
    <Button
      variant="primary"
      icon="userPlus"
      onClick={() => {
        setPicking(true);
      }}
    >
      Give access
    </Button>
  );
  return (
    <div className="card pad">
      <div className="row between wrap gap-8">
        <div>
          <h2>Users & roles</h2>
          <p className="muted small mt-4">
            People sign in with their Microsoft work account. What they can do comes from the roles you give them here.
          </p>
        </div>
        <div className="row gap-8">
          {tab === 'roles' ? (
            <Button
              variant="primary"
              icon="plus"
              onClick={() => {
                setEditing({ role: null });
              }}
            >
              New role
            </Button>
          ) : (
            giveAccess
          )}
        </div>
      </div>
      <div className="mt-12">
        <Tabs
          label="Users and roles"
          items={[
            { key: 'users', label: 'Users', count: userCount },
            { key: 'roles', label: 'Roles', count: roles.data?.length ?? 0 },
            { key: 'requests', label: 'Access requests', count: pending },
          ]}
          active={tab}
          onSelect={next => {
            setParams({ tab: next }, { replace: true });
          }}
        />
      </div>
      {tab === 'users' && (
        <UsersTable
          giveAccess={giveAccess}
          onRoles={u => {
            setAssigning({
              person: { id: u.employeeId, name: u.displayName, email: u.email },
              current: u.roles.map(r => r.code),
              version: u.version,
            });
          }}
        />
      )}
      {tab === 'roles' &&
        (roles.isError ? (
          <ErrorState what="the roles" onRetry={() => void roles.refetch()} />
        ) : (
          <RolesGrid roles={roles.data} onEdit={setEditing} />
        ))}
      {tab === 'requests' && (
        <RequestsTable
          requests={requests.data}
          onApprove={r => {
            setAssigning({
              person: { id: r.employeeId, name: r.displayName, email: null },
              current: [ENROLLER],
              version: null,
              request: r,
            });
          }}
        />
      )}
      <GiveAccessPicker
        open={picking}
        onClose={() => {
          setPicking(false);
        }}
        onPick={person => {
          setPicking(false);
          setAssigning({ person, current: [], version: null });
        }}
      />
      {assigning && (
        <RoleAssignDialog
          assigning={assigning}
          roles={roles.data ?? []}
          onClose={() => {
            setAssigning(null);
          }}
        />
      )}
      {editing && (
        <RoleEditor
          editing={editing}
          holders={editing.role?.users ?? 0}
          onClose={() => {
            setEditing(null);
          }}
        />
      )}
    </div>
  );
}

function GiveAccessPicker({
  open,
  onClose,
  onPick,
}: {
  open: boolean;
  onClose: () => void;
  onPick: (person: Person) => void;
}) {
  const has = useAccessIds(open).data;
  return (
    <EmployeePicker
      open={open}
      title="Give access to KRZ Attend"
      sub="Choose someone from the directory. Only people with a role can sign in."
      onClose={onClose}
      hide={e => has?.has(e.id) ?? false}
      onPick={e => {
        onPick({ id: e.id, name: e.name, email: e.email ?? null });
      }}
    />
  );
}

function UsersTable({ giveAccess, onRoles }: { giveAccess: React.ReactNode; onRoles: (u: UserView) => void }) {
  const now = useNow(60_000);
  const me = useMe().data?.id;
  const confirm = useConfirm();
  const remove = useRemoveAccess();
  const facets = useUserFacets().data;
  const filters = useMemo<readonly TableFilter[]>(
    () => [
      { key: 'role', label: 'Role', options: facets?.role ?? [] },
      { key: 'account', label: 'Account', options: facets?.account ?? [] },
    ],
    [facets],
  );
  const control = useTableState(filters, DEFAULT_SORT);
  const { state } = control;
  const filterQuery = { role: state.values.role ?? [], account: state.values.account ?? [], q: state.q };
  const sort = state.sort ? `${state.sort.key},${state.sort.dir}` : null;
  const result = useUsers({ ...filterQuery, sort, page: state.page - 1, size: state.size });
  const unsynced = useUnsynced().data;

  const columns = useMemo<readonly Column<UserView>[]>(() => {
    const removeAccess = async (u: UserView) => {
      const ok = await confirm({
        title: `Remove ${u.displayName}’s access?`,
        message: 'They are signed out at their next click and can’t sign in again until someone gives them a role.',
        confirmText: 'Remove access',
        danger: true,
      });
      if (!ok) return;
      remove.mutate(u.employeeId, {
        onSuccess: () => toast(`${u.displayName} no longer has access`, { type: 'success', logged: true }),
        onError: error => toast(error.message, { type: 'error' }),
      });
    };
    return [
      {
        key: 'user',
        label: 'User',
        sort: 'user',
        lock: true,
        render: u => (
          <>
            <PersonCell id={u.employeeId} name={u.displayName} sub={u.email ?? u.designation ?? ''} />
            {!u.accountEnabled && (
              <>
                {' '}
                <Chip tone="grey">Account disabled — can’t sign in</Chip>
              </>
            )}
            {u.employeeId === me && (
              <>
                {' '}
                <span className="tag">You</span>
              </>
            )}
          </>
        ),
      },
      {
        key: 'roles',
        label: 'Roles',
        sort: 'roles',
        render: u => (
          <>
            {u.roles.map((r, i) => (
              <span key={r.code}>
                {i > 0 && ' '}
                <span className="role-badge">{r.name}</span>
              </span>
            ))}
          </>
        ),
      },
      {
        key: 'last',
        label: 'Last sign-in',
        sort: 'last',
        render: u => <span className="small nowrap">{u.lastSignInAt ? fmtDateTime(u.lastSignInAt) : 'Never'}</span>,
      },
      {
        key: 'given',
        label: 'Access given',
        sort: 'given',
        render: u => (
          <>
            <span className="small">{u.addedBy}</span>
            <div className="muted xs">{fmtDate(u.addedAt)}</div>
          </>
        ),
      },
      {
        key: 'act',
        label: '',
        lock: true,
        render: u => (
          <span className="row gap-4 nowrap">
            <Button
              size="sm"
              icon="edit"
              aria-label={`Change roles for ${u.displayName}`}
              onClick={() => {
                onRoles(u);
              }}
            >
              Roles
            </Button>
            <Button
              size="sm"
              variant="ghost"
              icon="userMinus"
              aria-label={`Remove access for ${u.displayName}`}
              onClick={() => void removeAccess(u)}
            >
              Remove
            </Button>
          </span>
        ),
      },
    ];
  }, [confirm, me, onRoles, remove]);

  return (
    <div className="mt-12">
      <DataTable
        id="users"
        control={control}
        filters={filters}
        columns={columns}
        rowKey={u => u.employeeId}
        result={result}
        noun="users"
        noun1="user"
        searchPlaceholder="Search users"
        empty={{ icon: 'users', title: 'No users yet', action: giveAccess }}
        exportCsv={{
          href: apiUrl('/settings/users/export', { ...filterQuery, sort }),
          fileName: exportFileName('users', now),
          permission: 'events.export',
        }}
      />
      <p className="muted small mt-8">
        Only people with a role can sign in. There must always be at least one System Administrator, and you can’t
        remove your own administrator role.
      </p>
      {unsynced && unsynced.accounts.length > 0 && (
        <Callout tone="neutral" icon="info" className="mt-12 small">
          {unsynced.accounts.map((u, i) => (
            <span key={`${u.name}-${String(i)}`}>
              {i > 0 && ', '}
              <b>{u.name}</b>
              {u.email ? ` (${u.email})` : ''}
            </span>
          ))}{' '}
          {unsynced.accounts.length === 1 ? 'is' : 'are'} in Microsoft Entra ID but not in a sync-scope group, so they
          can’t be given access yet. Add them to{' '}
          {unsynced.groups.length ? unsynced.groups.join(' or ') : 'a sync-scope group'} in Entra ID.
        </Callout>
      )}
    </div>
  );
}

function RolesGrid({ roles, onEdit }: { roles: readonly RoleView[] | undefined; onEdit: (e: Editing) => void }) {
  const catalogue = usePermissionCatalogue(true).data;
  const confirm = useConfirm();
  const remove = useDeleteRole();
  if (!roles) return <SkeletonRows rows={4} columns={2} />;
  const total = catalogue?.length ?? 0;
  const deleteRole = async (r: RoleView) => {
    const ok = await confirm({
      title: `Delete the ${r.name} role?`,
      danger: true,
      confirmText: 'Delete role',
      message: r.users
        ? `${plural(r.users, 'user')} ${r.users === 1 ? 'has' : 'have'} this role. Anyone with no other role loses access completely.`
        : 'No one has this role.',
    });
    if (!ok) return;
    remove.mutate(r.code, {
      onSuccess: () => toast(`${r.name} deleted`, { type: 'success', logged: true }),
      onError: error => toast(error.message, { type: 'error' }),
    });
  };
  return (
    <div className="grid grid-2 mt-12">
      {roles.map(r => (
        <div key={r.code} className="card pad">
          <div className="row between">
            <h3 className="row gap-8">
              <Icon name={r.locked ? 'lock' : 'shield'} size="sm" />
              {r.name}
            </h3>
            <span className="row gap-4">
              <span className="tag">{r.builtIn ? 'Built-in' : 'Custom'}</span>
              {r.locked && <span className="tag">Locked</span>}
            </span>
          </div>
          <p className="small muted mt-4">{r.description}</p>
          <div className="row wrap gap-8 mt-8 small">
            <span>
              <b>{r.permissions.length}</b> of {total} permissions
            </span>
            <span>·</span>
            <span>
              <b>{r.users}</b> {r.users === 1 ? 'user' : 'users'}
            </span>
          </div>
          <div className="row wrap gap-4 mt-8">
            {navigationPreview(r.permissions as PermissionKey[]).map(n => (
              <span key={n} className="tag">
                {n}
              </span>
            ))}
          </div>
          <div className="row gap-8 mt-12">
            <Button
              size="sm"
              icon={r.locked ? 'eye' : 'edit'}
              onClick={() => {
                onEdit({ role: r });
              }}
            >
              {r.locked ? 'View' : 'Edit'}
            </Button>
            <Button
              size="sm"
              icon="copy"
              onClick={() => {
                onEdit({
                  role: null,
                  preset: { name: `${r.name} (copy)`, description: r.description, permissions: [...r.permissions] },
                });
              }}
            >
              Duplicate
            </Button>
            {!r.builtIn && (
              <Button size="sm" variant="ghost" icon="trash" onClick={() => void deleteRole(r)}>
                Delete
              </Button>
            )}
          </div>
        </div>
      ))}
    </div>
  );
}

function RequestsTable({
  requests,
  onApprove,
}: {
  requests: readonly AccessRequestView[] | undefined;
  onApprove: (r: AccessRequestView) => void;
}) {
  const confirm = useConfirm();
  const decide = useDecideRequest();
  if (!requests) return <SkeletonRows rows={4} columns={5} />;
  if (requests.length === 0) {
    return (
      <EmptyState
        icon="inbox"
        title="No access requests"
        text="People who sign in without a role can ask for access from the sign-in screen."
      />
    );
  }
  const list = [...requests].sort(
    (a, b) =>
      (a.status === 'PENDING' ? 0 : 1) - (b.status === 'PENDING' ? 0 : 1) || b.createdAt.localeCompare(a.createdAt),
  );
  const decline = async (r: AccessRequestView) => {
    const result = await confirm({
      title: `Decline ${r.displayName.split(' ')[0] ?? r.displayName}’s request?`,
      message: 'They see the reason on the sign-in screen.',
      reasons: DECLINE_REASONS,
      note: 'Note (required for Other)',
      confirmText: 'Decline',
    });
    if (!result) return;
    decide.mutate(
      { code: r.code, reason: result.reason === 'Other' ? result.note : result.reason },
      {
        onSuccess: () => toast('Request declined', { type: 'success', logged: true }),
        onError: error => toast(error.message, { type: 'error' }),
      },
    );
  };
  return (
    <div className="dt-wrap mt-12">
      <table className="dt">
        <thead>
          <tr>
            <th>Person</th>
            <th>Message</th>
            <th>Received</th>
            <th>Status</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {list.map(r => (
            <tr key={r.code}>
              <td>
                <PersonCell
                  id={r.employeeId}
                  name={r.displayName}
                  sub={`${r.designation ?? '—'} · ${r.department ?? '—'}`}
                />
              </td>
              <td className="small">{r.note ?? '—'}</td>
              <td className="nowrap small">{fmtDateTime(r.createdAt)}</td>
              <td>
                {r.status === 'PENDING' ? (
                  <Chip tone="amber">Pending</Chip>
                ) : r.status === 'APPROVED' ? (
                  <>
                    <Chip tone="green">Approved</Chip>
                    <div className="muted xs">{r.decidedBy ?? ''}</div>
                  </>
                ) : (
                  <>
                    <Chip tone="grey">Declined</Chip>
                    <div className="muted xs">
                      {r.reason ?? ''} · {r.decidedBy ?? ''}
                    </div>
                  </>
                )}
              </td>
              <td className="nowrap">
                {r.status === 'PENDING' && (
                  <>
                    <Button
                      size="sm"
                      variant="primary"
                      onClick={() => {
                        onApprove(r);
                      }}
                    >
                      Approve…
                    </Button>{' '}
                    <Button size="sm" onClick={() => void decline(r)}>
                      Decline
                    </Button>
                  </>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** The roles of one person: give access, change roles, or approve a request. */
function RoleAssignDialog({
  assigning: { person, current, version, request },
  roles,
  onClose,
}: {
  assigning: Assigning;
  roles: readonly RoleView[];
  onClose: () => void;
}) {
  const me = useMe().data?.id;
  const setRoles = useSetRoles();
  const decide = useDecideRequest();
  const [picked, setPicked] = useState<readonly string[]>(current);
  const [serverError, setServerError] = useState('');
  const permissions = [
    ...new Set(roles.filter(r => picked.includes(r.code)).flatMap(r => r.permissions)),
  ] as PermissionKey[];
  const error = !picked.length
    ? current.length && !request
      ? 'Choose at least one role, or use Remove access instead.'
      : 'Choose at least one role.'
    : person.id === me && current.includes(SYSTEM_ADMIN) && !picked.includes(SYSTEM_ADMIN)
      ? 'You can’t remove your own System Administrator role. Ask another administrator.'
      : serverError;
  const names = roles.filter(r => picked.includes(r.code)).map(r => r.name);
  const done = () => {
    onClose();
    toast(`${person.name} now has: ${names.join(', ')}`, { type: 'success', logged: true });
  };
  const failed = (e: Error) => {
    setServerError(e.message);
  };
  const save = () => {
    if (request) decide.mutate({ code: request.code, roleIds: [...picked] }, { onSuccess: done, onError: failed });
    else
      setRoles.mutate({ employeeId: person.id, roleIds: [...picked], version }, { onSuccess: done, onError: failed });
  };
  return (
    <Modal
      open
      title={request ? `Approve access for ${person.name}` : `Roles for ${person.name}`}
      {...(person.email ? { sub: person.email } : {})}
      onClose={onClose}
      footer={
        <>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            disabled={Boolean(error)}
            pending={setRoles.isPending || decide.isPending}
            onClick={save}
          >
            {request ? 'Approve' : current.length ? 'Save roles' : 'Give access'}
          </Button>
        </>
      }
    >
      {request?.note && (
        <Callout tone="neutral" icon="info" className="mb-12 small">
          “{request.note}”
        </Callout>
      )}
      <div className="col">
        {roles.map(r => (
          <label
            key={r.code}
            className="check"
            style={{
              alignItems: 'flex-start',
              padding: '8px 10px',
              border: '1px solid var(--border)',
              borderRadius: 8,
            }}
          >
            <input
              type="checkbox"
              checked={picked.includes(r.code)}
              onChange={e => {
                setServerError('');
                setPicked(p => (e.target.checked ? [...p, r.code] : p.filter(c => c !== r.code)));
              }}
            />
            <span>
              <b>{r.name}</b>
              <br />
              <span className="muted small">{r.description}</span>
            </span>
          </label>
        ))}
      </div>
      <div className="err-text mt-8">{error}</div>
      <div className="mt-12">
        {picked.length > 0 && (
          <>
            <div className="small muted mb-4">They will see:</div>
            <div className="row wrap gap-4">
              {navigationPreview(permissions).map(n => (
                <span key={n} className="tag">
                  {n}
                </span>
              ))}
            </div>
          </>
        )}
      </div>
    </Modal>
  );
}

/** The role editor (wide drawer): name, description, permissions by group and the navigation preview. */
function RoleEditor({
  editing: { role, preset },
  holders,
  onClose,
}: {
  editing: Editing;
  holders: number;
  onClose: () => void;
}) {
  const catalogue = usePermissionCatalogue(true).data ?? [];
  const save = useSaveRole();
  const navigate = useNavigate();
  const locked = role?.locked ?? false;
  const [name, setName] = useState(role?.name ?? preset?.name ?? '');
  const [description, setDescription] = useState(role?.description ?? preset?.description ?? '');
  const [permissions, setPermissions] = useState<readonly string[]>(role?.permissions ?? preset?.permissions ?? []);
  const [nameError, setNameError] = useState('');
  const groups = [...new Set(catalogue.map(p => p.group))];

  const submit = () => {
    const trimmed = name.trim();
    if (!trimmed) {
      setNameError('Enter a name.');
      return;
    }
    setNameError('');
    if (!permissions.length) {
      toast('Give the role at least one permission', { type: 'error' });
      return;
    }
    save.mutate(
      {
        code: role?.code ?? null,
        version: role?.version ?? 0,
        name: trimmed,
        description: description.trim(),
        permissions: permissions as RoleView['permissions'] & PermissionKey[],
      },
      {
        onSuccess: () => {
          onClose();
          toast(
            role
              ? `${trimmed} saved${holders ? ` · ${plural(holders, 'user')} updated` : ''}`
              : `${trimmed} created — give it to users from the Users tab`,
            { type: 'success', logged: true },
          );
          if (!role) void navigate('/settings/users?tab=roles', { replace: true });
        },
        onError: error => {
          if (error.message.toLowerCase().includes('name')) setNameError(error.message);
          else toast(error.message, { type: 'error' });
        },
      },
    );
  };

  return (
    <Drawer
      open
      wide
      onClose={onClose}
      title={locked && role ? role.name : role ? `Edit ${role.name}` : 'New role'}
      sub={
        locked
          ? 'Locked: the System Administrator role always has every permission.'
          : 'Permissions decide what people with this role can see and do.'
      }
      footer={
        locked ? (
          <Button onClick={onClose}>Close</Button>
        ) : (
          <>
            <Button onClick={onClose}>Cancel</Button>
            <Button variant="primary" pending={save.isPending} onClick={submit}>
              {role ? 'Save role' : 'Create role'}
            </Button>
          </>
        )
      }
    >
      <div className="form-grid mb-16">
        <div className="field">
          <label htmlFor="re-name">Name</label>
          <input
            id="re-name"
            className="input"
            value={name}
            disabled={locked || Boolean(role?.builtIn)}
            onChange={e => {
              setName(e.target.value);
            }}
          />
          <div className="err-text">{nameError}</div>
        </div>
        <div className="field">
          <label htmlFor="re-desc">Description</label>
          <input
            id="re-desc"
            className="input"
            value={description}
            disabled={locked}
            onChange={e => {
              setDescription(e.target.value);
            }}
          />
        </div>
      </div>
      <div className="grid" style={{ gridTemplateColumns: 'minmax(0,1fr) 240px', gap: 16, alignItems: 'start' }}>
        <div>
          {groups.map(g => (
            <fieldset key={g} className="mb-12" style={{ border: 0, padding: 0, margin: '0 0 12px' }}>
              <legend className="small" style={{ fontWeight: 650, color: 'var(--text-2)', marginBottom: 6 }}>
                {g}
              </legend>
              {catalogue
                .filter(p => p.group === g)
                .map(p => (
                  <label key={p.key} className="check" style={{ alignItems: 'flex-start', padding: '6px 0' }}>
                    <input
                      type="checkbox"
                      checked={permissions.includes(p.key)}
                      disabled={locked}
                      onChange={e => {
                        setPermissions(current =>
                          e.target.checked ? [...current, p.key] : current.filter(k => k !== p.key),
                        );
                      }}
                    />
                    <span>
                      <b>{p.label}</b> <span className="mono xs muted">{p.key}</span>
                      <br />
                      <span className="muted small">{p.description}</span>
                    </span>
                  </label>
                ))}
            </fieldset>
          ))}
        </div>
        <div className="card pad" style={{ position: 'sticky', top: 0 }}>
          <h3>Navigation preview</h3>
          <p className="muted small mb-8">What someone with only this role sees.</p>
          <div className="col" style={{ gap: 4 }}>
            {navigationPreview(permissions as PermissionKey[]).map(n => (
              <span key={n} className="row gap-4 small">
                <Icon name="check" size="sm" />
                {n}
              </span>
            ))}
          </div>
          <p className="small mt-12">
            <b>{permissions.length}</b> of {catalogue.length} permissions
            {holders ? ` · ${plural(holders, 'user')} affected` : ''}
          </p>
        </div>
      </div>
    </Drawer>
  );
}
