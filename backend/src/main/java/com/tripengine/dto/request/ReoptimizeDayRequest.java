package com.tripengine.dto.request;

import java.util.List;
import java.util.UUID;

public record ReoptimizeDayRequest(
        List<UUID> excludePoiIds,
        List<UUID> lockedStopIds,
        Integer dayBudgetOverride
) {}
