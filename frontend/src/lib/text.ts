/** Text helpers ported from the prototype's "Utilities" section. */

/** "1 crossing", "3 crossings", "2 HRMS pushes". */
export function plural(n: number, word: string, pluralWord?: string): string {
  return `${n} ${n === 1 ? word : (pluralWord ?? `${word}s`)}`;
}

/** Two letters from a display name: "Ananya Iyer" → "AI" (as the backend's Me.initials). */
export function initials(name: string): string {
  return name
    .trim()
    .split(/\s+/)
    .filter(Boolean)
    .map(word => word.charAt(0))
    .slice(0, 2)
    .join('')
    .toUpperCase();
}

/** "Ananya I" from "Ananya Iyer". */
export function shortName(name: string): string {
  const parts = name.trim().split(/\s+/);
  const last = parts.at(-1);
  return parts.length > 1 && last ? `${parts[0] ?? ''} ${last.charAt(0)}` : name;
}

/** FNV-1a, the prototype's hashStr: stable colours and seeds from ids. */
export function hashString(text: string): number {
  let h = 2166136261;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

/** A camera track as the prototype names it: "T402" (the overlay label and the crossing drawer). */
export function trackName(id: number): string {
  return `T${String(id)}`;
}

/** Numbers as the prototype formats counts: 1,23,456 (en-IN grouping). */
export function fmtCount(n: number): string {
  return n.toLocaleString('en-IN');
}
