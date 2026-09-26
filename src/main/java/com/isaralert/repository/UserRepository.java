package com.isaralert.repository;

import com.isaralert.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByTelegramChatId(Long telegramChatId);

    boolean existsByTelegramChatId(Long telegramChatId);

    List<User> findAllByActiveTrue();
}
