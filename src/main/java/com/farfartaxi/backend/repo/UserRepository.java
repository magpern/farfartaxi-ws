package com.farfartaxi.backend.repo;

import com.farfartaxi.backend.model.Role;
import com.farfartaxi.backend.model.UserEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    UserEntity save(UserEntity user);
    Optional<UserEntity> findById(Long id);
    List<UserEntity> findAll();
    void deleteById(Long id);

    Optional<UserEntity> findByEmailIgnoreCase(String email);

    Optional<UserEntity> findByGoogleSub(String googleSub);

    List<UserEntity> findByRole(Role role);

    long countByRole(Role role);

    long countByRoleAndEnabled(Role role, boolean enabled);

    List<UserEntity> findByEnabledTrueOrderByFullNameAsc();
}
