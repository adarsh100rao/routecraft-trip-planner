import { useState } from 'react';
import { Compass, MapPin, Users, Wallet, Calendar, Sparkles } from 'lucide-react';
import type { GenerateTripRequest, InterestTag, TripPace } from '../types/trip';

const ALL_INTERESTS: { tag: InterestTag; label: string }[] = [
  { tag: 'ADVENTURE', label: 'Adventure' },
  { tag: 'FOOD', label: 'Local Food' },
  { tag: 'CULTURE', label: 'Culture' },
  { tag: 'HISTORY', label: 'History' },
  { tag: 'NATURE', label: 'Nature' },
  { tag: 'NIGHTLIFE', label: 'Nightlife' },
  { tag: 'SHOPPING', label: 'Shopping' },
  { tag: 'RELAXATION', label: 'Relaxation' },
];

const POPULAR_DESTINATIONS = [
  'Jaipur, India',
  'Goa, India',
  'Manali, India',
  'Tokyo, Japan',
  'Paris, France',
];

const PACES: { value: TripPace; label: string; desc: string }[] = [
  { value: 'RELAXED', label: 'Relaxed', desc: '10am–8pm · 4 stops/day' },
  { value: 'MODERATE', label: 'Moderate', desc: '9am–9pm · 6 stops/day' },
  { value: 'PACKED', label: 'Packed', desc: '8:30am–10pm · 8 stops/day' },
];

interface Props {
  onSubmit: (req: GenerateTripRequest) => void;
  disabled: boolean;
}

export function TripInputForm({ onSubmit, disabled }: Props) {
  const [destination, setDestination] = useState('');
  const [startDate, setStartDate] = useState(() => {
    const d = new Date();
    d.setDate(d.getDate() + 7);
    return d.toISOString().split('T')[0];
  });
  const [days, setDays] = useState(3);
  const [peopleCount, setPeopleCount] = useState(2);
  const [totalBudget, setTotalBudget] = useState(18000);
  const [currency, setCurrency] = useState('INR');
  const [pace, setPace] = useState<TripPace>('MODERATE');
  const [interests, setInterests] = useState<InterestTag[]>(['CULTURE', 'FOOD', 'ADVENTURE']);

  const toggleInterest = (tag: InterestTag) => {
    setInterests((prev) =>
      prev.includes(tag)
        ? prev.length > 1
          ? prev.filter((t) => t !== tag)
          : prev
        : [...prev, tag]
    );
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!destination.trim()) return;
    onSubmit({
      destination: destination.trim(),
      startDate,
      days,
      peopleCount,
      totalBudget,
      currency,
      pace,
      interests,
    });
  };

  return (
    <form
      onSubmit={handleSubmit}
      className="relative rounded-2xl border border-[#24304A] bg-[#131C2E]/90 p-6 shadow-2xl shadow-[#0B1220]/80 ring-1 ring-white/5 backdrop-blur-xl sm:p-7"
    >
      <div className="mb-6 flex items-center gap-3">
        <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-[#FF6B4A]/25 to-[#FFB547]/20 text-[#FF6B4A] ring-1 ring-[#FF6B4A]/35">
          <Compass className="h-5 w-5" />
        </div>
        <div>
          <h2 className="text-lg font-semibold text-slate-100">Plan Your Trip</h2>
          <p className="text-xs text-slate-400">
            Customize your destination, budget, and travel style to get started.
          </p>
        </div>
      </div>

      <div className="space-y-5">
        <div>
          <label className="mb-1.5 flex items-center gap-1.5 text-sm font-medium text-slate-200">
            <MapPin className="h-4 w-4 text-[#FF6B4A]" /> Destination
          </label>
          <input
            type="text"
            required
            value={destination}
            onChange={(e) => setDestination(e.target.value)}
            placeholder="e.g., Jaipur, India or Goa, India or Tokyo, Japan"
            className="w-full rounded-xl border border-[#24304A] bg-[#0B1220]/90 px-4 py-2.5 text-sm text-slate-100 placeholder-slate-500 transition focus:border-[#FF6B4A] focus:ring-2 focus:ring-[#FF6B4A]/20 focus:outline-none"
          />
          <div className="mt-2 flex flex-wrap items-center gap-1.5">
            <span className="text-[11px] text-slate-400">Popular:</span>
            {POPULAR_DESTINATIONS.map((city) => (
              <button
                key={city}
                type="button"
                onClick={() => setDestination(city)}
                className="rounded-full border border-[#24304A] bg-[#0B1220]/75 px-2.5 py-0.5 text-[11px] text-slate-400 transition hover:border-[#FF6B4A]/60 hover:text-[#FFB547]"
              >
                {city.split(',')[0]}
              </button>
            ))}
          </div>
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <div>
            <label className="mb-1.5 flex items-center gap-1.5 text-sm font-medium text-slate-200">
              <Calendar className="h-4 w-4 text-[#FF6B4A]" /> Start Date
            </label>
            <input
              type="date"
              value={startDate}
              onChange={(e) => setStartDate(e.target.value)}
              className="w-full rounded-xl border border-[#24304A] bg-[#0B1220]/90 px-3 py-2 text-sm text-slate-100 transition focus:border-[#FF6B4A] focus:outline-none"
            />
          </div>

          <div>
            <label className="mb-1.5 block text-sm font-medium text-slate-200">
              Duration ({days} {days === 1 ? 'Day' : 'Days'})
            </label>
            <input
              type="range"
              min={1}
              max={7}
              value={days}
              onChange={(e) => setDays(Number(e.target.value))}
              className="mt-2 w-full accent-[#FF6B4A]"
            />
          </div>

          <div>
            <label className="mb-1.5 flex items-center gap-1.5 text-sm font-medium text-slate-200">
              <Users className="h-4 w-4 text-[#FF6B4A]" /> Travelers
            </label>
            <input
              type="number"
              min={1}
              max={20}
              value={peopleCount}
              onChange={(e) => setPeopleCount(Number(e.target.value))}
              className="w-full rounded-xl border border-[#24304A] bg-[#0B1220]/90 px-3 py-2 text-sm text-slate-100 transition focus:border-[#FF6B4A] focus:outline-none"
            />
          </div>
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
          <div className="sm:col-span-2">
            <label className="mb-1.5 flex items-center gap-1.5 text-sm font-medium text-slate-200">
              <Wallet className="h-4 w-4 text-[#FFB547]" /> Total Group Budget
            </label>
            <input
              type="number"
              min={500}
              step={500}
              value={totalBudget}
              onChange={(e) => setTotalBudget(Number(e.target.value))}
              className="w-full rounded-xl border border-[#24304A] bg-[#0B1220]/90 px-4 py-2 text-sm text-slate-100 transition focus:border-[#FFB547] focus:outline-none"
            />
          </div>
          <div>
            <label className="mb-1.5 block text-sm font-medium text-slate-200">Currency</label>
            <select
              value={currency}
              onChange={(e) => setCurrency(e.target.value)}
              className="w-full rounded-xl border border-[#24304A] bg-[#0B1220]/90 px-3 py-2 text-sm text-slate-100 transition focus:border-[#FFB547] focus:outline-none"
            >
              <option value="INR">INR (₹)</option>
              <option value="USD">USD ($)</option>
              <option value="EUR">EUR (€)</option>
            </select>
          </div>
        </div>

        <div>
          <label className="mb-2 block text-sm font-medium text-slate-200">Trip Pace</label>
          <div className="grid grid-cols-3 gap-2.5">
            {PACES.map((p) => (
              <button
                key={p.value}
                type="button"
                onClick={() => setPace(p.value)}
                className={`rounded-xl border px-3 py-2 text-left transition ${
                  pace === p.value
                    ? 'border-[#FF6B4A] bg-[#FF6B4A]/15 text-slate-100'
                    : 'border-[#24304A] bg-[#0B1220]/70 text-slate-400 hover:border-[#FF6B4A]/40 hover:text-slate-200'
                }`}
              >
                <div className="text-xs font-semibold">{p.label}</div>
                <div className="text-[11px] opacity-75">{p.desc}</div>
              </button>
            ))}
          </div>
        </div>

        <div>
          <label className="mb-2 block text-sm font-medium text-slate-200">What do you enjoy?</label>
          <div className="flex flex-wrap gap-2">
            {ALL_INTERESTS.map(({ tag, label }) => {
              const active = interests.includes(tag);
              return (
                <button
                  key={tag}
                  type="button"
                  onClick={() => toggleInterest(tag)}
                  className={`rounded-full border px-3.5 py-1.5 text-xs font-medium transition ${
                    active
                      ? 'border-[#FF6B4A] bg-[#FF6B4A]/20 text-[#FFD6CC]'
                      : 'border-[#24304A] bg-[#0B1220]/80 text-slate-400 hover:border-[#FF8566]/50 hover:text-slate-200'
                  }`}
                >
                  {label}
                </button>
              );
            })}
          </div>
        </div>

        <button
          type="submit"
          disabled={disabled}
          className="flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-[#FF6B4A] to-[#FFB547] px-5 py-3 text-sm font-bold text-[#1A0F0A] shadow-lg shadow-[#FF6B4A]/25 transition hover:from-[#FF8566] hover:to-[#FFC366] disabled:cursor-not-allowed disabled:opacity-50"
        >
          <Sparkles className="h-4 w-4" />
          {disabled ? 'Building Your Itinerary...' : 'Build My Itinerary'}
        </button>
      </div>
    </form>
  );
}
