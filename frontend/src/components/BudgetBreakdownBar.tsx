import type { TripResponse } from '../types/trip';

interface Props {
  trip: TripResponse;
}

export function BudgetBreakdownBar({ trip }: Props) {
  const foodPct = Math.min(100, (trip.foodCost / trip.totalBudget) * 100);
  const actPct = Math.min(100 - foodPct, (trip.activityCost / trip.totalBudget) * 100);
  const remaining = Math.max(0, trip.totalBudget - trip.estimatedTotalCost);
  const symbol = trip.currency === 'INR' ? '₹' : trip.currency === 'EUR' ? '€' : '$';

  return (
    <div className="rounded-xl border border-[#24304A] bg-[#131C2E]/90 p-4">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2 text-xs">
        <span className="font-semibold text-slate-100">
          Budget Usage: {symbol}
          {trip.estimatedTotalCost.toLocaleString()} / {symbol}
          {trip.totalBudget.toLocaleString()}
        </span>
        <span className="font-medium text-[#FFB547]">
          {symbol}
          {remaining.toLocaleString()} buffer remaining
        </span>
      </div>

      <div className="flex h-2.5 w-full overflow-hidden rounded-full bg-[#0B1220]">
        <div
          className="bg-[#FFB547] transition-all"
          style={{ width: `${foodPct}%` }}
          title={`Food: ${symbol}${trip.foodCost.toLocaleString()}`}
        />
        <div
          className="bg-[#FF6B4A] transition-all"
          style={{ width: `${actPct}%` }}
          title={`Activities: ${symbol}${trip.activityCost.toLocaleString()}`}
        />
      </div>

      <div className="mt-2 flex flex-wrap gap-4 text-[11px] text-slate-400">
        <span className="flex items-center gap-1.5">
          <span className="h-2 w-2 rounded-full bg-[#FFB547]" />
          Food & Dining: {symbol}
          {trip.foodCost.toLocaleString()}
        </span>
        <span className="flex items-center gap-1.5">
          <span className="h-2 w-2 rounded-full bg-[#FF6B4A]" />
          Places & Activities: {symbol}
          {trip.activityCost.toLocaleString()}
        </span>
      </div>
    </div>
  );
}
