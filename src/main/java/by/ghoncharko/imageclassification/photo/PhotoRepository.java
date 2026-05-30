package by.ghoncharko.imageclassification.photo;

import by.ghoncharko.imageclassification.user.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PhotoRepository extends JpaRepository<Photo, Long> {
    List<Photo> findByUploadedByOrderByUploadedAtDesc(AppUser uploadedBy);

    List<Photo> findAllByOrderByUploadedAtDesc();

    Optional<Photo> findByStoredFileName(String storedFileName);
}
