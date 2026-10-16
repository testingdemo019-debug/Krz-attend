import { Icon } from '@/components/icons/Icon';
import { cx } from '@/lib/cx';
import { hashString, initials } from '@/lib/text';
import './Avatar.module.css';

/**
 * Initials avatars replace the prototype's generated faces (F7): two letters on a colour picked by a hash of the
 * employee ID from the prototype's palette (its clothing colours; every one passes AA with white text).
 */
const PALETTE = [
  '#2f4b7c',
  '#4a6fa5',
  '#7a3e48',
  '#2e6b5e',
  '#5b4b8a',
  '#8a5a2b',
  '#3d4148',
  '#b0603a',
  '#556b2f',
  '#1f6f8b',
  '#9b4f6b',
  '#4d5d6c',
  '#a23b3b',
  '#3b7a57',
] as const;

export type AvatarSize = 'sm' | 'lg' | 'xl';

export function avatarColour(id: string): string {
  return PALETTE[hashString(id) % PALETTE.length] ?? PALETTE[0];
}

export function Avatar({ id, name, size }: { id: string; name: string; size?: AvatarSize | undefined }) {
  return (
    <span
      className={cx('avatar', 'initials', size)}
      title={name}
      style={{ background: avatarColour(id) }}
      aria-hidden="true"
    >
      {initials(name)}
    </span>
  );
}

/** Someone the directory does not know (an unknown face, a guest): the person icon on the neutral background. */
export function UnknownAvatar({ size }: { size?: AvatarSize | undefined }) {
  return (
    <span className={cx('avatar', 'initials', 'unknown', size)} aria-hidden="true">
      <Icon name="user" size="sm" />
    </span>
  );
}
