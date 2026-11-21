export type InterestTag =
  | 'ADVENTURE'
  | 'FOOD'
  | 'CULTURE'
  | 'NATURE'
  | 'NIGHTLIFE'
  | 'SHOPPING'
  | 'HISTORY'
  | 'RELAXATION';

export type TripPace = 'RELAXED' | 'MODERATE' | 'PACKED';

export type PoiCategory =
  | 'ATTRACTION'
  | 'RESTAURANT'
  | 'CAFE'
  | 'NATURE'
  | 'ADVENTURE'
  | 'CULTURE'
  | 'NIGHTLIFE'
  | 'SHOPPING';

export type SlotType = 'ACTIVITY' | 'LUNCH' | 'DINNER' | 'CAFE';

export interface GenerateTripRequest {
  destination: string;
  startDate: string;
  days: number;
  peopleCount: number;
  totalBudget: number;
  currency: string;
  pace: TripPace;
  interests: InterestTag[];
}

export interface StopResponse {
  stopId: string;
  poiId: string;
  name: string;
  category: PoiCategory;
  slotType: SlotType;
  orderIndex: number;
  startTime: string;
  endTime: string;
  travelMinsFromPrev: number;
  estimatedCost: number;
  priceTier: number;
  rating: number;
  lat: number;
  lng: number;
  locked: boolean;
  hoursEstimated: boolean;
  tags: Record<string, string>;
}

export interface DayPlanResponse {
  dayNumber: number;
  date: string;
  totalEstimatedCost: number;
  totalTravelMinutes: number;
  routePolyline: string;
  stops: StopResponse[];
}

export interface TripResponse {
  tripId: string;
  destination: string;
  centerLat: number;
  centerLng: number;
  startDate: string;
  days: number;
  peopleCount: number;
  totalBudget: number;
  currency: string;
  pace: TripPace;
  preferences: string[];
  estimatedTotalCost: number;
  foodCost: number;
  activityCost: number;
  routingMode: string;
  cacheHit: boolean;
  generationTimeMs: number;
  createdAt: string;
  dayPlans: DayPlanResponse[];
}

export interface SseProgressEvent {
  stage: string;
  message: string;
  progress: number;
  metadata?: Record<string, unknown>;
}
