package by.ghoncharko.imageclassification.photo;

import java.nio.file.Path;
import java.util.List;

public interface ImageInferenceService {

    InferenceResponse infer(Path absoluteImagePath, Long photoId, String mimeType);

    record InferenceResponse(
            String taskId,
            String status,
            List<Double> embedding,
            List<String> tags,
            List<Double> scores,
            String error
    ) {
    }
}
