package com.sangmyungyaho.barocare.user.repository;

import com.sangmyungyaho.barocare.user.entity.Provider;
import com.sangmyungyaho.barocare.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByProviderAndSocialId(Provider provider, String socialId);
}
