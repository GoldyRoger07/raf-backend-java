package com.raikiservice.raf.media.repository;

import java.util.List;

import com.raikiservice.raf.media.domain.MediaFile;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MediaFileRepository extends JpaRepository<MediaFile, Long> {

    Page<MediaFile> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    Page<MediaFile> findByFolderOrderByCreatedAtDescIdDesc(String folder, Pageable pageable);

    @Query("select distinct m.folder from MediaFile m where m.folder is not null order by m.folder")
    List<String> folders();
}
