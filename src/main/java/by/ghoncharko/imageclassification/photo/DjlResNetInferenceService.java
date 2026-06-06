package by.ghoncharko.imageclassification.photo;

import ai.djl.Application;
import ai.djl.ModelException;
import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.Batchifier;
import ai.djl.translate.TranslateException;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import ai.djl.nn.Block;
import ai.djl.nn.SequentialBlock;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class DjlResNetInferenceService implements ImageInferenceService {

    private static final Logger log = LoggerFactory.getLogger(DjlResNetInferenceService.class);

    private final int topTags;

    private final Object inferLock = new Object();

    private ZooModel<Image, Classifications> classificationModel;
    private Predictor<Image, Classifications> classifier;
    private ZooModel<Image, float[]> featureModel;
    private Predictor<Image, float[]> embedder;

    public DjlResNetInferenceService(
            @Value("${app.inference.top-tags:2}") int topTags
    ) {
        this.topTags = Math.max(1, topTags);
    }

    @PostConstruct
    void loadModels() throws ModelNotFoundException, ModelException, IOException {
        log.info("Loading ResNet-50 model (PyTorch via DJL)...");
        Criteria<Image, Classifications> classificationCriteria = Criteria.builder()
                .optApplication(Application.CV.IMAGE_CLASSIFICATION)
                .setTypes(Image.class, Classifications.class)
                .optFilter("layers", "50")
                .optFilter("dataset", "imagenet")
                .optEngine("PyTorch")
                .optProgress(new ProgressBar())
                .build();
        classificationModel = ModelZoo.loadModel(classificationCriteria);
        classifier = classificationModel.newPredictor();

        Criteria<Image, float[]> featureCriteria = Criteria.builder()
                .optApplication(Application.CV.IMAGE_CLASSIFICATION)
                .setTypes(Image.class, float[].class)
                .optFilter("layers", "50")
                .optFilter("dataset", "imagenet")
                .optEngine("PyTorch")
                .optTranslator(new ResNetFeatureTranslator())
                .optProgress(new ProgressBar())
                .build();
        featureModel = ModelZoo.loadModel(featureCriteria);
        stripClassificationHead(featureModel.getBlock());
        embedder = featureModel.newPredictor();
        log.info("ResNet-50 model loaded successfully");
    }

    @PreDestroy
    void closeModels() {
        closeQuietly(classifier);
        closeQuietly(classificationModel);
        closeQuietly(embedder);
        closeQuietly(featureModel);
    }

    @Override
    public InferenceResponse infer(Path absoluteImagePath, Long photoId, String mimeType) {
        String taskId = UUID.randomUUID().toString();
        try {
            Image image = ImageFactory.getInstance().fromFile(absoluteImagePath);
            Classifications classifications;
            float[] rawEmbedding;
            synchronized (inferLock) {
                classifications = classifier.predict(image);
                rawEmbedding = embedder.predict(image);
            }
            List<String> tags = new ArrayList<>();
            List<Double> scores = new ArrayList<>();
            List<String> classNames = classifications.getClassNames();
            List<Double> probabilities = classifications.getProbabilities();
            for (int i = 0; i < classNames.size() && tags.size() < topTags; i++) {
                tags.add(normalizeTag(classNames.get(i)));
                scores.add(probabilities.get(i));
            }
            List<Double> embedding = toDoubleList(l2Normalize(rawEmbedding));
            return new InferenceResponse(taskId, "ok", embedding, tags, scores, null);
        } catch (IOException ex) {
            return new InferenceResponse(taskId, "error", List.of(), List.of(), List.of(), ex.getMessage());
        } catch (TranslateException ex) {
            return new InferenceResponse(taskId, "error", List.of(), List.of(), List.of(), ex.getMessage());
        }
    }

    private void stripClassificationHead(Block block) {
        if (!(block instanceof SequentialBlock sequentialBlock)) {
            return;
        }
        if (sequentialBlock.getChildren().size() >= 2) {
            sequentialBlock.removeLastBlock();
            sequentialBlock.removeLastBlock();
        }
    }

    private String normalizeTag(String raw) {
        String label = raw.split(",")[0].trim().toLowerCase(Locale.ROOT);
        label = label.replaceFirst("^n\\d+[_\\s-]*", "");
        label = label.replaceAll("[^a-z0-9\\-]+", "_");
        label = label.replaceAll("_+", "_");
        return label.replaceAll("^_|_$", "");
    }

    private float[] l2Normalize(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0) {
            return vector;
        }
        double scale = 1.0 / Math.sqrt(norm);
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] * scale);
        }
        return normalized;
    }

    private List<Double> toDoubleList(float[] values) {
        List<Double> result = new ArrayList<>(values.length);
        for (float value : values) {
            result.add((double) value);
        }
        return result;
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ex) {
            log.warn("Failed to close DJL resource: {}", ex.getMessage());
        }
    }

    private static final class ResNetFeatureTranslator implements Translator<Image, float[]> {

        @Override
        public Batchifier getBatchifier() {
            return null;
        }

        @Override
        public NDList processInput(TranslatorContext ctx, Image input) {
            NDManager manager = ctx.getNDManager();
            NDArray mean = manager.create(new float[]{0.485f, 0.456f, 0.406f}).reshape(3, 1, 1);
            NDArray std = manager.create(new float[]{0.229f, 0.224f, 0.225f}).reshape(3, 1, 1);
            NDArray array = input.toNDArray(manager, Image.Flag.COLOR);
            array = NDImageUtils.resize(array, 224, 224);
            array = array.transpose(2, 0, 1).toType(DataType.FLOAT32, false).div(255f);
            array = array.sub(mean).div(std);
            array = array.expandDims(0);
            return new NDList(array);
        }

        @Override
        public float[] processOutput(TranslatorContext ctx, NDList list) {
            NDArray array = list.singletonOrThrow().flatten();
            return array.toFloatArray();
        }
    }
}
