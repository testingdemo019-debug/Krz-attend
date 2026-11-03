export type LivePillState = 'live' | 'rec' | 'conn' | 'off';

const TEXT: Record<LivePillState, string> = { live: 'Live', rec: 'Reconnecting', conn: 'Connecting', off: 'Offline' };

/** What each state means, as the pill's tooltip (D-121). */
const HELP: Record<LivePillState, string> = {
  live: 'Pictures are arriving and being processed',
  rec: 'The stream dropped; trying to connect again',
  conn: 'Connecting to the camera',
  off: 'No pictures from the camera',
};

/** The prototype's livePill: Live (pulsing dot), Reconnecting, Connecting or Offline. */
export function LivePill({ state, text }: { state: LivePillState; text?: string }) {
  return (
    <span className={`lpill ${state}`} title={HELP[state]}>
      <span className="d" />
      {text ?? TEXT[state]}
    </span>
  );
}
