import { Kbd } from '@/components/ui/Kbd';
import '@/components/data/DataTable.module.css';
import { Modal } from './Modal';

const SHORTCUTS: readonly (readonly [string, string])[] = [
  ['/', 'Focus global search'],
  ['?', 'Show keyboard shortcuts'],
  ['Esc', 'Close dialog, panel, calendar or menu'],
  ['Tab / Shift+Tab', 'Move between controls'],
  ['Arrow keys', 'Move between days in a calendar'],
  ['J / K', 'Review Queue: next / previous item'],
  ['C', 'Review Queue: confirm top candidate'],
  ['A', 'Review Queue: assign to employee'],
  ['V', 'Review Queue: not an employee'],
  ['R', 'Review Queue: reject — bad image'],
];

/** The prototype's showShortcuts ("?" or the user menu). */
export function ShortcutsDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <Modal open={open} onClose={onClose} title="Keyboard shortcuts" size="sm">
      <table className="dt">
        <tbody>
          {SHORTCUTS.map(([keys, description]) => (
            <tr key={keys}>
              <td style={{ width: '42%' }}>
                {keys.split(' / ').map((k, i) => (
                  <span key={k}>
                    {i > 0 && ' / '}
                    <Kbd>{k}</Kbd>
                  </span>
                ))}
              </td>
              <td>{description}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Modal>
  );
}
