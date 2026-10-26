import type { Booking } from '../api/types';

const fmt = (iso: string) => new Date(Date.parse(iso)).toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
const esc = (s: string) => s.replace(/\\/g, '\\\\').replace(/;/g, '\;').replace(/,/g, '\\,').replace(/\r?\n/g, '\\n');

/** One-event iCalendar file, built in the browser: no server round-trip, works with Google/Outlook/Apple. */
export function downloadIcs(b: Booking) {
  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//Room Booking//EN',
    'CALSCALE:GREGORIAN',
    'BEGIN:VEVENT',
    `UID:${b.id}@room-booking`,
    `DTSTAMP:${fmt(new Date().toISOString())}`,
    `DTSTART:${fmt(b.startTime)}`,
    `DTEND:${fmt(b.endTime)}`,
    `SUMMARY:${esc(b.title)}`,
    `LOCATION:${esc(b.room.name)}`,
    ...(b.description ? [`DESCRIPTION:${esc(b.description)}`] : []),
    'END:VEVENT',
    'END:VCALENDAR',
  ];
  const blob = new Blob([lines.join('\r\n') + '\r\n'], { type: 'text/calendar;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `${b.title.replace(/[^\w-]+/g, '_').slice(0, 40) || 'booking'}.ics`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
