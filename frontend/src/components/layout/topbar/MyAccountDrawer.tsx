import type { Me, PermissionView } from '@/api/me';
import { Drawer } from '@/components/feedback/Drawer';
import { Icon } from '@/components/icons/Icon';
import { Avatar } from '@/components/ui/Avatar';
import { Button } from '@/components/ui/Button';
import { RoleBadge, Tag } from '@/components/ui/Chip';
import { KeyValueList } from '@/components/ui/KeyValueList';
import { copyText } from '@/lib/clipboard';
import { fmtDateTime } from '@/lib/format';

/** The prototype's showMyAccount: identity from Microsoft, access from KRZ Attend, and the session. */
export function MyAccountDrawer({
  me,
  catalogue,
  open,
  onClose,
  onSignOut,
}: {
  me: Me;
  catalogue: readonly PermissionView[];
  open: boolean;
  onClose: () => void;
  onSignOut: () => void;
}) {
  const labels = new Map(catalogue.map(p => [p.key, p.label]));
  const permissions = [...me.permissions].sort();
  const placement = [me.designation, me.department].filter(Boolean).join(' · ');
  return (
    <Drawer
      open={open}
      onClose={onClose}
      title="My account"
      sub="Your identity comes from Microsoft. Your roles come from KRZ Attend."
      footer={
        <Button icon="logout" iconSize="default" onClick={onSignOut}>
          Sign out
        </Button>
      }
    >
      <div className="row gap-12 mb-16">
        <Avatar id={me.id} name={me.name} size="lg" />
        <div>
          <div style={{ fontWeight: 650, fontSize: 16 }}>{me.name}</div>
          {placement && <div className="muted small">{placement}</div>}
        </div>
      </div>
      <h3 className="mb-8">Identity (Microsoft Entra ID)</h3>
      <KeyValueList
        pairs={[
          ['Name', me.name],
          ['Work email', me.email],
          [
            'Object ID',
            <>
              <span className="mono xs">{me.identity.objectId}</span>{' '}
              <Button
                variant="ghost"
                size="sm"
                aria-label="Copy object ID"
                onClick={() => void copyText(me.identity.objectId)}
              >
                <Icon name="copy" size="sm" />
              </Button>
            </>,
          ],
          ['Tenant ID', <span className="mono xs">{me.identity.tenantId}</span>],
        ]}
      />
      <h3 className="mt-16 mb-8">Access in KRZ Attend</h3>
      <KeyValueList
        pairs={[
          [
            'Roles',
            me.roles.map((role, i) => (
              <span key={role}>
                {i > 0 && ' '}
                <RoleBadge>{role}</RoleBadge>
              </span>
            )),
          ],
          ['Effective permissions', `${permissions.length} of ${catalogue.length}`],
        ]}
      />
      <div className="row wrap gap-4 mt-8">
        {permissions.map(key => (
          <Tag key={key} className="mono" title={labels.get(key) ?? key}>
            {key}
          </Tag>
        ))}
      </div>
      <h3 className="mt-16 mb-8">Session</h3>
      <KeyValueList
        pairs={[
          ['Signed in', fmtDateTime(me.session.signedInAt)],
          ['Expires', `${fmtDateTime(me.session.expiresAt)} if there’s no activity (${me.session.idleTimeoutMin} min)`],
        ]}
      />
    </Drawer>
  );
}
