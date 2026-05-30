package by.ghoncharko.imageclassification.photo;

import ai.djl.Application;
import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.util.NDImageUtils;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.TranslateException;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class ResNetInferenceService {

    private static final String IMAGENET_EN_URL = "https://raw.githubusercontent.com/pytorch/hub/master/imagenet_classes.txt";
    private static final String BABEL_IMAGENET_URL = "https://raw.githubusercontent.com/gregor-ge/Babel-ImageNet/main/data/babel_imagenet.json";
    private static final Map<String, String> EN_TO_RU = Map.ofEntries(
            Map.entry("seashore", "морской_берег"),
            Map.entry("mountain bike", "горный_велосипед"),
            Map.entry("tabby", "полосатая_кошка"),
            Map.entry("tiger cat", "тигровая_кошка"),
            Map.entry("golden retriever", "золотистый_ретривер"),
            Map.entry("Labrador retriever", "лабрадор"),
            Map.entry("Siberian husky", "сибирский_хаски"),
            Map.entry("jeep", "внедорожник"),
            Map.entry("sports car", "спортивный_автомобиль"),
            Map.entry("airliner", "самолет"),
            Map.entry("container ship", "контейнеровоз"),
            Map.entry("volcano", "вулкан"),
            Map.entry("lakeside", "озеро"),
            Map.entry("valley", "долина"),
            Map.entry("pizza", "пицца"),
            Map.entry("ice cream", "мороженое")
    );
    private static final Map<String, String> WORD_TO_RU = Map.ofEntries(
            Map.entry("cat", "кошка"),
            Map.entry("dog", "собака"),
            Map.entry("bird", "птица"),
            Map.entry("car", "машина"),
            Map.entry("truck", "грузовик"),
            Map.entry("bus", "автобус"),
            Map.entry("train", "поезд"),
            Map.entry("airplane", "самолет"),
            Map.entry("airliner", "самолет"),
            Map.entry("boat", "лодка"),
            Map.entry("ship", "корабль"),
            Map.entry("bottle", "бутылка"),
            Map.entry("phone", "телефон"),
            Map.entry("laptop", "ноутбук"),
            Map.entry("computer", "компьютер"),
            Map.entry("keyboard", "клавиатура"),
            Map.entry("monitor", "монитор"),
            Map.entry("pizza", "пицца"),
            Map.entry("burger", "бургер"),
            Map.entry("sandwich", "сэндвич"),
            Map.entry("ice", "лед"),
            Map.entry("cream", "крем"),
            Map.entry("mountain", "гора"),
            Map.entry("beach", "пляж"),
            Map.entry("sea", "море"),
            Map.entry("lake", "озеро"),
            Map.entry("valley", "долина"),
            Map.entry("forest", "лес"),
            Map.entry("tree", "дерево"),
            Map.entry("flower", "цветок"),
            Map.entry("street", "улица"),
            Map.entry("building", "здание"),
            Map.entry("church", "церковь"),
            Map.entry("palace", "дворец"),
            Map.entry("clock", "часы"),
            Map.entry("ball", "мяч"),
            Map.entry("tennis", "теннис"),
            Map.entry("soccer", "футбол"),
            Map.entry("basketball", "баскетбол")
    );

    private final Object lock = new Object();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ZooModel<Image, Classifications> model;
    private List<String> ruLabels;

    @PostConstruct
    public void preloadOnStartup() {
        getModel();
        getRuLabels();
    }

    public List<String> autoTagsRu(BufferedImage image, int limit) {
        Classifications classifications = predict(image);
        List<String> labelDict = getRuLabels();
        Set<String> tags = new LinkedHashSet<>();
        List<Classifications.Classification> top = classifications.topK(Math.max(1, Math.min(limit, 8)));
        for (Classifications.Classification item : top) {
            if (item.getProbability() < 0.06) {
                continue;
            }
            Integer idx = parseClassIndex(item.getClassName());
            if (idx != null && idx >= 0 && idx < labelDict.size()) {
                tags.add(labelDict.get(idx));
                continue;
            }
            String translated = toRussian(item.getClassName());
            if (translated != null && !translated.isBlank()) {
                tags.add(translated);
            }
        }
        if (tags.isEmpty()) {
            tags.add("класс");
        }
        return List.copyOf(tags);
    }

    public Map<String, Double> embedding(BufferedImage image) {
        Classifications classifications = predict(image);
        Map<String, Double> vector = new HashMap<>();
        for (Classifications.Classification item : classifications.items()) {
            vector.put(item.getClassName(), item.getProbability());
        }
        return vector;
    }

    private Classifications predict(BufferedImage bufferedImage) {
        try {
            Image image = ImageFactory.getInstance().fromImage(toRgbImage(bufferedImage));
            try (Predictor<Image, Classifications> predictor = getModel().newPredictor(new ResNetTranslator())) {
                return predictor.predict(image);
            }
        } catch (TranslateException e) {
            throw new IllegalStateException("Ошибка инференса ResNet: " + e.getMessage(), e);
        }
    }

    private ZooModel<Image, Classifications> getModel() {
        if (model == null) {
            synchronized (lock) {
                if (model == null) {
                    model = tryLoadResNetModel();
                }
            }
        }
        return model;
    }

    @PreDestroy
    public void shutdown() {
        if (model != null) {
            model.close();
        }
    }

    private List<String> getRuLabels() {
        if (ruLabels == null) {
            synchronized (lock) {
                if (ruLabels == null) {
                    ruLabels = buildRuLabels();
                }
            }
        }
        return ruLabels;
    }

    private List<String> buildRuLabels() {
        List<String> english = loadEnglishLabels();
        List<String> result = english.stream()
                .map(this::transliterateToRussian)
                .collect(Collectors.toList());
        applyBabelRussianOverrides(result);
        return result;
    }

    private List<String> loadEnglishLabels() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(URI.create(IMAGENET_EN_URL).toURL().openStream())
        )) {
            List<String> lines = reader.lines()
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .toList();
            if (lines.size() == 1000) {
                return lines;
            }
        } catch (IOException ignored) {
            // fallback
        }
        return IntStream.range(0, 1000).mapToObj(i -> "class_" + i).toList();
    }

    private void applyBabelRussianOverrides(List<String> target) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(URI.create(BABEL_IMAGENET_URL).toURL().openStream())
        )) {
            String json = reader.lines().collect(Collectors.joining("\n"));
            JsonNode root = objectMapper.readTree(json);
            JsonNode ru = root.get("RU");
            if (ru == null || !ru.isArray() || ru.size() < 2) {
                return;
            }
            JsonNode indices = ru.get(0);
            JsonNode labels = ru.get(1);
            int count = Math.min(indices.size(), labels.size());
            for (int i = 0; i < count; i++) {
                int idx = indices.get(i).asInt(-1);
                if (idx < 0 || idx >= target.size()) {
                    continue;
                }
                String label = normalizeRussianLabel(labels.get(i).asText(""));
                if (!label.isBlank()) {
                    target.set(idx, label);
                }
            }
        } catch (Exception ignored) {
            // keep transliterated labels
        }
    }

    private ZooModel<Image, Classifications> tryLoadResNetModel() {
        List<Criteria<Image, Classifications>> attempts = List.of(
                Criteria.builder()
                        .setTypes(Image.class, Classifications.class)
                        .optApplication(Application.CV.IMAGE_CLASSIFICATION)
                        .optEngine("PyTorch")
                        .optFilter("layers", "50")
                        .optFilter("dataset", "imagenet")
                        .optTranslator(new ResNetTranslator())
                        .optProgress(new ProgressBar())
                        .build(),
                Criteria.builder()
                        .setTypes(Image.class, Classifications.class)
                        .optApplication(Application.CV.IMAGE_CLASSIFICATION)
                        .optEngine("PyTorch")
                        .optTranslator(new ResNetTranslator())
                        .optProgress(new ProgressBar())
                        .build()
        );

        Exception lastError = null;
        for (Criteria<Image, Classifications> criteria : attempts) {
            try {
                return criteria.loadModel();
            } catch (ModelNotFoundException | IOException | ai.djl.MalformedModelException ex) {
                lastError = ex;
            }
        }
        throw new IllegalStateException(
                "Не удалось загрузить модель ResNet. Проверьте интернет/доступ к model zoo и пересинхронизируйте Gradle.",
                lastError
        );
    }

    private String toRussian(String rawLabel) {
        if (rawLabel == null || rawLabel.isBlank()) {
            return null;
        }
        String normalized = rawLabel.trim();
        String translated = EN_TO_RU.get(normalized);
        if (translated != null) {
            return translated;
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (containsCyrillic(lower)) {
            return lower.replace(" ", "_").replace("-", "_");
        }
        String byWords = translateByWords(lower);
        if (byWords != null && !byWords.isBlank()) {
            return byWords;
        }
        return transliterateToRussian(lower);
    }

    private Set<String> fallbackVisualTags(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        long pixels = (long) width * height;
        double sum = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                sum += (r + g + b) / 3.0;
            }
        }
        double brightness = sum / pixels;
        Set<String> tags = new LinkedHashSet<>();
        tags.add("изображение");
        if ((double) width / height > 1.2) {
            tags.add("пейзаж");
        } else if ((double) width / height < 0.85) {
            tags.add("портрет");
        }
        tags.add(brightness > 160 ? "светлое" : "темное");
        return tags;
    }

    private boolean containsCyrillic(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ((ch >= '\u0400' && ch <= '\u04FF') || ch == 'ё' || ch == 'Ё') {
                return true;
            }
        }
        return false;
    }

    private String translateByWords(String lowerLabel) {
        List<String> mapped = java.util.Arrays.stream(lowerLabel.split("[,\\s_\\-]+"))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(token -> WORD_TO_RU.getOrDefault(token, ""))
                .filter(token -> !token.isEmpty())
                .distinct()
                .toList();
        if (mapped.isEmpty()) {
            return null;
        }
        return mapped.stream().collect(Collectors.joining("_"));
    }

    private Integer parseClassIndex(String className) {
        if (className == null || !className.startsWith("f_")) {
            return null;
        }
        try {
            return Integer.parseInt(className.substring(2));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String normalizeRussianLabel(String label) {
        String value = label == null ? "" : label.toLowerCase(Locale.ROOT);
        value = value.replace(' ', '_').replace('-', '_');
        value = value.replaceAll("[^а-яё0-9_]", "");
        value = value.replaceAll("_+", "_");
        return value.replaceAll("^_+|_+$", "");
    }

    private String transliterateToRussian(String text) {
        if (text == null || text.isBlank()) {
            return "класс";
        }
        String source = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s_-]", " ");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < source.length(); i++) {
            char ch = source.charAt(i);
            String repl = switch (ch) {
                case 'a' -> "а";
                case 'b' -> "б";
                case 'c' -> "к";
                case 'd' -> "д";
                case 'e' -> "е";
                case 'f' -> "ф";
                case 'g' -> "г";
                case 'h' -> "х";
                case 'i' -> "и";
                case 'j' -> "дж";
                case 'k' -> "к";
                case 'l' -> "л";
                case 'm' -> "м";
                case 'n' -> "н";
                case 'o' -> "о";
                case 'p' -> "п";
                case 'q' -> "к";
                case 'r' -> "р";
                case 's' -> "с";
                case 't' -> "т";
                case 'u' -> "у";
                case 'v' -> "в";
                case 'w' -> "в";
                case 'x' -> "кс";
                case 'y' -> "й";
                case 'z' -> "з";
                case '_', '-', ' ' -> "_";
                case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> String.valueOf(ch);
                default -> "";
            };
            out.append(repl);
        }
        String value = out.toString().replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        return value.isBlank() ? "класс" : value;
    }

    private BufferedImage toRgbImage(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_RGB || source.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            return source;
        }
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return rgb;
    }

    private static final class ResNetTranslator implements Translator<Image, Classifications> {
        @Override
        public NDList processInput(TranslatorContext ctx, Image input) {
            NDArray ndArray = input.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
            ndArray = NDImageUtils.resize(ndArray, 256, 256);
            ndArray = NDImageUtils.centerCrop(ndArray, 224, 224);
            ndArray = NDImageUtils.toTensor(ndArray);
            ndArray = NDImageUtils.normalize(
                    ndArray,
                    new float[]{0.485f, 0.456f, 0.406f},
                    new float[]{0.229f, 0.224f, 0.225f}
            );
            return new NDList(ndArray);
        }

        @Override
        public Classifications processOutput(TranslatorContext ctx, NDList list) {
            NDArray logits = list.singletonOrThrow().reshape(-1);
            NDArray probs = logits.softmax(0);
            int size = (int) probs.size();
            List<String> labels = resolveLabels(ctx, size);
            return new Classifications(labels, probs);
        }

        private List<String> resolveLabels(TranslatorContext ctx, int size) {
            return IntStream.range(0, size).mapToObj(i -> "f_" + i).toList();
        }
    }
}
