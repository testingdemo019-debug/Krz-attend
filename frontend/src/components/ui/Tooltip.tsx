import './Tooltip.module.css';

export type TipPosition = 'top' | 'bottom' | 'left';

/**
 * The prototype's data-tip tooltips (pure CSS: shown on hover and keyboard focus). Spread onto any element:
 * <button {...tip('Columns')}>.
 */
export function tip(text: string | null | undefined, position: TipPosition = 'top'): Record<string, string> {
  if (!text) return {};
  return position === 'top' ? { 'data-tip': text } : { 'data-tip': text, 'data-tip-pos': position };
}
