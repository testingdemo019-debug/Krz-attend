import { useCan, useNeedsPermissionText, type PermissionKey } from '@/lib/permissions';
import { Button, type ButtonProps } from './Button';

/**
 * The prototype's buttonWithPermission: the action when the user has the permission, otherwise the same button
 * disabled with the "Needs the … permission — ask a System Administrator" tooltip.
 */
export function PermissionButton({ permission, ...props }: ButtonProps & { permission: PermissionKey }) {
  const allowed = useCan(permission);
  const needs = useNeedsPermissionText();
  if (allowed) return <Button {...props} />;
  const { onClick, ...rest } = props;
  return <Button {...rest} disabled aria-disabled="true" tooltip={needs(permission)} />;
}
