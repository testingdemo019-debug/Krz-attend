/** The prototype's niceMax: the axis top as 2, 5 or 10 × a power of ten (at least 5). */
export function niceMax(value: number): number {
  if (value <= 5) return 5;
  const power = 10 ** Math.floor(Math.log10(value));
  const n = value / power;
  return (n <= 2 ? 2 : n <= 5 ? 5 : 10) * power;
}
