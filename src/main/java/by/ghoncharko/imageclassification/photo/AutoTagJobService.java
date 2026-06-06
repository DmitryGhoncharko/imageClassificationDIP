package by.ghoncharko.imageclassification.photo;

import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class AutoTagJobService {

    private final PhotoStorageService photoStorageService;
    private final Map<String, AutoTagJobStatus> statusByUsername = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public AutoTagJobService(PhotoStorageService photoStorageService) {
        this.photoStorageService = photoStorageService;
    }

    public AutoTagJobStatus getStatus(String username) {
        return statusByUsername.getOrDefault(username, AutoTagJobStatus.idle());
    }

    public synchronized boolean startForUser(String username) {
        AutoTagJobStatus current = getStatus(username);
        if (current.running()) {
            return false;
        }
        int total = photoStorageService.countUntaggedPhotos(username);
        Instant startedAt = Instant.now();
        statusByUsername.put(
                username,
                new AutoTagJobStatus(
                        "RUNNING",
                        true,
                        total == 0 ? "Нет фото для автотегирования" : "Автотегирование запущено",
                        total,
                        0,
                        0,
                        startedAt,
                        null
                )
        );
        CompletableFuture.runAsync(() -> runAutoTag(username, total, startedAt), executor);
        return true;
    }

    private void runAutoTag(String username, int total, Instant startedAt) {
        if (total == 0) {
            statusByUsername.put(
                    username,
                    new AutoTagJobStatus(
                            "COMPLETED",
                            false,
                            "Нечего автотегировать: все фото уже с тегами",
                            0,
                            0,
                            0,
                            startedAt,
                            Instant.now()
                    )
            );
            return;
        }
        try {
            int taggedCount = photoStorageService.autoTagAllUntaggedPhotos(username, (processed, progressTotal, tagged) ->
                    statusByUsername.put(
                            username,
                            new AutoTagJobStatus(
                                    "RUNNING",
                                    true,
                                    "Автотегирование в процессе",
                                    progressTotal,
                                    processed,
                                    tagged,
                                    startedAt,
                                    null
                            )
                    )
            );
            statusByUsername.put(
                    username,
                    new AutoTagJobStatus(
                            "COMPLETED",
                            false,
                            "Автотегирование завершено",
                            total,
                            total,
                            taggedCount,
                            startedAt,
                            Instant.now()
                    )
            );
        } catch (Exception ex) {
            AutoTagJobStatus current = getStatus(username);
            statusByUsername.put(
                    username,
                    new AutoTagJobStatus(
                            "FAILED",
                            false,
                            ex.getMessage() == null ? "Ошибка автотегирования" : ex.getMessage(),
                            current.total(),
                            current.processed(),
                            current.taggedCount(),
                            current.startedAt() == null ? startedAt : current.startedAt(),
                            Instant.now()
                    )
            );
        }
    }

    @PreDestroy
    public void shutdownExecutor() {
        executor.shutdownNow();
    }
}
