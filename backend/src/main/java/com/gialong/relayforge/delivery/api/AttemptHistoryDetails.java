package com.gialong.relayforge.delivery.api;

import java.util.Objects;

/**
 * Detail of one attempt. The response preview is bounded and HTML-escaped; neither URL nor secret material is exposed.
 */
public record AttemptHistoryDetails(
        AttemptHistorySummary attempt,
        short destinationFingerprintVersion,
        String destinationFingerprint,
        String responsePreview,
        boolean responseTruncated,
        LateAttemptDiagnostic lateDiagnostic
) {

    public AttemptHistoryDetails {
        Objects.requireNonNull(attempt, "attempt must not be null");
        if (destinationFingerprintVersion < 1) {
            throw new IllegalArgumentException("destinationFingerprintVersion must be positive");
        }
        Objects.requireNonNull(destinationFingerprint, "destinationFingerprint must not be null");
    }

    @Override
    public String toString() {
        return "AttemptHistoryDetails[attempt=" + attempt + ", destinationFingerprintVersion="
                + destinationFingerprintVersion + ", destinationFingerprint=" + destinationFingerprint
                + ", responsePreview=<redacted>, responseTruncated=" + responseTruncated
                + ", lateDiagnostic=" + lateDiagnostic + "]";
    }
}
