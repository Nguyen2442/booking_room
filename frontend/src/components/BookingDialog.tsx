import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { CalendarClock, Lightbulb, Lock, Repeat } from 'lucide-react';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import { useConfig, useFreeSlots, useInvalidateBookings, useRoomDay, useRooms } from '../api/hooks';
import type { Booking, Frequency, SeriesResponse } from '../api/types';
import { useI18n } from '../lib/i18n';
import {
  addDays, formatDate, fromMinutes, isWeekend, nowMinutesIn, occurrenceDates, todayIn, toMinutes, wallTime, zonedToIso,
} from '../lib/time';
import { useToast } from '../lib/toast';
import { Banner, Button, Field, Modal, Spinner } from './ui';

export interface BookingDraft {
  roomId?: string;
  date?: string;
  start?: string;
  end?: string;
  title?: string;
  description?: string;
  attendees?: number;
}

interface Props {
  open: boolean;
  onClose: () => void;
  /** set when editing an existing booking (the room may be changed to move it) */
  editing?: Booking;
  draft?: BookingDraft;
}

const QUICK = [30, 60, 90, 120];
type Policy = 'FAIL' | 'SKIP';
type RepeatMode = 'NONE' | Frequency;

export function BookingDialog({ open, onClose, editing, draft }: Props) {
  const { t } = useI18n();
  // The form is mounted only while open, so its state always starts from the current draft.
  return (
    <Modal open={open} onClose={onClose} title={editing ? t('bd.titleEdit') : t('bd.titleNew')}>
      {open && <BookingForm onClose={onClose} editing={editing} draft={draft} />}
    </Modal>
  );
}

function BookingForm({ onClose, editing, draft }: Omit<Props, 'open'>) {
  const cfg = useConfig().data!;
  const rooms = useRooms();
  const toast = useToast();
  const invalidate = useInvalidateBookings();
  const { t, dur, err } = useI18n();

  const today = todayIn(cfg.timezone);
  const ws = toMinutes(cfg.workdayStart);
  const we = toMinutes(cfg.workdayEnd);
  const slot = cfg.slotMinutes;
  const maxDate = addDays(today, cfg.maxAdvanceDays);

  const initial = useMemo(() => {
    if (editing) {
      return {
        roomId: editing.room.id, date: editing.startTime.slice(0, 10), start: wallTime(editing.startTime),
        end: wallTime(editing.endTime), title: editing.title, description: editing.description ?? '',
        attendees: editing.attendees ? String(editing.attendees) : '',
      };
    }
    // default: next free slot boundary today (or opening time on other days)
    const nextSlot = Math.max(ws, Math.ceil((nowMinutesIn(cfg.timezone) + 1) / slot) * slot);
    const date = draft?.date ?? (nextSlot + 60 > we ? addDays(today, 1) : today);
    const start = draft?.start ?? (date === today ? fromMinutes(Math.min(nextSlot, we - 60)) : fromMinutes(ws));
    const end = draft?.end ?? fromMinutes(Math.min(toMinutes(start) + 60, we));
    return {
      roomId: draft?.roomId ?? '', date, start, end, title: draft?.title ?? '', description: draft?.description ?? '',
      attendees: draft?.attendees ? String(draft.attendees) : '',
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const [roomId, setRoomId] = useState(initial.roomId);
  const [date, setDate] = useState(initial.date);
  const [start, setStart] = useState(initial.start);
  const [end, setEnd] = useState(initial.end);
  const [title, setTitle] = useState(initial.title);
  const [description, setDescription] = useState(initial.description);
  const [attendees, setAttendees] = useState(initial.attendees);
  const [isPrivate, setIsPrivate] = useState(editing?.private ?? false);
  const [repeat, setRepeat] = useState<RepeatMode>('NONE');
  const [occurrences, setOccurrences] = useState(4);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [serverError, setServerError] = useState<ApiError | null>(null);
  const alertRef = useRef<HTMLDivElement>(null);
  // the outcome of a submit is shown below a long form: make sure the user actually sees it
  useEffect(() => {
    if (serverError) alertRef.current?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }, [serverError]);

  useEffect(() => {
    if (!roomId && rooms.data?.content[0]) setRoomId(rooms.data.content[0].id);
  }, [roomId, rooms.data]);

  const duration = toMinutes(end) - toMinutes(start);
  const isToday = date === today;
  const nowMin = nowMinutesIn(cfg.timezone);
  const roomOptions = rooms.data?.content ?? [];
  const selectedRoom = roomOptions.find((r) => r.id === roomId);
  const attendeeCount = Number(attendees);
  const overCapacity = !!selectedRoom && Number.isInteger(attendeeCount) && attendeeCount > selectedRoom.capacity;

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
    const d = Math.max(duration, cfg.minDurationMinutes);
    setStart(v);
    setEnd(fromMinutes(Math.min(toMinutes(v) + d, we, toMinutes(v) + cfg.maxDurationMinutes)));
  }

  // ---- recurrence preview (mirrors the server's expansion and the booking horizon)
  const occAll = useMemo(() => (repeat === 'NONE' ? [] : occurrenceDates(date, repeat, 26)), [date, repeat]);
  const maxOccurrences = occAll.filter((d) => d <= maxDate).length;
  const effectiveOcc = Math.max(2, Math.min(occurrences, maxOccurrences));
  const occDates = useMemo(() => (repeat === 'NONE' ? [] : occurrenceDates(date, repeat, effectiveOcc)), [date, repeat, effectiveOcc]);
  const weekendProblem = repeat === 'WEEKDAYS' && isWeekend(date);
  const seriesProblem = repeat !== 'NONE' && (maxOccurrences < 2 || weekendProblem);

  // ---- live availability feedback: warn *before* the user submits
  const day = useRoomDay(roomId, date);
  const busy = useMemo(() => {
    const s = toMinutes(start), e = toMinutes(end);
    return (day.data?.bookings ?? []).filter(
      (b) => b.id !== editing?.id && toMinutes(wallTime(b.startTime)) < e && toMinutes(wallTime(b.endTime)) > s,
    );
  }, [day.data, start, end, editing?.id]);

  const inPast = isToday && toMinutes(start) < nowMin;
  const conflictCode = serverError?.code;
  const free = useFreeSlots(roomId, date, Math.max(duration, slot), conflictCode === 'BOOKING_CONFLICT');
  const suggestions = useMemo(() => (free.data ?? []).filter((f) => f.minutes >= duration).slice(0, 6), [free.data, duration]);
  const conflictDates: string[] = conflictCode === 'SERIES_CONFLICT' ? (serverError?.problem.conflicts ?? []) : [];

  const save = useMutation<Booking | SeriesResponse, unknown, Policy>({
    mutationFn: (policy) => {
      const base = {
        title: title.trim(),
        description: description.trim() || undefined,
        startTime: zonedToIso(date, start, cfg.timezone),
        endTime: zonedToIso(date, end, cfg.timezone),
        attendees: attendeeCount,
        private: isPrivate,
      };
      if (editing) return api<Booking>(`/bookings/${editing.id}`, { method: 'PUT', body: { ...base, roomId } });
      if (repeat !== 'NONE') {
        return api<SeriesResponse>('/bookings/series', {
          method: 'POST', body: { ...base, roomId, frequency: repeat, occurrences: effectiveOcc, onConflict: policy },
        });
      }
      return api<Booking>('/bookings', { method: 'POST', body: { ...base, roomId } });
    },
    onSuccess: (res) => {
      invalidate();
      if ('created' in res) {
        toast.success(res.skipped.length
          ? t('rec.createdSkipped', { n: res.created.length, skipped: res.skipped.length })
          : t('rec.created', { n: res.created.length }));
      } else {
        toast.success(editing ? t('bd.updated') : t('bd.booked'));
      }
      onClose();
    },
    onError: (e) => {
      if (e instanceof ApiError) {
        setServerError(e);
        setErrors(e.fieldErrors);
        if (e.code === 'BOOKING_CONFLICT' || e.code === 'SERIES_CONFLICT') invalidate(); // somebody took it: refresh what we show
      } else {
        setServerError(new ApiError(0, 'UNKNOWN', ''));
      }
    },
  });

  function submit(e: React.FormEvent, policy: Policy = 'FAIL') {
    e.preventDefault();
    const next: Record<string, string> = {};
    if (!roomId) next.roomId = t('bd.chooseRoom');
    if (!title.trim()) next.title = t('bd.needTitle');
    if (!attendees || !Number.isInteger(attendeeCount) || attendeeCount < 1) next.attendees = t('bd.attendeesRequired');
    else if (overCapacity && selectedRoom) next.attendees = t('bd.overCapacity', { room: selectedRoom.name, n: selectedRoom.capacity });
    setErrors(next);
    setServerError(null);
    if (Object.keys(next).length) return;
    save.mutate(policy);
  }

  function applySuggestion(s: { start: string }) {
    const st = toMinutes(s.start.slice(11, 16));
    setStart(fromMinutes(st));
    setEnd(fromMinutes(st + duration));
    setServerError(null);
  }

  const shortDate = (d: string) => formatDate(d, { weekday: 'short', day: 'numeric', month: 'short' });
  const movedRoom = !!editing && roomId !== editing.room.id;

  return (
    <form onSubmit={submit} noValidate className="form">
      <Field label={t('bd.title')} error={errors.title}>
        <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} autoFocus placeholder={t('bd.titlePh')} />
      </Field>

      <div className="grid-2">
        <Field label={t('bd.room')} error={errors.roomId} hint={movedRoom ? t('bd.moveHint') : undefined}>
          <select value={roomId} onChange={(e) => setRoomId(e.target.value)} disabled={rooms.isPending}>
            {rooms.isPending && <option>{t('bd.loading')}</option>}
            {roomOptions.map((r) => (
              <option key={r.id} value={r.id}>{t('bd.roomOption', { name: r.name, n: r.capacity })}</option>
            ))}
          </select>
        </Field>
        <Field
          label={t('bd.attendees')} error={errors.attendees}
          hint={selectedRoom ? t('bd.attendeesHint', { n: selectedRoom.capacity, room: selectedRoom.name }) : undefined}
        >
          <input
            type="number" inputMode="numeric" min={1} max={selectedRoom?.capacity ?? 1000}
            value={attendees} onChange={(e) => setAttendees(e.target.value)}
          />
        </Field>
      </div>

      <div className="grid-2">
        <Field label={t('common.date')} error={errors.date}>
          <input type="date" value={date} min={today} max={maxDate} onChange={(e) => e.target.value && setDate(e.target.value)} />
        </Field>
        {!editing && (
          <Field label={t('rec.repeat')}>
            <select value={repeat} onChange={(e) => { setRepeat(e.target.value as RepeatMode); setServerError(null); }}>
              <option value="NONE">{t('rec.none')}</option>
              <option value="WEEKLY">{t('rec.weekly')}</option>
              <option value="BIWEEKLY">{t('rec.biweekly')}</option>
              <option value="WEEKDAYS">{t('rec.weekdays')}</option>
            </select>
          </Field>
        )}
      </div>

      <div className="grid-2">
        <Field label={t('bd.starts')}>
          <select value={start} onChange={(e) => changeStart(e.target.value)}>
            {startOptions.map((x) => (
              <option key={x} value={x} disabled={isToday && toMinutes(x) < nowMin}>{x}</option>
            ))}
          </select>
        </Field>
        <Field label={t('bd.ends')}>
          <select value={end} onChange={(e) => setEnd(e.target.value)}>
            {endOptions.map((x) => (
              <option key={x} value={x}>{x} ({dur(toMinutes(x) - toMinutes(start))})</option>
            ))}
          </select>
        </Field>
      </div>

      <div className="quick" role="group" aria-label={t('bd.quick')}>
        {QUICK.filter((d) => d <= cfg.maxDurationMinutes && toMinutes(start) + d <= we).map((d) => (
          <button key={d} type="button" className={`chip chip-btn ${duration === d ? 'chip-active' : ''}`} onClick={() => setEnd(fromMinutes(toMinutes(start) + d))}>
            {dur(d)}
          </button>
        ))}
        <span className="muted small">{t('bd.hours', { start: cfg.workdayStart, end: cfg.workdayEnd, max: dur(cfg.maxDurationMinutes) })}</span>
      </div>

      {repeat !== 'NONE' && !editing && (
        <div className="repeat-box">
          <Field label={t('rec.times')}>
            <input
              type="number" inputMode="numeric" min={2} max={Math.max(2, maxOccurrences)}
              value={maxOccurrences >= 2 ? effectiveOcc : occurrences}
              onChange={(e) => setOccurrences(Math.max(2, Math.min(26, Number(e.target.value) || 2)))}
            />
          </Field>
          {weekendProblem && <Banner tone="warning">{t('rec.weekendStart')}</Banner>}
          {!weekendProblem && maxOccurrences >= 2 && (
            <p className="ok-line"><Repeat size={16} aria-hidden /> {t('rec.summary', { n: occDates.length, date: formatDate(occDates[occDates.length - 1], { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' }) })}</p>
          )}
          {!weekendProblem && maxOccurrences < 26 && occurrences > maxOccurrences && maxOccurrences >= 2 && (
            <p className="muted small">{t('rec.limit', { n: maxOccurrences, days: cfg.maxAdvanceDays })}</p>
          )}
          {maxOccurrences < 2 && !weekendProblem && <Banner tone="warning">{t('rec.limit', { n: maxOccurrences, days: cfg.maxAdvanceDays })}</Banner>}
        </div>
      )}

      <Field label={t('bd.desc')} error={errors.description}>
        <textarea value={description} onChange={(e) => setDescription(e.target.value)} rows={3} maxLength={2000} placeholder={t('bd.descPh')} />
      </Field>

      <div className="field">
        <label className="check"><input type="checkbox" checked={isPrivate} onChange={(e) => setIsPrivate(e.target.checked)} /> <Lock size={15} aria-hidden /> {t('priv.checkbox')}</label>
        <span className="field-hint">{t('priv.hint')}</span>
      </div>

      {inPast && <Banner tone="warning">{t('bd.pastWarn')}</Banner>}
      {!inPast && repeat === 'NONE' && busy.length > 0 && !serverError && (
        <Banner tone="warning">
          {t('bd.busy', { room: selectedRoom?.name ?? '', list: busy.map((b) => `${wallTime(b.startTime)}–${wallTime(b.endTime)} (${b.booker?.fullName ?? t('priv.label')})`).join(', ') })}
        </Banner>
      )}
      {!inPast && repeat === 'NONE' && busy.length === 0 && roomId && day.isSuccess && !serverError && (
        <p className="ok-line"><CalendarClock size={16} aria-hidden /> {t('bd.free', { date: shortDate(date), start, end })}</p>
      )}

      {serverError && <div ref={alertRef} className="alerts">
      {serverError && conflictCode !== 'SERIES_CONFLICT' && (
        <Banner tone="danger">{conflictCode === 'BOOKING_CONFLICT' ? t('bd.conflict') : err(serverError)}</Banner>
      )}

      {conflictCode === 'SERIES_CONFLICT' && conflictDates.length > 0 && (
        <div className="suggest">
          <Banner tone="danger">{t('rec.conflictTitle', { n: conflictDates.length, total: occDates.length })}</Banner>
          <div className="quick">{conflictDates.map((d) => <span key={d} className="chip">{shortDate(d)}</span>)}</div>
          {conflictDates.length < occDates.length && (
            <Button variant="secondary" loading={save.isPending} onClick={(e) => submit(e as unknown as React.FormEvent, 'SKIP')}>{t('rec.skip')}</Button>
          )}
        </div>
      )}
      {conflictCode === 'SERIES_CONFLICT' && conflictDates.length === 0 && serverError && <Banner tone="danger">{err(serverError)}</Banner>}

      {conflictCode === 'BOOKING_CONFLICT' && (
        <div className="suggest">
          <div className="suggest-title"><Lightbulb size={16} aria-hidden /> {t('bd.suggest', { room: selectedRoom?.name ?? '' })}</div>
          {free.isPending ? <Spinner /> : suggestions.length ? (
            <div className="quick">
              {suggestions.map((s) => (
                <button key={s.start} type="button" className="chip chip-btn" onClick={() => applySuggestion(s)}>
                  {s.start.slice(11, 16)}–{fromMinutes(toMinutes(s.start.slice(11, 16)) + duration)}
                </button>
              ))}
            </div>
          ) : (
            <p className="muted small">{t('bd.noGap', { dur: dur(duration) })}</p>
          )}
          <Link className="link" to={`/find?date=${date}&start=${start}&end=${end}&attendees=${attendees}`} onClick={onClose}>{t('bd.otherRoom')}</Link>
        </div>
      )}
      </div>}

      <div className="form-actions">
        <Button variant="ghost" onClick={onClose} disabled={save.isPending}>{t('common.dismiss')}</Button>
        <Button variant="primary" type="submit" loading={save.isPending} disabled={inPast || seriesProblem}>
          {editing ? t('common.save') : repeat !== 'NONE' && maxOccurrences >= 2 ? t('rec.submit', { n: occDates.length }) : t('bd.submitNew')}
        </Button>
      </div>
    </form>
  );
}
