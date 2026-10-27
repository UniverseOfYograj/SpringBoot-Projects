/**
 * Utilities and station data for Train Search.
 */

export interface StationOption {
  code: string;
  name: string;
  city: string;
}

/**
 * Curated list of major Indian Railways hub stations for quick selection pills.
 */
export const POPULAR_STATIONS: readonly StationOption[] = [
  { code: 'NDLS', name: 'New Delhi', city: 'Delhi' },
  { code: 'HWH',  name: 'Howrah Jn', city: 'Kolkata' },
  { code: 'CSMT', name: 'Chhatrapati Shivaji Maharaj Terminus', city: 'Mumbai' },
  { code: 'BCT',  name: 'Mumbai Central', city: 'Mumbai' },
  { code: 'MAS',  name: 'Chennai Central', city: 'Chennai' },
  { code: 'SBC',  name: 'KSR Bengaluru', city: 'Bengaluru' },
  { code: 'PNBE', name: 'Patna Jn', city: 'Patna' },
  { code: 'ADI',  name: 'Ahmedabad Jn', city: 'Ahmedabad' },
  { code: 'PUNE', name: 'Pune Jn', city: 'Pune' },
  { code: 'HYB',  name: 'Hyderabad Deccan', city: 'Hyderabad' },
] as const;

/**
 * Popular search pairs to help users quickly explore real data.
 */
export const POPULAR_ROUTES = [
  { from: 'HWH',  to: 'NDLS', label: 'Howrah → New Delhi' },
  { from: 'CSMT', to: 'NDLS', label: 'Mumbai CSMT → New Delhi' },
  { from: 'NDLS', to: 'PNBE', label: 'New Delhi → Patna' },
  { from: 'MAS',  to: 'SBC',  label: 'Chennai → Bengaluru' },
] as const;

/**
 * Formats backend time strings ("16:55:00" or "09:30") to clean "HH:mm".
 */
export function formatTimeString(timeStr: string | null | undefined): string {
  if (!timeStr) return '--:--';
  const parts = timeStr.trim().split(':');
  if (parts.length >= 2) {
    const hours = parts[0].padStart(2, '0');
    const minutes = parts[1].padStart(2, '0');
    return `${hours}:${minutes}`;
  }
  return timeStr;
}

/**
 * Calculates human-readable duration between departure and arrival,
 * accounting for day offset differences.
 *
 * Example:
 *   fromTime: "16:55:00", toTime: "10:05:00", fromOffset: 0, toOffset: 1
 *   -> "17h 10m"
 */
export function calculateDuration(
  fromTime: string | null | undefined,
  toTime: string | null | undefined,
  fromDayOffset: number = 0,
  toDayOffset: number = 0,
): string {
  if (!fromTime || !toTime) return '--';

  const [fromH, fromM] = fromTime.split(':').map((v) => parseInt(v, 10));
  const [toH, toM] = toTime.split(':').map((v) => parseInt(v, 10));

  if (isNaN(fromH) || isNaN(fromM) || isNaN(toH) || isNaN(toM)) {
    return '--';
  }

  const fromTotalMinutes = fromH * 60 + fromM;
  const dayDifference = Math.max(0, toDayOffset - fromDayOffset);
  const toTotalMinutes = toH * 60 + toM + dayDifference * 24 * 60;

  let diffMinutes = toTotalMinutes - fromTotalMinutes;

  // Handle case where arrival is next day but offsets are both 0 in source data
  if (diffMinutes < 0) {
    diffMinutes += 24 * 60;
  }

  const hours = Math.floor(diffMinutes / 60);
  const minutes = diffMinutes % 60;

  if (hours === 0 && minutes === 0) return '0m';
  if (hours === 0) return `${minutes}m`;
  if (minutes === 0) return `${hours}h`;
  return `${hours}h ${minutes}m`;
}

/**
 * Validates a single Indian Railways station code.
 * Rules:
 * - Must not be empty
 * - 2 to 5 characters long
 * - Letters only
 */
export function validateStationCode(code: string): string | null {
  const trimmed = code.trim().toUpperCase();
  if (!trimmed) {
    return 'Station code is required';
  }
  if (trimmed.length < 2) {
    return 'Station code must be at least 2 characters';
  }
  if (trimmed.length > 5) {
    return 'Station code cannot exceed 5 characters';
  }
  if (!/^[A-Z]+$/.test(trimmed)) {
    return 'Station code must contain letters only';
  }
  return null;
}

/**
 * Validates travel date string (YYYY-MM-DD).
 * Rules:
 * - If omitted/empty, returns null (date is optional context in timetable search)
 * - Must strictly match YYYY-MM-DD calendar date
 * - Must not be before today's local date
 */
export function validateTravelDate(dateStr: string): string | null {
  if (!dateStr || !dateStr.trim()) {
    return null;
  }
  const trimmed = dateStr.trim();
  const regex = /^\d{4}-\d{2}-\d{2}$/;
  if (!regex.test(trimmed)) {
    return 'Travel date must be in YYYY-MM-DD format';
  }
  const parsed = new Date(trimmed + 'T00:00:00');
  if (isNaN(parsed.getTime())) {
    return 'Invalid travel date';
  }
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  if (parsed < today) {
    return 'Travel date cannot be in the past';
  }
  return null;
}
