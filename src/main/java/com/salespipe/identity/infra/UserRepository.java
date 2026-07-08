package com.salespipe.identity.infra;

import com.salespipe.identity.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmailAndOrgId(String email, UUID orgId);
    Optional<User> findByEmail(String email);
}
