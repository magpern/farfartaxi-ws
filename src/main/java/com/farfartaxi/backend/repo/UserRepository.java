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

    /** Drivers (DRIVER or ADMIN) of one world who can receive offers. */
    List<UserEntity> findByRoleInAndEnabledTrueAndApprovedTrueAndTest(java.util.Collection<Role> roles, boolean test);

    long countByRole(Role role);

    long countByRoleAndEnabled(Role role, boolean enabled);

    List<UserEntity> findByEnabledTrueAndApprovedTrueAndTestOrderByFullNameAsc(boolean test);

    /** Direct update: does not touch the optimistic-lock version of a user who is editing something else. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update UserEntity u set u.locale = :locale where u.id = :id")
    void updateLocale(@org.springframework.data.repository.query.Param("id") Long id,
                      @org.springframework.data.repository.query.Param("locale") String locale);
}
