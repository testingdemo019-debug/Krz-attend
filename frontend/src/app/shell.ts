/** What the signed-in layout offers its pages (the outlet context): e.g. the Review Queue's "Shortcuts" button. */
import { useOutletContext } from 'react-router';

export interface ShellContext {
  showShortcuts: () => void;
}

export function useShell(): ShellContext {
  return useOutletContext<ShellContext>();
}
