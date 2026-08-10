package com.gialong.relayforge.identity.persistence;

import com.gialong.relayforge.identity.application.OwnerBootstrapRecord;
import com.gialong.relayforge.identity.application.OwnerBootstrapStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Repository
public class PostgreSqlOwnerBootstrapStore implements OwnerBootstrapStore {

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlOwnerBootstrapStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public OwnerBootstrapRecord insertOrGet(UUID candidateId, String loginName, String passwordHash) {
        List<UUID> insertedIds = jdbcTemplate.query(
                "insert into public.owner_accounts (id, login_name, password_hash) "
                        + "values (?, ?, ?) "
                        + "on conflict (login_name) do nothing "
                        + "returning id",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                candidateId,
                loginName,
                passwordHash
        );

        if (!insertedIds.isEmpty()) {
            return new OwnerBootstrapRecord(insertedIds.getFirst(), true);
        }

        UUID existingId = jdbcTemplate.queryForObject(
                "select id from public.owner_accounts where login_name = ?",
                UUID.class,
                loginName
        );
        return new OwnerBootstrapRecord(existingId, false);
    }
}
