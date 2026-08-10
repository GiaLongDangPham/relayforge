package com.gialong.relayforge.identity.persistence;

import com.gialong.relayforge.identity.application.OwnerCredentialRecord;
import com.gialong.relayforge.identity.application.OwnerCredentialStore;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JpaOwnerCredentialStore implements OwnerCredentialStore {

    private final EntityManager entityManager;

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<OwnerCredentialRecord> findByCanonicalLogin(String canonicalLogin) {
        return entityManager.createQuery(
                        "select new com.gialong.relayforge.identity.application.OwnerCredentialRecord("
                                + "owner.id, owner.loginName, owner.passwordHash) "
                                + "from OwnerAccount owner where owner.loginName = :loginName",
                        OwnerCredentialRecord.class
                )
                .setParameter("loginName", canonicalLogin)
                .getResultStream()
                .findFirst();
    }
}
