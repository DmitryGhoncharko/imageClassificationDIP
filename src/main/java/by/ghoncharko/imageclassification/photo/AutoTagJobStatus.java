package by.ghoncharko.imageclassification.photo;

import java.time.Instant;

public record AutoTagJobStatus(
        String state,
        boolean running,
        String message,
        int total,
        int processed,
        int taggedCount,
        Instant startedAt,
        Instant finishedAt
) {
    public static AutoTagJobStatus idle() {
        return new AutoTagJobStatus(
                "IDLE",
                false,
                "Задача еще не запускалась",
                0,
                0,
                0,
                null,
                null
        );
    }
}
