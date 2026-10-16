/**
 * The prototype's timeFieldHTML: a time of day as selects that follow the 12/24-hour setting (no native time input),
 * minutes in 5-minute steps. Values are "HH:MM".
 */
import { formatSettings } from '@/lib/format';

const pad = (n: number) => String(n).padStart(2, '0');
const MINUTES = Array.from({ length: 12 }, (_, i) => i * 5);

export function TimeField({
  value,
  label,
  onChange,
}: {
  value: string;
  label: string;
  onChange: (hm: string) => void;
}) {
  const [h = 0, m = 0] = value.split(':').map(Number);
  const minute = m - (m % 5);
  const set = (hour: number, min: number) => {
    onChange(`${pad(hour)}:${pad(min)}`);
  };
  const minutes = (
    <select
      className="select"
      aria-label={`${label} minutes`}
      style={{ width: 72 }}
      value={minute}
      onChange={event => {
        set(h, Number(event.target.value));
      }}
    >
      {MINUTES.map(x => (
        <option key={x} value={x}>
          {pad(x)}
        </option>
      ))}
    </select>
  );
  if (!formatSettings().hour12) {
    return (
      <span className="row gap-4">
        <select
          className="select"
          aria-label={`${label} hour`}
          style={{ width: 72 }}
          value={h}
          onChange={event => {
            set(Number(event.target.value), minute);
          }}
        >
          {Array.from({ length: 24 }, (_, i) => (
            <option key={i} value={i}>
              {pad(i)}
            </option>
          ))}
        </select>
        :{minutes}
      </span>
    );
  }
  const pm = h >= 12;
  return (
    <span className="row gap-4">
      <select
        className="select"
        aria-label={`${label} hour`}
        style={{ width: 68 }}
        value={h % 12}
        onChange={event => {
          set(Number(event.target.value) + (pm ? 12 : 0), minute);
        }}
      >
        {Array.from({ length: 12 }, (_, i) => i + 1).map(x => (
          <option key={x} value={x % 12}>
            {x}
          </option>
        ))}
      </select>
      :{minutes}
      <select
        className="select"
        aria-label={`${label} AM or PM`}
        style={{ width: 72 }}
        value={pm ? 12 : 0}
        onChange={event => {
          set((h % 12) + Number(event.target.value), minute);
        }}
      >
        <option value={0}>AM</option>
        <option value={12}>PM</option>
      </select>
    </span>
  );
}
