package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.dto.UserRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.UserResponseDTO;
import in.yograj.learnspringboot.railway_booking.entity.User;
import in.yograj.learnspringboot.railway_booking.exception.ResourceNotFoundException;
import in.yograj.learnspringboot.railway_booking.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserServiceImpl implements UserService {

    // UserServiceImpl user profile se judi validation aur password handling manage karta hai.
    // Yahan direct password raw storage nahi hoti; BCrypt encoding ke baad save hoti hai.

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public UserResponseDTO registerUser(UserRequestDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("User request payload is required");
        }

        String username = dto.username() == null ? "" : dto.username().trim();
        String email = dto.email() == null ? "" : dto.email().trim();

        if (userRepository.existsByUsername(username)) {
            throw new RuntimeException("Username already exists");
        }

        if (userRepository.existsByEmail(email)) {
            throw new RuntimeException("Email already exists");
        }

        User user = new User();
        user.setUsername(username);
        user.setFullName(dto.fullName().trim());
        user.setEmail(email);
        user.setPhoneNumber(dto.phoneNumber());
        user.setPassword(passwordEncoder.encode(dto.password()));
        user.setStatus("ACTIVE");
        user.setRole("ROLE_USER");

        User saved = userRepository.save(user);

        return mapToResponse(saved);
    }

    @Override
    public List<UserResponseDTO> getAllUsers() {
        return userRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    public UserResponseDTO getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + id));

        return mapToResponse(user);
    }

    @Override
    public UserResponseDTO getUserByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with username: " + username));

        return mapToResponse(user);
    }

    @Override
    public void deleteUser(Long id) {
        userRepository.deleteById(id);
    }

    private UserResponseDTO mapToResponse(User user) {
        return new UserResponseDTO(
                user.getUserId(),
                user.getUsername(),
                user.getFullName(),
                user.getEmail(),
                user.getPhoneNumber(),
                user.getStatus(),
                user.getCreatedAt()
        );
    }
}
