package by.ghoncharko.imageclassification.inference;

import ai.djl.Application;
import ai.djl.ModelException;
import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;

import java.io.IOException;

/**
 * Pre-downloads ResNet-50 weights into DJL cache during Docker image build.
 */
public final class ModelWarmup {

    private ModelWarmup() {
    }

    public static void main(String[] args) throws ModelNotFoundException, ModelException, IOException {
        String cacheDir = System.getenv("DJL_CACHE_DIR");
        System.out.println("Warming up ResNet-50 model cache" + (cacheDir == null ? "" : " at " + cacheDir));

        Criteria<Image, Classifications> classificationCriteria = Criteria.builder()
                .optApplication(Application.CV.IMAGE_CLASSIFICATION)
                .setTypes(Image.class, Classifications.class)
                .optFilter("layers", "50")
                .optFilter("dataset", "imagenet")
                .optEngine("PyTorch")
                .build();

        try (ZooModel<Image, Classifications> model = ModelZoo.loadModel(classificationCriteria);
             Predictor<Image, Classifications> ignored = model.newPredictor()) {
            System.out.println("Classification model cached");
        }

        System.out.println("Model warmup completed");
    }
}
