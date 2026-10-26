import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { MapPin, Search, Users } from 'lucide-react';
import { api, ApiError } from '../api/client';
import { useConfig, useRooms } from '../api/hooks';
import type { Page, Room } from '../api/types';
import { BookingDialog, type BookingDraft } from '../components/BookingDialog';
import { Banner, Button, Chip, EmptyState, ErrorState, Field, PageHeader, Skeleton } from '../components/ui';
import { useI18n } from '../lib/i18n';
import { addDays, fromMinutes, nowMinutesIn, todayIn, toMinutes, zonedToIso } from '../lib/time';

export function FindRoomPage() {
  const cfg = useConfig().data!;
  const { t, tn, dur, eq, err } = useI18n();
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
  const [attendees, setAttendees] = useState(params.get('attendees') ?? '');
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
  const minCap = attendees ? Math.max(1, Number(attendees)) : undefined;

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

  return (
    <>
      <PageHeader title={t('find.title')} subtitle={t('find.subtitle')} />

      <form className="card filters" onSubmit={(e) => e.preventDefault()}>
        <Field label={t('common.date')}>
          <input type="date" value={date} min={today} max={addDays(today, cfg.maxAdvanceDays)} onChange={(e) => e.target.value && setDate(e.target.value)} />
        </Field>
        <Field label={t('find.from')}>
          <select value={start} onChange={(e) => { setStart(e.target.value); if (toMinutes(e.target.value) >= toMinutes(end)) setEnd(fromMinutes(Math.min(toMinutes(e.target.value) + 60, we))); }}>
            {times.slice(0, -1).map((x) => <option key={x}>{x}</option>)}
          </select>
        </Field>
        <Field label={t('find.to')}>
          <select value={end} onChange={(e) => setEnd(e.target.value)}>
            {times.filter((x) => toMinutes(x) > toMinutes(start)).map((x) => <option key={x}>{x}</option>)}
          </select>
        </Field>
        <Field label={t('find.attendees')}>
          <input type="number" inputMode="numeric" min={1} max={1000} value={attendees} onChange={(e) => setAttendees(e.target.value)} placeholder={t('find.any')} />
        </Field>
        {knownEquipment.length > 0 && (
          <div className="filters-wide">
            <span className="field-label">{t('find.needs')}</span>
            <div className="quick" role="group" aria-label={t('find.equipment')}>
              {knownEquipment.map((e) => (
                <button key={e} type="button" aria-pressed={equipment.includes(e)} className={`chip chip-btn ${equipment.includes(e) ? 'chip-active' : ''}`} onClick={() => toggle(e)}>
                  {eq(e)}
                </button>
              ))}
            </div>
          </div>
        )}
      </form>

      {!valid && <Banner tone="warning">{t('find.endAfter')}</Banner>}
      {valid && results.isPending && <div className="cards">{Array.from({ length: 3 }, (_, i) => <div key={i} className="card"><Skeleton h={20} w="50%" /><Skeleton h={14} /></div>)}</div>}
      {results.isError && (
        results.error instanceof ApiError && results.error.status === 422
          ? <Banner tone="warning">{err(results.error)}</Banner>
          : <ErrorState error={results.error} onRetry={() => results.refetch()} />
      )}
      {results.data && (
        <>
          <p className="muted result-count" aria-live="polite">
            {tn('find.count', results.data.totalElements, { start, end, dur: dur(toMinutes(end) - toMinutes(start)) })}
          </p>
          {results.data.content.length === 0 ? (
            <EmptyState icon={<Search size={28} />} title={t('find.noneTitle')}>{t('find.noneHelp')}</EmptyState>
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
                  <div className="quick">{r.equipment.map((e) => <Chip key={e}>{eq(e)}</Chip>)}</div>
                  <Button variant="primary" onClick={() => setDraft({ roomId: r.id, date, start, end, attendees: minCap })}>{t('find.book')}</Button>
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
