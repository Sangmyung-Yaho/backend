package com.sangmyungyaho.barocare.global.security.repository;

import com.sangmyungyaho.barocare.global.security.entity.RefreshToken;
import org.springframework.data.repository.CrudRepository;

public interface RefreshTokenRepository extends CrudRepository<RefreshToken, String> {
}
