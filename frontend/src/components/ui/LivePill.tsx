import './LivePill.module.css';

export type LivePillState = 'live' | 'rec' | 'conn' | 'off';

const TEXT: Record<LivePillState, string> = { live: 'Live', rec: 'Reconnecting', conn: 'Connecting', off: 'Offline' };

/** The prototype's livePill: Live (pulsing dot), Reconnecting, Connecting or Offline. */
export function LivePill({ state, text }: { state: LivePillState; text?: string }) {
  return (
    <span className={`lpill ${state}`}>
      <span className="d" />
      {text ?? TEXT[state]}
    </span>
  );
}
