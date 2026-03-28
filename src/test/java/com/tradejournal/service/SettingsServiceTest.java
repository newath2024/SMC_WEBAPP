package com.tradejournal.service;

import com.tradejournal.auth.domain.User;
import com.tradejournal.auth.repository.UserRepository;
import com.tradejournal.settings.service.SettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {

    @Mock
    private UserRepository userRepository;

    @Test
    void updateProfileRejectsSvgAvatar() {
        SettingsService settingsService = new SettingsService(userRepository);
        User currentUser = new User();
        currentUser.setUsername("trader");
        currentUser.setEmail("trader@example.com");
        MockMultipartFile avatar = new MockMultipartFile(
                "avatarFile",
                "avatar.svg",
                "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes()
        );

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> settingsService.updateProfile(
                        currentUser,
                        "Trader",
                        "trader@example.com",
                        "Asia/Bangkok",
                        "Thailand",
                        avatar,
                        false
                )
        );

        assertTrue(error.getMessage().contains("Avatar must be PNG, JPG, WEBP, or GIF"));
        verify(userRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
