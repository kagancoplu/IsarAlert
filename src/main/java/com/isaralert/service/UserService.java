package com.isaralert.service;

import com.isaralert.exception.ResourceNotFoundException;
import com.isaralert.model.User;
import com.isaralert.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * User management service for CRUD operations on registered Telegram users.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    public Optional<User> findByTelegramChatId(Long chatId) {
        return userRepository.findByTelegramChatId(chatId);
    }

    public List<User> findAllActive() {
        return userRepository.findAllByActiveTrue();
    }

    @Transactional
    public User createOrGetUser(Long telegramChatId, String username, String firstName) {
        return userRepository.findByTelegramChatId(telegramChatId)
                .orElseGet(() -> {
                    User user = User.builder()
                            .telegramChatId(telegramChatId)
                            .username(username)
                            .firstName(firstName)
                            .active(true)
                            .build();
                    log.info("Creating new user: {} (chatId={})", username, telegramChatId);
                    return userRepository.save(user);
                });
    }

    @Transactional
    public User deactivateUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
        user.setActive(false);
        return userRepository.save(user);
    }

    @Transactional
    public User activateUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
        user.setActive(true);
        return userRepository.save(user);
    }
}
