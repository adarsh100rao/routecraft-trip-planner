package com.tripengine.service.clustering;

import com.tripengine.domain.model.DayCluster;
import com.tripengine.domain.model.GeoPoint;
import com.tripengine.domain.model.ScoredPoi;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Capacity-constrained K-Means++ spatial clusterer.
 * Ensures each day explores a geographically compact zone while keeping cluster sizes balanced.
 */
@Component
public class BalancedKMeansClusterer implements DayClusterer {

    private static final int MAX_ITERATIONS = 20;

    @Override
    public List<DayCluster> cluster(List<ScoredPoi> candidates, int numDays) {
        if (candidates.isEmpty() || numDays <= 0) {
            return List.of();
        }
        if (numDays == 1 || candidates.size() <= numDays) {
            GeoPoint centroid = computeCentroid(candidates);
            return List.of(new DayCluster(1, centroid, new ArrayList<>(candidates)));
        }

        List<ScoredPoi> activities = candidates.stream().filter(c -> !c.poi().getCategory().isFood()).toList();
        List<ScoredPoi> foodSpots = candidates.stream().filter(c -> c.poi().getCategory().isFood()).toList();

        // Cluster primarily around attractions/activities if enough exist; otherwise all candidates
        List<ScoredPoi> primary = activities.size() >= numDays ? activities : candidates;

        // 1. K-Means++ deterministic-seed initialization
        List<GeoPoint> centroids = initializeCentroidsKMeansPlusPlus(primary, numDays);
        int primaryMaxCap = (int) Math.ceil((double) primary.size() / numDays) + 2;

        List<List<ScoredPoi>> buckets = new ArrayList<>();
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            buckets = assignWithCapacity(primary, centroids, primaryMaxCap);
            List<GeoPoint> updated = new ArrayList<>(numDays);
            double maxShift = 0.0;
            for (int k = 0; k < numDays; k++) {
                GeoPoint next = buckets.get(k).isEmpty() ? centroids.get(k) : computeCentroid(buckets.get(k));
                maxShift = Math.max(maxShift, centroids.get(k).haversineKm(next));
                updated.add(next);
            }
            centroids = updated;
            if (maxShift < 0.05) { // converged within 50 meters
                break;
            }
        }

        // 2. Assign food spots (restaurants & cafes) to the nearest day zone with balanced capacity
        if (primary == activities && !foodSpots.isEmpty()) {
            int foodMaxCap = (int) Math.ceil((double) foodSpots.size() / numDays) + 1;
            List<List<ScoredPoi>> foodBuckets = assignWithCapacity(foodSpots, centroids, foodMaxCap);
            for (int k = 0; k < numDays; k++) {
                buckets.get(k).addAll(foodBuckets.get(k));
            }
        }

        List<DayCluster> result = new ArrayList<>(numDays);
        for (int k = 0; k < numDays; k++) {
            result.add(new DayCluster(k + 1, centroids.get(k), buckets.get(k)));
        }
        return result;
    }

    private List<GeoPoint> initializeCentroidsKMeansPlusPlus(List<ScoredPoi> candidates, int k) {
        List<GeoPoint> centroids = new ArrayList<>(k);
        // Start with highest-scored POI for reproducibility
        centroids.add(candidates.get(0).location());

        while (centroids.size() < k) {
            GeoPoint farthest = null;
            double maxMinDist = -1.0;
            for (ScoredPoi p : candidates) {
                double minDist = Double.MAX_VALUE;
                for (GeoPoint c : centroids) {
                    minDist = Math.min(minDist, p.location().haversineKm(c));
                }
                if (minDist > maxMinDist) {
                    maxMinDist = minDist;
                    farthest = p.location();
                }
            }
            centroids.add(farthest != null ? farthest : candidates.get(centroids.size() % candidates.size()).location());
        }
        return centroids;
    }

    private List<List<ScoredPoi>> assignWithCapacity(
            List<ScoredPoi> candidates, List<GeoPoint> centroids, int maxCapacity) {
        int k = centroids.size();
        List<List<ScoredPoi>> buckets = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            buckets.add(new ArrayList<>());
        }

        // Regret-based ordering: assign POIs with the largest penalty for missing their closest cluster first
        record PoiRegret(ScoredPoi poi, int[] sortedClusters, double regret) {}
        List<PoiRegret> regrets = new ArrayList<>(candidates.size());

        for (ScoredPoi p : candidates) {
            double[] dists = new double[k];
            Integer[] idx = new Integer[k];
            for (int c = 0; c < k; c++) {
                dists[c] = p.location().haversineKm(centroids.get(c));
                idx[c] = c;
            }
            Arrays.sort(idx, Comparator.comparingDouble(i -> dists[i]));
            int[] sorted = Arrays.stream(idx).mapToInt(Integer::intValue).toArray();
            double regret = k > 1 ? dists[sorted[1]] - dists[sorted[0]] : 0.0;
            regrets.add(new PoiRegret(p, sorted, regret));
        }

        regrets.sort(Comparator.comparingDouble(PoiRegret::regret).reversed());

        for (PoiRegret r : regrets) {
            boolean placed = false;
            for (int clusterIdx : r.sortedClusters()) {
                if (buckets.get(clusterIdx).size() < maxCapacity) {
                    buckets.get(clusterIdx).add(r.poi());
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                buckets.get(r.sortedClusters()[0]).add(r.poi());
            }
        }
        return buckets;
    }

    private GeoPoint computeCentroid(List<ScoredPoi> points) {
        double sumLat = 0.0;
        double sumLng = 0.0;
        for (ScoredPoi p : points) {
            sumLat += p.location().lat();
            sumLng += p.location().lng();
        }
        return new GeoPoint(sumLat / points.size(), sumLng / points.size());
    }
}
