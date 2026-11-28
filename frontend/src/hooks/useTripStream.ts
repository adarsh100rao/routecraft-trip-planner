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
  const pollTimerRef = useRef<number | null>(null);
  const completedRef = useRef(false);

  const stopAll = useCallback(() => {
    if (sourceRef.current) {
      sourceRef.current.close();
      sourceRef.current = null;
    }
    if (pollTimerRef.current !== null) {
      window.clearInterval(pollTimerRef.current);
      pollTimerRef.current = null;
    }
  }, []);

  const startPollingFallback = useCallback(
    (jobId: string) => {
      if (pollTimerRef.current !== null || completedRef.current) return;
      let attempts = 0;

      const poll = async () => {
        if (completedRef.current) {
          stopAll();
          return;
        }
        attempts += 1;
        if (attempts > 45) {
          stopAll();
          setError('Trip generation timed out while waking up the server. Please try again.');
          setIsGenerating(false);
          return;
        }

        try {
          const res = await fetch(`${BACKEND_ORIGIN}/api/v1/trips/jobs/${jobId}`);
          if (!res.ok) return;
          const data = await res.json();

          if (data.status === 'RUNNING' && data.lastStage) {
            const st = data.lastStage as SseProgressEvent;
            setProgress((prev) => Math.max(prev, st.progress ?? prev));
            setStages((prev) =>
              prev.some((p) => p.stage === st.stage) ? prev : [...prev, st]
            );
          } else if (data.status === 'COMPLETED' && data.result?.trip) {
            completedRef.current = true;
            stopAll();
            setProgress(100);
            setTrip(data.result.trip as TripResponse);
            setIsGenerating(false);
          } else if (data.status === 'FAILED') {
            completedRef.current = true;
            stopAll();
            setError(data.error || 'Trip generation failed');
            setIsGenerating(false);
          }
        } catch {
          // Transient mobile network blip — keep polling until timeout
        }
      };

      void poll();
      pollTimerRef.current = window.setInterval(() => {
        void poll();
      }, 2000);
    },
    [stopAll]
  );

  const generate = useCallback(
    async (request: GenerateTripRequest) => {
      stopAll();
      completedRef.current = false;
      setIsGenerating(true);
      setStages([
        {
          stage: 'CONNECTING',
          message: 'Connecting to optimization engine (first request may take ~15s if waking up)...',
          progress: 6,
        },
      ]);
      setProgress(6);
      setError(null);
      setTrip(null);

      try {
        const { jobId, streamUrl } = await startTripGeneration(request);
        const fullStreamUrl = streamUrl.startsWith('http')
          ? streamUrl
          : `${BACKEND_ORIGIN}${streamUrl}`;
        const es = new EventSource(fullStreamUrl);
        sourceRef.current = es;

        es.addEventListener('STAGE_UPDATE', (evt) => {
          const parsed: SseProgressEvent = JSON.parse(evt.data);
          setStages((prev) =>
            prev.some((p) => p.stage === parsed.stage) ? prev : [...prev, parsed]
          );
          setProgress(parsed.progress);
        });

        es.addEventListener('COMPLETED', (evt) => {
          const parsed: { tripId: string; trip: TripResponse } = JSON.parse(evt.data);
          completedRef.current = true;
          stopAll();
          setProgress(100);
          setTrip(parsed.trip);
          setIsGenerating(false);
        });

        es.addEventListener('ERROR', (evt) => {
          completedRef.current = true;
          stopAll();
          try {
            const parsed = JSON.parse((evt as MessageEvent).data);
            setError(parsed.message || 'Trip generation failed');
          } catch {
            setError('Trip generation failed. Please try again.');
          }
          setIsGenerating(false);
        });

        es.onerror = () => {
          if (completedRef.current) return;
          // Seamlessly switch to HTTP polling if SSE drops on mobile/Cloudflare
          es.close();
          sourceRef.current = null;
          startPollingFallback(jobId);
        };
      } catch (err) {
        setError(
          err instanceof Error
            ? err.message
            : 'Unable to reach the optimization server right now. Please try again in a few seconds.'
        );
        setIsGenerating(false);
      }
    },
    [startPollingFallback, stopAll]
  );

  const reset = useCallback(() => {
    completedRef.current = true;
    stopAll();
    setTrip(null);
    setStages([]);
    setProgress(0);
    setError(null);
    setIsGenerating(false);
  }, [stopAll]);

  return { isGenerating, stages, progress, error, trip, setTrip, generate, reset };
}
