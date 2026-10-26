/**
 * All wall-clock logic is done in the company time zone (from /config), not the browser's, so a user
 * travelling or with a wrong laptop zone still books the same real-world slot as everyone else.
 */

const pad = (n: number) => String(n).padStart(2, '0');

function parts(ms: number, tz: string) {
  const fmt = new Intl.DateTimeFormat('en-CA', {
    timeZone: tz, hourCycle: 'h23',
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
  });
  const o: Record<string, number> = {};
  for (const p of fmt.formatToParts(new Date(ms))) if (p.type !== 'literal') o[p.type] = Number(p.value);
  return o;
}

function offsetMs(ms: number, tz: string) {
  const p = parts(ms, tz);
  return Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second) - Math.floor(ms / 1000) * 1000;
}

/** "2026-10-27" + "10:00" in tz -> ISO instant (UTC). */
export function zonedToIso(date: string, time: string, tz: string): string {
  const [y, mo, d] = date.split('-').map(Number);
  const [h, mi] = time.split(':').map(Number);
  const guess = Date.UTC(y, mo - 1, d, h, mi);
  let utc = guess - offsetMs(guess, tz);
  utc = guess - offsetMs(utc, tz); // second pass handles DST edges
  return new Date(utc).toISOString();
}

export function todayIn(tz: string): string {
  const p = parts(Date.now(), tz);
  return `${p.year}-${pad(p.month)}-${pad(p.day)}`;
}

export function nowMinutesIn(tz: string): number {
  const p = parts(Date.now(), tz);
  return p.hour * 60 + p.minute;
}

export function addDays(date: string, n: number): string {
  const [y, m, d] = date.split('-').map(Number);
  const t = new Date(Date.UTC(y, m - 1, d + n));
  return `${t.getUTCFullYear()}-${pad(t.getUTCMonth() + 1)}-${pad(t.getUTCDate())}`;
}

export function formatDate(date: string, opts: Intl.DateTimeFormatOptions = { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }) {
  const [y, m, d] = date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d, 12)).toLocaleDateString('en-GB', { ...opts, timeZone: 'UTC' });
}

/** API timestamps already carry the company offset, so wall time is just a slice. */
export const wallDate = (iso: string) => iso.slice(0, 10);
export const wallTime = (iso: string) => iso.slice(11, 16);

export const toMinutes = (hhmm: string) => {
  const [h, m] = hhmm.split(':').map(Number);
  return h * 60 + m;
};
export const fromMinutes = (n: number) => `${pad(Math.floor(n / 60))}:${pad(n % 60)}`;

export function durationLabel(minutes: number) {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  if (h && m) return `${h}h ${m}m`;
  return h ? `${h}h` : `${m}m`;
}

export const hasEnded = (iso: string) => Date.parse(iso) <= Date.now();
export const hasStarted = (iso: string) => Date.parse(iso) <= Date.now();
