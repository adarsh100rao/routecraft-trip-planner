import { useEffect, useState } from 'react';
import { ArrowLeft, Compass, Loader2, Map, SlidersHorizontal, Wallet } from 'lucide-react';
import { useTripStream } from './hooks/useTripStream';
import { TripInputForm } from './components/TripInputForm';
import { GenerationStepper } from './components/GenerationStepper';
import { BudgetBreakdownBar } from './components/BudgetBreakdownBar';
import { DayTimeline } from './components/DayTimeline';
import { InteractiveMap } from './components/InteractiveMap';
import { deleteStop, fetchTrip, reoptimizeDay, toggleStopLock } from './api/tripApi';

function updateUrlParams(tripId: string | null, day?: number) {
  const url = new URL(window.location.href);
  if (tripId) {
    url.searchParams.set('tripId', tripId);
    url.searchParams.set('day', String(day ?? 1));
  } else {
    url.searchParams.delete('tripId');
    url.searchParams.delete('day');
  }
  window.history.replaceState(null, '', url.toString());
}

export function App() {
  const { isGenerating, stages, progress, error, trip, setTrip, generate, reset } = useTripStream();
  const [activeDay, setActiveDay] = useState<number>(() => {
    const dayParam = Number(new URLSearchParams(window.location.search).get('day'));
    return Number.isInteger(dayParam) && dayParam >= 1 ? dayParam : 1;
  });
  const [isHydrating, setIsHydrating] = useState<boolean>(() =>
    Boolean(new URLSearchParams(window.location.search).get('tripId'))
  );
  const [hoveredStopId, setHoveredStopId] = useState<string | null>(null);
  const [excludedByDay, setExcludedByDay] = useState<Record<number, string[]>>({});
  const [isReoptimizing, setIsReoptimizing] = useState(false);

  // Restore saved trip from PostgreSQL on page refresh if ?tripId=... is present
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const savedTripId = params.get('tripId');
    if (!savedTripId) return;

    fetchTrip(savedTripId)
      .then((loaded) => {
        setTrip(loaded);
        const dayParam = Number(params.get('day'));
        if (Number.isInteger(dayParam) && dayParam >= 1 && dayParam <= loaded.days) {
          setActiveDay(dayParam);
        } else {
          setActiveDay(1);
        }
      })
      .catch(() => {
        updateUrlParams(null);
      })
      .finally(() => setIsHydrating(false));
  }, [setTrip]);

  // Keep URL synced with active trip & day so browser refresh preserves exact page state
  useEffect(() => {
    if (trip) {
      updateUrlParams(trip.tripId, activeDay);
    }
  }, [trip, activeDay]);

  const activeDayPlan = trip?.dayPlans.find((d) => d.dayNumber === activeDay) ?? trip?.dayPlans[0];

  const handleToggleLock = async (stopId: string, locked: boolean) => {
    if (!trip) return;
    await toggleStopLock(trip.tripId, stopId, locked);
    const updated = await fetchTrip(trip.tripId);
    setTrip(updated);
  };

  const handleDeleteStop = async (stopId: string, poiId: string) => {
    if (!trip) return;
    setExcludedByDay((prev) => ({
      ...prev,
      [activeDay]: [...(prev[activeDay] ?? []), poiId],
    }));
    await deleteStop(trip.tripId, stopId);
    const updated = await fetchTrip(trip.tripId);
    setTrip(updated);
  };

  const handleReoptimizeDay = async () => {
    if (!trip || !activeDayPlan) return;
    setIsReoptimizing(true);
    try {
      const lockedIds = activeDayPlan.stops.filter((s) => s.locked).map((s) => s.stopId);
      const excluded = excludedByDay[activeDay] ?? [];
      await reoptimizeDay(trip.tripId, activeDayPlan.dayNumber, excluded, lockedIds);
      const updated = await fetchTrip(trip.tripId);
      setTrip(updated);
    } finally {
      setIsReoptimizing(false);
    }
  };

  const showLanding = !isHydrating && !trip && !isGenerating;

  return (
    <div className="app-sunset-bg relative min-h-screen overflow-x-clip text-slate-100">
      {/* Soothing India Gate twilight backdrop on the landing screen */}
      {showLanding && (
        <div className="pointer-events-none fixed inset-0 overflow-hidden" aria-hidden="true">
          <img
            src="/india-gate-hero.jpg"
            alt=""
            className="h-full w-full scale-[1.02] object-cover object-center opacity-30 saturate-[0.85]"
          />
          {/* Soft deep-navy & warm sunset shade overlays for calm, eye-friendly contrast */}
          <div className="absolute inset-0 bg-gradient-to-b from-[#0B1220]/75 via-[#0B1220]/60 to-[#0B1220]" />
          <div className="absolute inset-0 bg-[radial-gradient(ellipse_80%_50%_at_50%_-10%,rgba(255,107,74,0.16),transparent_60%),radial-gradient(ellipse_60%_40%_at_90%_10%,rgba(255,181,71,0.10),transparent_60%)]" />
          <div className="absolute inset-0 bg-[radial-gradient(ellipse_80%_65%_at_50%_35%,transparent_10%,rgba(11,18,32,0.84)_100%)]" />
        </div>
      )}

      <header className="relative z-10 border-b border-[#24304A]/80 bg-[#0B1220]/75 backdrop-blur-md">
        <div className="mx-auto flex max-w-7xl items-center justify-between px-4 py-3.5">
          <div className="flex items-center gap-2.5">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-[#FF6B4A]/15 ring-1 ring-[#FF6B4A]/35">
              <Compass className="h-5 w-5 text-[#FF6B4A]" />
            </div>
            <span className="text-base font-bold tracking-tight text-slate-100">RouteCraft</span>
          </div>

          {trip && (
            <button
              onClick={() => {
                setActiveDay(1);
                setExcludedByDay({});
                updateUrlParams(null);
                reset();
              }}
              className="inline-flex items-center gap-1.5 rounded-lg border border-[#24304A] bg-[#131C2E]/80 px-3 py-1.5 text-xs font-medium text-slate-200 transition hover:border-[#FF6B4A]/50 hover:text-white"
            >
              <ArrowLeft className="h-3.5 w-3.5" /> New Trip
            </button>
          )}
        </div>
      </header>

      <main className="relative z-10 mx-auto max-w-7xl px-4 py-8 sm:py-12">
        {error && (
          <div className="mx-auto mb-6 max-w-2xl rounded-xl border border-rose-500/40 bg-rose-500/10 px-4 py-3 text-sm text-rose-300">
            {error}
          </div>
        )}

        {isHydrating && (
          <div className="flex flex-col items-center justify-center py-24 text-sm text-slate-400">
            <Loader2 className="mb-3 h-6 w-6 animate-spin text-[#FF6B4A]" />
            Restoring your saved itinerary...
          </div>
        )}

        {showLanding && (
          <div className="mx-auto max-w-4xl">
            {/* Hero copy */}
            <div className="mx-auto mb-8 max-w-2xl text-center sm:mb-10">
              <div className="mb-4 inline-flex items-center gap-2 rounded-full border border-[#FF6B4A]/30 bg-[#FF6B4A]/10 px-3.5 py-1 text-xs font-medium text-[#FFB547] backdrop-blur-sm">
                <span className="h-1.5 w-1.5 rounded-full bg-[#FF6B4A]" />
                Smart Day-by-Day Travel Planner
              </div>
              <h1 className="text-3xl font-bold tracking-tight text-slate-100 sm:text-5xl sm:leading-[1.12]">
                Craft your ideal trip{' '}
                <span className="bg-gradient-to-r from-[#FF6B4A] via-[#FF8566] to-[#FFB547] bg-clip-text text-transparent">
                  in seconds
                </span>
              </h1>
              <p className="mx-auto mt-3.5 max-w-xl text-sm leading-relaxed text-slate-400 sm:text-base">
                Tell us where you&apos;re headed, your pace, and your budget. We&apos;ll map out a
                balanced itinerary with real places, realistic travel times, and zero backtracking.
              </p>
            </div>

            {/* Form card with subtle sunset glow behind it */}
            <div className="relative mx-auto max-w-2xl">
              <div
                className="pointer-events-none absolute -inset-1 rounded-[26px] bg-gradient-to-r from-[#FF6B4A]/30 via-[#FF8566]/15 to-[#FFB547]/25 opacity-80 blur-2xl"
                aria-hidden="true"
              />
              <TripInputForm
                onSubmit={(req) => {
                  setActiveDay(1);
                  generate(req);
                }}
                disabled={isGenerating}
              />
            </div>

            {/* Compact value pillars */}
            <div className="mx-auto mt-10 grid max-w-3xl grid-cols-1 gap-4 sm:mt-12 sm:grid-cols-3">
              <div className="rounded-2xl border border-[#24304A] bg-[#131C2E]/80 p-4 backdrop-blur-sm">
                <div className="mb-2.5 flex h-9 w-9 items-center justify-center rounded-xl bg-[#FF6B4A]/15 text-[#FF6B4A]">
                  <Map className="h-4 w-4" />
                </div>
                <h3 className="text-sm font-semibold text-slate-100">Smarter Daily Routes</h3>
                <p className="mt-1 text-xs leading-relaxed text-slate-400">
                  Nearby attractions and dining are grouped together so you spend less time in transit.
                </p>
              </div>

              <div className="rounded-2xl border border-[#24304A] bg-[#131C2E]/80 p-4 backdrop-blur-sm">
                <div className="mb-2.5 flex h-9 w-9 items-center justify-center rounded-xl bg-[#FFB547]/15 text-[#FFB547]">
                  <Wallet className="h-4 w-4" />
                </div>
                <h3 className="text-sm font-semibold text-slate-100">Built Around Your Budget</h3>
                <p className="mt-1 text-xs leading-relaxed text-slate-400">
                  Every stop and meal is matched to your spending limit, from street food to fine dining.
                </p>
              </div>

              <div className="rounded-2xl border border-[#24304A] bg-[#131C2E]/80 p-4 backdrop-blur-sm">
                <div className="mb-2.5 flex h-9 w-9 items-center justify-center rounded-xl bg-[#FF8566]/15 text-[#FF8566]">
                  <SlidersHorizontal className="h-4 w-4" />
                </div>
                <h3 className="text-sm font-semibold text-slate-100">Flexible On The Fly</h3>
                <p className="mt-1 text-xs leading-relaxed text-slate-400">
                  Lock your favorite stops, remove anything you don&apos;t want, and refresh a day in one click.
                </p>
              </div>
            </div>
          </div>
        )}

        {isGenerating && <GenerationStepper stages={stages} progress={progress} />}

        {trip && activeDayPlan && (
          <div className="space-y-5">
            <div className="flex flex-wrap items-end justify-between gap-4">
              <div>
                <h1 className="text-2xl font-bold text-slate-100">{trip.destination}</h1>
                <p className="text-xs text-slate-400">
                  {trip.days} Days · {trip.peopleCount}{' '}
                  {trip.peopleCount === 1 ? 'Traveler' : 'Travelers'} · {trip.pace} Pace
                </p>
              </div>

              <div className="flex gap-1.5">
                {trip.dayPlans.map((d) => (
                  <button
                    key={d.dayNumber}
                    onClick={() => setActiveDay(d.dayNumber)}
                    className={`rounded-lg px-3.5 py-1.5 text-xs font-semibold transition ${
                      activeDayPlan.dayNumber === d.dayNumber
                        ? 'bg-[#FF6B4A] text-[#1A0F0A] shadow shadow-[#FF6B4A]/25'
                        : 'border border-[#24304A] bg-[#131C2E] text-slate-400 hover:border-[#FF6B4A]/40 hover:text-slate-100'
                    }`}
                  >
                    Day {d.dayNumber}
                  </button>
                ))}
              </div>
            </div>

            <BudgetBreakdownBar trip={trip} />

            <div className="grid grid-cols-1 gap-5 lg:grid-cols-12">
              <div className="lg:col-span-5">
                <DayTimeline
                  dayPlan={activeDayPlan}
                  currency={trip.currency}
                  hoveredStopId={hoveredStopId}
                  isReoptimizing={isReoptimizing}
                  onHoverStop={setHoveredStopId}
                  onToggleLock={handleToggleLock}
                  onDeleteStop={handleDeleteStop}
                  onReoptimize={handleReoptimizeDay}
                />
              </div>

              <div className="lg:col-span-7 lg:sticky lg:top-6 lg:h-[calc(100vh-14rem)]">
                <InteractiveMap
                  center={[trip.centerLat, trip.centerLng]}
                  currency={trip.currency}
                  activeDayPlan={activeDayPlan}
                  hoveredStopId={hoveredStopId}
                  onSelectStop={setHoveredStopId}
                />
              </div>
            </div>
          </div>
        )}
      </main>
    </div>
  );
}

export default App;
