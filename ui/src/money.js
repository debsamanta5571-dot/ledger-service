// The API uses integer minor units. This UI assumes 2 decimal places (cents); zero- and three-decimal
// currencies (JPY, KWD) would need a per-currency exponent.

/** 12345 -> "123.45", -5 -> "-0.05" */
export function formatMinor(minor) {
  const negative = minor < 0;
  const abs = Math.abs(minor);
  const whole = Math.floor(abs / 100);
  const cents = String(abs % 100).padStart(2, '0');
  return `${negative ? '-' : ''}${whole}.${cents}`;
}

/** "12.5" -> 1250, "12" -> 1200, "12.345" / "abc" / "" -> null. Avoids float math on the way in. */
export function parseMajor(text) {
  const m = /^(\d{1,13})(?:\.(\d{1,2}))?$/.exec(String(text).trim());
  if (!m) return null;
  const cents = (m[2] ?? '').padEnd(2, '0');
  return Number(m[1]) * 100 + Number(cents);
}
