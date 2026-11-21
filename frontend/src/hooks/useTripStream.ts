import { useCallback, useRef, useState } from 'react';
import { BACKEND_ORIGIN, startTripGeneration } from '../api/tripApi';
import type { GenerateTripRequest, SseProgressEvent, TripResponse } from '../types/trip';

export function useTripStream() {
  const [isGenerating, setIsGenerating] = useState(false);
  const [stages, setStages] = useState<SseProgressEvent[]>([]);
  const [progress, setProgress] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [trip, setTrip] = useState<TripResponse | null>(null);
  const sourceRef = useRef<EventSource | null>(null);

  const generate = useCallback(async (request: GenerateTripRequest) => {
    if (sourceRef.current) {
      sourceRef.current.close();
    }
    setIsGenerating(true);
    setStages([]);
    setProgress(5);
    setError(null);
    setTrip(null);

    try {
      const { streamUrl } = await startTripGeneration(request);
      const fullStreamUrl = streamUrl.startsWith('http')
        ? streamUrl
        : `${BACKEND_ORIGIN}${streamUrl}`;
      const es = new EventSource(fullStreamUrl);
      sourceRef.current = es;

      es.addEventListener('STAGE_UPDATE', (evt) => {
        const parsed: SseProgressEvent = JSON.parse(evt.data);
        setStages((prev) => [...prev, parsed]);
        setProgress(parsed.progress);
      });

      es.addEventListener('COMPLETED', (evt) => {
        const parsed: { tripId: string; trip: TripResponse } = JSON.parse(evt.data);
        setProgress(100);
        setTrip(parsed.trip);
        setIsGenerating(false);
        es.close();
      });

      es.addEventListener('ERROR', (evt) => {
        try {
          const parsed = JSON.parse((evt as MessageEvent).data);
          setError(parsed.message || 'Trip generation failed');
        } catch {
          setError('Connection to optimization stream lost');
        }
        setIsGenerating(false);
        es.close();
      });

      es.onerror = () => {
        if (es.readyState === EventSource.CLOSED) return;
        setError('Unable to stream trip generation updates. Is the backend running on port 8080?');
        setIsGenerating(false);
        es.close();
      };
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to start generation');
      setIsGenerating(false);
    }
  }, []);

  const reset = useCallback(() => {
    if (sourceRef.current) {
      sourceRef.current.close();
    }
    setTrip(null);
    setStages([]);
    setProgress(0);
    setError(null);
    setIsGenerating(false);
  }, []);

  return { isGenerating, stages, progress, error, trip, setTrip, generate, reset };
}
