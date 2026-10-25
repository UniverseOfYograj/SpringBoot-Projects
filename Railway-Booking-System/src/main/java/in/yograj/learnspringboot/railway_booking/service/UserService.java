package in.yograj.learnspringboot.railway_booking.service;

import in.yograj.learnspringboot.railway_booking.dto.UserRequestDTO;
import in.yograj.learnspringboot.railway_booking.dto.UserResponseDTO;

import java.util.List;

public interface UserService {

    UserResponseDTO registerUser(UserRequestDTO dto);

    List<UserResponseDTO> getAllUsers();

    UserResponseDTO getUserById(Long id);

    UserResponseDTO getUserByUsername(String username);

    void deleteUser(Long id);
}
