/** Joins class names, skipping empty ones: cx('btn', primary && 'btn-primary', size) . */
export function cx(...names: (string | false | null | undefined)[]): string {
  return names.filter(Boolean).join(' ');
}
