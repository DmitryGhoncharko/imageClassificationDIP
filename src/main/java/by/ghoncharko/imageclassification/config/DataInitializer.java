package by.ghoncharko.imageclassification.config;

import by.ghoncharko.imageclassification.user.AppUser;
import by.ghoncharko.imageclassification.user.AppUserRepository;
import by.ghoncharko.imageclassification.user.Role;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Set;

@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner createAdminUser(AppUserRepository appUserRepository, PasswordEncoder passwordEncoder) {
        return args -> {
            if (!appUserRepository.existsByUsername("admin")) {
                AppUser admin = new AppUser();
                admin.setUsername("admin");
                admin.setPassword(passwordEncoder.encode("admin123"));
                admin.setRoles(Set.of(Role.ADMIN, Role.USER));
                appUserRepository.save(admin);
            }
        };
    }
}
