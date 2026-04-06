package com.github.yevhen.googleservice.repository;

import com.github.yevhen.googleservice.model.GoogleToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface GoogleTokenRepository extends JpaRepository<GoogleToken, UUID> {
    Optional<GoogleToken> findByUserId(UUID userId);
}
