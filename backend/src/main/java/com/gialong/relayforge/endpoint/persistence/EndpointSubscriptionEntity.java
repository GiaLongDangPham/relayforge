package com.gialong.relayforge.endpoint.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity(name = "EndpointSubscription")
@Table(name = "endpoint_subscriptions", schema = "public")
@IdClass(EndpointSubscriptionEntity.Identifier.class)
public class EndpointSubscriptionEntity {

    @Id
    @Column(name = "endpoint_id", nullable = false, updatable = false)
    private UUID endpointId;

    @Id
    @Column(name = "event_type", nullable = false, length = 200, updatable = false)
    private String eventType;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EndpointSubscriptionEntity() {
    }

    private EndpointSubscriptionEntity(UUID endpointId, String eventType) {
        this.endpointId = Objects.requireNonNull(endpointId, "endpointId must not be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
    }

    public static EndpointSubscriptionEntity create(UUID endpointId, String eventType) {
        return new EndpointSubscriptionEntity(endpointId, eventType);
    }

    public UUID endpointId() {
        return endpointId;
    }

    public String eventType() {
        return eventType;
    }

    public static final class Identifier implements Serializable {

        private UUID endpointId;
        private String eventType;

        public Identifier() {
        }

        public Identifier(UUID endpointId, String eventType) {
            this.endpointId = endpointId;
            this.eventType = eventType;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof Identifier other)) {
                return false;
            }
            return Objects.equals(endpointId, other.endpointId) && Objects.equals(eventType, other.eventType);
        }

        @Override
        public int hashCode() {
            return Objects.hash(endpointId, eventType);
        }
    }
}
