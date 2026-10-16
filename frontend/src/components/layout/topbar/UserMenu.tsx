import { useState } from 'react';
import type { Me } from '@/api/me';
import { Icon } from '@/components/icons/Icon';
import { Avatar } from '@/components/ui/Avatar';
import { RoleBadge } from '@/components/ui/Chip';
import { MenuItem, MenuLabel, MenuSeparator, Popover } from '@/components/ui/Popover';
import { Segmented } from '@/components/ui/Segmented';
import { setTheme, useThemePreference, type ThemePreference } from '@/lib/theme';

const THEMES = [
  { value: 'light', label: 'Light', icon: 'sun' },
  { value: 'dark', label: 'Dark', icon: 'moon' },
  { value: 'system', label: 'System', icon: 'monitor' },
] as const;

/** The user button and its popover: who, roles, theme, My account, Keyboard shortcuts and Sign out. */
export function UserMenu({
  me,
  onAccount,
  onShortcuts,
  onSignOut,
}: {
  me: Me;
  onAccount: () => void;
  onShortcuts: () => void;
  onSignOut: () => void;
}) {
  const [open, setOpen] = useState(false);
  const theme = useThemePreference();
  const first = me.name.split(/\s+/)[0] ?? me.name;
  const close = (then: () => void) => () => {
    setOpen(false);
    then();
  };
  return (
    <Popover
      align="right"
      open={open}
      onOpenChange={setOpen}
      trigger={
        <button type="button" className="user-btn" aria-haspopup="true" aria-label={`Account menu for ${me.name}`}>
          <Avatar id={me.id} name={me.name} size="sm" />
          <span className="small ellipsis user-name">{first}</span>
          <Icon name="chevDown" size="sm" />
        </button>
      }
    >
      <div style={{ width: 300, maxWidth: '100%', padding: 6 }}>
        <div className="row" style={{ padding: '6px 6px 10px' }}>
          <Avatar id={me.id} name={me.name} size="lg" />
          <div className="grow" style={{ minWidth: 0 }}>
            <div style={{ fontWeight: 650 }}>{me.name}</div>
            <div className="muted small ellipsis">{me.email}</div>
            {me.designation && <div className="muted small">{me.designation}</div>}
          </div>
        </div>
        <div className="row wrap gap-4" style={{ padding: '0 6px 10px' }}>
          {me.roles.map(role => (
            <RoleBadge key={role}>{role}</RoleBadge>
          ))}
        </div>
        <MenuSeparator />
        <MenuLabel>Theme</MenuLabel>
        <div style={{ padding: '2px 6px 8px' }}>
          <Segmented<ThemePreference> size="sm" label="Theme" value={theme} onChange={setTheme} segments={THEMES} />
        </div>
        <MenuItem icon="user" onClick={close(onAccount)}>
          My account
        </MenuItem>
        <MenuItem icon="keyboard" onClick={close(onShortcuts)}>
          Keyboard shortcuts
        </MenuItem>
        <MenuSeparator />
        <MenuItem icon="logout" onClick={close(onSignOut)}>
          Sign out
        </MenuItem>
      </div>
    </Popover>
  );
}
