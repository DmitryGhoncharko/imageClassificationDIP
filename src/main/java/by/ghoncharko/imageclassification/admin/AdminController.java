package by.ghoncharko.imageclassification.admin;

import by.ghoncharko.imageclassification.photo.PhotoStorageService;
import by.ghoncharko.imageclassification.user.UserService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class AdminController {

    private final UserService userService;
    private final PhotoStorageService photoStorageService;

    public AdminController(UserService userService, PhotoStorageService photoStorageService) {
        this.userService = userService;
        this.photoStorageService = photoStorageService;
    }

    @GetMapping("/admin")
    public String adminPage(Model model) {
        model.addAttribute("users", userService.findAll());
        model.addAttribute("photos", photoStorageService.allPhotos());
        return "admin";
    }

    @PostMapping("/admin/photos/{id}/delete")
    public String deletePhotoAsAdmin(@PathVariable("id") Long photoId, Authentication authentication) {
        try {
            photoStorageService.deletePhoto(photoId, authentication.getName());
            return "redirect:/admin?deleted";
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return "redirect:/admin?deleteError";
        }
    }
}
