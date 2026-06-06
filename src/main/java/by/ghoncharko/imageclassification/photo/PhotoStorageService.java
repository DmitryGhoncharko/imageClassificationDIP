package by.ghoncharko.imageclassification.photo;

import by.ghoncharko.imageclassification.user.AppUser;
import by.ghoncharko.imageclassification.user.AppUserRepository;
import by.ghoncharko.imageclassification.user.Role;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PhotoStorageService {

    private static final Logger log = LoggerFactory.getLogger(PhotoStorageService.class);
    private static final double MIN_SIMILARITY_SCORE = 0.65;
    private static final String DEFAULT_EMPTY_TAG = "без_тегов";
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/gif"
    );

    private final Path uploadPath;
    private final PhotoRepository photoRepository;
    private final AppUserRepository appUserRepository;
    private final ImageInferenceService imageInferenceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PhotoStorageService(
            @Value("${app.upload-dir}") String uploadDir,
            PhotoRepository photoRepository,
            AppUserRepository appUserRepository,
            ImageInferenceService imageInferenceService
    ) {
        this.uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.photoRepository = photoRepository;
        this.appUserRepository = appUserRepository;
        this.imageInferenceService = imageInferenceService;
    }

    @Transactional
    public void uploadBatch(MultipartFile[] files, String rawTags, String username) {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("Выберите хотя бы один файл");
        }
        List<MultipartFile> nonEmptyFiles = Arrays.stream(files)
                .filter(file -> file != null && !file.isEmpty())
                .toList();
        if (nonEmptyFiles.isEmpty()) {
            throw new IllegalArgumentException("Выберите хотя бы один файл");
        }

        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));
        Set<String> tags = parseTags(rawTags);

        for (MultipartFile file : nonEmptyFiles) {
            uploadSingleFile(file, user, tags);
        }
    }

    private void uploadSingleFile(MultipartFile file, AppUser user, Set<String> tags) {
        if (!ALLOWED_TYPES.contains(file.getContentType())) {
            throw new IllegalArgumentException("Разрешены только изображения: jpg, png, webp, gif");
        }
        String originalName = StringUtils.cleanPath(file.getOriginalFilename() == null ? "image" : file.getOriginalFilename());
        String extension = "";
        int dotIndex = originalName.lastIndexOf('.');
        if (dotIndex >= 0) {
            extension = originalName.substring(dotIndex);
        }
        String storedFileName = UUID.randomUUID() + extension;

        try {
            Files.createDirectories(uploadPath);
            Path target = uploadPath.resolve(storedFileName).normalize();
            if (!target.startsWith(uploadPath)) {
                throw new IllegalArgumentException("Некорректное имя файла");
            }
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сохранить файл", e);
        }

        Photo photo = new Photo();
        photo.setOriginalFileName(originalName);
        photo.setStoredFileName(storedFileName);
        photo.setUploadedAt(Instant.now());
        photo.setUploadedBy(user);
        photo.setTags(new LinkedHashSet<>(tags));
        photoRepository.save(photo);
    }

    public PhotoPage photosOfUser(String username, String nameQuery, String tagQuery, int page, int size) {
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));
        String normalizedNameQuery = normalizeQuery(nameQuery);
        String normalizedTagQuery = normalizeQuery(tagQuery);
        List<Photo> filtered = photoRepository.findByUploadedByOrderByUploadedAtDesc(user).stream()
                .filter(photo -> matchesName(photo, normalizedNameQuery))
                .filter(photo -> matchesTag(photo, normalizedTagQuery))
                .toList();
        return paginate(filtered, page, size);
    }

    public List<Photo> allPhotos() {
        return photoRepository.findAllByOrderByUploadedAtDesc();
    }

    public boolean canAccessFile(String storedFileName, String username) {
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));
        if (user.getRoles().contains(Role.ADMIN)) {
            return true;
        }
        return photoRepository.findByStoredFileName(storedFileName)
                .map(photo -> photo.getUploadedBy().getId().equals(user.getId()))
                .orElse(false);
    }

    @Transactional
    public void deletePhoto(Long photoId, String username) {
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));

        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("Фото не найдено"));

        boolean isAdmin = user.getRoles().contains(Role.ADMIN);
        boolean isOwner = photo.getUploadedBy().getId().equals(user.getId());
        if (!isAdmin && !isOwner) {
            throw new IllegalArgumentException("Недостаточно прав для удаления этого фото");
        }

        photoRepository.delete(photo);
        try {
            Path filePath = uploadPath.resolve(photo.getStoredFileName()).normalize();
            if (filePath.startsWith(uploadPath)) {
                Files.deleteIfExists(filePath);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Фото удалено из базы, но не удалось удалить файл", e);
        }
    }

    @Transactional
    public void updatePhotoTags(Long photoId, String rawTags, String username) {
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));

        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("Фото не найдено"));

        boolean isAdmin = user.getRoles().contains(Role.ADMIN);
        boolean isOwner = photo.getUploadedBy().getId().equals(user.getId());
        if (!isAdmin && !isOwner) {
            throw new IllegalArgumentException("Недостаточно прав для изменения тегов");
        }
        Set<String> tags = parseTags(rawTags);
        if (tags.isEmpty()) {
            tags.add(DEFAULT_EMPTY_TAG);
        }
        photo.setTags(tags);
        photoRepository.save(photo);
    }

    @Transactional
    public Set<String> autoTagPhoto(Long photoId, String username) {
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("Фото не найдено"));

        boolean isAdmin = user.getRoles().contains(Role.ADMIN);
        boolean isOwner = photo.getUploadedBy().getId().equals(user.getId());
        if (!isAdmin && !isOwner) {
            throw new IllegalArgumentException("Недостаточно прав для автотегирования");
        }

        ImageInferenceService.InferenceResponse response = inferPhotoAndPersistEmbedding(photo);
        Set<String> generatedTags = sanitizeTags(new LinkedHashSet<>(response.tags()));
        Set<String> merged = new LinkedHashSet<>(photo.getTags());
        merged.remove(DEFAULT_EMPTY_TAG);
        merged.addAll(generatedTags);
        photo.setTags(sanitizeTags(merged));
        photoRepository.save(photo);
        return generatedTags;
    }

    @Transactional
    public int autoTagAllUntaggedPhotos(String username) {
        return autoTagAllUntaggedPhotos(username, null);
    }

    public int countUntaggedPhotos(String username) {
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));
        List<Photo> photos = photoRepository.findByUploadedByOrderByUploadedAtDesc(user);
        return (int) photos.stream().filter(photo -> !hasRealTags(photo.getTags())).count();
    }

    @Transactional
    public int autoTagAllUntaggedPhotos(String username, AutoTagProgressListener progressListener) {
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));
        List<Photo> photos = photoRepository.findByUploadedByOrderByUploadedAtDesc(user);
        List<Photo> untaggedPhotos = photos.stream()
                .filter(photo -> !hasRealTags(photo.getTags()))
                .toList();
        int total = untaggedPhotos.size();
        int taggedCount = 0;
        int processed = 0;
        if (progressListener != null) {
            progressListener.onProgress(processed, total, taggedCount);
        }
        for (Photo photo : untaggedPhotos) {
            Path absolutePath = uploadPath.resolve(photo.getStoredFileName()).normalize();
            try {
                if (!absolutePath.startsWith(uploadPath) || !Files.exists(absolutePath)) {
                    log.warn("Skip autotag for photo id={} because file is missing: {}", photo.getId(), absolutePath);
                } else {
                    ImageInferenceService.InferenceResponse response = inferPhotoAndPersistEmbedding(photo);
                    Set<String> generatedTags = sanitizeTags(new LinkedHashSet<>(response.tags()));
                    if (!generatedTags.isEmpty()) {
                        photo.setTags(generatedTags);
                        photoRepository.save(photo);
                        taggedCount++;
                    }
                }
            } catch (Exception ex) {
                log.warn("Skip autotag for photo id={} due to inference error: {}", photo.getId(), ex.getMessage());
            }
            processed++;
            if (progressListener != null) {
                progressListener.onProgress(processed, total, taggedCount);
            }
        }
        return taggedCount;
    }

    public List<PhotoSimilarity> findSimilarByUploadedSample(MultipartFile file, String username, int limit) {
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Выберите изображение для поиска");
        }
        try (InputStream inputStream = file.getInputStream()) {
            BufferedImage sample = ImageIO.read(inputStream);
            if (sample == null) {
                throw new IllegalArgumentException("Не удалось прочитать изображение");
            }
            return findMostSimilar(sample, photoRepository.findByUploadedByOrderByUploadedAtDesc(user), null, limit);
        } catch (IOException ex) {
            throw new IllegalStateException("Ошибка чтения изображения", ex);
        }
    }

    public List<PhotoSimilarity> findSimilarByCrop(
            Long photoId,
            double cropX,
            double cropY,
            double cropW,
            double cropH,
            String username,
            int limit
    ) {
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден"));
        Photo source = photoRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("Фото не найдено"));

        boolean isAdmin = user.getRoles().contains(Role.ADMIN);
        boolean isOwner = source.getUploadedBy().getId().equals(user.getId());
        if (!isAdmin && !isOwner) {
            throw new IllegalArgumentException("Недостаточно прав для поиска по этому фото");
        }

        BufferedImage sourceImage = readStoredImage(source);
        BufferedImage cropped = cropByRelativeRect(sourceImage, cropX, cropY, cropW, cropH);
        return findMostSimilar(cropped, photoRepository.findByUploadedByOrderByUploadedAtDesc(user), source.getId(), limit);
    }

    private Set<String> parseTags(String rawTags) {
        if (!StringUtils.hasText(rawTags)) {
            return new LinkedHashSet<>();
        }
        return sanitizeTags(Arrays.stream(rawTags.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    private String normalizeQuery(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean matchesName(Photo photo, String normalizedNameQuery) {
        if (normalizedNameQuery == null) {
            return true;
        }
        return photo.getOriginalFileName() != null
                && photo.getOriginalFileName().toLowerCase(Locale.ROOT).contains(normalizedNameQuery);
    }

    private boolean matchesTag(Photo photo, String normalizedTagQuery) {
        if (normalizedTagQuery == null) {
            return true;
        }
        return photo.getTags() != null
                && photo.getTags().stream().anyMatch(tag -> tag.toLowerCase(Locale.ROOT).contains(normalizedTagQuery));
    }

    private PhotoPage paginate(List<Photo> source, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 50));
        long totalItems = source.size();
        int totalPages = totalItems == 0 ? 1 : (int) Math.ceil((double) totalItems / safeSize);
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        int fromIndex = safePage * safeSize;
        int toIndex = Math.min(fromIndex + safeSize, source.size());
        List<Photo> items = source.subList(fromIndex, toIndex);
        return new PhotoPage(items, safePage, safeSize, totalPages, totalItems);
    }

    private BufferedImage readStoredImage(Photo photo) {
        Path file = uploadPath.resolve(photo.getStoredFileName()).normalize();
        if (!file.startsWith(uploadPath) || !Files.exists(file)) {
            throw new IllegalArgumentException("Файл изображения не найден");
        }
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) {
                throw new IllegalArgumentException("Файл не является изображением");
            }
            return image;
        } catch (IOException ex) {
            throw new IllegalStateException("Не удалось прочитать файл изображения", ex);
        }
    }

    private List<PhotoSimilarity> findMostSimilar(
            BufferedImage sample,
            List<Photo> candidates,
            Long excludedPhotoId,
            int limit
    ) {
        List<Double> sampleEmbedding = inferForBufferedImage(sample, -1L).embedding();
        int safeLimit = Math.max(1, Math.min(limit, 30));
        return candidates.stream()
                .filter(photo -> excludedPhotoId == null || !photo.getId().equals(excludedPhotoId))
                .map(photo -> {
                    List<Double> candidateEmbedding = loadOrInferEmbedding(photo);
                    double score = cosineSimilarityDense(sampleEmbedding, candidateEmbedding);
                    return new PhotoSimilarity(photo, score);
                })
                .filter(similarity -> similarity.getScore() > MIN_SIMILARITY_SCORE)
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(safeLimit)
                .toList();
    }

    private BufferedImage cropByRelativeRect(BufferedImage image, double x, double y, double w, double h) {
        double safeX = clamp(x, 0, 1);
        double safeY = clamp(y, 0, 1);
        double safeW = clamp(w, 0.01, 1);
        double safeH = clamp(h, 0.01, 1);

        int startX = (int) Math.round(safeX * image.getWidth());
        int startY = (int) Math.round(safeY * image.getHeight());
        int width = (int) Math.round(safeW * image.getWidth());
        int height = (int) Math.round(safeH * image.getHeight());

        if (startX + width > image.getWidth()) {
            width = image.getWidth() - startX;
        }
        if (startY + height > image.getHeight()) {
            height = image.getHeight() - startY;
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Некорректная область выделения");
        }
        return image.getSubimage(startX, startY, width, height);
    }

    private double cosineSimilarityDense(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.size() != b.size()) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.size(); i++) {
            double av = a.get(i);
            double bv = b.get(i);
            dot += av * bv;
            normA += av * av;
            normB += bv * bv;
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private Set<String> sanitizeTags(Set<String> tags) {
        return tags.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean hasRealTags(Set<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        return tags.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .anyMatch(tag -> !DEFAULT_EMPTY_TAG.equalsIgnoreCase(tag));
    }

    private ImageInferenceService.InferenceResponse inferPhotoAndPersistEmbedding(Photo photo) {
        Path absolutePath = uploadPath.resolve(photo.getStoredFileName()).normalize();
        ImageInferenceService.InferenceResponse response = inferImage(
                absolutePath,
                photo.getId(),
                detectMimeType(absolutePath)
        );
        saveEmbedding(photo, response.embedding());
        photoRepository.save(photo);
        return response;
    }

    private ImageInferenceService.InferenceResponse inferForBufferedImage(BufferedImage image, Long pseudoId) {
        try {
            Path temp = Files.createTempFile("photo-infer-", ".png");
            try {
                ImageIO.write(image, "png", temp.toFile());
                return inferImage(temp.toAbsolutePath(), pseudoId, "image/png");
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Не удалось подготовить временный файл для инференса", ex);
        }
    }

    private ImageInferenceService.InferenceResponse inferImage(Path absolutePath, Long photoId, String mimeType) {
        ImageInferenceService.InferenceResponse response = imageInferenceService.infer(absolutePath, photoId, mimeType);
        if (!"ok".equalsIgnoreCase(response.status())) {
            throw new IllegalStateException(
                    "Ошибка классификации изображения: " + (response.error() == null ? "unknown" : response.error())
            );
        }
        if (response.embedding() == null || response.embedding().isEmpty()) {
            throw new IllegalStateException("Модель вернула пустой embedding");
        }
        return response;
    }

    private List<Double> loadOrInferEmbedding(Photo photo) {
        List<Double> stored = parseEmbedding(photo.getClipEmbeddingJson());
        if (!stored.isEmpty()) {
            return stored;
        }
        ImageInferenceService.InferenceResponse response = inferPhotoAndPersistEmbedding(photo);
        return response.embedding();
    }

    private void saveEmbedding(Photo photo, List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) {
            return;
        }
        try {
            photo.setClipEmbeddingJson(objectMapper.writeValueAsString(embedding));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Не удалось сериализовать embedding", ex);
        }
    }

    private List<Double> parseEmbedding(String rawJson) {
        if (!StringUtils.hasText(rawJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rawJson, new TypeReference<List<Double>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String detectMimeType(Path filePath) {
        try {
            String probe = Files.probeContentType(filePath);
            if (StringUtils.hasText(probe)) {
                return probe;
            }
        } catch (IOException ignored) {
            // fallback below
        }
        String name = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/jpeg";
    }

    @FunctionalInterface
    public interface AutoTagProgressListener {
        void onProgress(int processed, int total, int taggedCount);
    }
}
