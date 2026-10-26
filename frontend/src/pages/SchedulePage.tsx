import { useEffect, useMemo, useState } from 'react';
import { ChevronLeft, ChevronRight, DoorOpen, Lock, Plus, Wrench } from 'lucide-react';
import { useConfig, useDaySchedule } from '../api/hooks';
import type { Booking } from '../api/types';
import { BookingDialog, type BookingDraft } from '../components/BookingDialog';
import { BookingDetailDialog } from '../components/BookingDetailDialog';
import { Button, EmptyState, ErrorState, PageHeader, Skeleton } from '../components/ui';
import { useAuth } from '../lib/auth';
import { useI18n } from '../lib/i18n';
import { addDays, formatDate, fromMinutes, nowMinutesIn, todayIn, toMinutes, wallTime } from '../lib/time';
import { useToast } from '../lib/toast';

const MIN_HOUR_W = 84; // px per hour; below this the grid scrolls horizontally
const LABEL_W = 168;

export function SchedulePage() {
  const cfg = useConfig().data!;
  const { user } = useAuth();
  const { t } = useI18n();
  const toast = useToast();
  const today = todayIn(cfg.timezone);
  const [date, setDate] = useState(today);
  const [draft, setDraft] = useState<BookingDraft | null>(null);
  const [selected, setSelected] = useState<Booking | null>(null);
  const schedule = useDaySchedule(date);

  // business hours: where new bookings can be made
  const bizStart = toMinutes(cfg.workdayStart);
  const bizEnd = toMinutes(cfg.workdayEnd);
  // The grid normally shows exactly those hours. If working hours were shortened later, bookings made earlier can
  // lie outside them; they still occupy the room, so the grid widens (never hides) to include them.
  const { ws, we } = useMemo(() => {
    let lo = bizStart, hi = bizEnd;
    for (const r of schedule.data?.rooms ?? []) {
      for (const b of r.bookings) {
        if (b.kind === 'BLOCK') continue;
        lo = Math.min(lo, Math.floor(toMinutes(wallTime(b.startTime)) / 60) * 60);
        hi = Math.max(hi, Math.ceil(toMinutes(wallTime(b.endTime)) / 60) * 60);
      }
    }
    return { ws: lo, we: hi };
  }, [schedule.data, bizStart, bizEnd]);
  const hours = useMemo(() => Array.from({ length: Math.ceil((we - ws) / 60) }, (_, i) => ws + i * 60), [ws, we]);

  // Fill the available width on large screens (so titles are readable), scroll on small ones.
  const [scroller, setScroller] = useState<HTMLDivElement | null>(null);
  const [hourW, setHourW] = useState(MIN_HOUR_W);
  useEffect(() => {
    if (!scroller) return;
    const fit = () => setHourW(Math.max(MIN_HOUR_W, Math.floor((scroller.clientWidth - LABEL_W - 2) / hours.length)));
    fit();
    const ro = new ResizeObserver(fit);
    ro.observe(scroller);
    return () => ro.disconnect();
  }, [scroller, hours.length]);

  const width = ((we - ws) / 60) * hourW;
  const x = (min: number) => ((min - ws) / 60) * hourW;
  const nowMin = nowMinutesIn(cfg.timezone);
  const isToday = date === today;
  const isPast = date < today;
  const pastWidth = isPast ? width : isToday ? Math.max(0, Math.min(width, x(nowMin))) : 0;
  const maxDate = addDays(today, cfg.maxAdvanceDays);

  // When looking at today, bring "now" into view instead of making people scroll through the past.
  useEffect(() => {
    if (!scroller) return;
    scroller.scrollLeft = isToday ? Math.max(0, x(nowMinutesIn(cfg.timezone)) - 2 * hourW) : 0;
    // only when the day changes or the grid appears, not on every refresh/resize
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scroller, date]);

  function openCreate(roomId: string, startMin?: number) {
    if (isPast || (isToday && startMin !== undefined && startMin + cfg.slotMinutes <= nowMin)) {
      toast.info(t('schedule.passed'));
      return;
    }
    let s = Math.max(startMin ?? bizStart, bizStart);
    if (isToday && s < nowMin) s = Math.ceil(nowMin / cfg.slotMinutes) * cfg.slotMinutes;
    s = Math.min(s, bizEnd - cfg.minDurationMinutes);
    setDraft({ roomId, date, start: fromMinutes(s), end: fromMinutes(Math.min(s + 60, bizEnd)) });
  }

  function rowClick(e: React.MouseEvent<HTMLDivElement>, roomId: string) {
    if (e.target !== e.currentTarget) return; // a booking block handles its own click
    const rect = e.currentTarget.getBoundingClientRect();
    const min = ws + Math.floor((((e.clientX - rect.left) / hourW) * 60) / cfg.slotMinutes) * cfg.slotMinutes;
    openCreate(roomId, min);
  }

  return (
    <>
      <PageHeader
        title={t('schedule.title')}
        subtitle={formatDate(date)}
        actions={
          <>
            <div className="date-nav" role="group" aria-label={t('schedule.chooseDay')}>
              <Button variant="ghost" aria-label={t('schedule.prevDay')} disabled={date <= today} icon={<ChevronLeft size={18} />} onClick={() => setDate(addDays(date, -1))} />
              <input type="date" aria-label={t('common.date')} value={date} min={today} max={maxDate} onChange={(e) => e.target.value && setDate(e.target.value)} />
              <Button variant="ghost" aria-label={t('schedule.nextDay')} disabled={date >= maxDate} icon={<ChevronRight size={18} />} onClick={() => setDate(addDays(date, 1))} />
              <Button variant="secondary" disabled={isToday} onClick={() => setDate(today)}>{t('common.today')}</Button>
            </div>
            <Button variant="primary" icon={<Plus size={18} />} onClick={() => setDraft({ date })}>{t('schedule.new')}</Button>
          </>
        }
      />

      {schedule.isError && <ErrorState error={schedule.error} onRetry={() => schedule.refetch()} />}
      {schedule.isPending && (
        <div className="card stack">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} h={44} />)}</div>
      )}
      {schedule.data && schedule.data.rooms.length === 0 && (
        <EmptyState icon={<DoorOpen size={28} />} title={t('schedule.noRooms')}>{t('schedule.noRoomsHelp')}</EmptyState>
      )}

      {schedule.data && schedule.data.rooms.length > 0 && (
        <>
          <div className="legend" aria-hidden>
            <span><i className="dot mine" /> {t('schedule.legendMine')}</span>
            <span><i className="dot other" /> {t('schedule.legendOther')}</span>
            <span><i className="dot masked" /> {t('priv.label')}</span>
            <span><i className="dot block" /> {t('schedule.legendBlocked')}</span>
            <span><i className="dot past" /> {t('schedule.legendPast')}</span>
            <span className="muted">{t('schedule.hint')}</span>
          </div>
          <div ref={setScroller} className="timeline card" role="region" aria-label={t('schedule.region')} tabIndex={0}>
            <div className="tl-inner" style={{ gridTemplateColumns: `${LABEL_W}px ${width}px` }}>
              <div className="tl-corner" />
              <div className="tl-hours" style={{ width }}>
                {hours.map((h) => (
                  <div key={h} className="tl-hour" style={{ left: x(h), width: hourW }}>{fromMinutes(h)}</div>
                ))}
              </div>

              {schedule.data.rooms.map(({ room, bookings }) => (
                <div key={room.id} className="tl-row">
                  <div className="tl-room">
                    <span className="tl-room-name" title={room.name}>{room.name}</span>
                    <button className="icon-btn" aria-label={t('schedule.bookRoom', { room: room.name })} onClick={() => openCreate(room.id)}><Plus size={16} /></button>
                  </div>
                  <div
                    className="tl-track"
                    style={{ width, backgroundSize: `${hourW}px 100%` }}
                    onClick={(e) => rowClick(e, room.id)}
                    role="group"
                    aria-label={t('schedule.roomCount', { room: room.name, n: bookings.length })}
                  >
                    {pastWidth > 0 && <div className="tl-past" style={{ width: pastWidth }} />}
                    {isToday && nowMin >= ws && nowMin <= we && <div className="tl-now" style={{ left: x(nowMin) }} aria-hidden />}
                    {bookings.map((b) => {
                      // blocks can span several days: clamp to this day's working window
                      const s = Math.max(ws, b.startTime.slice(0, 10) < date ? ws : toMinutes(wallTime(b.startTime)));
                      const e = Math.min(we, b.endTime.slice(0, 10) > date ? we : toMinutes(wallTime(b.endTime)));
                      if (e <= s) return null;
                      const block = b.kind === 'BLOCK';
                      const mine = b.booker?.id === user?.id;
                      const label = b.masked ? t('priv.label') : b.title;
                      return (
                        <button
                          key={b.id}
                          className={`tl-booking ${block ? 'block' : b.masked ? 'masked' : mine ? 'mine' : 'other'}`}
                          style={{ left: x(s) + 1, width: Math.max(x(e) - x(s) - 2, 8) }}
                          onClick={() => setSelected(b)}
                          title={b.masked ? `${label} · ${wallTime(b.startTime)}–${wallTime(b.endTime)}` : `${b.title} · ${wallTime(b.startTime)}–${wallTime(b.endTime)} · ${b.booker?.fullName ?? ''}`}
                          aria-label={`${label}, ${wallTime(b.startTime)} - ${wallTime(b.endTime)}${b.booker ? `, ${b.booker.fullName}` : ''}`}
                        >
                          <span className="tl-b-title">{block && <Wrench size={12} aria-hidden />}{(b.masked || b.private) && <Lock size={12} aria-hidden />} {label}</span>
                          <span className="tl-b-sub">{wallTime(b.startTime)}–{wallTime(b.endTime)}{block || !b.booker ? '' : ` · ${b.booker.fullName}`}</span>
                        </button>
                      );
                    })}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </>
      )}

      <BookingDialog open={!!draft} onClose={() => setDraft(null)} draft={draft ?? undefined} />
      <BookingDetailDialog booking={selected} onClose={() => setSelected(null)} />
    </>
  );
}
