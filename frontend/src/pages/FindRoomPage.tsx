import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { MapPin, Search, Users } from 'lucide-react';
import { api, ApiError } from '../api/client';
import { useConfig, useRooms } from '../api/hooks';
import type { Page, Room } from '../api/types';
import { BookingDialog, type BookingDraft } from '../components/BookingDialog';
import { Banner, Button, Chip, EmptyState, ErrorState, Field, PageHeader, Skeleton } from '../components/ui';
import { prettyEquipment } from '../lib/format';
import { addDays, durationLabel, fromMinutes, nowMinutesIn, todayIn, toMinutes, zonedToIso } from '../lib/time';

export function FindRoomPage() {
  const cfg = useConfig().data!;
  const [params] = useSearchParams();
  const today = todayIn(cfg.timezone);
  const ws = toMinutes(cfg.workdayStart);
  const we = toMinutes(cfg.workdayEnd);

  // Default to the next bookable hour: a default in the past would only ever show an error.
  const defaults = useMemo(() => {
    const next = Math.ceil((nowMinutesIn(cfg.timezone) + 1) / cfg.slotMinutes) * cfg.slotMinutes;
    if (next + 60 <= we) return { date: today, start: Math.max(ws, next) };
    return { date: addDays(today, 1), start: Math.max(ws, 9 * 60) };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const [date, setDate] = useState(params.get('date') ?? defaults.date);
  const [start, setStart] = useState(params.get('start') ?? fromMinutes(defaults.start));
  const [end, setEnd] = useState(params.get('end') ?? fromMinutes(defaults.start + 60));
  const [capacity, setCapacity] = useState('');
  const [equipment, setEquipment] = useState<string[]>([]);
  const [draft, setDraft] = useState<BookingDraft | null>(null);

  const allRooms = useRooms();
  const knownEquipment = useMemo(
    () => [...new Set((allRooms.data?.content ?? []).flatMap((r) => r.equipment))].sort(),
    [allRooms.data],
  );

  const times = useMemo(() => {
    const out: string[] = [];
    for (let m = ws; m <= we; m += cfg.slotMinutes) out.push(fromMinutes(m));
    return out;
  }, [ws, we, cfg.slotMinutes]);

  const valid = toMinutes(end) > toMinutes(start);
  const minCap = capacity ? Math.max(1, Number(capacity)) : undefined;

  const results = useQuery({
    queryKey: ['bk', 'available', date, start, end, minCap, equipment],
    queryFn: () =>
      api<Page<Room>>('/rooms/available', {
        query: {
          start: zonedToIso(date, start, cfg.timezone),
          end: zonedToIso(date, end, cfg.timezone),
          minCapacity: minCap,
          equipment,
          size: 50,
        },
      }),
    enabled: valid,
    placeholderData: (prev) => prev,
  });

  const toggle = (e: string) => setEquipment((l) => (l.includes(e) ? l.filter((x) => x !== e) : [...l, e]));
  const pretty = prettyEquipment;

  return (
    <>
      <PageHeader title="Find a room" subtitle="See which rooms are free for the time you need." />

      <form className="card filters" onSubmit={(e) => e.preventDefault()}>
        <Field label="Date">
          <input type="date" value={date} min={today} max={addDays(today, cfg.maxAdvanceDays)} onChange={(e) => e.target.value && setDate(e.target.value)} />
        </Field>
        <Field label="From">
          <select value={start} onChange={(e) => { setStart(e.target.value); if (toMinutes(e.target.value) >= toMinutes(end)) setEnd(fromMinutes(Math.min(toMinutes(e.target.value) + 60, we))); }}>
            {times.slice(0, -1).map((t) => <option key={t}>{t}</option>)}
          </select>
        </Field>
        <Field label="To">
          <select value={end} onChange={(e) => setEnd(e.target.value)}>
            {times.filter((t) => toMinutes(t) > toMinutes(start)).map((t) => <option key={t}>{t}</option>)}
          </select>
        </Field>
        <Field label="Minimum seats">
          <input type="number" inputMode="numeric" min={1} max={1000} value={capacity} onChange={(e) => setCapacity(e.target.value)} placeholder="Any" />
        </Field>
        {knownEquipment.length > 0 && (
          <div className="filters-wide">
            <span className="field-label">Needs</span>
            <div className="quick" role="group" aria-label="Equipment">
              {knownEquipment.map((e) => (
                <button key={e} type="button" aria-pressed={equipment.includes(e)} className={`chip chip-btn ${equipment.includes(e) ? 'chip-active' : ''}`} onClick={() => toggle(e)}>
                  {pretty(e)}
                </button>
              ))}
            </div>
          </div>
        )}
      </form>

      {!valid && <Banner tone="warning">The end time must be after the start time.</Banner>}
      {valid && results.isPending && <div className="cards">{Array.from({ length: 3 }, (_, i) => <div key={i} className="card"><Skeleton h={20} w="50%" /><Skeleton h={14} /></div>)}</div>}
      {results.isError && (
        results.error instanceof ApiError && results.error.status === 422
          ? <Banner tone="warning">{results.error.message}</Banner>
          : <ErrorState error={results.error} onRetry={() => results.refetch()} />
      )}
      {results.data && (
        <>
          <p className="muted result-count" aria-live="polite">
            {results.data.totalElements} room{results.data.totalElements === 1 ? '' : 's'} free · {start}–{end} ({durationLabel(toMinutes(end) - toMinutes(start))})
          </p>
          {results.data.content.length === 0 ? (
            <EmptyState icon={<Search size={28} />} title="No room is free at that time">
              Try a different time, fewer requirements, or another day.
            </EmptyState>
          ) : (
            <div className="cards">
              {results.data.content.map((r) => (
                <article key={r.id} className="card room-card">
                  <div className="room-card-head">
                    <h3>{r.name}</h3>
                    <span className="muted cap"><Users size={16} aria-hidden /> {r.capacity}</span>
                  </div>
                  {r.location && <p className="muted small"><MapPin size={14} aria-hidden /> {r.location}</p>}
                  {r.description && <p className="small">{r.description}</p>}
                  <div className="quick">{r.equipment.map((e) => <Chip key={e}>{pretty(e)}</Chip>)}</div>
                  <Button variant="primary" onClick={() => setDraft({ roomId: r.id, date, start, end })}>Book this room</Button>
                </article>
              ))}
            </div>
          )}
        </>
      )}
      <BookingDialog open={!!draft} onClose={() => setDraft(null)} draft={draft ?? undefined} />
    </>
  );
}
