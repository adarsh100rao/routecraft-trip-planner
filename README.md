# RouteCraft — Constraint-Based Trip Planning Engine

A full-stack trip planning system engineered with **Java 21 (Spring Boot 3)**, **PostgreSQL + PostGIS**, **OSRM**, **Redis**, and **React + TypeScript + Leaflet**.

Instead of relying on an LLM to hallucinate itineraries, RouteCraft sources real-world POIs from OpenStreetMap, caches them spatially in PostGIS, geo-clusters them into compact daily zones, computes real travel-time matrices via OSRM, and runs a **Time-Window & Budget Heuristic Optimizer** with **2-Opt local search**.

## Key Engineering Highlights
- **Spatial Tile Caching (PostGIS + GIST):** Quantizes destinations into `0.05° × 0.05°` (~5.5km) geographic tiles with per-tile locking to prevent cache stampedes, serving warm queries via `ST_DWithin` in ~15ms.
- **Capacity-Constrained K-Means++ Clustering:** Groups attractions and restaurants into balanced, geographically compact daily zones so travelers don't zig-zag across a city.
- **Time-Window & Budget Optimizer + 2-Opt Refinement:** Enforces opening hours, dedicated lunch/dinner windows, locked stops, and hard per-person/group budget caps, then refines stop order via 2-opt to minimize total transit time.
- **Resilient Parallel Routing:** Executes per-day OSRM `/table` and `/route` requests concurrently on **Java 21 Virtual Threads**, wrapped in **Resilience4j** circuit breakers with automatic Haversine fallback.
- **Multi-Stage SSE Streaming:** Streams live progress events (`GEOCODING → POIS_LOADED → CLUSTERING → OPTIMIZING → COMPLETED`) over Server-Sent Events.
- **Interactive Lock & Re-optimize:** Lock favorite stops, remove unwanted ones, and re-optimize a single day in `<100ms`.

## Quick Start

### 1. Start PostgreSQL (PostGIS) & Redis
```bash
docker compose up -d
```

### 2. Run the Java Backend (Port 8080)
```bash
cd backend
mvn spring-boot:run
```
- Swagger UI: http://localhost:8080/swagger-ui.html
- Prometheus Metrics: http://localhost:8080/actuator/prometheus

### 3. Run the React + TypeScript Frontend (Port 5173)
```bash
cd frontend
npm install
npm run dev
```
Open http://localhost:5173 in your browser.

## Running Backend Tests
```bash
cd backend
mvn test
```

## API Endpoints
- `POST /api/v1/trips/generate` — Submit a trip request (`202 Accepted` with `jobId` & `streamUrl`)
- `GET /api/v1/trips/jobs/{jobId}/stream` — SSE stream of pipeline stages & final `TripResponse`
- `GET /api/v1/trips/{tripId}` — Fetch a persisted trip
- `PATCH /api/v1/trips/{tripId}/stops/{stopId}` — Lock/unlock a stop
- `DELETE /api/v1/trips/{tripId}/stops/{stopId}` — Remove a stop
- `POST /api/v1/trips/{tripId}/days/{dayNumber}/reoptimize` — Re-optimize a single day preserving locked stops
