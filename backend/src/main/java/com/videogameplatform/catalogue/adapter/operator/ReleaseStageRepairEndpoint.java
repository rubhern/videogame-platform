package com.videogameplatform.catalogue.adapter.operator;

import com.videogameplatform.catalogue.application.synchronization.ReleaseStageRepair;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderRequestException;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;

/** Explicit bounded operator repair on the private management port only. Defaults to dry run. */
@Endpoint(id = "releasestagerepair")
public final class ReleaseStageRepairEndpoint {
    private final ReleaseStageRepair repair;

    public ReleaseStageRepairEndpoint(ReleaseStageRepair repair) {
        this.repair = repair;
    }

    @ReadOperation
    public Object summary() {
        return repair.summary();
    }

    @WriteOperation
    public Object repair(
            @Nullable String after, @Nullable Integer limit, @Nullable Boolean dryRun) {
        try {
            return repair.repair(
                    after == null ? new UUID(0, 0) : UUID.fromString(after),
                    limit == null ? 10 : limit,
                    dryRun == null || dryRun);
        } catch (IllegalArgumentException failure) {
            return new WebEndpointResponse<>(Map.of("code", "INVALID_STAGE_REPAIR_REQUEST"), 400);
        } catch (IllegalStateException failure) {
            return new WebEndpointResponse<>(Map.of("code", "SYNCHRONIZATION_DISABLED"), 409);
        } catch (ProviderRequestException failure) {
            return new WebEndpointResponse<>(Map.of("code", failure.code().name()), 502);
        }
    }
}
