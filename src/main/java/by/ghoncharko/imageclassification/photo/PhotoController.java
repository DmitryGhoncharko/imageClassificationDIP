package by.ghoncharko.imageclassification.photo;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Controller
public class PhotoController {

    private final PhotoStorageService photoStorageService;

    public PhotoController(PhotoStorageService photoStorageService) {
        this.photoStorageService = photoStorageService;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/photos";
    }

    @GetMapping("/photos")
    public String photosPage(
            Authentication authentication,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "tag", required = false) String tag,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "12") int size,
            Model model
    ) {
        populatePageModel(authentication, model, name, tag, page, size, List.of());
        return "photos";
    }

    @PostMapping("/photos/upload")
    public String upload(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "tags", required = false) String tags,
            Authentication authentication,
            Model model
    ) {
        try {
            photoStorageService.uploadBatch(files, tags, authentication.getName());
            return "redirect:/photos?uploaded";
        } catch (IllegalArgumentException | IllegalStateException ex) {
            populatePageModel(authentication, model, null, null, 0, 12, List.of());
            model.addAttribute("error", ex.getMessage());
            return "photos";
        }
    }

    @PostMapping("/photos/{id}/tags")
    public String updateTags(
            @PathVariable("id") Long photoId,
            @RequestParam(value = "tags", required = false) String tags,
            Authentication authentication,
            Model model
    ) {
        try {
            photoStorageService.updatePhotoTags(photoId, tags, authentication.getName());
            return "redirect:/photos?tagsUpdated";
        } catch (IllegalArgumentException ex) {
            populatePageModel(authentication, model, null, null, 0, 12, List.of());
            model.addAttribute("error", ex.getMessage());
            return "photos";
        }
    }

    @PostMapping("/photos/autotag-all")
    public String autoTagAll(Authentication authentication, Model model) {
        try {
            int count = photoStorageService.autoTagAllUntaggedPhotos(authentication.getName());
            return "redirect:/photos?autoTaggedAll=" + count;
        } catch (IllegalArgumentException | IllegalStateException ex) {
            populatePageModel(authentication, model, null, null, 0, 12, List.of());
            model.addAttribute("error", ex.getMessage());
            return "photos";
        }
    }

    @PostMapping("/photos/similar/upload")
    public String findSimilarByUpload(
            @RequestParam("sample") MultipartFile sample,
            Authentication authentication,
            Model model
    ) {
        try {
            List<PhotoSimilarity> similar = photoStorageService.findSimilarByUploadedSample(sample, authentication.getName(), 12);
            populatePageModel(authentication, model, null, null, 0, 12, similar);
            return "photos";
        } catch (IllegalArgumentException | IllegalStateException ex) {
            populatePageModel(authentication, model, null, null, 0, 12, List.of());
            model.addAttribute("error", ex.getMessage());
            return "photos";
        }
    }

    @PostMapping("/photos/{id}/similar/crop")
    public String findSimilarByCrop(
            @PathVariable("id") Long photoId,
            @RequestParam("cropX") double cropX,
            @RequestParam("cropY") double cropY,
            @RequestParam("cropW") double cropW,
            @RequestParam("cropH") double cropH,
            Authentication authentication,
            Model model
    ) {
        try {
            List<PhotoSimilarity> similar = photoStorageService.findSimilarByCrop(
                    photoId, cropX, cropY, cropW, cropH, authentication.getName(), 12
            );
            populatePageModel(authentication, model, null, null, 0, 12, similar);
            model.addAttribute("similarSourcePhotoId", photoId);
            return "photos";
        } catch (IllegalArgumentException | IllegalStateException ex) {
            populatePageModel(authentication, model, null, null, 0, 12, List.of());
            model.addAttribute("error", ex.getMessage());
            return "photos";
        }
    }

    @PostMapping("/photos/{id}/delete")
    public String deletePhoto(@PathVariable("id") Long photoId, Authentication authentication, Model model) {
        try {
            photoStorageService.deletePhoto(photoId, authentication.getName());
            return "redirect:/photos?deleted";
        } catch (IllegalArgumentException | IllegalStateException ex) {
            populatePageModel(authentication, model, null, null, 0, 12, List.of());
            model.addAttribute("error", ex.getMessage());
            return "photos";
        }
    }

    private void populatePageModel(
            Authentication authentication,
            Model model,
            String name,
            String tag,
            int page,
            int size,
            List<PhotoSimilarity> similarResults
    ) {
        String username = authentication.getName();
        PhotoPage photoPage = photoStorageService.photosOfUser(username, name, tag, page, size);
        model.addAttribute("photos", photoPage.getItems());
        model.addAttribute("photoPage", photoPage);
        model.addAttribute("username", username);
        model.addAttribute("isAdmin", authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        model.addAttribute("name", name == null ? "" : name);
        model.addAttribute("tag", tag == null ? "" : tag);
        model.addAttribute("similarResults", similarResults);
    }
}
