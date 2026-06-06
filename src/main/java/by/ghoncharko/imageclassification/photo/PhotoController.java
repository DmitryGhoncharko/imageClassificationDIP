package by.ghoncharko.imageclassification.photo;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Controller
public class PhotoController {

    private static final int SIMILAR_PHOTOS_LIMIT = 100;

    private final PhotoStorageService photoStorageService;
    private final AutoTagJobService autoTagJobService;

    public PhotoController(PhotoStorageService photoStorageService, AutoTagJobService autoTagJobService) {
        this.photoStorageService = photoStorageService;
        this.autoTagJobService = autoTagJobService;
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
        populatePhotosPageModel(authentication, model, name, tag, page, size);
        return "photos";
    }

    @GetMapping("/photos/upload")
    public String uploadPage(Authentication authentication, Model model) {
        populateCommonModel(authentication, model, "upload");
        return "upload";
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
            return "redirect:/photos/upload?uploaded";
        } catch (IllegalArgumentException | IllegalStateException ex) {
            populateCommonModel(authentication, model, "upload");
            model.addAttribute("error", ex.getMessage());
            return "upload";
        }
    }

    @GetMapping("/photos/similar")
    public String similarPage(Authentication authentication, Model model) {
        populateSimilarPageModel(authentication, model, List.of());
        return "similar";
    }

    @GetMapping("/photos/autotag")
    public String autotagPage(Authentication authentication, Model model) {
        populateCommonModel(authentication, model, "autotag");
        return "autotag";
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
            populatePhotosPageModel(authentication, model, null, null, 0, 12);
            model.addAttribute("error", ex.getMessage());
            return "photos";
        }
    }

    @PostMapping("/photos/autotag-all")
    public String autoTagAll(Authentication authentication, Model model) {
        try {
            boolean started = autoTagJobService.startForUser(authentication.getName());
            if (!started) {
                return "redirect:/photos/autotag?autoTagAlreadyRunning";
            }
            return "redirect:/photos/autotag?autoTagStarted";
        } catch (IllegalArgumentException | IllegalStateException ex) {
            populateCommonModel(authentication, model, "autotag");
            model.addAttribute("error", ex.getMessage());
            return "autotag";
        }
    }

    @GetMapping("/photos/autotag-status")
    @ResponseBody
    public AutoTagJobStatus autoTagStatus(Authentication authentication) {
        return autoTagJobService.getStatus(authentication.getName());
    }

    @PostMapping("/photos/similar/upload")
    public String findSimilarByUpload(
            @RequestParam("sample") MultipartFile sample,
            Authentication authentication,
            Model model
    ) {
        try {
            List<PhotoSimilarity> similar = photoStorageService.findSimilarByUploadedSample(sample, authentication.getName(), 12);
            populateSimilarPageModel(authentication, model, similar);
            return "similar";
        } catch (IllegalArgumentException | IllegalStateException ex) {
            populateSimilarPageModel(authentication, model, List.of());
            model.addAttribute("error", ex.getMessage());
            return "similar";
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
            populateSimilarPageModel(authentication, model, similar);
            return "similar";
        } catch (IllegalArgumentException | IllegalStateException ex) {
            populateSimilarPageModel(authentication, model, List.of());
            model.addAttribute("error", ex.getMessage());
            return "similar";
        }
    }

    @PostMapping("/photos/{id}/delete")
    public String deletePhoto(@PathVariable("id") Long photoId, Authentication authentication, Model model) {
        try {
            photoStorageService.deletePhoto(photoId, authentication.getName());
            return "redirect:/photos?deleted";
        } catch (IllegalArgumentException | IllegalStateException ex) {
            populatePhotosPageModel(authentication, model, null, null, 0, 12);
            model.addAttribute("error", ex.getMessage());
            return "photos";
        }
    }

    private void populateCommonModel(Authentication authentication, Model model, String activeNav) {
        String username = authentication.getName();
        model.addAttribute("username", username);
        model.addAttribute("isAdmin", authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        model.addAttribute("activeNav", activeNav);
    }

    private void populatePhotosPageModel(
            Authentication authentication,
            Model model,
            String name,
            String tag,
            int page,
            int size
    ) {
        populateCommonModel(authentication, model, "photos");
        String username = authentication.getName();
        PhotoPage photoPage = photoStorageService.photosOfUser(username, name, tag, page, size);
        model.addAttribute("photos", photoPage.getItems());
        model.addAttribute("photoPage", photoPage);
        model.addAttribute("name", name == null ? "" : name);
        model.addAttribute("tag", tag == null ? "" : tag);
    }

    private void populateSimilarPageModel(
            Authentication authentication,
            Model model,
            List<PhotoSimilarity> similarResults
    ) {
        populateCommonModel(authentication, model, "similar");
        String username = authentication.getName();
        PhotoPage photoPage = photoStorageService.photosOfUser(username, null, null, 0, SIMILAR_PHOTOS_LIMIT);
        model.addAttribute("photos", photoPage.getItems());
        model.addAttribute("similarResults", similarResults);
    }
}
