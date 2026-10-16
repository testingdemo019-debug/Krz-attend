/** The prototype's shift colour picker: a radio group of swatches. */
import './SwatchPicker.module.css';

export function SwatchPicker({
  colours,
  value,
  name,
  onChange,
}: {
  colours: readonly string[];
  value: string;
  name: string;
  onChange: (colour: string) => void;
}) {
  return (
    <div className="row gap-8" role="radiogroup" aria-label="Colour">
      {colours.map(c => (
        <label key={c} className="swatch-pick">
          <input
            type="radio"
            name={name}
            value={c}
            checked={value === c}
            aria-label={`Colour ${c}`}
            onChange={() => {
              onChange(c);
            }}
          />
          <span className="sw" style={{ background: c, width: 22, height: 22 }} />
        </label>
      ))}
    </div>
  );
}
