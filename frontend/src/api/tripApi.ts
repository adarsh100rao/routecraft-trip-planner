import type {
  DayPlanResponse,
  GenerateTripRequest,
  StopResponse,
  TripResponse,
} from '../types/trip';

export const BACKEND_ORIGIN = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '');
const API_BASE = `${BACKEND_ORIGIN}/api/v1/trips`;

export async function startTripGeneration(
  request: GenerateTripRequest
): Promise<{ jobId: string; streamUrl: string }> {
  const res = await fetch(`${API_BASE}/generate`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({ message: 'Failed to start generation' }));
    throw new Error(err.message || `HTTP ${res.status}`);
  }
  return res.json();
}

export async function fetchTrip(tripId: string): Promise<TripResponse> {
  const res = await fetch(`${API_BASE}/${tripId}`);
  if (!res.ok) {
    throw new Error(`Failed to fetch trip (${res.status})`);
  }
  return res.json();
}

export async function toggleStopLock(
  tripId: string,
  stopId: string,
  isLocked: boolean
): Promise<StopResponse> {
  const res = await fetch(`${API_BASE}/${tripId}/stops/${stopId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ isLocked }),
  });
  if (!res.ok) {
    throw new Error('Failed to update stop lock');
  }
  return res.json();
}

export async function deleteStop(tripId: string, stopId: string): Promise<void> {
  const res = await fetch(`${API_BASE}/${tripId}/stops/${stopId}`, {
    method: 'DELETE',
  });
  if (!res.ok) {
    throw new Error('Failed to delete stop');
  }
}

export async function reoptimizeDay(
  tripId: string,
  dayNumber: number,
  excludePoiIds: string[],
  lockedStopIds: string[]
): Promise<DayPlanResponse> {
  const res = await fetch(`${API_BASE}/${tripId}/days/${dayNumber}/reoptimize`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ excludePoiIds, lockedStopIds }),
  });
  if (!res.ok) {
    throw new Error('Failed to re-optimize day');
  }
  return res.json();
}
