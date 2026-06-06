package by.ghoncharko.imageclassification;

import by.ghoncharko.imageclassification.photo.ImageInferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
class ImageClassificationApplicationTests {

    @MockitoBean
    ImageInferenceService imageInferenceService;

    @Test
    void contextLoads() {
    }

}
