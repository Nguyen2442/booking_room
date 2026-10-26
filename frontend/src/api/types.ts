export type Role = 'USER' | 'ADMIN';
export type BookingStatus = 'CONFIRMED' | 'CANCELLED';
export type BookingKind = 'BOOKING' | 'BLOCK';
export type Frequency = 'WEEKLY' | 'BIWEEKLY' | 'WEEKDAYS';

export interface User {
  id: string;
  email: string;
  fullName: string;
  role: Role;
  active: boolean;
  createdAt: string;
}

export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  user: User;
}

export interface Room {
  id: string;
  name: string;
  location?: string;
  capacity: number;
  equipment: string[];
  description?: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface RoomRef {
  id: string;
  name: string;
}

export interface Booking {
  id: string;
  kind: BookingKind;
  attendees?: number;
  seriesId?: string;
  room: RoomRef;
  /** absent when the booking is private and the viewer may not see its details */
  booker?: { id: string; fullName: string };
  private?: boolean;
  /** true when details were hidden for this viewer; `title` is then only a placeholder */
  masked?: boolean;
  title: string;
  description?: string;
  /** ISO-8601 in the company time zone, e.g. 2026-10-27T10:00:00+07:00 */
  startTime: string;
  endTime: string;
  status: BookingStatus;
  cancelledAt?: string;
  cancelReason?: string;
  /** somebody other than the owner (an administrator) cancelled it */
  cancelledByAnother?: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface AppConfig {
  timezone: string;
  workdayStart: string;
  workdayEnd: string;
  slotMinutes: number;
  minDurationMinutes: number;
  maxDurationMinutes: number;
  maxAdvanceDays: number;
  maxSeriesDays: number;
  maxActiveBookings: number;
  maxBookedMinutesPerDay: number;
  startGraceMinutes: number;
}

export interface FreeSlot {
  start: string;
  end: string;
  minutes: number;
}

export interface RoomSchedule {
  room: RoomRef;
  from: string;
  to: string;
  bookings: Booking[];
}

export interface DaySchedule {
  date: string;
  rooms: RoomSchedule[];
}

export interface SeriesResponse {
  seriesId: string;
  created: Booking[];
  skipped: { date: string; reason: string }[];
}

export interface BlockResult {
  block: Booking;
  cancelledBookings: Booking[];
}

export interface BlockConflict {
  id: string;
  title: string;
  booker: string;
  startTime: string;
  endTime: string;
}
