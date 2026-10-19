package com.raikiservice.raf.question.repository;

import java.util.List;
import java.util.Optional;

import com.raikiservice.raf.question.domain.Category;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findBySlug(String slug);

    Optional<Category> findByNameIgnoreCase(String name);

    boolean existsBySlug(String slug);

    List<Category> findByActiveTrueOrderByNameAsc();

    List<Category> findByParentIdOrderByNameAsc(Long parentId);
}
