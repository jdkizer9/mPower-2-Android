package org.sagebionetworks.research.mpower.tracking.view_model.logs;

import android.support.annotation.NonNull;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.TypeAdapter;

import java.util.Set;

@AutoValue
public abstract class MedicationLog implements TrackingItemLog {
    @AutoValue.Builder
    public abstract static class Builder {
        public abstract MedicationLog build();

        @NonNull
        public abstract Builder setIdentifier(@NonNull String identifier);

        @NonNull
        public abstract Builder setTimestamps(@NonNull Set<MedicationTimestamp> timestamps);
    }

    @NonNull
    public abstract Set<MedicationTimestamp> getTimestamps();

    @NonNull
    public static Builder builder() {
        return new AutoValue_MedicationLog.Builder()
                .setTimestamps(ImmutableSet.of());
    }

    @NonNull
    public static TypeAdapter<MedicationLog> typeAdapter(Gson gson) {
        return new AutoValue_MedicationLog.GsonTypeAdapter(gson);
    }

    @NonNull
    public abstract Builder toBuilder();
}
