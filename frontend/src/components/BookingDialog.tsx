import { useEffect, useMemo, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { CalendarClock, Lightbulb } from 'lucide-react';
import { Link } from 'react-router-dom';
import { api, ApiError, errorMessage } from '../api/client';
import { useConfig, useFreeSlots, useInvalidateBookings, useRoomDay, useRooms } from '../api/hooks';
import type { Booking } from '../api/types';
import { addDays, durationLabel, formatDate, fromMinutes, nowMinutesIn, todayIn, toMinutes, wallTime, zonedToIso } from '../lib/time';
import { useToast } from '../lib/toast';
import { Banner, Button, Field, Modal, Spinner } from './ui';

export interface BookingDraft {
  roomId?: string;
  date?: string;
  start?: string;
  end?: string;
  title?: string;
  description?: string;
}

interface Props {
  open: boolean;
  onClose: () => void;
  /** set when editing an existing booking */
  editing?: Booking;
  draft?: BookingDraft;
}

const QUICK = [30, 60, 90, 120];

export function BookingDialog({ open, onClose, editing, draft }: Props) {
  // Remount the form whenever the dialog opens so state always starts from the new draft.
  return (
    <Modal open={open} onClose={onClose} title={editing ? 'Edit booking' : 'New booking'}>
      {open && <BookingForm onClose={onClose} editing={editing} draft={draft} />}
    </Modal>
  );
}

function BookingForm({ onClose, editing, draft }: Omit<Props, 'open'>) {
  const cfg = useConfig().data!;
  const rooms = useRooms();
  const toast = useToast();
  const invalidate = useInvalidateBookings();

  const today = todayIn(cfg.timezone);
  const ws = toMinutes(cfg.workdayStart);
  const we = toMinutes(cfg.workdayEnd);
  const slot = cfg.slotMinutes;

  const initial = useMemo(() => {
    if (editing) {
      return {
        roomId: editing.room.id,
        date: editing.startTime.slice(0, 10),
        start: wallTime(editing.startTime),
        end: wallTime(editing.endTime),
        title: editing.title,
        description: editing.description ?? '',
      };
    }
    // default: next free slot boundary today (or opening time on other days)
    const nextSlot = Math.max(ws, Math.ceil((nowMinutesIn(cfg.timezone) + 1) / slot) * slot);
    const date = draft?.date ?? (nextSlot + 60 > we ? addDays(today, 1) : today);
    const start = draft?.start ?? (date === today ? fromMinutes(Math.min(nextSlot, we - 60)) : fromMinutes(ws));
    const end = draft?.end ?? fromMinutes(Math.min(toMinutes(start) + 60, we));
    return { roomId: draft?.roomId ?? '', date, start, end, title: draft?.title ?? '', description: draft?.description ?? '' };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const [roomId, setRoomId] = useState(initial.roomId);
  const [date, setDate] = useState(initial.date);
  const [start, setStart] = useState(initial.start);
  const [end, setEnd] = useState(initial.end);
  const [title, setTitle] = useState(initial.title);
  const [description, setDescription] = useState(initial.description);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [serverError, setServerError] = useState<ApiError | null>(null);

  useEffect(() => {
    if (!roomId && rooms.data?.content[0]) setRoomId(rooms.data.content[0].id);
  }, [roomId, rooms.data]);

  const duration = toMinutes(end) - toMinutes(start);
  const isToday = date === today;
  const nowMin = nowMinutesIn(cfg.timezone);

  const startOptions = useMemo(() => {
    const out: string[] = [];
    for (let m = ws; m <= we - cfg.minDurationMinutes; m += slot) out.push(fromMinutes(m));
    return out;
  }, [ws, we, slot, cfg.minDurationMinutes]);

  const endOptions = useMemo(() => {
    const s = toMinutes(start);
    const out: string[] = [];
    for (let m = s + cfg.minDurationMinutes; m <= Math.min(s + cfg.maxDurationMinutes, we); m += slot) out.push(fromMinutes(m));
    return out;
  }, [start, we, slot, cfg.minDurationMinutes, cfg.maxDurationMinutes]);

  function changeStart(v: string) {
    // keep the chosen duration when the start moves, clamped to what is allowed
    const dur = Math.max(duration, cfg.minDurationMinutes);
    setStart(v);
    setEnd(fromMinutes(Math.min(toMinutes(v) + dur, we, toMinutes(v) + cfg.maxDurationMinutes)));
  }

  // --- live availability feedback: warn *before* the user submits ---
  const day = useRoomDay(roomId, date);
  const busy = useMemo(() => {
    const s = toMinutes(start), e = toMinutes(end);
    return (day.data?.bookings ?? []).filter(
      (b) => b.id !== editing?.id && toMinutes(wallTime(b.startTime)) < e && toMinutes(wallTime(b.endTime)) > s,
    );
  }, [day.data, start, end, editing?.id]);

  const inPast = isToday && toMinutes(start) < nowMin;
  const free = useFreeSlots(roomId, date, Math.max(duration, slot), !!serverError && serverError.code === 'BOOKING_CONFLICT');
  const suggestions = useMemo(
    () => (free.data ?? []).filter((f) => f.minutes >= duration).slice(0, 6),
    [free.data, duration],
  );

  const save = useMutation({
    mutationFn: () => {
      const body = {
        title: title.trim(),
        description: description.trim() || undefined,
        startTime: zonedToIso(date, start, cfg.timezone),
        endTime: zonedToIso(date, end, cfg.timezone),
      };
      return editing
        ? api<Booking>(`/bookings/${editing.id}`, { method: 'PUT', body })
        : api<Booking>('/bookings', { method: 'POST', body: { ...body, roomId } });
    },
    onSuccess: () => {
      invalidate();
      toast.success(editing ? 'Booking updated' : 'Room booked');
      onClose();
    },
    onError: (e) => {
      if (e instanceof ApiError) {
        setServerError(e);
        setErrors(e.fieldErrors);
        if (e.code === 'BOOKING_CONFLICT') invalidate(); // someone else just took it: refresh what we show
      } else {
        setServerError(new ApiError(0, 'UNKNOWN', errorMessage(e)));
      }
    },
  });

  function submit(e: React.FormEvent) {
    e.preventDefault();
    const next: Record<string, string> = {};
    if (!roomId) next.roomId = 'Choose a room';
    if (!title.trim()) next.title = 'Give the meeting a title';
    setErrors(next);
    setServerError(null);
    if (Object.keys(next).length) return;
    save.mutate();
  }

  function applySuggestion(s: { start: string }) {
    const st = toMinutes(s.start.slice(11, 16));
    setStart(fromMinutes(st));
    setEnd(fromMinutes(st + duration));
    setServerError(null);
  }

  const maxDate = addDays(today, cfg.maxAdvanceDays);
  const roomOptions = rooms.data?.content ?? [];
  const selectedRoom = roomOptions.find((r) => r.id === roomId);

  return (
    <form onSubmit={submit} noValidate className="form">
      <Field label="Title" error={errors.title}>
        <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} autoFocus placeholder="e.g. Sprint planning" />
      </Field>

      <div className="grid-2">
        <Field label="Room" error={errors.roomId}>
          <select value={roomId} onChange={(e) => setRoomId(e.target.value)} disabled={!!editing || rooms.isPending}>
            {rooms.isPending && <option>Loading…</option>}
            {roomOptions.map((r) => (
              <option key={r.id} value={r.id}>{r.name} · {r.capacity} seats</option>
            ))}
          </select>
        </Field>
        <Field label="Date" error={errors.date}>
          <input type="date" value={date} min={today} max={maxDate} onChange={(e) => e.target.value && setDate(e.target.value)} />
        </Field>
      </div>

      <div className="grid-2">
        <Field label="Starts">
          <select value={start} onChange={(e) => changeStart(e.target.value)}>
            {startOptions.map((t) => (
              <option key={t} value={t} disabled={isToday && toMinutes(t) < nowMin}>{t}</option>
            ))}
          </select>
        </Field>
        <Field label="Ends">
          <select value={end} onChange={(e) => setEnd(e.target.value)}>
            {endOptions.map((t) => (
              <option key={t} value={t}>{t} ({durationLabel(toMinutes(t) - toMinutes(start))})</option>
            ))}
          </select>
        </Field>
      </div>

      <div className="quick" role="group" aria-label="Quick duration">
        {QUICK.filter((d) => d <= cfg.maxDurationMinutes && toMinutes(start) + d <= we).map((d) => (
          <button
            key={d}
            type="button"
            className={`chip chip-btn ${duration === d ? 'chip-active' : ''}`}
            onClick={() => setEnd(fromMinutes(toMinutes(start) + d))}
          >
            {durationLabel(d)}
          </button>
        ))}
        <span className="muted small">Bookable {cfg.workdayStart}–{cfg.workdayEnd}, up to {durationLabel(cfg.maxDurationMinutes)}</span>
      </div>

      <Field label="Description (optional)" error={errors.description}>
        <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={3} maxLength={2000} placeholder="Agenda, attendees, links…" />
      </Field>

      {inPast && <Banner tone="warning">That start time has already passed today.</Banner>}
      {!inPast && busy.length > 0 && !serverError && (
        <Banner tone="warning">
          <strong>{selectedRoom?.name ?? 'This room'}</strong> is busy at that time:{' '}
          {busy.map((b) => `${wallTime(b.startTime)}–${wallTime(b.endTime)} (${b.booker.fullName})`).join(', ')}.
        </Banner>
      )}
      {!inPast && busy.length === 0 && roomId && day.isSuccess && !serverError && (
        <p className="ok-line"><CalendarClock size={16} aria-hidden /> Free on {formatDate(date, { weekday: 'short', day: 'numeric', month: 'short' })}, {start}–{end}</p>
      )}

      {serverError && (
        <Banner tone="danger">
          {serverError.code === 'BOOKING_CONFLICT'
            ? 'Someone just booked this room for that time.'
            : serverError.message}
        </Banner>
      )}
      {serverError?.code === 'BOOKING_CONFLICT' && (
        <div className="suggest">
          <div className="suggest-title"><Lightbulb size={16} aria-hidden /> Free at these times in {selectedRoom?.name}</div>
          {free.isPending ? <Spinner /> : suggestions.length ? (
            <div className="quick">
              {suggestions.map((s) => (
                <button key={s.start} type="button" className="chip chip-btn" onClick={() => applySuggestion(s)}>
                  {s.start.slice(11, 16)}–{fromMinutes(toMinutes(s.start.slice(11, 16)) + duration)}
                </button>
              ))}
            </div>
          ) : (
            <p className="muted small">No gap of {durationLabel(duration)} left that day.</p>
          )}
          <Link className="link" to={`/find?date=${date}&start=${start}&end=${end}`} onClick={onClose}>Find another room for this time →</Link>
        </div>
      )}

      <div className="form-actions">
        <Button variant="ghost" onClick={onClose} disabled={save.isPending}>Cancel</Button>
        <Button variant="primary" type="submit" loading={save.isPending} disabled={inPast}>
          {editing ? 'Save changes' : 'Book room'}
        </Button>
      </div>
    </form>
  );
}
