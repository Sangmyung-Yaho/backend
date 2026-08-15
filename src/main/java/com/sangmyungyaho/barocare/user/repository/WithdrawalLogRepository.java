package com.sangmyungyaho.barocare.user.repository;

import com.sangmyungyaho.barocare.user.entity.WithdrawalLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WithdrawalLogRepository extends JpaRepository<WithdrawalLog, Long> {
}
