import { useMemo, useState } from 'react';
import { ChevronLeft, ChevronRight, Plus } from 'lucide-react';
import { useConfig, useDaySchedule } from '../api/hooks';
import type { Booking } from '../api/types';
import { BookingDialog, type BookingDraft } from '../components/BookingDialog';
import { BookingDetailDialog } from '../components/BookingDetailDialog';
import { Button, EmptyState, ErrorState, PageHeader, Skeleton } from '../components/ui';
import { useAuth } from '../lib/auth';
import { addDays, formatDate, fromMinutes, nowMinutesIn, todayIn, toMinutes, wallTime } from '../lib/time';
import { useToast } from '../lib/toast';
import { DoorOpen } from 'lucide-react';

const HOUR_W = 84; // px per hour

export function SchedulePage() {
  const cfg = useConfig().data!;
  const { user } = useAuth();
  const toast = useToast();
  const today = todayIn(cfg.timezone);
  const [date, setDate] = useState(today);
  const [draft, setDraft] = useState<BookingDraft | null>(null);
  const [selected, setSelected] = useState<Booking | null>(null);
  const schedule = useDaySchedule(date);

  const ws = toMinutes(cfg.workdayStart);
  const we = toMinutes(cfg.workdayEnd);
  const hours = useMemo(() => Array.from({ length: Math.ceil((we - ws) / 60) }, (_, i) => ws + i * 60), [ws, we]);
  const width = ((we - ws) / 60) * HOUR_W;
  const x = (min: number) => ((min - ws) / 60) * HOUR_W;
  const nowMin = nowMinutesIn(cfg.timezone);
  const isToday = date === today;
  const isPast = date < today;
  const pastWidth = isPast ? width : isToday ? Math.max(0, Math.min(width, x(nowMin))) : 0;
  const maxDate = addDays(today, cfg.maxAdvanceDays);

  function openCreate(roomId: string, startMin?: number) {
    if (isPast || (isToday && startMin !== undefined && startMin + cfg.slotMinutes <= nowMin)) {
      toast.info('That time has already passed.');
      return;
    }
    let s = startMin ?? ws;
    if (isToday && s < nowMin) s = Math.ceil(nowMin / cfg.slotMinutes) * cfg.slotMinutes;
    s = Math.min(s, we - cfg.minDurationMinutes);
    setDraft({ roomId, date, start: fromMinutes(s), end: fromMinutes(Math.min(s + 60, we)) });
  }

  function rowClick(e: React.MouseEvent<HTMLDivElement>, roomId: string) {
    if (e.target !== e.currentTarget) return; // a booking block handles its own click
    const rect = e.currentTarget.getBoundingClientRect();
    const min = ws + Math.floor((((e.clientX - rect.left) / HOUR_W) * 60) / cfg.slotMinutes) * cfg.slotMinutes;
    openCreate(roomId, min);
  }

  return (
    <>
      <PageHeader
        title="Schedule"
        subtitle={formatDate(date)}
        actions={
          <>
            <div className="date-nav" role="group" aria-label="Choose day">
              <Button variant="ghost" aria-label="Previous day" disabled={date <= today} icon={<ChevronLeft size={18} />} onClick={() => setDate(addDays(date, -1))} />
              <input type="date" aria-label="Date" value={date} min={today} max={maxDate} onChange={(e) => e.target.value && setDate(e.target.value)} />
              <Button variant="ghost" aria-label="Next day" disabled={date >= maxDate} icon={<ChevronRight size={18} />} onClick={() => setDate(addDays(date, 1))} />
              <Button variant="secondary" disabled={isToday} onClick={() => setDate(today)}>Today</Button>
            </div>
            <Button variant="primary" icon={<Plus size={18} />} onClick={() => setDraft({ date })}>New booking</Button>
          </>
        }
      />

      {schedule.isError && <ErrorState error={schedule.error} onRetry={() => schedule.refetch()} />}
      {schedule.isPending && (
        <div className="card stack">{Array.from({ length: 6 }, (_, i) => <Skeleton key={i} h={44} />)}</div>
      )}
      {schedule.data && schedule.data.rooms.length === 0 && (
        <EmptyState icon={<DoorOpen size={28} />} title="No rooms yet">An administrator needs to add meeting rooms first.</EmptyState>
      )}

      {schedule.data && schedule.data.rooms.length > 0 && (
        <>
          <div className="legend" aria-hidden>
            <span><i className="dot mine" /> Your bookings</span>
            <span><i className="dot other" /> Booked by others</span>
            <span><i className="dot past" /> Past</span>
            <span className="muted">Click an empty area to book</span>
          </div>
          <div className="timeline card" role="region" aria-label="Room schedule" tabIndex={0}>
            <div className="tl-inner" style={{ gridTemplateColumns: `168px ${width}px` }}>
              <div className="tl-corner" />
              <div className="tl-hours" style={{ width }}>
                {hours.map((h) => (
                  <div key={h} className="tl-hour" style={{ left: x(h), width: HOUR_W }}>{fromMinutes(h)}</div>
                ))}
              </div>

              {schedule.data.rooms.map(({ room, bookings }) => (
                <div key={room.id} className="tl-row">
                  <div className="tl-room">
                    <span className="tl-room-name" title={room.name}>{room.name}</span>
                    <button className="icon-btn" aria-label={`Book ${room.name}`} onClick={() => openCreate(room.id)}><Plus size={16} /></button>
                  </div>
                  <div
                    className="tl-track"
                    style={{ width, backgroundSize: `${HOUR_W}px 100%` }}
                    onClick={(e) => rowClick(e, room.id)}
                    role="group"
                    aria-label={`${room.name}: ${bookings.length} bookings`}
                  >
                    {pastWidth > 0 && <div className="tl-past" style={{ width: pastWidth }} />}
                    {isToday && nowMin >= ws && nowMin <= we && <div className="tl-now" style={{ left: x(nowMin) }} aria-hidden />}
                    {bookings.map((b) => {
                      const s = toMinutes(wallTime(b.startTime));
                      const e = toMinutes(wallTime(b.endTime));
                      const mine = b.booker.id === user?.id;
                      return (
                        <button
                          key={b.id}
                          className={`tl-booking ${mine ? 'mine' : 'other'}`}
                          style={{ left: x(s) + 1, width: Math.max(x(e) - x(s) - 2, 8) }}
                          onClick={() => setSelected(b)}
                          title={`${b.title} · ${wallTime(b.startTime)}–${wallTime(b.endTime)} · ${b.booker.fullName}`}
                          aria-label={`${b.title}, ${wallTime(b.startTime)} to ${wallTime(b.endTime)}, ${b.booker.fullName}`}
                        >
                          <span className="tl-b-title">{b.title}</span>
                          <span className="tl-b-sub">{wallTime(b.startTime)}–{wallTime(b.endTime)} · {b.booker.fullName}</span>
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
