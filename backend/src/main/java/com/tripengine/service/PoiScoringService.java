package com.tripengine.service;

import com.tripengine.domain.entity.Poi;
import com.tripengine.domain.enums.InterestTag;
import com.tripengine.domain.enums.PoiCategory;
import com.tripengine.domain.model.ScoredPoi;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class PoiScoringService {

    public List<ScoredPoi> scoreAndSelectCandidates(
            List<Poi> pois,
            List<InterestTag> interests,
            int days,
            int peopleCount,
            int totalBudget) {

        Set<InterestTag> interestSet = EnumSet.copyOf(interests);
        double dailyBudget = (double) totalBudget / Math.max(1, days);
        double perPersonPerDay = dailyBudget / Math.max(1, peopleCount);
        double targetPriceTier = resolveTargetPriceTier(perPersonPerDay);

        List<ScoredPoi> activities = new ArrayList<>();
        List<ScoredPoi> foodSpots = new ArrayList<>();

        for (Poi poi : pois) {
            double interestScore = computeInterestMatch(poi, interestSet);
            double ratingScore = Math.min(1.0, poi.getRating() / 5.0);
            double budgetFit = computeBudgetTierFit(poi, peopleCount, dailyBudget, targetPriceTier);
            double completeness = computeCompleteness(poi);

            // Give strong weight (30%) to matching the traveler's budget tier (budget vs mid vs upscale vs luxury)
            double totalScore = 0.38 * interestScore
                    + 0.22 * ratingScore
                    + 0.30 * budgetFit
                    + 0.10 * completeness;

            ScoredPoi scored = new ScoredPoi(poi, Math.round(totalScore * 1000.0) / 1000.0);
            if (poi.getCategory().isFood()) {
                foodSpots.add(scored);
            } else {
                activities.add(scored);
            }
        }

        activities.sort(Comparator.comparingDouble(ScoredPoi::score).reversed());
        foodSpots.sort(Comparator.comparingDouble(ScoredPoi::score).reversed());

        int maxActivities = Math.min(activities.size(), Math.min(25 * days, 120));
        int maxFood = Math.min(foodSpots.size(), Math.min(15 * days, 80));

        List<ScoredPoi> combined = new ArrayList<>(maxActivities + maxFood);
        combined.addAll(activities.subList(0, maxActivities));
        combined.addAll(foodSpots.subList(0, maxFood));
        return combined;
    }

    /**
     * Maps per-person daily spend (in INR-equivalent) to an ideal POI price tier [1.0 .. 4.0]:
     * - <= 2,000 / person / day  -> Tier 1 (Budget / Street food / Free sights)
     * - <= 6,000 / person / day  -> Tier 2 (Moderate / Popular cafes & attractions)
     * - <= 15,000 / person / day -> Tier 3 (Upscale dining & curated experiences)
     * - >  15,000 / person / day -> Tier 4 (Luxury fine dining, 5-star lounges & VIP experiences)
     */
    public double resolveTargetPriceTier(double perPersonPerDay) {
        if (perPersonPerDay <= 2000) return 1.0;
        if (perPersonPerDay <= 6000) return 2.0;
        if (perPersonPerDay <= 15000) return 3.0;
        return 4.0;
    }

    private double computeBudgetTierFit(Poi poi, int peopleCount, double dailyBudget, double targetTier) {
        double groupCost = (double) poi.getEstimatedCost() * peopleCount;
        // Hard affordability penalty if a single stop consumes > 55% of the entire day's budget
        if (dailyBudget > 0 && groupCost > dailyBudget * 0.55) {
            return 0.05;
        }
        // Iconic cultural/nature monuments are often low-cost even for luxury travelers;
        // don't heavily penalize free/cheap landmarks, but strongly match food/nightlife/adventure tiers.
        double tierDiff = Math.abs(poi.getPriceTier() - targetTier);
        if (!poi.getCategory().isFood()
                && (poi.getCategory() == PoiCategory.CULTURE || poi.getCategory() == PoiCategory.NATURE)
                && poi.getPriceTier() < targetTier) {
            return Math.max(0.65, 1.0 - 0.12 * tierDiff);
        }
        return Math.max(0.1, 1.0 - 0.30 * tierDiff);
    }

    private double computeInterestMatch(Poi poi, Set<InterestTag> interests) {
        PoiCategory cat = poi.getCategory();
        if (cat == PoiCategory.ADVENTURE && interests.contains(InterestTag.ADVENTURE)) return 1.0;
        if ((cat == PoiCategory.RESTAURANT || cat == PoiCategory.CAFE) && interests.contains(InterestTag.FOOD)) return 1.0;
        if (cat == PoiCategory.CULTURE && (interests.contains(InterestTag.CULTURE) || interests.contains(InterestTag.HISTORY))) return 1.0;
        if (cat == PoiCategory.NATURE && (interests.contains(InterestTag.NATURE) || interests.contains(InterestTag.RELAXATION))) return 1.0;
        if (cat == PoiCategory.NIGHTLIFE && interests.contains(InterestTag.NIGHTLIFE)) return 1.0;
        if (cat == PoiCategory.SHOPPING && interests.contains(InterestTag.SHOPPING)) return 1.0;
        if (cat == PoiCategory.ATTRACTION) return 0.7;
        return 0.35;
    }

    private double computeCompleteness(Poi poi) {
        double score = 0.3;
        if (!poi.isHoursEstimated()) score += 0.35;
        if (poi.getTags() != null && (poi.getTags().containsKey("localTip")
                || poi.getTags().containsKey("wikipedia")
                || poi.getTags().containsKey("website"))) {
            score += 0.35;
        }
        return Math.min(1.0, score);
    }
}
