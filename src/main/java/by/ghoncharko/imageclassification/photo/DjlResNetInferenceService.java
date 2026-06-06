package by.ghoncharko.imageclassification.photo;

import ai.djl.Application;
import ai.djl.ModelException;
import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.nn.Block;
import ai.djl.nn.SequentialBlock;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.Batchifier;
import ai.djl.translate.TranslateException;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class DjlResNetInferenceService implements ImageInferenceService {

    private static final Logger log = LoggerFactory.getLogger(DjlResNetInferenceService.class);
    private static final float[] IMAGENET_MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] IMAGENET_STD = {0.229f, 0.224f, 0.225f};

    private final int topTags;
    private final double minConfidence;

    private final Object inferLock = new Object();

    private Map<String, String> synsetLabels = Map.of();
    private ZooModel<Image, Classifications> classificationModel;
    private Predictor<Image, Classifications> classifier;
    private ZooModel<Image, float[]> featureModel;
    private Predictor<Image, float[]> embedder;

    public DjlResNetInferenceService(
            @Value("${app.inference.top-tags:2}") int topTags,
            @Value("${app.inference.min-confidence:0.08}") double minConfidence
    ) {
        this.topTags = Math.max(1, topTags);
        this.minConfidence = Math.max(0.01, minConfidence);
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
                .optArgument("topk", "10")
                .optProgress(new ProgressBar())
                .build();
        classificationModel = ModelZoo.loadModel(classificationCriteria);
        synsetLabels = loadSynsetLabels(classificationModel);
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
        log.info("ResNet-50 model loaded successfully, synset entries={}", synsetLabels.size());
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
            TagSelection selection = pickTags(classifications);
            log.debug("Inference photoId={} file={} tags={} scores={}",
                    photoId, absoluteImagePath.getFileName(), selection.tags(), selection.scores());
            List<Double> embedding = toDoubleList(l2Normalize(rawEmbedding));
            return new InferenceResponse(taskId, "ok", embedding, selection.tags(), selection.scores(), null);
        } catch (IOException ex) {
            return new InferenceResponse(taskId, "error", List.of(), List.of(), List.of(), ex.getMessage());
        } catch (TranslateException ex) {
            return new InferenceResponse(taskId, "error", List.of(), List.of(), List.of(), ex.getMessage());
        }
    }

    private TagSelection pickTags(Classifications classifications) {
        List<String> classNames = classifications.getClassNames();
        List<Double> probabilities = classifications.getProbabilities();
        LinkedHashSet<String> uniqueTags = new LinkedHashSet<>();
        List<String> tags = new ArrayList<>();
        List<Double> scores = new ArrayList<>();

        for (int i = 0; i < classNames.size() && tags.size() < topTags; i++) {
            double probability = probabilities.get(i);
            String tag = normalizeTag(classNames.get(i));
            if (!StringUtils.hasText(tag) || !uniqueTags.add(tag)) {
                continue;
            }
            if (probability < minConfidence) {
                continue;
            }
            tags.add(tag);
            scores.add(probability);
        }

        if (tags.isEmpty()) {
            for (int i = 0; i < classNames.size() && tags.size() < topTags; i++) {
                String tag = normalizeTag(classNames.get(i));
                if (!StringUtils.hasText(tag) || !uniqueTags.add(tag)) {
                    continue;
                }
                tags.add(tag);
                scores.add(probabilities.get(i));
            }
        }
        return new TagSelection(tags, scores);
    }

    private Map<String, String> loadSynsetLabels(ZooModel<?, ?> model) {
        Map<String, String> labels = new HashMap<>();
        try {
            URL artifact = model.getArtifact("synset.txt");
            try (InputStream inputStream = artifact.openStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.length() <= 9 || !line.startsWith("n")) {
                        continue;
                    }
                    String synsetId = line.substring(0, 9);
                    String label = line.substring(9).trim();
                    if (StringUtils.hasText(label)) {
                        labels.put(synsetId, label);
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("Could not load synset.txt: {}", ex.getMessage());
        }
        return Map.copyOf(labels);
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
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String label = raw.trim();
        if (label.matches("^n\\d{8}$")) {
            label = synsetLabels.getOrDefault(label, label);
        }
        label = label.split(",")[0].trim().toLowerCase(Locale.ROOT);
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

    private record TagSelection(List<String> tags, List<Double> scores) {
    }

    private static final class ResNetFeatureTranslator implements Translator<Image, float[]> {

        @Override
        public Batchifier getBatchifier() {
            return null;
        }

        @Override
        public NDList processInput(TranslatorContext ctx, Image input) {
            NDManager manager = ctx.getNDManager();
            NDArray mean = manager.create(IMAGENET_MEAN).reshape(3, 1, 1);
            NDArray std = manager.create(IMAGENET_STD).reshape(3, 1, 1);
            NDArray array = input.toNDArray(manager, Image.Flag.COLOR);
            array = NDImageUtils.resize(array, 256);
            array = NDImageUtils.centerCrop(array, 224, 224);
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
