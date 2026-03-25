package com.tradejournal.service;

import com.tradejournal.entity.PlanType;
import com.tradejournal.entity.User;
import com.tradejournal.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import static com.tradejournal.controller.AuthController.SESSION_USER_ID;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User getCurrentUser(HttpSession session) {
        Object userIdObj = session.getAttribute(SESSION_USER_ID);

        if (userIdObj == null) {
            return null;
        }

        return userRepository.findById(String.valueOf(userIdObj)).orElse(null);
    }

    public User requireCurrentUser(HttpSession session) {
        User user = getCurrentUser(session);

        if (user == null) {
            throw new IllegalStateException("User is not logged in");
        }

        return user;
    }

    public User requireAdmin(HttpSession session) {
        User user = requireCurrentUser(session);

        if (!isAdmin(user)) {
            throw new IllegalStateException("Admin access required");
        }

        return user;
    }

    public boolean isAdmin(User user) {
        return user != null && ("ADMIN".equalsIgnoreCase(user.getRole()) || user.getPlanType() == PlanType.ADMIN);
    }

    public boolean hasProAccess(User user) {
        return user != null && (user.getPlanType() == PlanType.PRO || user.getPlanType() == PlanType.ADMIN || isAdmin(user));
    }

    public boolean isStandard(User user) {
        return user != null && user.getPlanType() == PlanType.STANDARD;
    }

    public int resolveTradeLimit(User user) {
        return hasProAccess(user) ? Integer.MAX_VALUE : 100;
    }

    public int resolveSetupLimit(User user) {
        return hasProAccess(user) ? Integer.MAX_VALUE : 5;
    }

    public int resolveMistakeTagLimit(User user) {
        return hasProAccess(user) ? Integer.MAX_VALUE : 10;
    }

    public int resolveImageLimitPerTrade(User user) {
        return hasProAccess(user) ? Integer.MAX_VALUE : 1;
    }

    public String resolvePlanLabel(User user) {
        if (user == null || user.getPlanType() == null) {
            return "Standard";
        }
        return switch (user.getPlanType()) {
            case PRO -> "Pro Plan";
            case ADMIN -> "Admin Plan";
            case STANDARD -> "Standard Plan";
        };
    }

    public void requireProAccess(User user, String message) {
        if (!hasProAccess(user)) {
            throw new IllegalStateException(message);
        }
    }
}
