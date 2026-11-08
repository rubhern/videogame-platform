package com.videogameplatform.catalogue.adapter.persistence.preferences;

import com.videogameplatform.catalogue.application.preferences.ExportPlatformPreferences;
import com.videogameplatform.catalogue.application.preferences.port.PlatformPreferenceStore;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

public final class JdbcPreferenceExport implements ExportPlatformPreferences {
    private final NamedParameterJdbcOperations jdbc;
    private final PlatformPreferenceStore store;

    public JdbcPreferenceExport(NamedParameterJdbcOperations jdbc, PlatformPreferenceStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    @Override
    public Choice read(String userId) {
        var saved =
                jdbc.query(
                        "SELECT updated_at FROM catalogue.platform_preference_choice WHERE user_id = CAST(:user AS uuid)",
                        Map.of("user", userId),
                        (rs, row) -> rs.getTimestamp(1).toInstant());
        return new Choice(store.find(userId), saved.isEmpty() ? null : saved.getFirst());
    }
}
