package com.raikiservice.raf.media.repository;

import java.util.Optional;

import com.raikiservice.raf.media.domain.MediaFile;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaFileRepository extends JpaRepository<MediaFile, Long> {

    Optional<MediaFile> findByImagekitFileId(String imagekitFileId);
}
