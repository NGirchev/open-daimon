package io.github.ngirchev.opendaimon.common.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import io.github.ngirchev.opendaimon.common.model.Bugreport;
import io.github.ngirchev.opendaimon.common.model.BugreportType;
import io.github.ngirchev.opendaimon.common.model.User;

import java.util.List;

public interface BugreportRepository extends JpaRepository<Bugreport, Long> {
    
    List<Bugreport> findByUserOrderByCreatedAtDesc(User user);
    
    List<Bugreport> findByTypeOrderByCreatedAtDesc(BugreportType type);
    
    List<Bugreport> findByUserAndTypeOrderByCreatedAtDesc(User user, BugreportType type);
}

