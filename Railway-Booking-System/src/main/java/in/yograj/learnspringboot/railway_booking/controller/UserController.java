package in.yograj.learnspringboot.railway_booking.controller;

import in.yograj.learnspringboot.railway_booking.dto.UserRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.UserResponseDTO;
import in.yograj.learnspringboot.railway_booking.service.UserService;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    // UserController user CRUD operations ko expose karta hai.
    // Administrative flows ROLE_ADMIN ke dwara protected hain aur profile access ownership check ke saath secured hai.

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // REGISTER USER
    @PostMapping("/register")
    public UserResponseDTO registerUser(@Valid @RequestBody UserRequestDTO dto) {
        return userService.registerUser(dto);
    }

    // GET ALL USERS (Restricted to ROLE_ADMIN)
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public List<UserResponseDTO> getAllUsers() {
        return userService.getAllUsers();
    }

    // GET CURRENT AUTHENTICATED USER PROFILE
    @GetMapping("/me")
    public UserResponseDTO getCurrentUser(Authentication authentication) {
        return userService.getUserByUsername(authentication.getName());
    }

    // GET USER BY ID (Admin or own profile only)
    @GetMapping("/{id}")
    public UserResponseDTO getUserById(@PathVariable Long id, Authentication authentication) {
        boolean isAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

        if (!isAdmin) {
            UserResponseDTO currentUser = userService.getUserByUsername(authentication.getName());
            if (!currentUser.userId().equals(id)) {
                throw new AccessDeniedException("Access denied: You are not authorized to view this profile");
            }
        }

        return userService.getUserById(id);
    }

    // DELETE USER (Restricted to ROLE_ADMIN)
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public String deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return "User deleted successfully";
    }
}
