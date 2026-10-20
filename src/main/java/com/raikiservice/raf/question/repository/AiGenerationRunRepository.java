package com.raikiservice.raf.question.repository;

import java.time.Instant;

import com.raikiservice.raf.question.domain.AiGenerationRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiGenerationRunRepository extends JpaRepository<AiGenerationRun, Long> {

    long countByCreatedAtGreaterThanEqual(Instant since);
}
