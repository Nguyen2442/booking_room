import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type { AppConfig, DaySchedule, FreeSlot, Page, Room, RoomSchedule } from './types';

export const useConfig = () =>
  useQuery({ queryKey: ['config'], queryFn: () => api<AppConfig>('/config'), staleTime: Infinity });

/** All rooms visible to the user (the API caps the page size at 100, far above ~10 rooms). */
export const useRooms = (active?: boolean) =>
  useQuery({
    queryKey: ['rooms', { active }],
    queryFn: () => api<Page<Room>>('/rooms', { query: { size: 100, sort: 'name', active } }),
  });

export const useDaySchedule = (date: string) =>
  useQuery({
    queryKey: ['bk', 'day', date],
    queryFn: () => api<DaySchedule>('/schedule', { query: { date } }),
    refetchInterval: 30_000, // other people book too: keep the grid fresh
    placeholderData: (prev) => prev,
  });

export const useRoomDay = (roomId: string | undefined, date: string) =>
  useQuery({
    queryKey: ['bk', 'room-day', roomId, date],
    queryFn: () => api<RoomSchedule>(`/rooms/${roomId}/schedule`, { query: { date } }),
    enabled: !!roomId && !!date,
  });

export const useFreeSlots = (roomId: string | undefined, date: string, minDuration: number, enabled: boolean) =>
  useQuery({
    queryKey: ['bk', 'free', roomId, date, minDuration],
    queryFn: () => api<FreeSlot[]>(`/rooms/${roomId}/free-slots`, { query: { date, minDuration } }),
    enabled: enabled && !!roomId && !!date,
  });

/** Everything derived from bookings becomes stale after any booking change. */
export function useInvalidateBookings() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: ['bk'] });
}
