package by.ghoncharko.imageclassification.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class RegistrationForm {

    @NotBlank(message = "Введите логин")
    @Size(min = 3, max = 50, message = "Логин от 3 до 50 символов")
    private String username;

    @NotBlank(message = "Введите пароль")
    @Size(min = 6, max = 100, message = "Пароль минимум 6 символов")
    private String password;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
